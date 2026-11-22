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
import net.minecraft.entity.player.PlayerEntity;
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
 * AutoSellPro - v2 (latency-aware).
 *
 * Main change vs v1: a drop in the number of items inside the sell GUI is NO LONGER treated as a
 * rollback straight away. With high ping the server's replies to earlier clicks arrive while the
 * client has already predicted later clicks, so the GUI "flickers" back to an older state. v1 read
 * that as a rollback, closed the GUI in the middle of the sale and then spammed ~70 pickup/putback
 * clicks, which is what actually produced the ghost items.
 *
 * v2 sends a batch, WAITS for the server to answer (ack-wait-ticks, grows automatically with your
 * ping), and only then compares the real GUI content with what it expects. Only a drop that is
 * still there after the wait counts as a real rejection.
 */
public class AutoSellPro extends Module {
    private static final int INVENTORY_SIZE = 36;
    private static final String SELL_GUI_TITLE = "sell";
    private static final int EXPECTED_GUI_ROWS = 6;
    private static final int INPUT_SLOT_END = 44;
    private static final int CONFIRM_SLOT = 53;
    private static final int MAX_ROUNDS = 6;
    private static final int MAX_BACKOFF_SEC = 60;
    private static final int MAX_REJECTS_PER_SESSION = 3;
    private static final int MAX_ACK_EXTRA = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDelays = settings.createGroup("Delays");

    private final Setting<List<Item>> whitelist = sgGeneral.add(new ItemListSetting.Builder()
        .name("whitelist")
        .description("Whitelist of items to automatically sell.")
        .defaultValue(List.of())
        .build());

    private final Setting<Integer> minStacks = sgGeneral.add(new IntSetting.Builder()
        .name("min-stacks")
        .description("Auto-sell triggers when the inventory has no free slot AND at least this many whitelisted stacks.")
        .defaultValue(1).range(1, 36).sliderRange(1, 36)
        .build());

    private final Setting<Integer> manualCloseDelaySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("manual-close-cooldown")
        .description("Cooldown in seconds after manually closing the sell GUI before auto-selling resumes.")
        .defaultValue(10).range(0, 120).sliderRange(0, 60)
        .build());

    private final Setting<Boolean> pauseKillAura = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-killaura")
        .description("Temporarily pause KillAura while selling.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> resyncEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("ghost-item-resync")
        .description("After a failed/aborted sell session, touch the inventory slots to force the server to reconcile ghost items.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> resyncAlways = sgGeneral.add(new BoolSetting.Builder()
        .name("resync-always")
        .description("Also run the slot resync after every CLEAN sell (slow, ~2-4s of extra clicks). Leave off unless you still see ghost items after a good sell.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> debugLog = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Write every step to autosell-debug.log in the game folder.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> maxFails = sgGeneral.add(new IntSetting.Builder()
        .name("max-failed-sells")
        .description("Disable the module after this many failed sells in a row.")
        .defaultValue(3).range(1, 10).sliderRange(1, 10)
        .build());

    private final Setting<Integer> guiTimeoutSeconds = sgDelays.add(new IntSetting.Builder()
        .name("gui-timeout-seconds")
        .description("Maximum wait time in seconds for the sell GUI.")
        .defaultValue(5).range(1, 30).sliderRange(1, 15)
        .build());

    private final Setting<Integer> batchSize = sgDelays.add(new IntSetting.Builder()
        .name("batch-size")
        .description("Stacks shift-clicked per batch. Start low (2-3).")
        .defaultValue(3).range(1, 45).sliderRange(1, 15)
        .build());

    private final Setting<Integer> batchInterval = sgDelays.add(new IntSetting.Builder()
        .name("batch-interval")
        .description("Extra ticks to wait between two batches (on top of ack-wait-ticks).")
        .defaultValue(2).range(1, 20).sliderRange(1, 10)
        .build());

    private final Setting<Integer> batchJitter = sgDelays.add(new IntSetting.Builder()
        .name("batch-jitter")
        .description("Random extra 0..N ticks added to every wait.")
        .defaultValue(1).range(0, 10).sliderRange(0, 5)
        .build());

    private final Setting<Integer> ackWaitTicks = sgDelays.add(new IntSetting.Builder()
        .name("ack-wait-ticks")
        .description("Ticks to wait after each batch for the SERVER to answer before checking the GUI. Should be a bit more than your ping (ping 250ms = 5 ticks -> use 8). Grows automatically when the server keeps returning items.")
        .defaultValue(8).range(2, 40).sliderRange(2, 20)
        .build());

    private final Setting<Integer> stabilizeTicks = sgDelays.add(new IntSetting.Builder()
        .name("gui-stabilize-ticks")
        .description("Ticks to wait after the sell GUI opens before the first click.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10)
        .build());

    private final Setting<Integer> confirmDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("confirm-delay")
        .description("Ticks to wait after moving items before clicking confirm.")
        .defaultValue(3).range(0, 20).sliderRange(0, 10)
        .build());

    private final Setting<Integer> postSellDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("post-sell-delay")
        .description("Ticks to wait after confirming for the server to process the sale.")
        .defaultValue(6).range(0, 40).sliderRange(0, 20)
        .build());

    private final Setting<Integer> cooldownSeconds = sgDelays.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Minimum pause after a finished sell session. Doubles after every failed sell.")
        .defaultValue(2).range(0, 60).sliderRange(0, 20)
        .build());

    private final Setting<Integer> resyncDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("resync-delay")
        .description("Ticks to wait after the sell GUI closes before starting the inventory resync.")
        .defaultValue(8).range(0, 40).sliderRange(0, 20)
        .build());

    private State state = State.IDLE;
    private int stateTicks;
    private int sessionSyncId = -1;
    private int round;
    private int movedLastRound;
    private boolean forceSell;
    private boolean pausedKillAura;
    private long cooldownEndTime;
    private boolean sessionFailed;
    private int adaptiveBatch;
    private int slowTicks;
    private int cleanStreak;
    private int whitelistedBefore;
    private int failStreak;
    private boolean resyncVerify;
    private long resyncCooldownMs;
    private boolean queueBuilt;
    private final ArrayDeque<Integer> touchQueue = new ArrayDeque<>();
    private int pendingPutBack = -1;

    // v2: server-ack tracking
    private int expectedFilled = -1;   // how many GUI input slots we expect to be filled
    private boolean verifyPending;     // a batch was sent and still has to be verified
    private int ackWait;               // ticks left to wait for the server's answer
    private int ackExtra;              // adaptive extra ticks (grows with rejects / latency)
    private int rejects;               // real rejections in this session
    private boolean guiRetried;        // /sell re-sent once while waiting for the GUI
    private int resyncGap;             // pause between resync slot touches
    private boolean resyncNeeded;      // did this session need a resync at all

    public AutoSellPro() {
        super(AddonTemplate.CATEGORY, "auto-sell-pro",
            "Bind to Z: sells all whitelisted items instantly via /sell, then keeps auto-selling whenever the inventory is full.");
    }

    @Override
    public void onActivate() {
        reset();
        failStreak = 0;
        adaptiveBatch = batchSize.get();
        slowTicks = 0;
        cleanStreak = 0;
        ackExtra = 0;
        forceSell = true;
    }

    @Override
    public void onDeactivate() {
        restoreKillAura();
        // Do not leave the sell GUI open with clicks in flight.
        if (mc.player != null && isSellScreen()) mc.player.closeHandledScreen();
        reset();
    }

    @Override
    public String getInfoString() {
        return state == State.IDLE ? null : state.name();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) return;
        stateTicks++;
        try {
            switch (state) {
                case IDLE -> tickIdle();
                case WAITING_GUI -> tickWaitingGui();
                case STABILIZING -> tickStabilizing();
                case MOVING -> tickMoving();
                case CONFIRMING -> tickConfirming();
                case WAITING_SALE -> tickWaitingSale();
                case RESYNC -> tickResync();
                case RESYNC_PUTBACK -> tickResyncPutBack();
            }
        } catch (Exception e) {
            error("AutoSellPro hit an error, aborting session: " + e.getMessage());
            abort(false);
        }
    }

    // ------------------------------------------------------------------ states

    private void tickIdle() {
        if (System.currentTimeMillis() < cooldownEndTime) return;
        if (mc.currentScreen != null) return;

        boolean trigger = forceSell || shouldAutoSell();
        if (!trigger) return;

        int count = countWhitelistedInInventory();
        if (count == 0) {
            forceSell = false;
            info("No whitelisted items to sell.");
            return;
        }

        forceSell = false;
        round = 0;
        movedLastRound = 0;
        whitelistedBefore = count;
        expectedFilled = -1;
        verifyPending = false;
        ackWait = 0;
        rejects = 0;
        guiRetried = false;

        dbg("start sell, whitelisted=" + count + " batch=" + adaptiveBatch + " slow=" + slowTicks + " ackExtra=" + ackExtra);
        mc.getNetworkHandler().sendChatCommand(SELL_GUI_TITLE);
        setState(State.WAITING_GUI);
    }

    private void tickWaitingGui() {
        if (isSellScreen()) {
            sessionSyncId = mc.player.currentScreenHandler.syncId;
            pauseKillAuraNow();
            setState(State.STABILIZING);
            return;
        }

        int timeoutTicks = guiTimeoutSeconds.get() * 20;

        // The server sometimes swallows the first /sell (rate limit / lag). Retry once.
        if (!guiRetried && stateTicks > timeoutTicks / 2 && mc.currentScreen == null) {
            guiRetried = true;
            dbg("no sell GUI yet, re-sending /sell");
            mc.getNetworkHandler().sendChatCommand(SELL_GUI_TITLE);
        }

        if (stateTicks > timeoutTicks) {
            warning("Timed out waiting for the sell GUI.");
            abort(false);
        }
    }

    private void tickStabilizing() {
        if (!ownsSellGui()) {
            abortManualClose();
            return;
        }
        if (stateTicks >= stabilizeTicks.get()) {
            expectedFilled = 0;
            verifyPending = false;
            ackWait = 0;
            setState(State.MOVING);
        }
    }

    private void tickMoving() {
        if (!ownsSellGui()) {
            abortManualClose();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        int invStart = handler.slots.size() - INVENTORY_SIZE;

        // 1) Give the server time to answer the last batch. Do NOT judge the GUI yet.
        if (ackWait > 0) {
            ackWait--;
            return;
        }

        // 2) The server had its time: now compare reality with what we expect.
        if (verifyPending) {
            verifyPending = false;
            int actual = guiFilled(handler);
            dbg("verify: expected=" + expectedFilled + " actual=" + actual);
            if (actual < expectedFilled) {
                onReject(actual);
                return;
            }
        }

        // 3) Nothing left to move (or GUI full) -> confirm what is inside.
        if (!hasMovableLeft(handler, invStart)) {
            if (guiFilled(handler) == 0) {
                if (round == 0) warning("Sell GUI input slots are full or nothing could be moved.");
                finish(true);
                return;
            }
            setState(State.CONFIRMING);
            return;
        }

        // 4) Send the next batch.
        if (adaptiveBatch <= 0) adaptiveBatch = batchSize.get();
        int limit = Math.max(1, Math.min(adaptiveBatch, batchSize.get()));
        int moved = 0;
        for (int slot = invStart; slot < handler.slots.size() && moved < limit && freeInputSlots(handler) != 0; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || !whitelist.get().contains(stack.getItem())) continue;
            mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            moved++;
        }

        movedLastRound += moved;
        expectedFilled = guiFilled(handler);
        verifyPending = true;

        int jitter = batchJitter.get();
        ackWait = Math.max(1, ackWaitTicks.get() + ackExtra + slowTicks + batchInterval.get() - 1
            + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0));

        dbg("batch moved=" + moved + " limit=" + limit + " guiFilled=" + expectedFilled + " ackWait=" + ackWait);

        if (moved == 0) {
            // freeInputSlots hit 0 mid-loop; nothing sent, go straight to confirm.
            verifyPending = false;
            ackWait = 0;
            if (guiFilled(handler) == 0) {
                finish(true);
            } else {
                setState(State.CONFIRMING);
            }
        }
    }

    private void tickConfirming() {
        if (!ownsSellGui()) {
            abortManualClose();
            return;
        }
        if (stateTicks < confirmDelayTicks.get()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int actual = guiFilled(handler);

        if (actual < expectedFilled) {
            // Items disappeared from the GUI even after we waited for the server.
            dbg("confirm check: expected=" + expectedFilled + " actual=" + actual);
            onReject(actual);
            return;
        }
        if (actual == 0) {
            warning("Server returned all moved items, cancelling this sell.");
            sessionFailed = true;
            failStreak++;
            beginResync(true, false, 0L);
            return;
        }

        mc.interactionManager.clickSlot(handler.syncId, CONFIRM_SLOT, 0, SlotActionType.PICKUP, mc.player);
        setState(State.WAITING_SALE);
    }

    private void tickWaitingSale() {
        if (!ownsSellGui()) {
            finish(false);
            return;
        }
        if (stateTicks < postSellDelayTicks.get() + ackExtra) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int invStart = handler.slots.size() - INVENTORY_SIZE;

        if (round + 1 < MAX_ROUNDS && movedLastRound > 0 && hasMovableLeft(handler, invStart)) {
            round++;
            movedLastRound = 0;
            expectedFilled = 0;
            verifyPending = false;
            ackWait = 0;
            setState(State.MOVING);
            return;
        }
        finish(true);
    }

    // ------------------------------------------------------------------ resync (only when needed)

    private void tickResync() {
        if (isSellScreen()) {
            mc.player.closeHandledScreen();
            return;
        }
        if (stateTicks < resyncDelayTicks.get() + ackExtra) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean isPlayerInv = mc.currentScreen == null && handler == mc.player.playerScreenHandler;
        if (!isPlayerInv) return;

        if (!resyncEnabled.get() || !(resyncNeeded || resyncAlways.get())) {
            completeSession();
            return;
        }

        if (resyncGap > 0) {
            resyncGap--;
            return;
        }

        if (!queueBuilt) {
            touchQueue.clear();
            pendingPutBack = -1;
            for (int slot = 9; slot <= INPUT_SLOT_END; slot++) {
                if (handler.getSlot(slot).getStack().isEmpty()) continue;
                touchQueue.add(slot);
            }
            queueBuilt = true;
            dbg("resync queue built: " + touchQueue.size() + " slots to touch");
        }

        Integer slot = touchQueue.poll();
        if (slot == null) {
            completeSession();
            return;
        }

        dbg("resync pickup slot " + slot);
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        pendingPutBack = slot;
        state = State.RESYNC_PUTBACK;
    }

    private void tickResyncPutBack() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean isPlayerInv = mc.currentScreen == null && handler == mc.player.playerScreenHandler;
        if (!isPlayerInv) {
            pendingPutBack = -1;
            completeSession();
            return;
        }
        if (pendingPutBack >= 0) {
            dbg("resync putback slot " + pendingPutBack);
            mc.interactionManager.clickSlot(handler.syncId, pendingPutBack, 0, SlotActionType.PICKUP, mc.player);
            pendingPutBack = -1;
        }
        resyncGap = 2; // breathe between touches, the server does not like a constant click stream
        state = State.RESYNC;
    }

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
            cooldownMs = (long) cooldownSeconds.get() * 1000L;
            if (++cleanStreak >= 3) {
                cleanStreak = 0;
                if (slowTicks > 0) slowTicks--;
                else if (ackExtra > 0) ackExtra--;
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
        resyncVerify = verify;
        resyncCooldownMs = cooldownMs;
        // A clean, verified session needs no resync. Failures/aborts do.
        resyncNeeded = !verify || sessionFailed;
        queueBuilt = false;
        touchQueue.clear();
        pendingPutBack = -1;
        resyncGap = 0;
        setState(State.RESYNC);
    }

    private void finish(boolean closeGui) {
        beginResync(closeGui, true, (long) cooldownSeconds.get() * 1000L);
    }

    private void abort(boolean closeGui) {
        beginResync(closeGui, false, (long) cooldownSeconds.get() * 1000L);
    }

    private void abortManualClose() {
        info("Sell GUI closed. Pausing auto-sell for " + manualCloseDelaySeconds.get() + " seconds.");
        beginResync(false, false, (long) manualCloseDelaySeconds.get() * 1000L);
    }

    /**
     * The GUI holds fewer items than expected EVEN AFTER the server had time to answer:
     * wait longer / go slower and just re-move the missing stacks. Close the GUI only if it keeps happening.
     */
    private void onReject(int actual) {
        rejects++;
        ackExtra = Math.min(MAX_ACK_EXTRA, ackExtra + 2);
        adaptiveBatch = Math.max(1, adaptiveBatch / 2);
        slowTicks = Math.min(4, slowTicks + 1);
        cleanStreak = 0;
        dbg("REJECT #" + rejects + " expected=" + expectedFilled + " actual=" + actual + " ackExtra=" + ackExtra + " batch=" + adaptiveBatch);

        if (rejects > MAX_REJECTS_PER_SESSION) {
            sessionFailed = true;
            failStreak++;
            warning("Server keeps returning items. Slowing down: batch " + adaptiveBatch + ", ack +" + ackExtra
                + " ticks. (" + failStreak + "/" + maxFails.get() + ")");
            beginResync(true, false, 0L);
            return;
        }

        expectedFilled = actual;
        verifyPending = false;
        ackWait = ackWaitTicks.get() + ackExtra;
        if (state != State.MOVING) setState(State.MOVING);
    }

    private void reset() {
        state = State.IDLE;
        stateTicks = 0;
        sessionSyncId = -1;
        round = 0;
        movedLastRound = 0;
        queueBuilt = false;
        touchQueue.clear();
        pendingPutBack = -1;
        expectedFilled = -1;
        verifyPending = false;
        ackWait = 0;
        rejects = 0;
        guiRetried = false;
        resyncGap = 0;
        resyncNeeded = false;
        sessionFailed = false;
    }

    private void setState(State next) {
        dbg("state " + state + " -> " + next);
        state = next;
        stateTicks = 0;
    }

    private void dbg(String msg) {
        if (!debugLog.get()) return;
        try {
            Files.writeString(Path.of("autosell-debug.log"),
                LocalTime.now() + " [" + state + "] " + msg + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean shouldAutoSell() {
        int free = 0;
        int whitelisted = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) {
                free++;
                continue;
            }
            if (whitelist.get().contains(s.getItem())) whitelisted++;
        }
        return free == 0 && whitelisted >= minStacks.get();
    }

    private int countWhitelistedInInventory() {
        int n = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty() || !whitelist.get().contains(s.getItem())) continue;
            n++;
        }
        return n;
    }

    private boolean hasMovableLeft(ScreenHandler handler, int invStart) {
        if (freeInputSlots(handler) == 0) return false;
        for (int i = invStart; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || !whitelist.get().contains(stack.getItem())) continue;
            return true;
        }
        return false;
    }

    private int freeInputSlots(ScreenHandler handler) {
        int free = 0;
        for (int i = 0; i <= INPUT_SLOT_END; i++) {
            if (!handler.getSlot(i).hasStack()) free++;
        }
        return free;
    }

    private int guiFilled(ScreenHandler handler) {
        return (INPUT_SLOT_END + 1) - freeInputSlots(handler);
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
        if (ka != null && ka.isActive()) {
            ka.toggle();
            pausedKillAura = true;
        }
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
        RESYNC_PUTBACK
    }
}
