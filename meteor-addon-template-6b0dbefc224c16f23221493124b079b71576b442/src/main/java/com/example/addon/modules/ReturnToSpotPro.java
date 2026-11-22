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
import net.minecraft.util.math.Vec3d;

/**
 * Nhớ vị trí bật module, tự đi về khi bị đẩy ra.
 *
 * FIX: chống giật khi bị push.
 *   - Khi dist <= BRAKE_RANGE: cancel horizontal velocity trước, rồi mới set input.
 *     Điều này ngăn overshoot → đổi hướng → giật kiểu ping-pong.
 *   - Khi dist <= SNAP_RANGE trên mặt đất: teleport chính xác 1 bước, zero velocity.
 *   - Khi đang đứng đúng chỗ (dist <= EPSILON): liên tục zero velocity để server
 *     knockback không tích lũy qua nhiều tick.
 *   - releaseAll() chỉ release key nào module đang giữ, không đụng key player đang bấm.
 */
public class ReturnToSpotPro extends Module {

    /** Bán kính snap chính xác cuối cùng (nhỏ hơn 1 bước đi = 0.2158 blocks). */
    private static final double SNAP_RANGE = 0.18;

    /** Gần hơn này = coi như đang đứng đúng chỗ. */
    private static final double EPSILON = 0.003;

    /**
     * Khi dist <= BRAKE_RANGE: bắt đầu damp horizontal velocity.
     * Lớn hơn SNAP_RANGE đủ để có vài tick brake trước khi snap.
     */
    private static final double BRAKE_RANGE = 0.55;

    /** Hệ số damp velocity khi đang trong vùng brake (mỗi tick nhân với số này). */
    private static final double BRAKE_FACTOR = 0.35;

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
        .defaultValue(8.0)
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

    private double homeX, homeZ;
    private boolean hasHome;

    // Trạng thái key đang được module giữ.
    private boolean fwd, back, left, right;

    public ReturnToSpotPro() {
        super(AddonTemplate.CATEGORY, "return-to-spot-pro",
            "Walks you back to exactly where you enabled it after you get pushed away.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) { hasHome = false; return; }
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

        // Quá xa (bị teleport / chết): không làm gì.
        if (dist > maxDistance.get()) {
            releaseAll();
            return;
        }

        // --- Chế độ đơn giản (exact = off): đi đến trong 0.2 block rồi thôi. ---
        if (!exact.get()) {
            if (dist <= SNAP_RANGE) releaseAll();
            else walkTowards(dx, dz);
            return;
        }

        // --- Chế độ chính xác ---

        // 1. Đứng đúng chỗ: giữ velocity = 0 liên tục để không bị trôi.
        if (dist <= EPSILON) {
            releaseAll();
            zeroHorizontalVelocity();
            return;
        }

        // 2. Snap cuối: 1 bước chính xác, zero velocity trước để không overshoot.
        if (dist <= SNAP_RANGE && mc.player.isOnGround()) {
            releaseAll();
            zeroHorizontalVelocity();
            mc.player.setVelocity(dx, mc.player.getVelocity().y, dz);
            return;
        }

        // 3. Vùng brake: damp horizontal velocity, bỏ input để không "đấu" với damp.
        //    Điều này ngăn module đi quá đà rồi quay lại → giật.
        if (dist <= BRAKE_RANGE) {
            releaseAll();
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity(v.x * BRAKE_FACTOR, v.y, v.z * BRAKE_FACTOR);
            // Vẫn set walking direction nhưng với tốc độ nhỏ hơn nhiều.
            // Không gọi walkTowards (sẽ set key full speed) mà chỉ nudge velocity trực tiếp.
            if (dist > EPSILON) {
                double len = dist;
                double nudgeScale = 0.04; // rất nhỏ, chỉ để giữ hướng
                mc.player.addVelocity(dx / len * nudgeScale, 0, dz / len * nudgeScale);
            }
            return;
        }

        // 4. Đi bình thường (đủ xa để dùng key input).
        walkTowards(dx, dz);
    }

    /**
     * Chuyển offset thế giới thành forward/right theo hướng nhìn, rồi set key.
     * Không đụng key nào player đang tự bấm.
     */
    private void walkTowards(double dx, double dz) {
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw),  fz = Math.cos(yaw);   // forward vector
        double rx = -Math.cos(yaw),  rz = -Math.sin(yaw);  // right vector
        double forward = dx * fx + dz * fz;
        double side    = dx * rx + dz * rz;
        double dead = 0.05;

        fwd   = set(mc.options.forwardKey, fwd,   forward >  dead);
        back  = set(mc.options.backKey,    back,   forward < -dead);
        right = set(mc.options.rightKey,   right,  side    >  dead);
        left  = set(mc.options.leftKey,    left,   side    < -dead);
    }

    /** Set/release một key; chỉ gọi setPressed khi trạng thái thực sự đổi. */
    private boolean set(KeyBinding key, boolean current, boolean wanted) {
        if (wanted && !current)  key.setPressed(true);
        else if (!wanted && current) key.setPressed(false);
        return wanted;
    }

    /** Release tất cả key module đang giữ. */
    private void releaseAll() {
        if (mc.options == null) return;
        if (fwd)   { mc.options.forwardKey.setPressed(false); fwd   = false; }
        if (back)  { mc.options.backKey.setPressed(false);    back  = false; }
        if (right) { mc.options.rightKey.setPressed(false);   right = false; }
        if (left)  { mc.options.leftKey.setPressed(false);    left  = false; }
    }

    /** Zero horizontal velocity — dùng để giữ player đứng yên trên spot. */
    private void zeroHorizontalVelocity() {
        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(0.0, v.y, 0.0);
    }
}
