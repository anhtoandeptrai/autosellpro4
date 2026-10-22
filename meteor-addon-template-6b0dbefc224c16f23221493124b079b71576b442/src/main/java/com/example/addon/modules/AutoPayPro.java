package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks your balance with /bal every few seconds. When the balance reaches one of the
 * configured tiers (e.g. 20m, 50m) it runs: /pay <recipient> <tier>
 */
public class AutoPayPro extends Module {
    // $1,234,567 | $20M | 20.5m | 1,5B ...
    private static final Pattern MONEY = Pattern.compile("\\$?\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmMbBtT])?");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> recipient = sgGeneral.add(new StringSetting.Builder()
        .name("recipient")
        .description("Username of the account that receives the money (your main account).")
        .defaultValue("dwnh_")
        .build()
    );

    private final Setting<List<String>> tiers = sgGeneral.add(new StringListSetting.Builder()
        .name("tiers")
        .description("When your balance reaches a tier, that amount is sent. The highest reached tier is used. Examples: 20m, 50m, 1b.")
        .defaultValue(List.of("20m", "50m"))
        .build()
    );

    private final Setting<Integer> checkSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("check-seconds")
        .description("How often the balance is checked with /bal.")
        .defaultValue(20)
        .range(5, 600)
        .sliderRange(5, 120)
        .build()
    );

    private final Setting<Integer> cooldownSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-seconds")
        .description("Minimum pause after a payment before the next one.")
        .defaultValue(10)
        .range(0, 600)
        .sliderRange(0, 120)
        .build()
    );

    private final Setting<String> balanceCommand = sgGeneral.add(new StringSetting.Builder()
        .name("balance-command")
        .description("Command used to read your balance (without the slash).")
        .defaultValue("bal")
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print what the module reads from the balance reply. Turn on if it does not detect your balance.")
        .defaultValue(false)
        .build()
    );

    private int ticks;
    private long waitingUntil;
    private long cooldownUntil;
    private int sent;

    public AutoPayPro() {
        super(AddonTemplate.CATEGORY, "auto-pay-pro", "Sends money to your main account when your balance reaches a tier.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        waitingUntil = 0;
        cooldownUntil = 0;
        sent = 0;
        if (recipient.get().isBlank()) {
            warning("Set a recipient in the module settings first.");
            toggle();
        }
    }

    @Override
    public String getInfoString() {
        return recipient.get().isBlank() ? null : recipient.get();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        if (mc.currentScreen != null) return;

        long now = System.currentTimeMillis();
        if (now < cooldownUntil || now < waitingUntil) return;

        if (++ticks < checkSeconds.get() * 20) return;
        ticks = 0;

        // Ask for the balance and wait up to 5 seconds for the reply.
        waitingUntil = now + 5000;
        mc.getNetworkHandler().sendChatCommand(balanceCommand.get().trim());
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (waitingUntil == 0 || System.currentTimeMillis() > waitingUntil) return;

        String text = event.getMessage().getString();
        String lower = text.toLowerCase();
        if (!(lower.contains("$") || lower.contains("bal") || lower.contains("money"))) return;

        double balance = parseMoney(text);
        if (balance < 0) return;

        waitingUntil = 0;
        if (debug.get()) info("Read balance: " + format(balance) + " from: " + text);

        evaluate(balance);
    }

    private void evaluate(double balance) {
        String name = recipient.get().trim();
        if (name.isEmpty()) return;

        if (mc.getSession() != null && name.equalsIgnoreCase(mc.getSession().getUsername())) {
            warning("Recipient is your own account. Disabling.");
            toggle();
            return;
        }

        // Pick the highest tier that the balance has reached.
        String bestText = null;
        double bestValue = -1;
        for (String tier : tiers.get()) {
            double value = parseMoney(tier);
            if (value > 0 && balance >= value && value > bestValue) {
                bestValue = value;
                bestText = tier.trim();
            }
        }
        if (bestText == null) return;

        mc.getNetworkHandler().sendChatCommand("pay " + name + " " + bestText);
        sent++;
        cooldownUntil = System.currentTimeMillis() + cooldownSeconds.get() * 1000L;
        info("Balance " + format(balance) + " -> sent /pay " + name + " " + bestText + " (#" + sent + ")");
    }

    /** Parses "$1,234,567", "20m", "1.5b" ... Returns -1 when no number is found. */
    private static double parseMoney(String s) {
        Matcher m = MONEY.matcher(s);
        while (m.find()) {
            String num = m.group(1).replace(",", "");
            double value;
            try {
                value = Double.parseDouble(num);
            } catch (NumberFormatException e) {
                continue;
            }
            String suffix = m.group(2);
            if (suffix != null) {
                switch (Character.toLowerCase(suffix.charAt(0))) {
                    case 'k' -> value *= 1_000d;
                    case 'm' -> value *= 1_000_000d;
                    case 'b' -> value *= 1_000_000_000d;
                    case 't' -> value *= 1_000_000_000_000d;
                }
            }
            return value;
        }
        return -1;
    }

    private static String format(double v) {
        if (v >= 1_000_000_000d) return String.format("%.2fB", v / 1_000_000_000d);
        if (v >= 1_000_000d) return String.format("%.2fM", v / 1_000_000d);
        if (v >= 1_000d) return String.format("%.2fK", v / 1_000d);
        return String.format("%.0f", v);
    }
}
