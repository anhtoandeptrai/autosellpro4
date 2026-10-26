package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
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
import net.minecraft.item.Items;

import java.util.List;

public class AutoEatPro extends Module {
    /** True while this module is eating. Other modules (e.g. Kill Aura Pro) should not touch the hotbar then. */
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

    private boolean eating;
    private int prevSlot = -1;
    private int eatTicks;
    private int cooldown;

    public AutoEatPro() {
        super(AddonTemplate.CATEGORY, "auto-eat-pro", "Automatically eats food from your hotbar when hungry.");
    }

    @Override
    public void onDeactivate() {
        stopEating();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            eating = false;
            active = false;
            prevSlot = -1;
            return;
        }

        if (cooldown > 0) cooldown--;

        int food = mc.player.getHungerManager().getFoodLevel();

        if (eating) {
            eatTicks++;

            // Finished, interrupted, or stuck for more than 8 seconds -> always release and go back.
            if (food >= 20 || mc.currentScreen != null || !mc.player.isAlive() || eatTicks > 160) {
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

        if (cooldown > 0) return;
        if (mc.currentScreen != null) return;
        if (food > hungerThreshold.get()) return;
        if (mc.player.isUsingItem()) return;

        FindItemResult item = findFood();
        if (!item.found()) return;

        prevSlot = mc.player.getInventory().getSelectedSlot();
        InvUtils.swap(item.slot(), false);
        mc.options.useKey.setPressed(true);
        eating = true;
        active = true;
        eatTicks = 0;
    }

    private void stopEating() {
        if (mc.options != null) mc.options.useKey.setPressed(false);

        // Go back to the slot we were on (sword etc.), using our own saved slot.
        if (prevSlot != -1 && mc.player != null) {
            InvUtils.swap(prevSlot, false);
        }
        prevSlot = -1;

        if (eating) cooldown = cooldownTicks.get();
        eating = false;
        active = false;
        eatTicks = 0;
    }

    private FindItemResult findFood() {
        return InvUtils.findInHotbar(stack -> isFood(stack.getItem()) && stack.contains(DataComponentTypes.FOOD));
    }

    private boolean isFood(Item item) {
        return item.getComponents().contains(DataComponentTypes.FOOD) && !blacklist.get().contains(item);
    }
}
