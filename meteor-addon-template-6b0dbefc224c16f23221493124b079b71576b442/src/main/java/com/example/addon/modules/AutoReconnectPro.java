package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AutoReconnectPro
 *
 * - Reconnects to the last server after a disconnect, with a growing delay (so the server's
 *   "connection throttled" / "already logged in" protection does not keep kicking you).
 * - If a stranger (non-friend player) was near you right before the disconnect (e.g. AutoLog fired),
 *   or the kick/disconnect reason contains a ban keyword (banned, botting ...), it does NOT reconnect.
 *   Any other disconnect (lag, network drop, server restart) reconnects.
 * - Before every attempt it brings the Minecraft window to the front (same as clicking into it by hand).
 * - Turn OFF Meteor's built-in "auto-reconnect" while using this one.
 */
public class AutoReconnectPro extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStranger = settings.createGroup("Stranger check");

    private final Setting<Integer> delaySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("delay-seconds")
        .description("Seconds to wait on the disconnect screen before the first reconnect attempt.")
        .defaultValue(10).range(1, 300).sliderRange(1, 60)
        .build());

    private final Setting<Boolean> backoff = sgGeneral.add(new BoolSetting.Builder()
        .name("backoff")
        .description("Double the wait after every failed attempt (10s, 20s, 40s ... up to max-delay-seconds). Fixes getting kicked again and again when reconnecting too fast.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxDelaySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay-seconds")
        .description("Upper limit of the wait between two attempts.")
        .defaultValue(120).range(5, 900).sliderRange(10, 300)
        .build());

    private final Setting<Integer> stableSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("stable-seconds")
        .description("If you stay connected at least this long, the backoff resets to delay-seconds.")
        .defaultValue(30).range(5, 600).sliderRange(5, 120)
        .build());

    private final Setting<Integer> maxAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("max-attempts")
        .description("Give up after this many failed attempts in a row (0 = never give up).")
        .defaultValue(0).range(0, 200).sliderRange(0, 50)
        .build());

    private final Setting<Boolean> focusWindow = sgGeneral.add(new BoolSetting.Builder()
        .name("focus-window")
        .description("Bring the Minecraft window to the front before every reconnect attempt (works around reconnects that only succeed after you click into the window yourself).")
        .defaultValue(true)
        .build());

    private final Setting<List<String>> blockKeywords = sgGeneral.add(new StringListSetting.Builder()
        .name("no-reconnect-keywords")
        .description("If the disconnect reason contains one of these words (case-insensitive), do not reconnect. Example: banned, botting.")
        .defaultValue(List.of("banned", "botting", "permanently"))
        .build());

    private final Setting<Boolean> strangerCheck = sgStranger.add(new BoolSetting.Builder()
        .name("no-reconnect-if-stranger")
        .description("Do not reconnect when a non-friend player was near you right before the disconnect.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> strangerRange = sgStranger.add(new IntSetting.Builder()
        .name("stranger-range")
        .description("Distance in blocks that counts as 'near'.")
        .defaultValue(128).range(8, 512).sliderRange(16, 256)
        .build());

    private final Setting<Integer> strangerWindow = sgStranger.add(new IntSetting.Builder()
        .name("stranger-window-seconds")
        .description("A stranger seen within this many seconds before the disconnect blocks the reconnect.")
        .defaultValue(5).range(1, 60).sliderRange(1, 30)
        .build());

    private ServerInfo lastInfo;
    private long connectedSince;   // 0 = not in a world
    private int attempts;
    private boolean waiting;
    private boolean blocked;
    private long reconnectAt;
    private long lastStrangerMs;
    private String lastStrangerName = "";
    private String lastReason = "";

    public AutoReconnectPro() {
        super(AddonTemplate.CATEGORY, "auto-reconnect-pro",
            "Reconnects after a disconnect with a growing delay; does not reconnect if a stranger was nearby.");
    }

    @Override
    public void onActivate() {
        attempts = 0;
        waiting = false;
        blocked = false;
        connectedSince = 0;
        lastStrangerMs = 0;
        lastStrangerName = "";
        lastReason = "";
    }

    @Override
    public String getInfoString() {
        if (blocked) return "blocked";
        if (waiting) return Math.max(0, (reconnectAt - System.currentTimeMillis()) / 1000) + "s";
        return null;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        try {
            if (event.packet instanceof DisconnectS2CPacket p) {
                lastReason = p.reason().getString();
            } else if (event.packet instanceof LoginDisconnectS2CPacket p) {
                lastReason = p.reason().getString();
            }
        } catch (Exception ignored) {
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();
        boolean inGame = mc.world != null && mc.player != null && mc.getNetworkHandler() != null;

        if (inGame) {
            if (connectedSince == 0) {
                connectedSince = now;
                blocked = false;
                waiting = false;
                lastReason = "";
            }
            if (mc.isInSingleplayer()) {
                lastInfo = null;
            } else {
                ServerInfo cur = mc.getCurrentServerEntry();
                if (cur != null) lastInfo = cur;
            }
            if (attempts > 0 && now - connectedSince >= stableSeconds.get() * 1000L) {
                attempts = 0;
            }
            trackStrangers(now);
            return;
        }

        // Just left the world: decide once whether we are allowed to reconnect.
        if (connectedSince != 0) {
            long onlineMs = now - connectedSince;
            connectedSince = 0;
            waiting = false;
            AddonTemplate.LOG.info("[AutoReconnectPro] disconnected after " + (onlineMs / 1000) + "s");
            if (strangerCheck.get() && lastStrangerMs != 0 && now - lastStrangerMs <= strangerWindow.get() * 1000L) {
                blocked = true;
                AddonTemplate.LOG.info("[AutoReconnectPro] stranger '" + lastStrangerName
                    + "' was nearby - NOT reconnecting automatically.");
            }
        }

        if (!(mc.currentScreen instanceof DisconnectedScreen)) {
            waiting = false;
            return;
        }
        if (blocked || lastInfo == null) return;

        if (!waiting) {
            if (!lastReason.isEmpty()) {
                AddonTemplate.LOG.info("[AutoReconnectPro] disconnect reason: " + lastReason);
                String low = lastReason.toLowerCase();
                for (String k : blockKeywords.get()) {
                    if (!k.isBlank() && low.contains(k.toLowerCase().trim())) {
                        blocked = true;
                        AddonTemplate.LOG.info("[AutoReconnectPro] reason matches '" + k + "' - NOT reconnecting.");
                        return;
                    }
                }
            }
            if (maxAttempts.get() > 0 && attempts >= maxAttempts.get()) {
                blocked = true;
                AddonTemplate.LOG.info("[AutoReconnectPro] max attempts reached - giving up.");
                return;
            }
            long delay = delaySeconds.get() * 1000L;
            if (backoff.get()) {
                delay = Math.min(maxDelaySeconds.get() * 1000L, delay << Math.min(attempts, 6));
            }
            delay += ThreadLocalRandom.current().nextLong(0, 1500);
            reconnectAt = now + delay;
            waiting = true;
            AddonTemplate.LOG.info("[AutoReconnectPro] attempt " + (attempts + 1) + " in " + (delay / 1000) + "s");
        }

        if (now >= reconnectAt) {
            waiting = false;
            ++attempts;
            if (focusWindow.get()) focusMinecraftWindow();
            ServerAddress address = ServerAddress.parse(lastInfo.address);
            ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), mc, address, lastInfo, false, null);
        }
    }

    private void focusMinecraftWindow() {
        try {
            long h = mc.getWindow().getHandle();
            if (GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
                GLFW.glfwRestoreWindow(h);
            }
            GLFW.glfwFocusWindow(h);
            GLFW.glfwRequestWindowAttention(h);
        } catch (Throwable t) {
            AddonTemplate.LOG.info("[AutoReconnectPro] could not focus window: " + t);
        }
    }

    private void trackStrangers(long now) {
        if (!strangerCheck.get()) return;
        double r2 = (double) strangerRange.get() * strangerRange.get();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (Friends.get().isFriend(p)) continue;
            if (mc.player.squaredDistanceTo(p) <= r2) {
                lastStrangerMs = now;
                lastStrangerName = p.getName().getString();
                return;
            }
        }
    }
}
