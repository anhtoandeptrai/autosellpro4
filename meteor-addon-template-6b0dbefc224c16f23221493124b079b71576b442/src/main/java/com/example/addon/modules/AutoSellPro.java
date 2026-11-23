package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AutoSellPro - now with two sell modes:
 *
 *  FAST : all whitelisted stacks go into /sell in ONE tick, tiny delays, no inventory resync.
 *  SAFE : the old anti-ghost behaviour (small batches, long ack waits, resync after failures).
 *  AUTO : starts FAST. As soon as the server rejects / returns items (ghost / ROLLBACK risk)
 *         it drops to SAFE for N clean sells, then goes back to FAST.
 */
public class AutoSellPro extends Module {
    public enum Mode { AUTO, FAST, SAFE }

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
    private final SettingGroup sgFast = settings.createGroup("Fast mode");
    private final SettingGroup sgDelays = settings.createGroup("Delays (safe mode)");
    private final SettingGroup sgWither = settings.createGroup("Auto sell skeleton wither");

    // ---------------- auto sell skeleton wither ----------------
    private final Setting<Boolean> witherMode = sgWither.add(new BoolSetting.Builder()
        .name("wither-skeleton-mode")
        .description("Only sells BONE and COAL (ignores the whitelist, never sells skulls / anything else). Every stack is sold with 1 item left in the slot (63 of 64), so the inventory stays full and you never pick up stone swords. Works together with AUTO / FAST / SAFE.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> witherMinStack = sgWither.add(new IntSetting.Builder()
        .name("wither-min-stack")
        .description("A bone/coal stack is only sold when it has at least this many items (64 = full stacks only, sells 63 and keeps 1).")
        .defaultValue(64).range(2, 64).sliderRange(2, 64)
        .build());

    // ---------------- general ----------------
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("AUTO = fast, falls back to safe (anti-ghost) when the server rejects. FAST = always fast. SAFE = always slow/anti-ghost.")
        .defaultValue(Mode.AUTO)
        .build());

    private final Setting<Integer> safeSellsBeforeFast = sgGeneral.add(new IntSetting.Builder()
        .name("safe-sells-before-fast")
        .description("AUTO mode: number of clean SAFE sells before trying FAST again.")
        .defaultValue(3).range(1, 20).sliderRange(1, 10)
        .build());

    private final Setting<List<Item>> whitelist = sgGeneral.add(new ItemListSetting.Builder()
        .name("whitelist")
        .description("Whitelist of items to automatically sell.")
        .defaultValue(List.of())
        .build());

    private final Setting<Integer> minStacks = sgGeneral.add(new IntSetting.Builder()
        .name("min-stacks")
        .description("Auto-sell triggers when the inventory is full (see trigger-free-slots) AND at least this many whitelisted stacks.")
        .defaultValue(1).range(1, 36).sliderRange(1, 36)
        .build());

    private final Setting<Integer> triggerFreeSlots = sgGeneral.add(new IntSetting.Builder()
        .name("trigger-free-slots")
        .description("Auto-sell as soon as the inventory has this many free slots or fewer (0 = only when completely full). Example: 2 sells while 2 slots are still free, so drops are never lost.")
        .defaultValue(0).range(0, 35).sliderRange(0, 10)
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
        .description("After a failed/aborted sell session, touch the inventory slots to force the server to reconcile ghost items (slow).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> resyncAlways = sgGeneral.add(new BoolSetting.Builder()
        .name("resync-always")
        .description("SAFE mode only: also run the slot resync after every CLEAN sell (slow). Leave off unless you still see ghost items after a good sell.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> debugLog = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Write every step to autosell-debug.log in the game folder.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> ghostRetryMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("ghost-retry-minutes")
        .description("A slot the server rejects (ghost item) is skipped for this many minutes, then tried again. Everything else keeps selling; the module never turns itself off.")
        .defaultValue(3).range(1, 120).sliderRange(1, 30)
        .build());

    // ---------------- fast mode ----------------
    private final Setting<Integer> fastBatch = sgFast.add(new IntSetting.Builder()
        .name("fast-batch-size")
        .description("Stacks shift-clicked in ONE tick in fast mode (45 = everything at once).")
        .defaultValue(45).range(1, 45).sliderRange(1, 45)
        .build());

    private final Setting<Integer> fastAckWait = sgFast.add(new IntSetting.Builder()
        .name("fast-ack-wait")
        .description("Ticks to wait after the batch before checking that the server accepted it. Raise it if your ping is high (ping 150ms ~ 3 ticks).")
        .defaultValue(3).range(1, 20).sliderRange(1, 10)
        .build());

    private final Setting<Integer> fastStabilize = sgFast.add(new IntSetting.Builder()
        .name("fast-stabilize")
        .description("Ticks to wait after the sell GUI opens before the first click.")
        .defaultValue(1).range(1, 10).sliderRange(1, 5)
        .build());

    private final Setting<Integer> fastConfirmDelay = sgFast.add(new IntSetting.Builder()
        .name("fast-confirm-delay")
        .description("Ticks between the batch check and clicking confirm.")
        .defaultValue(1).range(0, 10).sliderRange(0, 5)
        .build());

    private final Setting<Integer> fastPostSell = sgFast.add(new IntSetting.Builder()
        .name("fast-post-sell-delay")
        .description("Ticks to wait after confirming before closing the GUI.")
        .defaultValue(3).range(0, 20).sliderRange(0, 10)
        .build());

    private final Setting<Integer> fastCloseDelay = sgFast.add(new IntSetting.Builder()
        .name("fast-close-delay")
        .description("Ticks to wait after the GUI closes before the session is considered done.")
        .defaultValue(2).range(0, 20).sliderRange(0, 10)
        .build());

    private final Setting<Integer> fastCooldown = sgFast.add(new IntSetting.Builder()
        .name("fast-cooldown-seconds")
        .description("Minimum pause between two fast sells.")
        .defaultValue(1).range(0, 20).sliderRange(0, 10)
        .build());

    // ---------------- safe mode delays (unchanged) ----------------
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
        .description("Ticks to wait after each batch for the SERVER to answer before checking the GUI. Should be a bit more than your ping. Grows automatically when the server keeps returning items.")
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

    // ---------------- runtime state ----------------
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
    private int expectedFilled = -1;
    private boolean verifyPending;
    private int ackWait;
    private int ackExtra;
    private int rejects;
    private boolean guiRetried;
    private int resyncGap;
    private boolean resyncNeeded;

    // ghost slots: player-inventory slot (0..35 relative to the sell GUI) -> item + skip-until time
    private record BadSlot(Item item, long until) {}
    private final Map<Integer, BadSlot> badSlots = new HashMap<>();
    private int lastClickedRel = -1;   // slot of the last batch when it contained exactly 1 click
    private int lastBatchMoved;

    // AUTO mode bookkeeping
    private boolean autoSafe;      // true while AUTO is temporarily using SAFE behaviour
    private int safeSellsLeft;     // clean SAFE sells left before trying FAST again

    public AutoSellPro() {
        super(AddonTemplate.CATEGORY, "auto-sell-pro",
            "Bind to Z: sells all whitelisted items instantly via /sell, then keeps auto-selling whenever the inventory is full. Modes: AUTO / FAST / SAFE.");
    }

    // ---------------- mode helpers ----------------

    /** True when the SAFE (anti-ghost) behaviour is in effect right now. */
    private boolean isSafe() {
        return switch (mode.get()) {
            case SAFE -> true;
            case FAST -> false;
            case AUTO -> autoSafe;
        };
    }

    /** AUTO only: server pushed back -> use SAFE for the next few sells. */
    private void escalateToSafe(String why) {
        if (mode.get() != Mode.AUTO) return;
        if (!autoSafe) {
            info("Fast sell failed (" + why + ") - switching to SAFE mode for " + safeSellsBeforeFast.get() + " sells.");
        }
        autoSafe = true;
        safeSellsLeft = safeSellsBeforeFast.get();
        dbg("escalate to SAFE: " + why);
    }

    private long cleanCooldownMs() {
        return (long) (isSafe() ? cooldownSeconds.get() : fastCooldown.get()) * 1000L;
    }

    // ---------------- lifecycle ----------------

    @Override
    public void onActivate() {
        reset();
        failStreak = 0;
        adaptiveBatch = batchSize.get();
        slowTicks = 0;
        cleanStreak = 0;
        ackExtra = 0;
        autoSafe = false;
        safeSellsLeft = 0;
        badSlots.clear();
        forceSell = true;
    }

    @Override
    public void onDeactivate() {
        restoreKillAura();
        if (mc.player != null && isSellScreen()) {
            mc.player.closeHandledScreen();
        }
        reset();
    }

    @Override
    public String getInfoString() {
        String m = isSafe() ? "SAFE" : "FAST";
        if (witherMode.get()) m += " WITHER";
        if (!badSlots.isEmpty()) m += " ghost:" + badSlots.size();
        return state == State.IDLE ? m : m + " " + state.name();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            return;
        }
        ++stateTicks;
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

    // ---------------- state machine ----------------

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
        dbg("start sell, mode=" + (isSafe() ? "SAFE" : "FAST") + " whitelisted=" + count
            + " batch=" + adaptiveBatch + " slow=" + slowTicks + " ackExtra=" + ackExtra);
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
        int wait = isSafe() ? stabilizeTicks.get() : fastStabilize.get();
        if (stateTicks >= wait) {
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

        if (ackWait > 0) {
            --ackWait;
            return;
        }

        if (verifyPending) {
            verifyPending = false;
            int actual = guiFilled(handler);
            dbg("verify: expected=" + expectedFilled + " actual=" + actual);
            if (actual < expectedFilled) {
                onReject(actual, true);
                return;
            }
        }

        if (!hasMovableLeft(handler, invStart)) {
            if (guiFilled(handler) == 0) {
                if (round == 0) {
                    warning("Sell GUI input slots are full or nothing could be moved.");
                }
                finish(true);
                return;
            }
            setState(State.CONFIRMING);
            return;
        }

        boolean safe = isSafe();
        if (adaptiveBatch <= 0) adaptiveBatch = batchSize.get();
        int limit = safe ? Math.max(1, Math.min(adaptiveBatch, batchSize.get())) : fastBatch.get();

        int moved = 0;
        int clickedRel = -1;
        for (int slot = invStart; slot < handler.slots.size() && moved < limit && freeInputSlots(handler) != 0; ++slot) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!isSellable(stack)) continue;
            if (isGhostSlot(slot - invStart, stack)) continue;
            if (witherMode.get()) {
                if (!moveAllButOne(handler, slot)) break;
            } else {
                mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            }
            clickedRel = slot - invStart;
            ++moved;
        }
        lastBatchMoved = moved;
        lastClickedRel = moved == 1 ? clickedRel : -1;
        movedLastRound += moved;
        expectedFilled = guiFilled(handler);
        verifyPending = true;

        if (safe) {
            int jitter = batchJitter.get();
            ackWait = Math.max(1, ackWaitTicks.get() + ackExtra + slowTicks + batchInterval.get() - 1
                + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0));
        } else {
            ackWait = Math.max(1, fastAckWait.get());
        }
        dbg("batch moved=" + moved + " limit=" + limit + " guiFilled=" + expectedFilled + " ackWait=" + ackWait);

        if (moved == 0) {
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
        int wait = isSafe() ? confirmDelayTicks.get() : fastConfirmDelay.get();
        if (stateTicks < wait) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int actual = guiFilled(handler);
        if (actual < expectedFilled) {
            dbg("confirm check: expected=" + expectedFilled + " actual=" + actual);
            onReject(actual, false);
            return;
        }
        if (actual == 0) {
            warning("Server returned all moved items, cancelling this sell.");
            sessionFailed = true;
            ++failStreak;
            escalateToSafe("server returned all items");
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
        int wait = isSafe() ? postSellDelayTicks.get() + ackExtra : fastPostSell.get();
        if (stateTicks < wait) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int invStart = handler.slots.size() - INVENTORY_SIZE;
        if (round + 1 < MAX_ROUNDS && movedLastRound > 0 && hasMovableLeft(handler, invStart)) {
            ++round;
            movedLastRound = 0;
            expectedFilled = 0;
            verifyPending = false;
            ackWait = 0;
            setState(State.MOVING);
            return;
        }
        finish(true);
    }

    private void tickResync() {
        if (isSellScreen()) {
            mc.player.closeHandledScreen();
            return;
        }
        // A clean FAST session only waits a couple of ticks; failed/aborted sessions always use the safe delay.
        boolean slow = isSafe() || resyncNeeded;
        int wait = slow ? resyncDelayTicks.get() + ackExtra : fastCloseDelay.get();
        if (stateTicks < wait) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean isPlayerInv = mc.currentScreen == null && handler == mc.player.playerScreenHandler;
        if (!isPlayerInv) return;

        boolean wantResync = resyncNeeded || (resyncAlways.get() && isSafe());
        if (!resyncEnabled.get() || !wantResync) {
            completeSession();
            return;
        }
        if (resyncGap > 0) {
            --resyncGap;
            return;
        }
        if (!queueBuilt) {
            touchQueue.clear();
            pendingPutBack = -1;
            for (int s = 9; s <= 44; ++s) {
                if (handler.getSlot(s).getStack().isEmpty()) continue;
                touchQueue.add(s);
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
        resyncGap = 2;
        state = State.RESYNC;
    }

    private void completeSession() {
        long cooldownMs = resyncCooldownMs;

        if (sessionFailed) {
            escalateToSafe("session failed");
            long base = Math.max(1, cooldownSeconds.get());
            cooldownMs = Math.min(MAX_BACKOFF_SEC, base << Math.min(failStreak, 6)) * 1000L;
        } else if (resyncVerify) {
            dbg("session ok, whitelisted before=" + whitelistedBefore + " now=" + countWhitelistedInInventory());
            failStreak = 0;
            cooldownMs = cleanCooldownMs();

            // AUTO: after enough clean SAFE sells, try FAST again
            if (mode.get() == Mode.AUTO && autoSafe && --safeSellsLeft <= 0) {
                autoSafe = false;
                info("Back to FAST mode.");
            }

            if (++cleanStreak >= 3) {
                cleanStreak = 0;
                if (slowTicks > 0) {
                    --slowTicks;
                } else if (ackExtra > 0) {
                    --ackExtra;
                } else {
                    adaptiveBatch = Math.min(batchSize.get(), adaptiveBatch + 1);
                }
            }
        }
        restoreKillAura();
        reset();
        cooldownEndTime = System.currentTimeMillis() + cooldownMs;
    }

    private void beginResync(boolean closeGui, boolean verify, long cooldownMs) {
        if (closeGui && mc.player != null && isSellScreen()) {
            mc.player.closeHandledScreen();
        }
        restoreKillAura();
        resyncVerify = verify;
        resyncCooldownMs = cooldownMs;
        resyncNeeded = !verify || sessionFailed;
        queueBuilt = false;
        touchQueue.clear();
        pendingPutBack = -1;
        resyncGap = 0;
        setState(State.RESYNC);
    }

    private void finish(boolean closeGui) {
        beginResync(closeGui, true, cleanCooldownMs());
    }

    private void abort(boolean closeGui) {
        beginResync(closeGui, false, (long) cooldownSeconds.get() * 1000L);
    }

    private void abortManualClose() {
        info("Sell GUI closed. Pausing auto-sell for " + manualCloseDelaySeconds.get() + " seconds.");
        beginResync(false, false, (long) manualCloseDelaySeconds.get() * 1000L);
    }

    private void onReject(int actual, boolean fromVerify) {
        // Exactly one stack was clicked and the server gave it back -> that slot is a ghost item.
        // Skip it for a while and keep selling the rest (no slowdown, no failure).
        if (fromVerify && lastBatchMoved == 1 && lastClickedRel >= 0) {
            ScreenHandler handler = mc.player.currentScreenHandler;
            int invStart = handler.slots.size() - INVENTORY_SIZE;
            ItemStack st = handler.getSlot(invStart + lastClickedRel).getStack();
            if (!st.isEmpty()) {
                badSlots.put(lastClickedRel, new BadSlot(st.getItem(), System.currentTimeMillis() + ghostRetryMinutes.get() * 60_000L));
                dbg("GHOST slot rel=" + lastClickedRel + " item=" + st.getItem() + " -> skipped " + ghostRetryMinutes.get() + " min");
                escalateToSafe("ghost slot " + lastClickedRel);
                expectedFilled = actual;
                verifyPending = false;
                ackWait = isSafe() ? ackWaitTicks.get() + ackExtra : fastAckWait.get() * 2;
                lastClickedRel = -1;
                if (state != State.MOVING) setState(State.MOVING);
                return;
            }
        }

        ++rejects;
        escalateToSafe("server returned items");
        ackExtra = Math.min(MAX_ACK_EXTRA, ackExtra + 2);
        adaptiveBatch = Math.max(1, adaptiveBatch / 2);
        slowTicks = Math.min(4, slowTicks + 1);
        cleanStreak = 0;
        dbg("REJECT #" + rejects + " expected=" + expectedFilled + " actual=" + actual
            + " ackExtra=" + ackExtra + " batch=" + adaptiveBatch);

        if (rejects > MAX_REJECTS_PER_SESSION) {
            sessionFailed = true;
            ++failStreak;
            warning("Server keeps returning items. Slowing down: batch " + adaptiveBatch + ", ack +" + ackExtra
                + " ticks. (fail streak " + failStreak + ")");
            beginResync(true, false, 0L);
            return;
        }
        expectedFilled = actual;
        verifyPending = false;
        ackWait = isSafe() ? ackWaitTicks.get() + ackExtra : fastAckWait.get() * 2;
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

    // ---------------- inventory helpers ----------------

    /** Inventory index (0-8 hotbar, 9-35 main) -> slot index relative to the start of the player part of a container GUI. */
    private static int toRel(int invIndex) {
        return invIndex < 9 ? 27 + invIndex : invIndex - 9;
    }

    /** True if this slot is a known ghost item (still shows the same item, skip time not over). Cleans up stale entries. */
    private boolean isGhostSlot(int rel, ItemStack stack) {
        BadSlot b = badSlots.get(rel);
        if (b == null) return false;
        if (stack.isEmpty() || stack.getItem() != b.item() || System.currentTimeMillis() > b.until()) {
            badSlots.remove(rel);
            return false;
        }
        return true;
    }

    private boolean shouldAutoSell() {
        int free = 0;
        int whitelisted = 0;
        for (int i = 0; i < INVENTORY_SIZE; ++i) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) {
                ++free;
                continue;
            }
            if (isSellable(s) && !isGhostSlot(toRel(i), s)) ++whitelisted;
        }
        return free <= triggerFreeSlots.get() && whitelisted >= minStacks.get();
    }

    private int countWhitelistedInInventory() {
        int n = 0;
        for (int i = 0; i < INVENTORY_SIZE; ++i) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!isSellable(s) || isGhostSlot(toRel(i), s)) continue;
            ++n;
        }
        return n;
    }

    private boolean hasMovableLeft(ScreenHandler handler, int invStart) {
        if (freeInputSlots(handler) == 0) return false;
        for (int i = invStart; i < handler.slots.size(); ++i) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!isSellable(stack)) continue;
            if (isGhostSlot(i - invStart, stack)) continue;
            return true;
        }
        return false;
    }

    /**
     * Whether a stack may be sold right now.
     * Normal: item is in the whitelist.
     * Wither skeleton mode: only BONE / COAL (whitelist ignored, skulls never), and only stacks with
     * at least wither-min-stack items (so the 1 item we keep behind is never sold again).
     */
    private boolean isSellable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (witherMode.get()) {
            Item it = stack.getItem();
            return (it == Items.BONE || it == Items.COAL) && stack.getCount() >= Math.max(2, witherMinStack.get());
        }
        return whitelist.get().contains(stack.getItem());
    }

    /**
     * Moves (count - 1) items of the stack in {@code slot} into the first free sell-input slot and leaves
     * exactly 1 item behind: pick up all, right-click the origin slot (puts 1 back), place the rest.
     * Returns false if there is no free input slot.
     */
    private boolean moveAllButOne(ScreenHandler handler, int slot) {
        int target = -1;
        for (int i = 0; i <= INPUT_SLOT_END; ++i) {
            if (!handler.getSlot(i).hasStack()) {
                target = i;
                break;
            }
        }
        if (target < 0) return false;
        int id = handler.syncId;
        mc.interactionManager.clickSlot(id, slot, 0, SlotActionType.PICKUP, mc.player);   // pick up whole stack
        mc.interactionManager.clickSlot(id, slot, 1, SlotActionType.PICKUP, mc.player);   // put 1 back
        mc.interactionManager.clickSlot(id, target, 0, SlotActionType.PICKUP, mc.player); // drop the rest into the sell GUI
        if (!handler.getCursorStack().isEmpty()) {
            // something went wrong: return whatever is on the cursor to the original slot
            mc.interactionManager.clickSlot(id, slot, 0, SlotActionType.PICKUP, mc.player);
        }
        return true;
    }

    private int freeInputSlots(ScreenHandler handler) {
        int free = 0;
        for (int i = 0; i <= INPUT_SLOT_END; ++i) {
            if (handler.getSlot(i).hasStack()) continue;
            ++free;
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
