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
import net.minecraft.client.gui.Click;
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
        Map<String, String> displayNames = new HashMap<>();
        String lastName = null;
        boolean showHud = true;
        int hudX = -1;
        int hudY = 8;
        float hudScale = 1.0f;
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
                data.displayNames.put(pending.toLowerCase(Locale.ROOT), pending);
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
            data.displayNames.put(key, name);
            data.lastName = name;
        }
        pending = null;
        save();
    }

    private static java.util.List<String> hudStreamerNames() {
        return data.counts.keySet().stream()
                .filter(name -> data.streamerEnabled.getOrDefault(name, true))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static String displayName(String key) {
        return data.displayNames.getOrDefault(key,
                key.equalsIgnoreCase(data.lastName == null ? "" : data.lastName)
                        ? data.lastName : key);
    }

    private static int hudWidth(float scale) {
        return (int) (132 * scale);
    }

    private static int hudHeight(float scale) {
        int rows = Math.max(1, hudStreamerNames().size());
        return (int) ((18 + rows * 11 + 5) * scale);
    }

    // Keep the full HUD visible when its growing list reaches the bottom of the screen.
    private static int visibleHudY(int screenHeight) {
        return Math.max(0, Math.min(data.hudY, screenHeight - hudHeight(data.hudScale) - 8));
    }

    private static void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!data.showHud || hudStreamerNames().isEmpty()) return;

        int width = ctx.getScaledWindowWidth();
        int height = ctx.getScaledWindowHeight();
        int x = data.hudX < 0 ? width - hudWidth(data.hudScale) - 8 : data.hudX;
        drawHud(ctx, x, visibleHudY(height), data.hudScale);
    }

    // Compact, translucent rectangular panel with every enabled streamer that has been counted.
    private static void drawHud(DrawContext ctx, int x, int y, float scale) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        java.util.List<String> names = hudStreamerNames();
        int width = 132;
        int height = 18 + Math.max(1, names.size()) * 11 + 5;

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(scale, scale);

        // Semi-transparent charcoal background, square corners, subtle accent line.
        ctx.fill(0, 0, width, height, 0x990B111A);
        ctx.fill(0, 0, width, 2, 0xB8E8B64D);
        ctx.drawText(tr, "STREAMERS", 6, 6, 0xFFE8B64D, false);

        if (names.isEmpty()) {
            ctx.drawText(tr, "No TPs yet", 6, 21, 0xFFCCCCCC, false);
        } else {
            int rowY = 20;
            for (String key : names) {
                String name = tr.trimToWidth(displayName(key), 83);
                String count = String.valueOf(data.counts.getOrDefault(key, 0));
                ctx.drawText(tr, name, 6, rowY, 0xFFE8EDF5, false);
                ctx.drawText(tr, count, width - 7 - tr.getWidth(count), rowY, 0xFFFFC85A, false);
                rowY += 11;
            }
        }
        ctx.getMatrices().popMatrix();
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
        private boolean dragging = false;
        private double dragOffsetX;
        private double dragOffsetY;

        protected HudEditScreen(Screen parent) {
            super(Text.literal("Edit HUD"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            addDrawableChild(ButtonWidget.builder(Text.literal("−"), button -> {
                data.hudScale = Math.max(0.6f, data.hudScale - 0.1f);
                save();
            }).dimensions(center - 112, this.height - 54, 42, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), button -> {
                data.hudScale = Math.min(2.0f, data.hudScale + 0.1f);
                save();
            }).dimensions(center - 65, this.height - 54, 42, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Reset Position"), button -> {
                data.hudX = -1;
                data.hudY = 8;
                save();
            }).dimensions(center - 18, this.height - 54, 112, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                    .dimensions(center - 45, this.height - 27, 90, 20).build());
        }

        private String previewName() {
            return data.lastName == null ? "StreamerName" : data.lastName;
        }

        private int previewCount() {
            return data.lastName == null ? 12 :
                    data.counts.getOrDefault(data.lastName.toLowerCase(Locale.ROOT), 0);
        }

        private int hudX() {
            return data.hudX < 0 ? this.width - hudWidth(data.hudScale) - 8 : data.hudX;
        }

        private boolean overHud(double mouseX, double mouseY) {
            int x = hudX();
            int y = visibleHudY(this.height);
            int w = hudWidth(data.hudScale);
            int h = hudHeight(data.hudScale);
            return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            double mouseX = click.x();
            double mouseY = click.y();
            if (click.button() == 0 && overHud(mouseX, mouseY)) {
                dragging = true;
                dragOffsetX = mouseX - hudX();
                dragOffsetY = mouseY - visibleHudY(this.height);
                return true;
            }
            return super.mouseClicked(click, doubled);
        }

        @Override
        public boolean mouseDragged(Click click, double offsetX, double offsetY) {
            if (dragging && click.button() == 0) {
                int w = hudWidth(data.hudScale);
                int h = hudHeight(data.hudScale);
                data.hudX = Math.max(0, Math.min(this.width - w, (int) (click.x() - dragOffsetX)));
                data.hudY = Math.max(0, Math.min(this.height - h, (int) (click.y() - dragOffsetY)));
                return true;
            }
            return super.mouseDragged(click, offsetX, offsetY);
        }

        @Override
        public boolean mouseReleased(Click click) {
            if (click.button() == 0 && dragging) {
                dragging = false;
                save();
                return true;
            }
            return super.mouseReleased(click);
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            ctx.fill(0, 0, this.width, this.height, 0xB0000000);
            ctx.drawCenteredTextWithShadow(textRenderer, "HUD Preview", this.width / 2, 28, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, "Drag the HUD to your preferred position.",
                    this.width / 2, 46, 0xFFCCCCCC);
            drawHud(ctx, hudX(), visibleHudY(this.height), data.hudScale);
            ctx.drawCenteredTextWithShadow(textRenderer, "Size: " + Math.round(data.hudScale * 100) + "%",
                    this.width / 2, this.height - 68, 0xFFFFC247);
            super.render(ctx, mouseX, mouseY, delta);
        }

        @Override
        public void close() {
            save();
            MinecraftClient.getInstance().setScreen(parent);
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
                int rowY = firstRowY + (i - from) * ROW_HEIGHT;
                int actionWidth = 54;
                int gap = 4;
                int toggleWidth = buttonWidth - actionWidth * 2 - gap * 2;
                addDrawableChild(ButtonWidget.builder(streamerButtonText(name, count), button -> {
                    boolean enabled = !data.streamerEnabled.getOrDefault(name, true);
                    data.streamerEnabled.put(name, enabled);
                    save();
                    button.setMessage(streamerButtonText(name, data.counts.getOrDefault(name, 0)));
                }).dimensions(left, rowY, toggleWidth, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), button -> {
                    data.counts.put(name, 0);
                    save();
                    clearAndInit();
                }).dimensions(left + toggleWidth + gap, rowY, actionWidth, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), button -> {
                    data.counts.remove(name);
                    data.streamerEnabled.remove(name);
                    data.displayNames.remove(name);
                    if (data.lastName != null && data.lastName.equalsIgnoreCase(name)) {
                        data.lastName = null;
                    }
                    if (pending != null && pending.equalsIgnoreCase(name)) {
                        pending = null;
                    }
                    save();
                    clearAndInit();
                }).dimensions(left + toggleWidth + gap + actionWidth + gap, rowY, actionWidth, 20).build());
            }

            if (pageCount > 1) {
                int navWidth = 90;
                ButtonWidget previous = addDrawableChild(ButtonWidget.builder(Text.literal("Previous"), button -> {
                    page--;
                    clearAndInit();
                }).dimensions(center - navWidth - 5, bottom - 28, navWidth, 20).build());
                previous.active = page > 0;

                ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal("Next"), button -> {
                    page++;
                    clearAndInit();
                }).dimensions(center + 5, bottom - 28, navWidth, 20).build());
                next.active = page < pageCount - 1;
            }

            int halfWidth = (buttonWidth - 6) / 2;
            addDrawableChild(ButtonWidget.builder(hudButtonText(), button -> {
                data.showHud = !data.showHud;
                save();
                button.setMessage(hudButtonText());
            }).dimensions(left, this.height - 98, halfWidth, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD"), button ->
                    MinecraftClient.getInstance().setScreen(new HudEditScreen(this)))
                    .dimensions(left + halfWidth + 6, this.height - 98, halfWidth, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Reset All Counters"), button -> {
                data.counts.clear();
                data.lastName = null;
                pending = null;
                save();
                clearAndInit();
            }).dimensions(left, this.height - 70, buttonWidth, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                    .dimensions(left, this.height - 42, buttonWidth, 20).build());
        }

        private Text streamerButtonText(String name, int count) {
            boolean enabled = data.streamerEnabled.getOrDefault(name, true);
            return Text.literal(name + "  |  " + (enabled ? "ON" : "OFF") + "  |  " + count + " TPs");
        }

        private Text hudButtonText() {
            return Text.literal("HUD: " + (data.showHud ? "ON" : "OFF"));
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
                    "Enable or disable streamers individually", this.width / 2, top + 40, 0xFFFFD54F);

            if (streamerNames().isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer,
                        "No streamers detected yet.", this.width / 2, top + 78, 0xFFFFFFFF);
                ctx.drawCenteredTextWithShadow(textRenderer,
                        "Streamers will appear here after /tpahere <name>.", this.width / 2, top + 96, 0xFFCCCCCC);
            }

            super.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredTextWithShadow(textRenderer, "Made by AlpBey3503",
                    this.width / 2, this.height - 14, 0xFFE8B64D);
        }

        @Override
        public void close() {
            MinecraftClient.getInstance().setScreen(parent);
        }
    }
}
