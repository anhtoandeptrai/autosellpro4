package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

import java.util.List;

public class AutoEatPro extends Module {
    /** True while this module is eating or returning to the weapon. */
    private static boolean active;

    public static boolean isEating() {
        return active;
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> hungerThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("Start eating when the food bar is at or below this value (20 = full).")
        .defaultValue(14)
        .range(1, 19)
        .sliderRange(1, 19)
        .build()
    );

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks")
        .description("Ticks to wait after finishing a meal before eating again.")
        .defaultValue(10)
        .range(0, 100)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<Integer> returnTicksSetting = sgGeneral.add(new IntSetting.Builder()
        .name("return-ticks")
        .description("After eating, keep forcing your original slot back for this many ticks (beats other modules that change slots).")
        .defaultValue(30)
        .range(5, 100)
        .sliderRange(5, 100)
        .build()
    );

    private final Setting<List<Item>> blacklist = sgGeneral.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("Foods that will never be eaten.")
        .defaultValue(
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.CHORUS_FRUIT,
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.CHICKEN,
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.SUSPICIOUS_STEW
        )
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print each step (start, finish, slot changes) in chat.")
        .defaultValue(false)
        .build()
    );

    private boolean eating;
    private int homeSlot = -1;
    private int returnTicks;
    private int eatTicks;
    private int lastFood;
    private int noProgressTicks;
    private int cooldown;

    public AutoEatPro() {
        super(AddonTemplate.CATEGORY, "auto-eat-pro", "Automatically eats food from your hotbar when hungry.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) mc.options.useKey.setPressed(false);
        if (homeSlot != -1 && mc.player != null) goHome();
        resetState();
    }

    private void resetState() {
        eating = false;
        active = false;
        homeSlot = -1;
        returnTicks = 0;
        eatTicks = 0;
        noProgressTicks = 0;
        cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            resetState();
            return;
        }

        if (cooldown > 0) cooldown--;

        int food = mc.player.getHungerManager().getFoodLevel();

        // Phase 3: returning. Keep forcing the original slot for a while.
        if (!eating && returnTicks > 0) {
            returnTicks--;
            if (homeSlot != -1 && mc.player.getInventory().getSelectedSlot() != homeSlot) {
                goHome();
            }
            if (returnTicks == 0) {
                homeSlot = -1;
                active = false;
            }
            return;
        }

        // Phase 2: eating.
        if (eating) {
            eatTicks++;

            // No hunger progress for 3 seconds means we cannot eat any more (full, or bad food) -> give up.
            if (food > lastFood) {
                lastFood = food;
                noProgressTicks = 0;
            } else {
                noProgressTicks++;
            }

            if (food >= 20 || mc.currentScreen != null || !mc.player.isAlive()
                || eatTicks > 200 || noProgressTicks > 60) {
                stopEating();
                return;
            }

            FindItemResult item = findFood();
            if (!item.found()) {
                stopEating();
                return;
            }

            if (!isFood(mc.player.getMainHandStack().getItem())) {
                InvUtils.swap(item.slot(), false);
            }

            mc.options.useKey.setPressed(true);
            return;
        }

        // Phase 1: decide whether to start eating.
        if (cooldown > 0) return;
        if (mc.currentScreen != null) return;
        if (food > hungerThreshold.get()) return;
        if (mc.player.isUsingItem()) return;

        FindItemResult item = findFood();
        if (!item.found()) return;

        int current = mc.player.getInventory().getSelectedSlot();
        if (isFood(mc.player.getMainHandStack().getItem())) {
            // Already holding food: our home is the first weapon in the hotbar (or here if none).
            FindItemResult weapon = InvUtils.findInHotbar(this::isWeapon);
            homeSlot = weapon.found() ? weapon.slot() : current;
        } else {
            homeSlot = current;
        }

        if (debug.get()) info("Start eating: food=" + food + ", slot " + current + " -> " + item.slot() + ", home=" + homeSlot);

        InvUtils.swap(item.slot(), false);
        mc.options.useKey.setPressed(true);
        eating = true;
        active = true;
        eatTicks = 0;
        noProgressTicks = 0;
        lastFood = food;
    }

    private void stopEating() {
        if (mc.options != null) mc.options.useKey.setPressed(false);

        if (debug.get()) info("Stop eating after " + eatTicks + " ticks, back to slot " + homeSlot);

        if (homeSlot != -1 && mc.player != null) {
            goHome();
            returnTicks = returnTicksSetting.get(); // keep enforcing the slot for a bit
            active = true;
        } else {
            active = false;
        }

        cooldown = cooldownTicks.get();
        eating = false;
        eatTicks = 0;
    }

    private void goHome() {
        if (homeSlot < 0 || homeSlot > 8) return;
        InvUtils.swap(homeSlot, false);
    }

    private FindItemResult findFood() {
        return InvUtils.findInHotbar(stack -> isFood(stack.getItem()) && stack.contains(DataComponentTypes.FOOD));
    }

    private boolean isFood(Item item) {
        return item.getComponents().contains(DataComponentTypes.FOOD) && !blacklist.get().contains(item);
    }

    private boolean isWeapon(ItemStack stack) {
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || stack.isOf(Items.MACE);
    }
}
