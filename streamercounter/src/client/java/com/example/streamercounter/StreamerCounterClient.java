package com.example.streamercounter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class StreamerCounterClient implements ClientModInitializer {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("streamer-counter.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long TIMEOUT_MS = 60_000;
    private static final double NEAR_SQ = 8 * 8;
    private static final double JUMP_SQ = 20 * 20;

    private static Data data = new Data();
    private static KeyMapping openMenuKey;
    private static String pending;
    private static long deadline;
    private static boolean primed;
    private static double prevDistSq = Double.NaN;

    private static class Data {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Boolean> streamerEnabled = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        String lastName;
        boolean showHud = true;
        int hudX = -1;
        int hudY = 8;
        float hudScale = 1.0f;
    }

    @Override
    public void onInitializeClient() {
        load();
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("streamercounter", "general"));
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.streamercounter.open_menu", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K, category));

        ClientSendMessageEvents.COMMAND.register(command -> {
            String[] args = command.trim().split("\\s+");
            if (args.length >= 2 && args[0].equalsIgnoreCase("tpahere")) {
                pending = args[1];
                String key = pending.toLowerCase(Locale.ROOT);
                data.streamerEnabled.putIfAbsent(key, true);
                data.displayNames.put(key, pending);
                save();
                deadline = System.currentTimeMillis() + TIMEOUT_MS;
                primed = false;
                prevDistSq = Double.NaN;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.consumeClick()) {
                if (client.player != null) {
                    client.gui.setScreen(new CounterMenuScreen(client.gui.screen(), 0));
                }
            }
            if (pending == null || client.level == null || client.player == null) return;
            if (System.currentTimeMillis() > deadline) {
                pending = null;
                return;
            }

            double distSq = Double.NaN;
            String shownName = pending;
            for (AbstractClientPlayer p : client.level.players()) {
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
                ResourceLocation.fromNamespaceAndPath("streamercounter", "hud"),
                StreamerCounterClient::renderHud);
    }

    private static void arrived(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (data.streamerEnabled.getOrDefault(key, true)) {
            data.counts.merge(key, 1, Integer::sum);
            data.displayNames.put(key, name);
            data.lastName = name;
        }
        pending = null;
        save();
    }

    private static List<String> hudNames() {
        return data.counts.keySet().stream()
                .filter(name -> data.streamerEnabled.getOrDefault(name, true))
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static String displayName(String key) {
        return data.displayNames.getOrDefault(key, key);
    }

    private static int hudWidth() {
        return Math.round(132 * data.hudScale);
    }

    private static int hudHeight() {
        return Math.round((18 + Math.max(1, hudNames().size()) * 11 + 5) * data.hudScale);
    }

    private static int visibleHudY(int screenHeight) {
        return Math.max(0, Math.min(data.hudY, screenHeight - hudHeight() - 8));
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (!data.showHud || hudNames().isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        int x = data.hudX < 0 ? width - hudWidth() - 8 : data.hudX;
        drawHud(graphics, x, visibleHudY(height));
    }

    private static void drawHud(GuiGraphicsExtractor graphics, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        List<String> names = hudNames();
        int scale = Math.max(1, Math.round(data.hudScale));
        int width = hudWidth();
        int height = hudHeight();
        graphics.fill(x, y, x + width, y + height, 0x990B111A);
        graphics.fill(x, y, x + width, y + 2, 0xB8E8B64D);
        graphics.text(mc.font, "STREAMERS", x + 6, y + 6, 0xFFE8B64D, false);
        int rowY = y + 20;
        for (String key : names) {
            String name = displayName(key);
            String count = String.valueOf(data.counts.getOrDefault(key, 0));
            graphics.text(mc.font, name, x + 6, rowY, 0xFFE8EDF5, false);
            graphics.text(mc.font, count, x + width - 7 - mc.font.width(count), rowY, 0xFFFFC85A, false);
            rowY += 11;
        }
    }

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                Data loaded = GSON.fromJson(Files.readString(FILE), Data.class);
                if (loaded != null) {
                    if (loaded.counts == null) loaded.counts = new HashMap<>();
                    if (loaded.streamerEnabled == null) loaded.streamerEnabled = new HashMap<>();
                    if (loaded.displayNames == null) loaded.displayNames = new HashMap<>();
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

    private static class HudEditScreen extends Screen {
        private final Screen parent;

        HudEditScreen(Screen parent) {
            super(Component.literal("Edit HUD"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int center = width / 2;
            addRenderableWidget(Button.builder(Component.literal("Smaller"), b -> {
                data.hudScale = Math.max(0.6f, data.hudScale - 0.1f);
                save();
            }).bounds(center - 112, height - 58, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Larger"), b -> {
                data.hudScale = Math.min(2.0f, data.hudScale + 0.1f);
                save();
            }).bounds(center - 38, height - 58, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Reset Position"), b -> {
                data.hudX = -1;
                data.hudY = 8;
                save();
            }).bounds(center + 36, height - 58, 112, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(center - 45, height - 30, 90, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xB0000000);
            graphics.text(font, "HUD Preview", width / 2 - font.width("HUD Preview") / 2, 28, 0xFFFFFFFF, true);
            graphics.text(font, "Use the controls below to resize or reset the HUD.",
                    width / 2 - 115, 46, 0xFFCCCCCC, true);
            int x = data.hudX < 0 ? width - hudWidth() - 8 : data.hudX;
            drawHud(graphics, x, visibleHudY(height));
            graphics.text(font, "Size: " + Math.round(data.hudScale * 100) + "%",
                    width / 2 - 35, height - 78, 0xFFFFC247, true);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        @Override
        public void onClose() {
            save();
            Minecraft.getInstance().gui.setScreen(parent);
        }
    }

    private static class CounterMenuScreen extends Screen {
        private final Screen parent;
        private final int page;
        private static final int ROW_HEIGHT = 24;

        CounterMenuScreen(Screen parent, int page) {
            super(Component.literal("Streamer Counter"));
            this.parent = parent;
            this.page = page;
        }

        private List<String> streamerNames() {
            Set<String> names = new TreeSet<>();
            names.addAll(data.counts.keySet());
            names.addAll(data.streamerEnabled.keySet());
            if (pending != null) names.add(pending.toLowerCase(Locale.ROOT));
            return new ArrayList<>(names);
        }

        private void reopen(int newPage) {
            Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent, newPage));
        }

        @Override
        protected void init() {
            int center = width / 2;
            int buttonWidth = Math.min(300, width - 40);
            int left = center - buttonWidth / 2;
            int bottom = height - 126;
            int firstRowY = 88;
            int maxRows = Math.max(1, (bottom - firstRowY - 34) / ROW_HEIGHT);
            List<String> names = streamerNames();
            int pageCount = Math.max(1, (names.size() + maxRows - 1) / maxRows);
            int currentPage = Math.max(0, Math.min(page, pageCount - 1));
            int from = currentPage * maxRows;
            int to = Math.min(names.size(), from + maxRows);

            for (int i = from; i < to; i++) {
                String name = names.get(i);
                int count = data.counts.getOrDefault(name, 0);
                int rowY = firstRowY + (i - from) * ROW_HEIGHT;
                int actionWidth = 54;
                int gap = 4;
                int toggleWidth = buttonWidth - actionWidth * 2 - gap * 2;
                addRenderableWidget(Button.builder(streamerButtonText(name, count), button -> {
                    data.streamerEnabled.put(name, !data.streamerEnabled.getOrDefault(name, true));
                    save();
                    reopen(currentPage);
                }).bounds(left, rowY, toggleWidth, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Reset"), button -> {
                    data.counts.put(name, 0);
                    save();
                    reopen(currentPage);
                }).bounds(left + toggleWidth + gap, rowY, actionWidth, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Delete"), button -> {
                    data.counts.remove(name);
                    data.streamerEnabled.remove(name);
                    data.displayNames.remove(name);
                    if (data.lastName != null && data.lastName.equalsIgnoreCase(name)) data.lastName = null;
                    if (pending != null && pending.equalsIgnoreCase(name)) pending = null;
                    save();
                    reopen(currentPage);
                }).bounds(left + toggleWidth + gap + actionWidth + gap, rowY, actionWidth, 20).build());
            }

            if (pageCount > 1) {
                Button previous = addRenderableWidget(Button.builder(Component.literal("Previous"),
                        b -> reopen(currentPage - 1)).bounds(center - 95, bottom - 28, 90, 20).build());
                previous.active = currentPage > 0;
                Button next = addRenderableWidget(Button.builder(Component.literal("Next"),
                        b -> reopen(currentPage + 1)).bounds(center + 5, bottom - 28, 90, 20).build());
                next.active = currentPage < pageCount - 1;
            }

            int halfWidth = (buttonWidth - 6) / 2;
            addRenderableWidget(Button.builder(hudButtonText(), button -> {
                data.showHud = !data.showHud;
                save();
                button.setMessage(hudButtonText());
            }).bounds(left, height - 98, halfWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Edit HUD"), button ->
                    Minecraft.getInstance().gui.setScreen(new HudEditScreen(this)))
                    .bounds(left + halfWidth + 6, height - 98, halfWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Reset All Counters"), button -> {
                data.counts.clear();
                data.lastName = null;
                pending = null;
                save();
                reopen(0);
            }).bounds(left, height - 70, buttonWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                    .bounds(left, height - 42, buttonWidth, 20).build());
        }

        private Component streamerButtonText(String name, int count) {
            boolean enabled = data.streamerEnabled.getOrDefault(name, true);
            return Component.literal(name + "  |  " + (enabled ? "ON" : "OFF") + "  |  " + count + " TPs");
        }

        private Component hudButtonText() {
            return Component.literal("HUD: " + (data.showHud ? "ON" : "OFF"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0x90000000);
            int panelWidth = Math.min(360, width - 20);
            int left = (width - panelWidth) / 2;
            int top = 12;
            int right = left + panelWidth;
            int bottom = height - 126;
            graphics.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFFFFD54F);
            graphics.fill(left, top, right, bottom, 0xFF18202C);
            graphics.fill(left + 2, top + 2, right - 2, top + 5, 0xFF6C9BCB);
            String heading = "Streamer Counter";
            graphics.text(font, heading, width / 2 - font.width(heading) / 2, top + 16, 0xFFFFFFFF, true);
            String subtitle = "Enable or disable streamers individually";
            graphics.text(font, subtitle, width / 2 - font.width(subtitle) / 2, top + 40, 0xFFFFD54F, true);
            if (streamerNames().isEmpty()) {
                String line1 = "No streamers detected yet.";
                String line2 = "Streamers will appear here after /tpahere <name>.";
                graphics.text(font, line1, width / 2 - font.width(line1) / 2, top + 78, 0xFFFFFFFF, true);
                graphics.text(font, line2, width / 2 - font.width(line2) / 2, top + 96, 0xFFCCCCCC, true);
            }
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            String credit = "Made by AlpBey3503";
            graphics.text(font, credit, width / 2 - font.width(credit) / 2, height - 14, 0xFFE8B64D, true);
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().gui.setScreen(parent);
        }
    }
}
