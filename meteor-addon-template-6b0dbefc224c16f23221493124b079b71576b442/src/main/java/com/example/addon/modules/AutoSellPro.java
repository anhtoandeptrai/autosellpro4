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
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** AutoSellPro - ghost-item fix (batching, rollback detection, resync). */
public class AutoSellPro extends Module {
    private static final int INVENTORY_SIZE = 36;
    private static final String SELL_GUI_TITLE = "sell";
    private static final int EXPECTED_GUI_ROWS = 6;
    private static final int INPUT_SLOT_END = 44;
    private static final int CONFIRM_SLOT = 53;
    private static final int MAX_ROUNDS = 6;
    private static final int MAX_BACKOFF_SECONDS = 60;

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
        .defaultValue(1)
        .range(1, 36)
        .sliderRange(1, 36)
        .build());

    private final Setting<Integer> manualCloseDelaySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("manual-close-cooldown")
        .description("Cooldown in seconds after manually closing the sell GUI before auto-selling resumes.")
        .defaultValue(10)
        .range(0, 120)
        .sliderRange(0, 60)
        .build());

    private final Setting<Boolean> pauseKillAura = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-killaura")
        .description("Temporarily pause KillAura while selling.")
        .defaultValue(true)
        .build());

    // ---- NEW: ghost item protection ----
    private final Setting<Boolean> resyncEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("ghost-item-resync")
        .description("After every sell session, re-sync the whitelisted slots with the server so ghost items disappear.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debugLog = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Write every step to autosell-debug.log in the game folder (send it when ghost items happen).")
        .defaultValue(false)
        .build());

    private final Setting<Integer> maxFails = sgGeneral.add(new IntSetting.Builder()
        .name("max-failed-sells")
        .description("Disable the module after this many failed sells in a row (server/anti-cheat is blocking /sell).")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 10)
        .build());

    private final Setting<Integer> guiTimeoutSeconds = sgDelays.add(new IntSetting.Builder()
        .name("gui-timeout-seconds")
        .description("Maximum wait time in seconds for the sell GUI after sending /sell.")
        .defaultValue(5)
        .range(1, 30)
        .sliderRange(1, 15)
        .build());

    private final Setting<Integer> batchSize = sgDelays.add(new IntSetting.Builder()
        .name("batch-size")
        .description("Stacks shift-clicked per batch. Start low (2-3); raise slowly while watching for the server returning your items.")
        .defaultValue(3)
        .range(1, 45)
        .sliderRange(1, 15)
        .build());

    private final Setting<Integer> batchInterval = sgDelays.add(new IntSetting.Builder()
        .name("batch-interval")
        .description("Ticks to wait between two batches of clicks.")
        .defaultValue(2)
        .range(1, 20)
        .sliderRange(1, 10)
        .build());

    private final Setting<Integer> batchJitter = sgDelays.add(new IntSetting.Builder()
        .name("batch-jitter")
        .description("Random extra 0..N ticks added to every batch interval so the click rhythm is not perfectly regular.")
        .defaultValue(1)
        .range(0, 10)
        .sliderRange(0, 5)
        .build());

    private final Setting<Integer> stabilizeTicks = sgDelays.add(new IntSetting.Builder()
        .name("gui-stabilize-ticks")
        .description("Ticks to wait after the sell GUI opens before the first click. Lower = GUI is open for less time.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .build());

    private final Setting<Integer> confirmDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("confirm-delay")
        .description("Ticks to wait after moving items before clicking confirm.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 10)
        .build());

    private final Setting<Integer> postSellDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("post-sell-delay")
        .description("Ticks to wait after confirming for the server to process the sale.")
        .defaultValue(6)
        .range(0, 40)
        .sliderRange(0, 20)
        .build());

    private final Setting<Integer> cooldownSeconds = sgDelays.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Minimum pause after a finished sell session before the next one can start. Doubles after every failed sell.")
        .defaultValue(2)
        .range(0, 60)
        .sliderRange(0, 20)
        .build());

    private final Setting<Integer> resyncDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("resync-delay")
        .description("Ticks to wait after the sell GUI closes (server returns items) before re-syncing the inventory.")
        .defaultValue(6)
        .range(0, 40)
        .sliderRange(0, 20)
        .build());

    private State state = State.IDLE;
    private int stateTicks;
    private int sessionSyncId = -1;
    private int round;
    private int movedLastRound;
    private boolean forceSell;
    private boolean pausedKillAura;
    private long cooldownEndTime;

    // ---- NEW fields ----
    private boolean sessionFailed;
    private int adaptiveBatch;
    private int slowTicks;
    private int cleanStreak;
    private int moveWait;
    private int expectedInvWl = -1;
    private int whitelistedBefore;
    private int failStreak;
    private boolean resyncVerify;
    private long resyncCooldownMs;
    private boolean queueBuilt;
    private final ArrayDeque<Integer> touchQueue = new ArrayDeque<>();

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
        forceSell = true;
    }

    @Override
    public void onDeactivate() {
        restoreKillAura();
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
            if (state == State.IDLE) tickIdle();
            else if (state == State.WAITING_GUI) tickWaitingGui();
            else if (state == State.STABILIZING) tickStabilizing();
            else if (state == State.MOVING) tickMoving();
            else if (state == State.CONFIRMING) tickConfirming();
            else if (state == State.WAITING_SALE) tickWaitingSale();
            else if (state == State.RESYNC) tickResync();
        } catch (Exception e) {
            error("AutoSellPro hit an error, aborting session: " + e.getMessage());
            abort(false);
        }
    }

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
        expectedInvWl = -1;
        moveWait = 0;
        dbg("start sell, whitelisted stacks=" + count + " batch=" + adaptiveBatch + " slow=" + slowTicks);
        mc.getNetworkHandler().sendChatCommand("sell");
        setState(State.WAITING_GUI);
    }

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

    private void tickStabilizing() {
        if (!ownsSellGui()) {
            abortManualClose();
            return;
        }
        if (stateTicks >= stabilizeTicks.get()) setState(State.MOVING);
    }

    private void tickMoving() {
        if (!ownsSellGui()) {
            abortManualClose();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        int invStart = handler.slots.size() - INVENTORY_SIZE;

        // Server handed items back to us -> it is rate-limiting / rolling back our clicks.
        if (rollbackDetected(handler, invStart)) {
            onRollback();
            return;
        }

        if (moveWait > 0) {
            moveWait--;
            return;
        }

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
        expectedInvWl = guiFilled(handler);
        dbg("batch moved=" + moved + " limit=" + limit + " guiFilled=" + expectedInvWl + " invWhitelistedLeft=" + countWhitelistedInHandler(handler, invStart));

        if (moved == 0) {
            if (round == 0) warning("Sell GUI input slots are full or nothing could be moved.");
            finish(true);
            return;
        }

        if (hasMovableLeft(handler, invStart)) {
            int jitter = batchJitter.get();
            moveWait = batchInterval.get() + slowTicks + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0) - 1;
            return;
        }

        setState(State.CONFIRMING);
    }

    private int countWhitelistedInHandler(ScreenHandler handler, int invStart) {
        int n = 0;
        for (int i = invStart; i < handler.slots.size(); i++) {
            ItemStack st = handler.getSlot(i).getStack();
            if (!st.isEmpty() && whitelist.get().contains(st.getItem())) n++;
        }
        return n;
    }

    private int guiFilled(ScreenHandler handler) {
        return INPUT_SLOT_END + 1 - freeInputSlots(handler);
    }

    // Items we put into the sell GUI never leave it by themselves while we are still moving/confirming.
    // If the number of filled GUI slots DROPS, the server removed/returned them (rate limit / rollback).
    // Only the GUI is looked at, so items picked up from farming can't trigger a false alarm.
    private boolean rollbackDetected(ScreenHandler handler, int invStart) {
        return expectedInvWl >= 0 && guiFilled(handler) < expectedInvWl;
    }

    private void onRollback() {
        int before = adaptiveBatch;
        adaptiveBatch = Math.max(1, adaptiveBatch / 2);
        slowTicks = Math.min(4, slowTicks + 1);
        cleanStreak = 0;
        failStreak++;
        sessionFailed = true;
        warning("Server returned items while moving (click rate limit). Slowing down: batch " + before + " -> " + adaptiveBatch + ", +" + slowTicks + " ticks. (" + failStreak + "/" + maxFails.get() + ")");
        dbg("ROLLBACK detected, guiFilled dropped below " + expectedInvWl + ", batch=" + adaptiveBatch + " slowTicks=" + slowTicks);
        beginResync(true, false, 0);
    }

    private void tickConfirming() {
        if (!ownsSellGui()) {
            abortManualClose();
            return;
        }
        if (stateTicks < confirmDelayTicks.get()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;

        if (rollbackDetected(handler, handler.slots.size() - INVENTORY_SIZE)) {
            onRollback();
            return;
        }

        // NEW: if the server rolled the moves back, the GUI is empty on our side. Never confirm an empty GUI.
        if (freeInputSlots(handler) >= INPUT_SLOT_END + 1) {
            warning("Server rolled back the moved items, cancelling this sell.");
            finish(true);
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

    // ------------------------------------------------------------------
    //  NEW: RESYNC state - closes GUI, waits for the server to hand items back,
    //  "touches" every whitelisted slot (pickup + put back) so the server compares
    //  its real stack against ours and corrects any ghost item, then verifies the sale.
    // ------------------------------------------------------------------
    private void tickResync() {
        if (isSellScreen()) {
            mc.player.closeHandledScreen();
            return;
        }

        if (stateTicks < resyncDelayTicks.get()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean canTouch = resyncEnabled.get() && mc.currentScreen == null && handler.syncId == 0;

        if (canTouch) {
            if (!queueBuilt) {
                touchQueue.clear();
                for (int slot = 9; slot <= 44 && slot < handler.slots.size(); slot++) {
                    ItemStack stack = handler.getSlot(slot).getStack();
                    if (!stack.isEmpty() && whitelist.get().contains(stack.getItem())) touchQueue.add(slot);
                }
                queueBuilt = true;
            }

            Integer slot = touchQueue.poll();
            if (slot != null) {
                // pickup + put back in the same tick: no net change if the item is real,
                // but forces the server to reconcile this slot if it is a ghost.
                dbg("resync touch slot " + slot);
                mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                return;
            }
        }

        completeSession();
    }

    private void completeSession() {
        long cooldownMs = resyncCooldownMs;
        boolean disableModule = false;

        if (sessionFailed) {
            long base = Math.max(1, cooldownSeconds.get());
            cooldownMs = Math.min(MAX_BACKOFF_SECONDS, base << Math.min(failStreak, 6)) * 1000L;
            if (failStreak >= maxFails.get()) {
                warning("Too many failed sells in a row - disabling auto-sell to avoid more ghost items.");
                disableModule = true;
            }
        } else if (resyncVerify) {
            // Session ended normally. (The inventory count is only logged: farm drops make it useless as a verdict.)
            dbg("session ok, whitelisted stacks before=" + whitelistedBefore + " now=" + countWhitelistedInInventory());
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
        restoreKillAura(); // GUI is gone -> give KillAura back immediately, resync runs in the background
        resyncVerify = verify;
        resyncCooldownMs = cooldownMs;
        queueBuilt = false;
        touchQueue.clear();
        setState(State.RESYNC);
    }

    private void finish(boolean closeGui) {
        beginResync(closeGui, true, cooldownSeconds.get() * 1000L);
    }

    private void abort(boolean closeGui) {
        beginResync(closeGui, false, cooldownSeconds.get() * 1000L);
    }

    private void abortManualClose() {
        info("Sell GUI closed. Pausing auto-sell for " + manualCloseDelaySeconds.get() + " seconds.");
        beginResync(false, false, manualCloseDelaySeconds.get() * 1000L);
    }

    private void reset() {
        state = State.IDLE;
        stateTicks = 0;
        sessionSyncId = -1;
        round = 0;
        movedLastRound = 0;
        queueBuilt = false;
        touchQueue.clear();
        expectedInvWl = -1;
        moveWait = 0;
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
            Files.writeString(Path.of("autosell-debug.log"), LocalTime.now() + " [" + state + "] " + msg + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private boolean shouldAutoSell() {
        int free = 0;
        int whitelisted = 0;

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
        for (int i = 0; i <= INPUT_SLOT_END; i++) {
            if (!handler.getSlot(i).hasStack()) free++;
        }
        return free;
    }

    private boolean isSellScreen() {
        if (mc.currentScreen == null || mc.player == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        GenericContainerScreenHandler container;
        if (handler instanceof GenericContainerScreenHandler) container = (GenericContainerScreenHandler) handler;
        else return false;
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
        RESYNC
    }
}
