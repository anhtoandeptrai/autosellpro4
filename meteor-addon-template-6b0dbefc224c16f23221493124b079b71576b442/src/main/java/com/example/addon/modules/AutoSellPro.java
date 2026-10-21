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

import java.util.List;

/**
 * AutoSellPro (rewrite)
 *
 * - Bind the module to Z (Meteor keybind). Pressing Z turns it on and SELLS IMMEDIATELY.
 * - All whitelisted stacks are shift-clicked into the /sell GUI within a single tick
 *   (no per-stack delay), then the confirm slot is clicked and the GUI is closed.
 * - The module stays active and re-opens /sell by itself whenever the inventory is full
 *   (no free slot and at least `min-stacks` whitelisted stacks). Press Z again to turn it off.
 */
public class AutoSellPro extends Module {
    private static final int INVENTORY_SIZE = 36;
    private static final int GUI_STABILIZE_TICKS = 3;
    private static final String SELL_GUI_TITLE = "sell";
    private static final int EXPECTED_GUI_ROWS = 6;
    private static final int INPUT_SLOT_END = 44;   // sell GUI input slots: 0..44
    private static final int CONFIRM_SLOT = 53;     // sell GUI confirm button
    private static final int MAX_ROUNDS = 6;        // safety cap of fill+confirm cycles per /sell

    private enum State { IDLE, WAITING_GUI, STABILIZING, MOVING, CONFIRMING, WAITING_SALE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDelays = settings.createGroup("Delays");

    private final Setting<List<Item>> whitelist = sgGeneral.add(new ItemListSetting.Builder()
        .name("whitelist")
        .description("Whitelist of items to automatically sell.")
        .defaultValue(List.<Item>of())
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

    private final Setting<Integer> guiTimeoutSeconds = sgDelays.add(new IntSetting.Builder()
        .name("gui-timeout-seconds")
        .description("Maximum wait time in seconds for the sell GUI after sending /sell.")
        .defaultValue(5)
        .range(1, 30)
        .sliderRange(1, 15)
        .build());

    private final Setting<Integer> clicksPerTick = sgDelays.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("How many stacks are shift-clicked per tick. 45 = the whole GUI in a single tick.")
        .defaultValue(45)
        .range(1, 45)
        .sliderRange(1, 45)
        .build());

    private final Setting<Integer> confirmDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("confirm-delay")
        .description("Ticks to wait after moving items before clicking confirm.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 10)
        .build());

    private final Setting<Integer> postSellDelayTicks = sgDelays.add(new IntSetting.Builder()
        .name("post-sell-delay")
        .description("Ticks to wait after confirming for the server to process the sale.")
        .defaultValue(3)
        .range(0, 40)
        .sliderRange(0, 20)
        .build());

    private final Setting<Integer> cooldownSeconds = sgDelays.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Minimum pause after a finished sell session before the next one can start.")
        .defaultValue(2)
        .range(0, 60)
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

    public AutoSellPro() {
        super(AddonTemplate.CATEGORY, "auto-sell-pro",
            "Bind to Z: sells all whitelisted items instantly via /sell, then keeps auto-selling whenever the inventory is full.");
    }

    @Override
    public void onActivate() {
        reset();
        forceSell = true; // pressing the keybind = sell right now
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

        try {
            stateTicks++;
            switch (state) {
                case IDLE -> tickIdle();
                case WAITING_GUI -> tickWaitingGui();
                case STABILIZING -> tickStabilizing();
                case MOVING -> tickMoving();
                case CONFIRMING -> tickConfirming();
                case WAITING_SALE -> tickWaitingSale();
            }
        } catch (Exception e) {
            error("AutoSellPro hit an error, aborting session: " + e.getMessage());
            abort(false);
        }
    }

    // ---------------------------------------------------------------- states

    private void tickIdle() {
        if (System.currentTimeMillis() < cooldownEndTime) return;
        if (mc.currentScreen != null) return; // don't interrupt other screens

        boolean trigger = forceSell || shouldAutoSell();
        if (!trigger) return;

        if (countWhitelistedInInventory() == 0) {
            forceSell = false;
            info("No whitelisted items to sell.");
            return;
        }

        forceSell = false;
        round = 0;
        movedLastRound = 0;
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
        if (stateTicks >= GUI_STABILIZE_TICKS) setState(State.MOVING);
    }

    /** Moves every whitelisted stack into the GUI in ONE tick (bounded by clicks-per-tick and free input slots). */
    private void tickMoving() {
        if (!ownsSellGui()) {
            abortManualClose();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        int invStart = handler.slots.size() - INVENTORY_SIZE;
        int limit = clicksPerTick.get();
        int moved = 0;

        for (int slot = invStart; slot < handler.slots.size() && moved < limit; slot++) {
            if (freeInputSlots(handler) == 0) break;

            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || !whitelist.get().contains(stack.getItem())) continue;

            mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            moved++;
        }

        movedLastRound = moved;

        if (moved == 0) {
            if (round == 0) warning("Sell GUI input slots are full or nothing could be moved.");
            finish(true);
            return;
        }

        // Still more whitelisted items waiting (limited by clicks-per-tick)? keep moving next tick.
        if (moved >= limit && hasMovableLeft(handler, invStart)) {
            stateTicks = 0;
            return;
        }

        setState(State.CONFIRMING);
    }

    private void tickConfirming() {
        if (!ownsSellGui()) {
            abortManualClose();
            return;
        }
        if (stateTicks < confirmDelayTicks.get()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        mc.interactionManager.clickSlot(handler.syncId, CONFIRM_SLOT, 0, SlotActionType.PICKUP, mc.player);
        setState(State.WAITING_SALE);
    }

    private void tickWaitingSale() {
        // Some servers close the GUI by themselves after the sale: that is a normal finish.
        if (!ownsSellGui()) {
            finish(false);
            return;
        }
        if (stateTicks < postSellDelayTicks.get()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int invStart = handler.slots.size() - INVENTORY_SIZE;

        if (round + 1 < MAX_ROUNDS && movedLastRound > 0 && hasMovableLeft(handler, invStart)) {
            round++;
            setState(State.MOVING);
            return;
        }

        finish(true);
    }

    // ---------------------------------------------------------------- helpers

    private boolean shouldAutoSell() {
        int empty = 0, wl = 0;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) empty++;
            else if (whitelist.get().contains(s.getItem())) wl++;
        }
        return empty == 0 && wl >= minStacks.get();
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
        for (int slot = invStart; slot < handler.slots.size(); slot++) {
            ItemStack s = handler.getSlot(slot).getStack();
            if (!s.isEmpty() && whitelist.get().contains(s.getItem())) return true;
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
        if (!(handler instanceof GenericContainerScreenHandler container)) return false;
        if (container.getRows() != EXPECTED_GUI_ROWS) return false;
        return mc.currentScreen.getTitle().getString().toLowerCase().contains(SELL_GUI_TITLE);
    }

    private boolean ownsSellGui() {
        return isSellScreen() && mc.player.currentScreenHandler.syncId == sessionSyncId;
    }

    private void setState(State next) {
        state = next;
        stateTicks = 0;
    }

    /** Normal end of a session: close the GUI and wait for the inventory to fill up again. */
    private void finish(boolean closeGui) {
        if (closeGui && mc.player != null && mc.currentScreen != null && isSellScreen()) {
            mc.player.closeHandledScreen();
        }
        restoreKillAura();
        reset();
        cooldownEndTime = System.currentTimeMillis() + cooldownSeconds.get() * 1000L;
    }

    /** The player closed the GUI by hand: back off for a while. */
    private void abortManualClose() {
        info("Sell GUI closed. Pausing auto-sell for " + manualCloseDelaySeconds.get() + " seconds.");
        restoreKillAura();
        reset();
        cooldownEndTime = System.currentTimeMillis() + manualCloseDelaySeconds.get() * 1000L;
    }

    private void abort(boolean closeGui) {
        if (closeGui && mc.player != null && isSellScreen()) mc.player.closeHandledScreen();
        restoreKillAura();
        reset();
        cooldownEndTime = System.currentTimeMillis() + cooldownSeconds.get() * 1000L;
    }

    private void reset() {
        state = State.IDLE;
        stateTicks = 0;
        sessionSyncId = -1;
        round = 0;
        movedLastRound = 0;
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
}
