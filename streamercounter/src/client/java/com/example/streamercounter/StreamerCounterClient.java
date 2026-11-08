package com.example.streamercounter; // an das Package deines Templates anpassen

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Zeigt oben rechts einen halbtransparenten Kasten mit dem Namen des zuletzt
 * "geholten" Streamers und wie oft er schon zu dir teleportiert ist.
 *
 * Erkennung: Du sendest /tpahere <Name>. Danach wird bis zu 60 Sekunden lang
 * beobachtet, ob dieser Spieler plötzlich in deiner Nähe auftaucht
 * (vorher nicht geladen oder weit weg). Dann zählt der Mod +1.
 */
public class StreamerCounterClient implements ClientModInitializer {

    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("streamer-counter.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final long TIMEOUT_MS = 60_000;      // so lange wird nach /tpahere gewartet
    private static final double NEAR_SQ = 8 * 8;        // "bei dir" = höchstens 8 Blöcke entfernt
    private static final double JUMP_SQ = 20 * 20;      // vorher mehr als 20 Blöcke weg = Teleport

    /** Gespeicherte Daten. */
    private static class Data {
        Map<String, Integer> counts = new HashMap<>();
        String lastName = null;
    }

    private static Data data = new Data();

    // Zustand der aktuellen Anfrage
    private static String pending = null;
    private static long deadline = 0;
    private static boolean primed = false;
    private static double prevDistSq = Double.NaN;

    @Override
    public void onInitializeClient() {
        load();

        // 1) /tpahere <Name> erkennen
        ClientSendMessageEvents.COMMAND.register(command -> {
            String[] args = command.trim().split("\\s+");
            if (args.length >= 2 && args[0].equalsIgnoreCase("tpahere")) {
                pending = args[1];
                deadline = System.currentTimeMillis() + TIMEOUT_MS;
                primed = false;
                prevDistSq = Double.NaN;
            }
        });

        // 2) Beobachten, ob der Spieler bei dir auftaucht
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
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

            // Erster Tick nach dem Befehl: nur Ausgangslage merken
            if (!primed) {
                prevDistSq = distSq;
                primed = true;
                return;
            }

            boolean nearNow = !Double.isNaN(distSq) && distSq <= NEAR_SQ;
            boolean wasAway = Double.isNaN(prevDistSq) || prevDistSq > JUMP_SQ;
            if (nearNow && wasAway) {
                arrived(shownName);
            }
            prevDistSq = distSq;
        });

        // 3) HUD oben rechts
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
        if (data.lastName == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;

        String line1 = data.lastName;
        String line2 = "TPs: " + data.counts.getOrDefault(data.lastName.toLowerCase(Locale.ROOT), 0);

        int w = Math.max(tr.getWidth(line1), tr.getWidth(line2)) + 8;
        int h = tr.fontHeight * 2 + 10;
        int x = ctx.getScaledWindowWidth() - w - 4;
        int y = 4;

        ctx.fill(x, y, x + w, y + h, 0x80000000);                       // durchsichtiges Schwarz
        ctx.drawTextWithShadow(tr, line1, x + 4, y + 4, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, line2, x + 4, y + 6 + tr.fontHeight, 0xFFFFAA00);
    }

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                Data loaded = GSON.fromJson(Files.readString(FILE), Data.class);
                if (loaded != null && loaded.counts != null) data = loaded;
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
}
