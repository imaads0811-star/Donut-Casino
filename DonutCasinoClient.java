package com.donutcasino;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DonutCasinoClient implements ClientModInitializer {

    // ================= CONFIG =================
    static final String SERVER_HOST = "donutsmp";
    static final long MIN_BET = 1_000_000L;        // 1M
    static final long MAX_BET = 25_000_000L;       // 25M
    static final long MAX_PAYOUT = 600_000_000L;   // = 25M x 24x, so the cap never changes a displayed result

    // PUBLISHED ODDS (shown on screen + /codds). Weights are out of the total.
    static final double[] MULT   = {0.1, 0.4, 1, 1.2, 1.5, 4, 7, 12, 24};
    static final int[]    WEIGHT = {220, 320, 260, 100, 60, 28, 8, 3, 1};
    static final int[]    COLOR  = {0xFFAA2222, 0xFF555566, 0xFF2E6F95, 0xFF3A9A3A, 0xFF9A8A1E, 0xFFD2691E, 0xFFC0399A, 0xFF8A2BE2, 0xFF00A8A8};

    // Picture shown on each wheel frame (same order as MULT). Change to any item you like.
    static final Item[] ICONS = {Items.COAL, Items.COPPER_INGOT, Items.IRON_INGOT, Items.GOLD_INGOT,
            Items.LAPIS_LAZULI, Items.EMERALD, Items.DIAMOND, Items.NETHERITE_INGOT, Items.NETHER_STAR};

    // Anchored at start: only real server system messages like ".Name paid you $2.5M"
    static final Pattern PAY = Pattern.compile(
            "(?i)^\\W*(\\.?\\w{3,17})\\s+paid\\s+you\\s+\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kmbt]?)\\b");

    static final int SPIN_TICKS = 80, HOLD_TICKS = 50, CMD_GAP = 25;

    // ================= STATE =================
    static boolean enabled = false;   // starts OFF for safety, press K to turn on
    static KeyBinding toggleKey;
    static final SecureRandom RNG = new SecureRandom();
    static final int TOTAL = Arrays.stream(WEIGHT).sum();
    static final Deque<Spin> queue = new ArrayDeque<>();
    static final Deque<String> commands = new ArrayDeque<>();
    static final List<long[]> bigWins = new ArrayList<>(); // {payout} paired with names below
    static final List<String> bigWinNames = new ArrayList<>();
    static Spin current; static int spinTick, cmdCooldown;
    static long lastMsgHash; static int lastMsgTick; static int tickCount;
    static Path logFile;

    record Spin(String name, long bet, int idx, long payout, boolean simulate) {}

    @Override
    public void onInitializeClient() {
        logFile = FabricLoader.getInstance().getConfigDir().resolve("donutcasino-log.txt");
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("donutcasino", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutcasino.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));

        // GAME only (server system messages) -> players can't spoof payments via public chat
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) handle(msg.getString()); });
        ClientTickEvents.END_CLIENT_TICK.register(DonutCasinoClient::tick);
        HudRenderCallback.EVENT.register(DonutCasinoClient::render);

        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> {
            d.register(ClientCommandManager.literal("codds").executes(c -> {
                c.getSource().sendFeedback(Text.literal("Odds (RTP " + String.format("%.1f", rtp()) + "%, house edge "
                        + String.format("%.1f", 100 - rtp()) + "%) | bets " + fmt(MIN_BET) + "-" + fmt(MAX_BET)
                        + " | max payout " + fmt(MAX_PAYOUT)).formatted(Formatting.GOLD));
                for (int i = 0; i < MULT.length; i++)
                    c.getSource().sendFeedback(Text.literal(mstr(MULT[i]) + "x  -  " + String.format("%.1f", 100.0 * WEIGHT[i] / TOTAL) + "%"));
                return 1;
            }));
            // /ctest 5m  -> simulate a spin, NO real payment is sent
            d.register(ClientCommandManager.literal("ctest")
                    .then(ClientCommandManager.argument("amount", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .executes(c -> {
                                Matcher m = Pattern.compile("(?i)([\\d.]+)([kmbt]?)")
                                        .matcher(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "amount"));
                                if (m.matches()) enqueue("TestUser", parse(m.group(1), m.group(2)), true);
                                return 1;
                            })));
        });
    }

    static boolean onDonut() {
        ServerInfo si = MinecraftClient.getInstance().getCurrentServerEntry();
        return si != null && si.address != null && si.address.toLowerCase(Locale.ROOT).contains(SERVER_HOST);
    }

    static double rtp() { double s = 0; for (int i = 0; i < MULT.length; i++) s += MULT[i] * WEIGHT[i]; return 100.0 * s / TOTAL; }

    // ================= INPUT =================
    static void handle(String text) {
        if (!enabled || !onDonut()) return;
        Matcher m = PAY.matcher(text);
        if (!m.find()) return;
        // de-dupe identical message within 2 ticks
        long h = text.hashCode();
        if (h == lastMsgHash && tickCount - lastMsgTick < 2) return;
        lastMsgHash = h; lastMsgTick = tickCount;
        long amt = parse(m.group(2).replace(",", ""), m.group(3));
        String name = m.group(1);
        MinecraftClient.getInstance().execute(() -> enqueue(name, amt, false));
    }

    static void enqueue(String name, long bet, boolean sim) {
        if (bet < MIN_BET || bet > MAX_BET) {
            // outside 1M-25M -> no spin, no refund: counts as a donation
            if (!sim) log(name, bet, -1, 0, "DONATION outside limits");
            return;
        }
        int idx = roll();
        long payout = Math.min(MAX_PAYOUT, Math.round(bet * MULT[idx]));
        queue.add(new Spin(name, bet, idx, payout, sim));
    }

    static int roll() {
        int r = RNG.nextInt(TOTAL), acc = 0;
        for (int i = 0; i < WEIGHT.length; i++) { acc += WEIGHT[i]; if (r < acc) return i; }
        return 0;
    }

    static long parse(String n, String s) {
        double v; try { v = Double.parseDouble(n); } catch (Exception e) { return 0; }
        switch (s.toLowerCase(Locale.ROOT)) { case "k" -> v *= 1e3; case "m" -> v *= 1e6; case "b" -> v *= 1e9; case "t" -> v *= 1e12; }
        return (long) v;
    }

    // ================= TICK =================
    static void tick(MinecraftClient mc) {
        tickCount++;
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            if (!enabled) { queue.clear(); }
            if (mc.player != null) mc.player.sendMessage(Text.literal("Donut Casino: " + (enabled ? "ON (auto-paying!)" : "OFF"))
                    .formatted(enabled ? Formatting.GREEN : Formatting.RED), true);
        }
        if (mc.player == null) return;

        if (cmdCooldown > 0) cmdCooldown--;
        if (cmdCooldown == 0 && !commands.isEmpty() && enabled && onDonut()) {
            mc.player.networkHandler.sendChatCommand(commands.poll());
            cmdCooldown = CMD_GAP;
        }

        if (current == null && !queue.isEmpty()) { current = queue.poll(); spinTick = 0; }
        if (current != null) {
            spinTick++;
            if (spinTick % 4 == 0 && spinTick < SPIN_TICKS) mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.2f);
            if (spinTick == SPIN_TICKS) {
                Spin s = current;
                mc.player.playSound(s.payout() > s.bet() ? SoundEvents.ENTITY_PLAYER_LEVELUP : SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1f, 1f);
                if (!s.simulate()) {
                    if (s.payout() > 0) commands.add("pay " + s.name() + " " + s.payout());
                    log(s.name(), s.bet(), s.idx(), s.payout(), "SPIN");
                }
                if (s.payout() > s.bet()) recordWin(s.name(), s.payout());
            }
            if (spinTick >= SPIN_TICKS + HOLD_TICKS) current = null;
        }
    }

    static void recordWin(String name, long payout) {
        bigWins.add(new long[]{payout}); bigWinNames.add(name);
        // keep sorted top 3
        Integer[] order = new Integer[bigWins.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(bigWins.get(b)[0], bigWins.get(a)[0]));
        List<long[]> w = new ArrayList<>(); List<String> n = new ArrayList<>();
        for (int i = 0; i < Math.min(3, order.length); i++) { w.add(bigWins.get(order[i])); n.add(bigWinNames.get(order[i])); }
        bigWins.clear(); bigWins.addAll(w); bigWinNames.clear(); bigWinNames.addAll(n);
    }

    static void log(String name, long bet, int idx, long payout, String tag) {
        try {
            String line = System.currentTimeMillis() + " | " + tag + " | " + name + " | bet " + bet + " | "
                    + (idx >= 0 ? mstr(MULT[idx]) + "x" : "-") + " | payout " + payout + "\n";
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    // ================= HUD =================
    static void render(DrawContext ctx, net.minecraft.client.render.RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !onDonut()) return;
        int w = ctx.getScaledWindowWidth();

        // published odds panel (always visible when enabled, so players see the real odds)
        if (enabled) {
            int x = 6, y = 6;
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal("ODDS  (RTP " + String.format("%.1f", rtp()) + "%)").formatted(Formatting.GOLD, Formatting.BOLD), x, y, 0xFFFFFF);
            for (int i = 0; i < MULT.length; i++) {
                y += 10;
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal(mstr(MULT[i]) + "x  " + String.format("%.1f", 100.0 * WEIGHT[i] / TOTAL) + "%"), x, y, COLOR[i] | 0xFF000000);
            }
            y += 12;
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal("Bet " + fmt(MIN_BET) + " - " + fmt(MAX_BET)).formatted(Formatting.YELLOW), x, y, 0xFFFFFF);
            if (!queue.isEmpty())
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal(queue.size() + " queued").formatted(Formatting.GRAY), x, y + 10, 0xFFFFFF);

            // biggest wins
            int bx = w - 150, by = ctx.getScaledWindowHeight() - 80;
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal("BIGGEST WINS").formatted(Formatting.GOLD, Formatting.BOLD), bx, by, 0xFFFFFF);
            for (int i = 0; i < bigWins.size(); i++)
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal(bigWinNames.get(i) + "  " + fmt(bigWins.get(i)[0])).formatted(Formatting.GREEN), bx, by + 10 * (i + 1), 0xFFFFFF);
        }

        if (current == null) return;
        // wheel strip
        int n = MULT.length, cw = 48, ch = 52, cx = w / 2, cy = 40;
        ctx.fill(cx - 190, cy - 28, cx + 190, cy + ch + 28, 0xAA000000);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,
                Text.literal(current.name() + " \u2022 $" + String.format("%,d", current.bet()) + " stake").formatted(Formatting.YELLOW), cx, cy - 20, 0xFFFFFF);

        float t = Math.min(1f, (spinTick + tc.getTickProgress(false)) / SPIN_TICKS);
        float eased = 1f - (1f - t) * (1f - t) * (1f - t);
        float pos = eased * (n * 4 + current.idx());
        int base = Math.round(pos);
        for (int o = -3; o <= 3; o++) {
            int i = Math.floorMod(base + o, n);
            int x = cx + (int) ((o - (pos - base)) * (cw + 4)) - cw / 2;
            if (x < cx - 190 || x + cw > cx + 190) continue;
            ctx.fill(x, cy, x + cw, cy + ch, COLOR[i]);                 // colored frame
            ctx.fill(x + 2, cy + 2, x + cw - 2, cy + ch - 2, 0xDD10161C); // dark inside
            ctx.fill(x + 2, cy + ch - 4, x + cw - 2, cy + ch - 2, COLOR[i]);
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(x + (cw - 24) / 2f, cy + 6);
            ctx.getMatrices().scale(1.5f, 1.5f);
            ctx.drawItem(new ItemStack(ICONS[i]), 0, 0);
            ctx.getMatrices().popMatrix();
            ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(mstr(MULT[i]) + "x"), x + cw / 2, cy + ch - 16, 0xFFFFFF);
        }
        ctx.fill(cx - 1, cy - 6, cx + 1, cy + ch + 6, 0xFFFFFFFF); // pointer

        String status;
        if (spinTick < SPIN_TICKS) status = "Spinning...";
        else status = current.payout() > 0 ? mstr(MULT[current.idx()]) + "x  -  paid $" + fmt(current.payout()) : "0x  -  better luck next time";
        ctx.drawCenteredTextWithShadow(mc.textRenderer, Text.literal(status).formatted(Formatting.GOLD, Formatting.BOLD), cx, cy + ch + 12, 0xFFFFFF);
    }

    // ================= UTIL =================
    static String mstr(double m) { return m == Math.floor(m) ? String.valueOf((long) m) : String.valueOf(m); }
    static String fmt(long v) {
        if (v >= 1_000_000_000L) return trim(v / 1e9) + "B";
        if (v >= 1_000_000L) return trim(v / 1e6) + "M";
        if (v >= 1_000L) return trim(v / 1e3) + "K";
        return String.valueOf(v);
    }
    static String trim(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.2f", d); }
}
