package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;

/**
 * Remembers the spot where you enabled it and walks back to it whenever you get pushed away
 * (blaze, hoglin knockback, ...). Uses normal movement keys, your camera is never touched.
 */
public class ReturnToSpotPro extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> tolerance = sgGeneral.add(new DoubleSetting.Builder()
        .name("tolerance")
        .description("Stop walking when you are this close to the spot (in blocks).")
        .defaultValue(0.2)
        .range(0.05, 2)
        .sliderRange(0.05, 1)
        .build()
    );

    private final Setting<Double> maxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-distance")
        .description("If you are farther than this from the spot (teleported, died...), do not walk back.")
        .defaultValue(8)
        .range(1, 64)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Boolean> snapToCenter = sgGeneral.add(new BoolSetting.Builder()
        .name("snap-to-block-center")
        .description("Use the center of the block you were standing on instead of your exact position.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> slowNear = sgGeneral.add(new BoolSetting.Builder()
        .name("slow-near-spot")
        .description("Sneak for the last stretch so you do not overshoot the spot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseInScreens = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-in-screens")
        .description("Do not walk while a menu or chat is open.")
        .defaultValue(true)
        .build()
    );

    private double homeX;
    private double homeZ;
    private boolean hasHome;

    // Which keys we are currently holding, so we never release keys the player is pressing.
    private boolean fwd, back, left, right, sneak;

    public ReturnToSpotPro() {
        super(AddonTemplate.CATEGORY, "return-to-spot-pro", "Walks you back to where you enabled it after you get pushed away.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) {
            hasHome = false;
            return;
        }
        homeX = mc.player.getX();
        homeZ = mc.player.getZ();
        if (snapToCenter.get()) {
            homeX = Math.floor(homeX) + 0.5;
            homeZ = Math.floor(homeZ) + 0.5;
        }
        hasHome = true;
        info("Spot saved: %.2f, %.2f", homeX, homeZ);
    }

    @Override
    public void onDeactivate() {
        releaseAll();
        hasHome = false;
    }

    @Override
    public String getInfoString() {
        return hasHome ? String.format("%.1f, %.1f", homeX, homeZ) : null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || !hasHome) {
            releaseAll();
            return;
        }
        if (pauseInScreens.get() && mc.currentScreen != null) {
            releaseAll();
            return;
        }

        double dx = homeX - mc.player.getX();
        double dz = homeZ - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= tolerance.get() || dist > maxDistance.get()) {
            releaseAll();
            return;
        }

        // Convert the world-space offset into forward/right components relative to where you look.
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);      // forward vector
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);     // right vector
        double forward = dx * fx + dz * fz;
        double side = dx * rx + dz * rz;

        double dead = Math.max(0.05, tolerance.get() * 0.5);

        set(mc.options.forwardKey, forward > dead, 0);
        set(mc.options.backKey, forward < -dead, 1);
        set(mc.options.rightKey, side > dead, 2);
        set(mc.options.leftKey, side < -dead, 3);
        set(mc.options.sneakKey, slowNear.get() && dist < 0.8, 4);
    }

    /** Presses or releases a key, only touching it if we are the ones holding it. */
    private void set(KeyBinding key, boolean wanted, int index) {
        boolean current = switch (index) {
            case 0 -> fwd;
            case 1 -> back;
            case 2 -> right;
            case 3 -> left;
            default -> sneak;
        };

        if (wanted && !current) {
            key.setPressed(true);
        } else if (!wanted && current) {
            key.setPressed(false);
        }

        switch (index) {
            case 0 -> fwd = wanted;
            case 1 -> back = wanted;
            case 2 -> right = wanted;
            case 3 -> left = wanted;
            default -> sneak = wanted;
        }
    }

    private void releaseAll() {
        if (mc.options == null) return;
        if (fwd) mc.options.forwardKey.setPressed(false);
        if (back) mc.options.backKey.setPressed(false);
        if (right) mc.options.rightKey.setPressed(false);
        if (left) mc.options.leftKey.setPressed(false);
        if (sneak) mc.options.sneakKey.setPressed(false);
        fwd = back = left = right = sneak = false;
    }
}
