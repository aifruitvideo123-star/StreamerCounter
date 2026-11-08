package com.example.streamercounter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.util.InputUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class StreamerCounterClient implements ClientModInitializer {
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("streamer-counter.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final long TIMEOUT_MS = 60_000;
    private static final double NEAR_SQ = 8 * 8;
    private static final double JUMP_SQ = 20 * 20;

    private static Data data = new Data();
    private static KeyBinding openMenuKey;

    private static String pending = null;
    private static long deadline = 0;
    private static boolean primed = false;
    private static double prevDistSq = Double.NaN;

    private static class Data {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Boolean> streamerEnabled = new HashMap<>();
        String lastName = null;
        boolean showHud = true;
    }

    @Override
    public void onInitializeClient() {
        load();

        KeyBinding.Category category = KeyBinding.Category.create(
                Identifier.of("streamercounter", "general"));
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.streamercounter.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                category
        ));

        ClientSendMessageEvents.COMMAND.register(command -> {
            String[] args = command.trim().split("\\s+");
            if (args.length >= 2 && args[0].equalsIgnoreCase("tpahere")) {
                pending = args[1];
                data.streamerEnabled.putIfAbsent(pending.toLowerCase(Locale.ROOT), true);
                save();
                deadline = System.currentTimeMillis() + TIMEOUT_MS;
                primed = false;
                prevDistSq = Double.NaN;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.wasPressed()) {
                if (client.player != null) {
                    client.setScreen(new CounterMenuScreen(client.currentScreen));
                }
            }

            if (pending == null || client.world == null || client.player == null) return;
            if (System.currentTimeMillis() > deadline) {
                pending = null;
                return;
            }

            double distSq = Double.NaN;
            String shownName = pending;
            for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                if (p == client.player) continue;
                String name = p.getName().getString();
                if (name.equalsIgnoreCase(pending)) {
                    double dx = p.getX() - client.player.getX();
                    double dy = p.getY() - client.player.getY();
                    double dz = p.getZ() - client.player.getZ();
                    distSq = dx * dx + dy * dy + dz * dz;
                    shownName = name;
                    break;
                }
            }

            if (!primed) {
                prevDistSq = distSq;
                primed = true;
                return;
            }

            boolean nearNow = !Double.isNaN(distSq) && distSq <= NEAR_SQ;
            boolean wasAway = Double.isNaN(prevDistSq) || prevDistSq > JUMP_SQ;
            if (nearNow && wasAway) arrived(shownName);
            prevDistSq = distSq;
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("streamercounter", "hud"),
                StreamerCounterClient::renderHud
        );
    }

    private static void arrived(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (data.streamerEnabled.getOrDefault(key, true)) {
            data.counts.merge(key, 1, Integer::sum);
            data.lastName = name;
        }
        pending = null;
        save();
    }

    private static void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!data.showHud || data.lastName == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        String line1 = data.lastName;
        String line2 = "TPs: " + data.counts.getOrDefault(line1.toLowerCase(Locale.ROOT), 0);
        int w = Math.max(tr.getWidth(line1), tr.getWidth(line2)) + 12;
        int h = tr.fontHeight * 2 + 12;
        int x = ctx.getScaledWindowWidth() - w - 6;
        int y = 6;

        ctx.fill(x, y, x + w, y + h, 0xA0000000);
        ctx.drawTextWithShadow(tr, line1, x + 6, y + 5, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, line2, x + 6, y + 7 + tr.fontHeight, 0xFFFFAA00);
    }

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                Data loaded = GSON.fromJson(Files.readString(FILE), Data.class);
                if (loaded != null) {
                    if (loaded.counts == null) loaded.counts = new HashMap<>();
                    if (loaded.streamerEnabled == null) loaded.streamerEnabled = new HashMap<>();
                    data = loaded;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(data));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class CounterMenuScreen extends Screen {
        private final Screen parent;
        private int page = 0;
        private static final int ROW_HEIGHT = 24;

        protected CounterMenuScreen(Screen parent) {
            super(Text.literal("Streamer Counter"));
            this.parent = parent;
        }

        private java.util.List<String> streamerNames() {
            java.util.Set<String> names = new java.util.TreeSet<>();
            names.addAll(data.counts.keySet());
            names.addAll(data.streamerEnabled.keySet());
            if (pending != null) names.add(pending.toLowerCase(Locale.ROOT));
            return new java.util.ArrayList<>(names);
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int buttonWidth = Math.min(300, this.width - 40);
            int left = center - buttonWidth / 2;
            int top = 12;
            int bottom = this.height - 126;
            int firstRowY = top + 76;
            int maxRows = Math.max(1, (bottom - firstRowY - 34) / ROW_HEIGHT);

            java.util.List<String> names = streamerNames();
            int pageCount = Math.max(1, (names.size() + maxRows - 1) / maxRows);
            page = Math.max(0, Math.min(page, pageCount - 1));
            int from = page * maxRows;
            int to = Math.min(names.size(), from + maxRows);

            for (int i = from; i < to; i++) {
                String name = names.get(i);
                int count = data.counts.getOrDefault(name, 0);
                addDrawableChild(ButtonWidget.builder(streamerButtonText(name, count), button -> {
                    boolean enabled = !data.streamerEnabled.getOrDefault(name, true);
                    data.streamerEnabled.put(name, enabled);
                    save();
                    button.setMessage(streamerButtonText(name, data.counts.getOrDefault(name, 0)));
                }).dimensions(left, firstRowY + (i - from) * ROW_HEIGHT, buttonWidth, 20).build());
            }

            if (pageCount > 1) {
                int navWidth = 90;
                ButtonWidget previous = addDrawableChild(ButtonWidget.builder(Text.literal("Zurück"), button -> {
                    page--;
                    clearAndInit();
                }).dimensions(center - navWidth - 5, bottom - 28, navWidth, 20).build());
                previous.active = page > 0;

                ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal("Weiter"), button -> {
                    page++;
                    clearAndInit();
                }).dimensions(center + 5, bottom - 28, navWidth, 20).build());
                next.active = page < pageCount - 1;
            }

            addDrawableChild(ButtonWidget.builder(hudButtonText(), button -> {
                data.showHud = !data.showHud;
                save();
                button.setMessage(hudButtonText());
            }).dimensions(left, this.height - 98, buttonWidth, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Alle Zähler zurücksetzen"), button -> {
                data.counts.clear();
                data.lastName = null;
                pending = null;
                save();
                clearAndInit();
            }).dimensions(left, this.height - 70, buttonWidth, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Schließen"), button -> close())
                    .dimensions(left, this.height - 42, buttonWidth, 20).build());
        }

        private Text streamerButtonText(String name, int count) {
            boolean enabled = data.streamerEnabled.getOrDefault(name, true);
            return Text.literal(name + "  |  " + (enabled ? "AN" : "AUS") + "  |  " + count + " TPs");
        }

        private Text hudButtonText() {
            return Text.literal("HUD: " + (data.showHud ? "AN" : "AUS"));
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            // Draw a plain dim overlay instead of Lunar Client's blur-backed background.
            // This avoids the 'Can only blur once per frame' conflict in Lunar Client.
            ctx.fill(0, 0, this.width, this.height, 0x90000000);

            int panelWidth = Math.min(360, this.width - 20);
            int left = (this.width - panelWidth) / 2;
            int top = 12;
            int right = left + panelWidth;
            int bottom = this.height - 126;

            ctx.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFFFFD54F);
            ctx.fill(left, top, right, bottom, 0xFF18202C);
            ctx.fill(left + 2, top + 2, right - 2, top + 5, 0xFF6C9BCB);

            ctx.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, top + 16, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer,
                    "Streamer einzeln an- oder ausschalten", this.width / 2, top + 40, 0xFFFFD54F);

            if (streamerNames().isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer,
                        "Noch keine Streamer erkannt.", this.width / 2, top + 78, 0xFFFFFFFF);
                ctx.drawCenteredTextWithShadow(textRenderer,
                        "Nach /tpahere Name erscheint der Streamer hier.", this.width / 2, top + 96, 0xFFCCCCCC);
            }

            super.render(ctx, mouseX, mouseY, delta);
        }

        @Override
        public void close() {
            MinecraftClient.getInstance().setScreen(parent);
        }
    }
}
