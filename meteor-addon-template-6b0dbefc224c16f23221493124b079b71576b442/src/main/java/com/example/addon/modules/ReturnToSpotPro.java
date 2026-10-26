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
 * (blaze, hoglin knockback, ...). It walks with the normal movement keys, then finishes with one
 * small exact step so you end up on the saved coordinates. Your camera is never touched.
 */
public class ReturnToSpotPro extends Module {
    /** Largest correction done in a single tick (less than one normal walking step, 0.2158 blocks). */
    private static final double SNAP_RANGE = 0.2;
    /** Closer than this counts as "exactly on the spot": we just hold still. */
    private static final double EPSILON = 0.003;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> exact = sgGeneral.add(new BoolSetting.Builder()
        .name("exact-position")
        .description("Finish exactly on the saved coordinates. If off, it stops when within 0.2 blocks.")
        .defaultValue(true)
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
    private boolean fwd, back, left, right;

    public ReturnToSpotPro() {
        super(AddonTemplate.CATEGORY, "return-to-spot-pro", "Walks you back to exactly where you enabled it after you get pushed away.");
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
        info("Spot saved: %.3f, %.3f", homeX, homeZ);
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

        if (dist > maxDistance.get()) {
            releaseAll();
            return;
        }

        if (!exact.get()) {
            // Simple mode: just walk until within 0.2 blocks.
            if (dist <= SNAP_RANGE) {
                releaseAll();
            } else {
                walkTowards(dx, dz);
            }
            return;
        }

        // Exact mode.
        if (dist <= EPSILON) {
            // Standing on the spot: release keys and hold still.
            releaseAll();
            mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
            return;
        }

        if (dist <= SNAP_RANGE && mc.player.isOnGround()) {
            // Last small step: move exactly onto the spot this tick.
            releaseAll();
            mc.player.setVelocity(dx, mc.player.getVelocity().y, dz);
            return;
        }

        walkTowards(dx, dz);
    }

    private void walkTowards(double dx, double dz) {
        // Convert the world-space offset into forward/right components relative to where you look.
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);      // forward vector
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);     // right vector
        double forward = dx * fx + dz * fz;
        double side = dx * rx + dz * rz;

        double dead = 0.05;

        fwd = set(mc.options.forwardKey, fwd, forward > dead);
        back = set(mc.options.backKey, back, forward < -dead);
        right = set(mc.options.rightKey, right, side > dead);
        left = set(mc.options.leftKey, left, side < -dead);
    }

    /** Presses or releases a key, only touching it if we are the ones holding it. Returns the new state. */
    private boolean set(KeyBinding key, boolean current, boolean wanted) {
        if (wanted && !current) key.setPressed(true);
        else if (!wanted && current) key.setPressed(false);
        return wanted;
    }

    private void releaseAll() {
        if (mc.options == null) return;
        if (fwd) mc.options.forwardKey.setPressed(false);
        if (back) mc.options.backKey.setPressed(false);
        if (right) mc.options.rightKey.setPressed(false);
        if (left) mc.options.leftKey.setPressed(false);
        fwd = back = left = right = false;
    }
}
