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
    private boolean swapped;
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
            swapped = false;
            return;
        }

        if (cooldown > 0) cooldown--;

        int food = mc.player.getHungerManager().getFoodLevel();

        if (eating) {
            // Done: full enough, or interrupted by a GUI/death.
            if (food >= 20 || mc.currentScreen != null || !mc.player.isAlive()) {
                stopEating();
                return;
            }

            // Food ran out of the current slot -> try another one.
            FindItemResult item = findFood();
            if (!item.found()) {
                stopEating();
                return;
            }

            if (!isFood(mc.player.getMainHandStack().getItem())) {
                InvUtils.swap(item.slot(), true);
                swapped = true;
            }

            mc.options.useKey.setPressed(true);
            return;
        }

        if (cooldown > 0) return;
        if (mc.currentScreen != null) return;
        if (food > hungerThreshold.get()) return;

        FindItemResult item = findFood();
        if (!item.found()) return;

        InvUtils.swap(item.slot(), true);
        swapped = true;
        mc.options.useKey.setPressed(true);
        eating = true;
    }

    private void stopEating() {
        if (mc.options != null) mc.options.useKey.setPressed(false);
        if (swapped) {
            InvUtils.swapBack();
            swapped = false;
        }
        if (eating) cooldown = cooldownTicks.get();
        eating = false;
    }

    private FindItemResult findFood() {
        return InvUtils.findInHotbar(stack -> isFood(stack.getItem()) && stack.contains(DataComponentTypes.FOOD));
    }

    private boolean isFood(Item item) {
        return item.getComponents().contains(DataComponentTypes.FOOD) && !blacklist.get().contains(item);
    }
}
