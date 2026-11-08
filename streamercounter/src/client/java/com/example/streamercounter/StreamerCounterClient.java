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
        data.counts.merge(key, 1, Integer::sum);
        data.lastName = name;
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
        private ButtonWidget hudButton;

        protected CounterMenuScreen(Screen parent) {
            super(Text.literal("Streamer Counter"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int center = this.width / 2;
            int buttonWidth = Math.min(220, this.width - 40);
            int left = center - buttonWidth / 2;

            hudButton = addDrawableChild(ButtonWidget.builder(hudButtonText(), button -> {
                data.showHud = !data.showHud;
                save();
                button.setMessage(hudButtonText());
            }).dimensions(left, this.height - 98, buttonWidth, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Zähler zurücksetzen"), button -> {
                data.counts.clear();
                data.lastName = null;
                pending = null;
                save();
            }).dimensions(left, this.height - 70, buttonWidth, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Schließen"), button -> close())
                    .dimensions(left, this.height - 42, buttonWidth, 20).build());
        }

        private Text hudButtonText() {
            return Text.literal("HUD-Kasten: " + (data.showHud ? "AN" : "AUS"));
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            renderBackground(ctx, mouseX, mouseY, delta);

            // Draw the buttons first; draw the custom panel and labels afterwards.
            super.render(ctx, mouseX, mouseY, delta);

            int panelWidth = Math.min(340, this.width - 20);
            int left = (this.width - panelWidth) / 2;
            int top = 12;
            int right = left + panelWidth;
            int bottom = this.height - 126;

            ctx.fill(left - 3, top - 3, right + 3, bottom + 3, 0xFFFFD54F);
            ctx.fill(left, top, right, bottom, 0xFF18202C);
            ctx.fill(left + 2, top + 2, right - 2, top + 5, 0xFF6C9BCB);

            ctx.drawCenteredTextWithShadow(textRenderer, title, this.width / 2, top + 16, 0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer,
                    "DEIN STREAMER-COUNTER", this.width / 2, top + 40, 0xFFFFD54F);

            int y = top + 70;
            if (data.counts.isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer,
                        "Noch keine Streamer gezaehlt.", this.width / 2, y, 0xFFFFFFFF);
            } else {
                for (Map.Entry<String, Integer> entry : data.counts.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).toList()) {
                    if (y > bottom - 12) break;
                    ctx.drawCenteredTextWithShadow(textRenderer,
                            entry.getKey() + "  -  " + entry.getValue() + " TPs",
                            this.width / 2, y, 0xFFFFFFFF);
                    y += 16;
                }
            }
        }
        @Override
        public void close() {
            MinecraftClient.getInstance().setScreen(parent);
        }
    }
}
