package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AutoSellPro — ghost-item fix.
 *
 * FIX so với bản cũ:
 *
 * 1. RESYNC touch toàn bộ inventory (không chỉ whitelist).
 *    Khi server rollback, ghost item có thể xuất hiện ở bất kỳ slot nào
 *    mà shift-click đã "nhảy" qua, không chỉ whitelist slots.
 *    → touchQueue bây giờ cover slot 9-44 (toàn bộ main inventory + hotbar).
 *
 * 2. RESYNC dùng window ID đúng.
 *    Bản cũ dùng handler.syncId == 0 (player inventory) nhưng sau khi GUI đóng,
 *    syncId có thể vẫn là giá trị cũ trong 1-2 tick. Fix: chờ đến khi
 *    mc.player.currentScreenHandler == mc.player.playerScreenHandler (inventory thật).
 *
 * 3. Touch pattern đúng hơn.
 *    Bản cũ: pickup + pickup trong cùng 1 tick (2 packet cùng lúc).
 *    Server xử lý 2 packet liên tiếp có thể bỏ qua cái thứ 2 vì inventory đang thay đổi.
 *    Fix: pickup tick N, put-back tick N+1 (PICKUP_ALL -> DROP_ALL với empty cursor).
 *    Mỗi slot tốn 2 tick nhưng reliable hơn nhiều.
 *
 * 4. Thêm RESYNC_PUTBACK state để handle tick-split ở trên.
 */
public class AutoSellPro extends Module {
    private static final int INVENTORY_SIZE   = 36;
    private static final String SELL_GUI_TITLE = "sell";
    private static final int EXPECTED_GUI_ROWS = 6;
    private static final int INPUT_SLOT_END    = 44;
    private static final int CONFIRM_SLOT      = 53;
    private static final int MAX_ROUNDS        = 6;
    private static final int MAX_BACKOFF_SEC   = 60;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDelays  = settings.createGroup("Delays");

    private final Setting<List<Item>> whitelist = sgGeneral.add(new ItemListSetting.Builder()
        .name("whitelist")
        .description("Whitelist of items to automatically sell.")
        .defaultValue(List.of())
        .build());

    private final Setting<Integer> minStacks = sgGeneral.add(new IntSetting.Builder()
        .name("min-stacks")
        .description("Auto-sell triggers when the inventory has no free slot AND at least this many whitelisted stacks.")
        .defaultValue(1).range(1, 36).sliderRange(1, 36).build());

    private final Setting<Integer> manualCloseDelaySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("manual-close-cooldown")
        .description("Cooldown in seconds after manually closing the sell GUI before auto-selling resumes.")
        .defaultValue(10).range(0, 120).sliderRange(0, 60).build());

    private final Setting<Boolean> pauseKillAura = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-killaura")
        .description("Temporarily pause KillAura while selling.")
        .defaultValue(true).build());

    private final Setting<Boolean> resyncEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("ghost-item-resync")
        .description("After every sell session, touch every inventory slot to force the server to reconcile ghost items.")
        .defaultValue(true).build());

    private final Setting<Boolean> debugLog = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Write every step to autosell-debug.log in the game folder.")
        .defaultValue(false).build());

    private final Setting<Integer> maxFails = sgGeneral.add(new IntSetting.Builder()
        .name("max-failed-sells")
        .description("Disable the module after this many failed sells in a row.")
        .defaultValue(3).range(1, 10).sliderRange(1, 10).build());

    private final Setting<Integer> guiTimeoutSeconds = sgDelays.add(new IntSetting.Builder()
        .name("gui-timeout-seconds").description("Maximum wait time in seconds for the sell GUI.")
        .defaultValue(5).range(1, 30).sliderRange(1, 15).build());

    private final Setting<Integer> batchSize = sgDelays.add(new IntSetting.Builder()
        .name("batch-size")
        .description("Stacks shift-clicked per batch. Start low (2-3).")
        .defaultValue(3).range(1, 45).sliderRange(1, 15).build());

    private final Setting<Integer> batchInterval = sgDelays.add(new IntSetting.Builder()
        .name("batch-interval").description("Ticks to wait between two batches of clicks.")
        .defaultValue(2).range(1, 20).sliderRange(1, 10).build());

    private final Setting<Integer> batchJitter = sgDelays.add(new IntSetting.Builder()
        .name("batch-jitter").description("Random extra 0..N ticks added to every batch interval.")
        .defaultValue(1).range(0, 10).sliderRange(0, 5).build());

    private final Setting<Integer> stabilizeTicks = sgDelays.add(new IntSetting.Builder()
        .name("gui-stabilize-ticks").description("Ticks to wait after the sell GUI opens before the first click.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10).build());

    private final Setting<Integer> confirmDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("confirm-delay").description("Ticks to wait after moving items before clicking confirm.")
        .defaultValue(3).range(0, 20).sliderRange(0, 10).build());

    private final Setting<Integer> postSellDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("post-sell-delay").description("Ticks to wait after confirming for the server to process the sale.")
        .defaultValue(6).range(0, 40).sliderRange(0, 20).build());

    private final Setting<Integer> cooldownSeconds = sgDelays.add(new IntSetting.Builder()
        .name("cooldown-seconds").description("Minimum pause after a finished sell session. Doubles after every failed sell.")
        .defaultValue(2).range(0, 60).sliderRange(0, 20).build());

    private final Setting<Integer> resyncDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("resync-delay").description("Ticks to wait after the sell GUI closes before starting the inventory resync.")
        .defaultValue(8).range(0, 40).sliderRange(0, 20).build());

    // --- runtime state ---
    private State state = State.IDLE;
    private int   stateTicks;
    private int   sessionSyncId = -1;
    private int   round;
    private int   movedLastRound;
    private boolean forceSell;
    private boolean pausedKillAura;
    private long    cooldownEndTime;

    private boolean sessionFailed;
    private int     adaptiveBatch;
    private int     slowTicks;
    private int     cleanStreak;
    private int     moveWait;
    private int     expectedInvWl = -1;
    private int     whitelistedBefore;
    private int     failStreak;
    private boolean resyncVerify;
    private long    resyncCooldownMs;

    // FIX 1+3: touch queue split across two states
    private boolean           queueBuilt;
    private final ArrayDeque<Integer> touchQueue    = new ArrayDeque<>();
    private int               pendingPutBack = -1; // slot waiting for put-back tick

    public AutoSellPro() {
        super(AddonTemplate.CATEGORY, "auto-sell-pro",
            "Bind to Z: sells all whitelisted items instantly via /sell, then keeps auto-selling whenever the inventory is full.");
    }

    @Override public void onActivate() {
        reset();
        failStreak    = 0;
        adaptiveBatch = batchSize.get();
        slowTicks     = 0;
        cleanStreak   = 0;
        forceSell     = true;
    }

    @Override public void onDeactivate() {
        restoreKillAura();
        reset();
    }

    @Override public String getInfoString() {
        return state == State.IDLE ? null : state.name();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) return;
        stateTicks++;
        try {
            switch (state) {
                case IDLE          -> tickIdle();
                case WAITING_GUI   -> tickWaitingGui();
                case STABILIZING   -> tickStabilizing();
                case MOVING        -> tickMoving();
                case CONFIRMING    -> tickConfirming();
                case WAITING_SALE  -> tickWaitingSale();
                case RESYNC        -> tickResync();
                case RESYNC_PUTBACK -> tickResyncPutBack();
            }
        } catch (Exception e) {
            error("AutoSellPro hit an error, aborting session: " + e.getMessage());
            abort(false);
        }
    }

    // ------------------------------------------------------------------ IDLE
    private void tickIdle() {
        if (System.currentTimeMillis() < cooldownEndTime) return;
        if (mc.currentScreen != null) return;

        boolean trigger = forceSell || shouldAutoSell();
        if (!trigger) return;

        int count = countWhitelistedInInventory();
        if (count == 0) { forceSell = false; info("No whitelisted items to sell."); return; }

        forceSell        = false;
        round            = 0;
        movedLastRound   = 0;
        whitelistedBefore = count;
        expectedInvWl    = -1;
        moveWait         = 0;
        dbg("start sell, whitelisted=" + count + " batch=" + adaptiveBatch + " slow=" + slowTicks);
        mc.getNetworkHandler().sendChatCommand("sell");
        setState(State.WAITING_GUI);
    }

    // ------------------------------------------------------------ WAITING_GUI
    private void tickWaitingGui() {
        if (isSellScreen()) {
            sessionSyncId = mc.player.currentScreenHandler.syncId;
            pauseKillAuraNow();
            setState(State.STABILIZING);
            return;
        }
        if (stateTicks > guiTimeoutSeconds.get() * 20) {
            warning("Timed out waiting for the sell GUI.");
            abort(false);
        }
    }

    // ------------------------------------------------------------ STABILIZING
    private void tickStabilizing() {
        if (!ownsSellGui()) { abortManualClose(); return; }
        if (stateTicks >= stabilizeTicks.get()) setState(State.MOVING);
    }

    // --------------------------------------------------------------- MOVING
    private void tickMoving() {
        if (!ownsSellGui()) { abortManualClose(); return; }

        ScreenHandler handler = mc.player.currentScreenHandler;
        int invStart = handler.slots.size() - INVENTORY_SIZE;

        if (rollbackDetected(handler)) { onRollback(); return; }
        if (moveWait > 0) { moveWait--; return; }

        if (adaptiveBatch <= 0) adaptiveBatch = batchSize.get();
        int limit = Math.max(1, Math.min(adaptiveBatch, batchSize.get()));
        int moved = 0;

        for (int slot = invStart; slot < handler.slots.size() && moved < limit; slot++) {
            if (freeInputSlots(handler) == 0) break;
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || !whitelist.get().contains(stack.getItem())) continue;
            mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            moved++;
        }

        movedLastRound = moved;
        expectedInvWl  = guiFilled(handler);
        dbg("batch moved=" + moved + " limit=" + limit + " guiFilled=" + expectedInvWl);

        if (moved == 0) { if (round == 0) warning("Sell GUI input slots are full or nothing could be moved."); finish(true); return; }

        if (hasMovableLeft(handler, invStart)) {
            int jitter = batchJitter.get();
            moveWait = batchInterval.get() + slowTicks + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0) - 1;
            return;
        }

        setState(State.CONFIRMING);
    }

    // ------------------------------------------------------------ CONFIRMING
    private void tickConfirming() {
        if (!ownsSellGui()) { abortManualClose(); return; }
        if (stateTicks < confirmDelayTicks.get()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;

        if (rollbackDetected(handler)) { onRollback(); return; }
        if (freeInputSlots(handler) >= INPUT_SLOT_END + 1) {
            warning("Server rolled back the moved items, cancelling this sell.");
            finish(true);
            return;
        }

        mc.interactionManager.clickSlot(handler.syncId, CONFIRM_SLOT, 0, SlotActionType.PICKUP, mc.player);
        setState(State.WAITING_SALE);
    }

    // ---------------------------------------------------------- WAITING_SALE
    private void tickWaitingSale() {
        if (!ownsSellGui()) { finish(false); return; }
        if (stateTicks < postSellDelayTicks.get()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int invStart = handler.slots.size() - INVENTORY_SIZE;

        if (round + 1 < MAX_ROUNDS && movedLastRound > 0 && hasMovableLeft(handler, invStart)) {
            round++;
            expectedInvWl = -1;
            moveWait = 0;
            setState(State.MOVING);
            return;
        }

        finish(true);
    }

    // ---------------------------------------------------------------- RESYNC
    /**
     * FIX 2: Chờ đến khi handler thực sự là player inventory (syncId 0 và không có GUI mở).
     * FIX 1: Touch toàn bộ slot 9-44 (không chỉ whitelist).
     * FIX 3: Chỉ gửi PICKUP trong tick này, put-back sang RESYNC_PUTBACK tick sau.
     */
    private void tickResync() {
        // Đóng GUI nếu vẫn còn mở
        if (isSellScreen()) {
            mc.player.closeHandledScreen();
            return;
        }

        // Chờ đủ tick sau khi GUI đóng
        if (stateTicks < resyncDelayTicks.get()) return;

        // FIX 2: Chờ đến khi thực sự đang dùng player inventory handler
        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean isPlayerInv = (mc.currentScreen == null) && (handler == mc.player.playerScreenHandler);
        if (!isPlayerInv) return;

        if (!resyncEnabled.get()) {
            completeSession();
            return;
        }

        // Build queue lần đầu
        if (!queueBuilt) {
            touchQueue.clear();
            pendingPutBack = -1;
            // FIX 1: touch toàn bộ main inventory + hotbar (slot 9-44 trong player inventory).
            // Slot 0-8 = crafting + armor — không touch, không liên quan đến sell.
            // Slot 9-35 = main inventory, 36-44 = hotbar.
            for (int slot = 9; slot <= 44; slot++) {
                // Chỉ touch slot có item, không cần touch slot rỗng.
                if (!handler.getSlot(slot).getStack().isEmpty()) {
                    touchQueue.add(slot);
                }
            }
            queueBuilt = true;
            dbg("resync queue built: " + touchQueue.size() + " slots to touch");
        }

        // Poll slot tiếp theo và gửi PICKUP
        Integer slot = touchQueue.poll();
        if (slot == null) {
            // Queue rỗng → xong
            completeSession();
            return;
        }

        // FIX 3: Chỉ PICKUP tick này, lưu slot để put-back ở tick sau
        dbg("resync pickup slot " + slot);
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        pendingPutBack = slot;
        // Chuyển sang RESYNC_PUTBACK để put-back tick sau
        // Không dùng setState() vì không muốn reset stateTicks — giữ nguyên bộ đếm
        state = State.RESYNC_PUTBACK;
    }

    // -------------------------------------------------------- RESYNC_PUTBACK
    /**
     * FIX 3: Put-back item vừa pickup ở tick trước.
     * Nếu slot đó là ghost item → server sẽ không có gì để put-back và sẽ
     * gửi SetSlot packet xóa ghost item trên client.
     */
    private void tickResyncPutBack() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean isPlayerInv = (mc.currentScreen == null) && (handler == mc.player.playerScreenHandler);
        if (!isPlayerInv) {
            // Mất inventory access, dừng resync
            pendingPutBack = -1;
            completeSession();
            return;
        }

        if (pendingPutBack >= 0) {
            dbg("resync putback slot " + pendingPutBack);
            mc.interactionManager.clickSlot(handler.syncId, pendingPutBack, 0, SlotActionType.PICKUP, mc.player);
            pendingPutBack = -1;
        }

        // Tiếp tục tick RESYNC ở tick sau
        state = State.RESYNC;
    }

    // ---------------------------------------------------------------- helpers
    private void completeSession() {
        long cooldownMs = resyncCooldownMs;
        boolean disableModule = false;

        if (sessionFailed) {
            long base = Math.max(1, cooldownSeconds.get());
            cooldownMs = Math.min(MAX_BACKOFF_SEC, base << Math.min(failStreak, 6)) * 1000L;
            if (failStreak >= maxFails.get()) {
                warning("Too many failed sells in a row - disabling auto-sell to avoid more ghost items.");
                disableModule = true;
            }
        } else if (resyncVerify) {
            dbg("session ok, whitelisted before=" + whitelistedBefore + " now=" + countWhitelistedInInventory());
            failStreak = 0;
            cooldownMs = cooldownSeconds.get() * 1000L;
            if (++cleanStreak >= 3) {
                cleanStreak = 0;
                if (slowTicks > 0) slowTicks--;
                else adaptiveBatch = Math.min(batchSize.get(), adaptiveBatch + 1);
            }
        }

        restoreKillAura();
        reset();
        cooldownEndTime = System.currentTimeMillis() + cooldownMs;
        if (disableModule) toggle();
    }

    private void beginResync(boolean closeGui, boolean verify, long cooldownMs) {
        if (closeGui && mc.player != null && isSellScreen()) mc.player.closeHandledScreen();
        restoreKillAura();
        resyncVerify    = verify;
        resyncCooldownMs = cooldownMs;
        queueBuilt      = false;
        touchQueue.clear();
        pendingPutBack  = -1;
        setState(State.RESYNC);
    }

    private void finish(boolean closeGui) { beginResync(closeGui, true,  cooldownSeconds.get() * 1000L); }
    private void abort (boolean closeGui) { beginResync(closeGui, false, cooldownSeconds.get() * 1000L); }

    private void abortManualClose() {
        info("Sell GUI closed. Pausing auto-sell for " + manualCloseDelaySeconds.get() + " seconds.");
        beginResync(false, false, manualCloseDelaySeconds.get() * 1000L);
    }

    private void onRollback() {
        int before = adaptiveBatch;
        adaptiveBatch = Math.max(1, adaptiveBatch / 2);
        slowTicks     = Math.min(4, slowTicks + 1);
        cleanStreak   = 0;
        failStreak++;
        sessionFailed = true;
        warning("Server returned items (rate limit). Slowing: batch " + before + " -> " + adaptiveBatch
            + ", +" + slowTicks + " ticks. (" + failStreak + "/" + maxFails.get() + ")");
        dbg("ROLLBACK, guiFilled dropped below " + expectedInvWl);
        beginResync(true, false, 0);
    }

    private boolean rollbackDetected(ScreenHandler handler) {
        return expectedInvWl >= 0 && guiFilled(handler) < expectedInvWl;
    }

    private void reset() {
        state          = State.IDLE;
        stateTicks     = 0;
        sessionSyncId  = -1;
        round          = 0;
        movedLastRound = 0;
        queueBuilt     = false;
        touchQueue.clear();
        pendingPutBack = -1;
        expectedInvWl  = -1;
        moveWait       = 0;
        sessionFailed  = false;
    }

    private void setState(State next) {
        dbg("state " + state + " -> " + next);
        state      = next;
        stateTicks = 0;
    }

    private void dbg(String msg) {
        if (!debugLog.get()) return;
        try {
            Files.writeString(Path.of("autosell-debug.log"),
                LocalTime.now() + " [" + state + "] " + msg + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private boolean shouldAutoSell() {
        int free = 0, whitelisted = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) free++;
            else if (whitelist.get().contains(s.getItem())) whitelisted++;
        }
        return free == 0 && whitelisted >= minStacks.get();
    }

    private int countWhitelistedInInventory() {
        int n = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && whitelist.get().contains(s.getItem())) n++;
        }
        return n;
    }

    private boolean hasMovableLeft(ScreenHandler handler, int invStart) {
        if (freeInputSlots(handler) == 0) return false;
        for (int i = invStart; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && whitelist.get().contains(stack.getItem())) return true;
        }
        return false;
    }

    private int freeInputSlots(ScreenHandler handler) {
        int free = 0;
        for (int i = 0; i <= INPUT_SLOT_END; i++) if (!handler.getSlot(i).hasStack()) free++;
        return free;
    }

    private int guiFilled(ScreenHandler handler) {
        return INPUT_SLOT_END + 1 - freeInputSlots(handler);
    }

    private boolean isSellScreen() {
        if (mc.currentScreen == null || mc.player == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!(handler instanceof GenericContainerScreenHandler container)) return false;
        if (container.getRows() != EXPECTED_GUI_ROWS) return false;
        return mc.currentScreen.getTitle().getString().toLowerCase().contains(SELL_GUI_TITLE);
    }

    private boolean ownsSellGui() {
        return isSellScreen() && mc.player.currentScreenHandler.syncId == sessionSyncId;
    }

    private void pauseKillAuraNow() {
        if (!pauseKillAura.get() || pausedKillAura) return;
        KillAura ka = Modules.get().get(KillAura.class);
        if (ka != null && ka.isActive()) { ka.toggle(); pausedKillAura = true; }
    }

    private void restoreKillAura() {
        if (!pausedKillAura) return;
        KillAura ka = Modules.get().get(KillAura.class);
        if (ka != null && !ka.isActive()) ka.toggle();
        pausedKillAura = false;
    }

    private enum State {
        IDLE,
        WAITING_GUI,
        STABILIZING,
        MOVING,
        CONFIRMING,
        WAITING_SALE,
        RESYNC,
        RESYNC_PUTBACK   // FIX 3: tick thứ 2 của mỗi slot touch
    }
}
