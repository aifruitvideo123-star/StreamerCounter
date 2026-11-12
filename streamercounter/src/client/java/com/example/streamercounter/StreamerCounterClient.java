package com.example.streamercounter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.util.InputUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class StreamerCounterClient implements ClientModInitializer {
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("streamer-counter-media.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final long TIMEOUT_MS = 60_000;
    private static final double NEAR_SQ = 8 * 8;
    private static final double JUMP_SQ = 20 * 20;

    private static long timerFinishedAt = 0L;
    private static Data data = new Data();
    // Cache the sorted HUD roster; rebuild only after saved data changes.
    private static boolean hudNamesDirty = true;
    private static List<String> cachedHudNames = List.of();
    private static final double[] TIMER_CIRCLE_COS = new double[60];
    private static final double[] TIMER_CIRCLE_SIN = new double[60];
    private static final double[] TIMER_PARTICLE_COS = new double[8];
    private static final double[] TIMER_PARTICLE_SIN = new double[8];
    static {
        for (int i = 0; i < 60; i++) {
            double angle = -Math.PI / 2.0 + i * Math.PI * 2.0 / 60.0;
            TIMER_CIRCLE_COS[i] = Math.cos(angle);
            TIMER_CIRCLE_SIN[i] = Math.sin(angle);
        }
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0;
            TIMER_PARTICLE_COS[i] = Math.cos(angle);
            TIMER_PARTICLE_SIN[i] = Math.sin(angle);
        }
    }
    private static KeyBinding openMenuKey;

    private static String pending = null;
    private static long deadline = 0;
    private static boolean primed = false;
    private static double prevDistSq = Double.NaN;

    private static class SessionRecord {
        long startedAt;
        long endedAt;
        double income;
        double expenses;
        int transactions;
    }

    private static class Data {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Boolean> streamerEnabled = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        String lastName = null;
        boolean showHud = true;
        int hudX = -1;
        int hudY = 8;
        float hudScale = 1.0f;
        float hudOpacity = 0.6f;
        boolean compactHud = false;
        int textColor = 0xFFFFC85A;
        boolean iceTheme = false;
        String vipTheme = "GOLD";
        int selectedTextSection = 0;
        List<ScreenText> screenTexts = new ArrayList<>();
        boolean auctionActive = false;
        String auctionItem = "Auction item";
        String auctionHighestPlayer = "No bids yet";
        double auctionHighestBid = 0.0;
        int auctionDurationSeconds = 60;
        long auctionEndTime = 0L;
        Map<String, Double> auctionBids = new java.util.LinkedHashMap<>();
        String auctionWinner = "";
        boolean auctionSold = false;
        long auctionSoldUntil = 0L;
        int auctionMinBid = 0;
        boolean auctionShowWorld = true;
        int auctionHudX=-1, auctionHudY=28, timerHudX=-1, timerHudY=-1;
        float auctionHudScale=1.0f, timerHudScale=1.0f;
        int timerDurationSeconds = 60;
        long timerEndTime = 0L;
        boolean timerActive = false;
        boolean timerFinished = false;
        boolean sessionActive = false;
        long sessionStartedAt = 0L;
        double sessionIncome = 0.0;
        double sessionExpenses = 0.0;
        int sessionTransactions = 0;
        List<SessionRecord> sessionHistory = new ArrayList<>();
    }

    private static double parseSessionAmount(String raw, String suffix) {
        try {
            double value=Double.parseDouble(raw.replace(",",""));
            if(suffix!=null)value*=switch(suffix.toLowerCase(Locale.ROOT)){case "k"->1_000d;case "m"->1_000_000d;case "b"->1_000_000_000d;default->1d;};
            return value;
        } catch(NumberFormatException ex){return 0d;}
    }
    private static void startSession() {
        data.sessionActive=true;data.sessionStartedAt=System.currentTimeMillis();
        data.sessionIncome=0d;data.sessionExpenses=0d;data.sessionTransactions=0;save();
    }
    private static void endSession() {
        if(!data.sessionActive)return;
        SessionRecord record=new SessionRecord();record.startedAt=data.sessionStartedAt;
        record.endedAt=System.currentTimeMillis();record.income=data.sessionIncome;
        record.expenses=data.sessionExpenses;record.transactions=data.sessionTransactions;
        if(data.sessionHistory==null)data.sessionHistory=new ArrayList<>();
        data.sessionHistory.add(0,record);
        while(data.sessionHistory.size()>30)data.sessionHistory.remove(data.sessionHistory.size()-1);
        data.sessionActive=false;save();
    }
    private static String sessionDuration(long from,long to) {
        long seconds=Math.max(0,(to-from)/1000);return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);
    }

    private static String formatAuctionDuration(int seconds) {
        if (seconds >= 60 && seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    private static String formatAuctionMoney(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000_000d) return String.format(Locale.ROOT, "%.2fT", value / 1_000_000_000_000d).replaceAll("\\.?0+T$", "T");
        if (abs >= 1_000_000_000d) return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000d).replaceAll("\\.?0+B$", "B");
        if (abs >= 1_000_000d) return String.format(Locale.ROOT, "%.2fM", value / 1_000_000d).replaceAll("\\.?0+M$", "M");
        if (abs >= 1_000d) return String.format(Locale.ROOT, "%.2fK", value / 1_000d).replaceAll("\\.?0+K$", "K");
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static class ScreenText {
        String text = "Your text";
        int x = 80, y = 80, color = 0xFFFFFFFF, size = 16;
        String font = "default";
        String effect = "flow";
        boolean visible = true, bold = false, italic = false, shadow = true;
    }

    @Override
    public void onInitializeClient() {
        load();

        KeyBinding.Category category = KeyBinding.Category.create(
                Identifier.of("streamercounter_vip", "general"));
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.streamercounter_vip.open_menu",
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

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if(overlay)return;
            String line=message.getString().trim();
            if(data.sessionActive){
                java.util.regex.Matcher incoming=java.util.regex.Pattern.compile("(?i)^(.+?)\\s+paid you\\s+\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)([kmb])?(?:\\s+.*)?$").matcher(line);
                java.util.regex.Matcher outgoing=java.util.regex.Pattern.compile("(?i)^you paid\\s+(.+?)\\s+\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)([kmb])?(?:\\s+.*)?$").matcher(line);
                if(incoming.matches()){
                    double amount=parseSessionAmount(incoming.group(2),incoming.group(3));
                    if(amount>0){data.sessionIncome+=amount;data.sessionTransactions++;save();}
                }else if(outgoing.matches()){
                    double amount=parseSessionAmount(outgoing.group(2),outgoing.group(3));
                    if(amount>0){data.sessionExpenses+=amount;data.sessionTransactions++;save();}
                }
            }
            if(!data.auctionActive)return;
            java.util.regex.Matcher payment=java.util.regex.Pattern.compile("(?i)^(.+?)\\s+paid you\\s+\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)([kmb])?\\s*$").matcher(line);
            if(!payment.matches())return;
            String bidder=payment.group(1).trim();
            double amount=parseSessionAmount(payment.group(2),payment.group(3));
            if(amount<=0)return;
            data.auctionBids.merge(bidder,amount,Double::sum);
            double total=data.auctionBids.get(bidder);
            if(total>data.auctionHighestBid){data.auctionHighestBid=total;data.auctionHighestPlayer=bidder;}
            save();
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
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.player.sendMessage(Text.literal("VIP: " + name + " joined your TPAHERE!"), true);
        }
        pending = null;
        save();
    }

    private static java.util.List<String> hudStreamerNames() {
        if (hudNamesDirty) {
            cachedHudNames = data.counts.keySet().stream()
                    .filter(name -> data.streamerEnabled.getOrDefault(name, true))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            hudNamesDirty = false;
        }
        return cachedHudNames;
    }

    private static String displayName(String key) {
        return data.displayNames.getOrDefault(key,
                key.equalsIgnoreCase(data.lastName == null ? "" : data.lastName)
                        ? data.lastName : key);
    }

    private static int hudPanelColor() { int alpha=(int)(153 * Math.max(0.15f,Math.min(1.0f,data.hudOpacity))); return (alpha << 24) | 0x000B111A; }

    private static int hudWidth(float scale) {
        return (int) (132 * scale);
    }

    private static int hudHeight(float scale) {
        int rows = Math.max(1, hudStreamerNames().size());
        return (int) ((18 + rows * (data.compactHud ? 9 : 11) + 5) * scale);
    }

    // Keep the full HUD visible when its growing list reaches the bottom of the screen.
    private static int visibleHudY(int screenHeight) {
        return Math.max(0, Math.min(data.hudY, screenHeight - hudHeight(data.hudScale) - 8));
    }

    private static void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        if (data.showHud && !hudStreamerNames().isEmpty()) {
        int width = ctx.getScaledWindowWidth();
        int height = ctx.getScaledWindowHeight();
        int x = data.hudX < 0 ? width - hudWidth(data.hudScale) - 8 : data.hudX;
        drawHud(ctx, x, visibleHudY(height), data.hudScale);
        }
        if (data.screenTexts == null) data.screenTexts = new ArrayList<>();
        for (ScreenText entry : data.screenTexts) {
            if (entry == null || !entry.visible || entry.text == null || entry.text.isEmpty()) continue;
            float scale = Math.max(8, Math.min(96, entry.size)) / 9.0f;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(entry.x, entry.y);
            ctx.getMatrices().scale(scale, scale);
            
            if ("flow".equals(entry.effect)) {
                drawFlowText(ctx, MinecraftClient.getInstance().textRenderer, entry.text, 0, 0, 0xFFE34B55, entry.shadow);
            } else if ("pulse".equals(entry.effect)) {
                int pulse = (int)(128 + 127 * Math.sin(System.currentTimeMillis() / 180.0));
                int color = (pulse > 185) ? 0xFFFFFFFF : 0xFFE34B55;
                Text styled = Text.literal(entry.text).styled(st -> st.withBold(entry.bold).withItalic(entry.italic));
                ctx.drawText(MinecraftClient.getInstance().textRenderer, styled, 0, 0, color, entry.shadow);
            } else {
                Text styled = Text.literal(entry.text).styled(st -> st.withBold(entry.bold).withItalic(entry.italic));
                ctx.drawText(MinecraftClient.getInstance().textRenderer, styled, 0, 0, 0xFFFFFFFF, entry.shadow);
            }
            ctx.getMatrices().popMatrix();
        }
    
        if (data.auctionSold && System.currentTimeMillis() > data.auctionSoldUntil) { data.auctionSold=false; data.auctionWinner=""; data.auctionHighestBid=0; data.auctionHighestPlayer="No bids yet"; save(); }
        if (data.auctionShowWorld && (data.auctionActive || (data.auctionSold && System.currentTimeMillis() <= data.auctionSoldUntil))) {
            int hudW=ctx.getScaledWindowWidth(), hudH=ctx.getScaledWindowHeight();
            float auctionScale=Math.max(0.6f,Math.min(1.8f,data.auctionHudScale)); int aw=(int)(132*auctionScale), ah=(int)((data.auctionSold?104:76)*auctionScale);
            int cx=data.auctionHudX<0?hudW/2:data.auctionHudX, y=data.auctionHudY;
            long left = data.auctionActive ? Math.max(0,(data.auctionEndTime-System.currentTimeMillis()+999)/1000) : 0;
            ctx.fill(cx-aw,y,cx+aw,y+ah,0xCC120C08); ctx.fill(cx-aw,y,cx+aw,y+2,0xFFFF8126);
            if (data.auctionActive && MinecraftClient.getInstance().player != null) {
                var player = MinecraftClient.getInstance().player;
                for (int slot=0; slot<player.getInventory().size(); slot++) {
                    var stack=player.getInventory().getStack(slot);
                    if (!stack.isEmpty() && stack.getName().getString().equals(data.auctionItem)) { ctx.drawItem(stack,cx-124,y+8); break; }
                }
            }
            drawFlowText(ctx,MinecraftClient.getInstance().textRenderer,data.auctionSold ? "SOLD" : data.auctionItem,cx-100,y+8,0xFFFFFFFF,true);
            drawFlowText(ctx,MinecraftClient.getInstance().textRenderer,data.auctionActive ? String.format(Locale.ROOT,"%02d:%02d",left/60,left%60) : data.auctionWinner,cx-100,y+27,0xFFFF8126,true);
            drawFlowText(ctx,MinecraftClient.getInstance().textRenderer,"DURATION "+formatAuctionDuration(data.auctionDurationSeconds),cx-100,y+39,0xFFFFC04A,true);
            drawFlowText(ctx,MinecraftClient.getInstance().textRenderer,data.auctionHighestPlayer+"  $"+formatAuctionMoney(data.auctionHighestBid),cx-100,y+49,data.textColor,true);
            if (data.auctionActive && System.currentTimeMillis() >= data.auctionEndTime) {
            data.auctionActive=false; data.auctionSold=true;
            data.auctionWinner=data.auctionHighestBid>0?data.auctionHighestPlayer:"No bids";
            data.auctionSoldUntil=System.currentTimeMillis()+10000L; save();
        }
        if (data.auctionSold && System.currentTimeMillis() <= data.auctionSoldUntil) {
            long bannerElapsed=10000L-(data.auctionSoldUntil-System.currentTimeMillis());
            int bannerPulse=(int)(Math.sin(bannerElapsed/150.0)*5)+5;
            int bannerCx=ctx.getScaledWindowWidth()/2, bannerCy=ctx.getScaledWindowHeight()/2;
            ctx.fill(bannerCx-154-bannerPulse,bannerCy-42,bannerCx+154+bannerPulse,bannerCy+43,0xDD120C08);
            ctx.fill(bannerCx-154-bannerPulse,bannerCy-42,bannerCx+154+bannerPulse,bannerCy-39,0xFFFF8126);
            drawFlowText(ctx,MinecraftClient.getInstance().textRenderer,"★ AUCTION WON ★",bannerCx-92,bannerCy-29,0xFFFFC04A,true);
            drawFlowText(ctx,MinecraftClient.getInstance().textRenderer,data.auctionWinner,bannerCx-100,bannerCy-7,0xFFFFFFFF,true);
            drawFlowText(ctx,MinecraftClient.getInstance().textRenderer,"WON FOR $"+formatAuctionMoney(data.auctionHighestBid),bannerCx-100,bannerCy+14,0xFFFF8126,true);
        }
        }
        if(data.timerActive && System.currentTimeMillis()>=data.timerEndTime){data.timerActive=false;data.timerFinished=true;timerFinishedAt=System.currentTimeMillis();save();}
        if(timerFinishedAt>0 && System.currentTimeMillis()-timerFinishedAt>=5200){
            data.timerFinished=false;
            timerFinishedAt=0L;
            save();
        }
        if(data.timerActive || data.timerFinished){
            long remain=data.timerActive?Math.max(0,(data.timerEndTime-System.currentTimeMillis()+999)/1000):0;
            int timerCx=data.timerHudX<0?ctx.getScaledWindowWidth()/2:data.timerHudX, timerCy=data.timerHudY<0?ctx.getScaledWindowHeight()/3:data.timerHudY; float timerScale=Math.max(0.6f,Math.min(1.8f,data.timerHudScale)); int timerRadius=(int)(62*timerScale);
            int total=Math.max(1,data.timerDurationSeconds); double progress=data.timerActive?Math.max(0,Math.min(1,(double)remain/total)):0;
            ctx.fill(timerCx-timerRadius,timerCy-timerRadius,timerCx+timerRadius,timerCy+timerRadius,0xCC10131B);
            for(int i=0;i<60;i++){int px=timerCx+(int)(timerRadius*0.85*TIMER_CIRCLE_COS[i]),py=timerCy+(int)(timerRadius*0.85*TIMER_CIRCLE_SIN[i]);int col=(i<Math.round(progress*60))?0xFFFFC04A:0xFF48505D;ctx.fill(px-2,py-2,px+3,py+3,col);}
            String time=String.format(Locale.ROOT,"%02d:%02d",remain/60,remain%60);
            ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal(time),timerCx,timerCy-5,0xFFFFFFFF);
            ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal(data.timerFinished?"TIME UP":"TIMER"),timerCx,timerCy+13,data.timerFinished?0xFFFF6B6B:0xFFFFC04A);
            if(timerFinishedAt>0 && System.currentTimeMillis()-timerFinishedAt<5200){
                long elapsed=System.currentTimeMillis()-timerFinishedAt;float pulse=(float)(1.0+0.035*Math.sin(elapsed/110.0));int bw=(int)(300*pulse),bh=76;
                int bx=ctx.getScaledWindowWidth()/2,by=ctx.getScaledWindowHeight()/2-38;
                ctx.fill(bx-bw/2-2,by-2,bx+bw/2+2,by+bh+2,0xFFFF8126);ctx.fill(bx-bw/2,by,bx+bw/2,by+bh,0xF20D0B09);
                ctx.fill(bx-bw/2,by,bx+bw/2,by+3,0xFFFF8126);
                drawFlowText(ctx,MinecraftClient.getInstance().textRenderer,"⏱ TIMER FINISHED",bx-75,by+19,0xFFFFC04A,true);
                ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("Your countdown is complete"),bx,by+43,0xFFFFFFFF);
            }
        }
    }

    private static final String[] HUD_COLOR_NAMES = {"Gold", "Ice", "Red", "Green", "Purple", "White"};
    private static final int[] HUD_COLORS = {0xFFFFC85A, 0xFF69D8FF, 0xFFFF6B6B, 0xFF78E08F, 0xFFB98CFF, 0xFFFFFFFF};

    private static String hudColorName() {
        for (int i = 0; i < HUD_COLORS.length; i++) if (HUD_COLORS[i] == data.textColor) return HUD_COLOR_NAMES[i];
        return "Custom";
    }
    private static int blendColor(int from, int to, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        int a = Math.round(((from >>> 24) & 255) * (1-t) + ((to >>> 24) & 255) * t);
        int r = Math.round(((from >>> 16) & 255) * (1-t) + ((to >>> 16) & 255) * t);
        int g = Math.round(((from >>> 8) & 255) * (1-t) + ((to >>> 8) & 255) * t);
        int bl = Math.round((from & 255) * (1-t) + (to & 255) * t);
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }
    private static void drawFlowText(DrawContext ctx, TextRenderer font, String text, int x, int y, int base, boolean shadow) {
        int cursor = x, index = 0;
        double phase = (System.currentTimeMillis() % 2400L) / 2400.0 * Math.PI * 2.0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            String glyph = new String(Character.toChars(cp));
            float wave = (float)(0.5 + 0.5 * Math.sin(index * 0.72 - phase));
            ctx.drawText(font, glyph, cursor, y, blendColor(0xFFE34B55, 0xFFFFFFFF, 0.10f + wave * 0.78f), shadow);
            cursor += font.getWidth(glyph);
            offset += Character.charCount(cp);
            index++;
        }
    }
    private static void drawHud(DrawContext ctx, int x, int y, float scale) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        java.util.List<String> names = hudStreamerNames();
        int width = 132, height = 18 + Math.max(1, names.size()) * (data.compactHud ? 9 : 11) + 5;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(scale, scale);
        ctx.fill(0, 0, width, height, hudPanelColor());
        ctx.fill(0, 0, width, 2, data.iceTheme ? 0xFF69D8FF : 0xFFE8B64D);
        drawFlowText(ctx, tr, "STREAMERS VIP", 6, 6, data.textColor, false);
        int rowY = 20;
        for (String key : names) {
            String name = tr.trimToWidth(displayName(key), 83);
            String count = String.valueOf(data.counts.getOrDefault(key, 0));
            drawFlowText(ctx, tr, name, 6, rowY, data.textColor, false);
            drawFlowText(ctx, tr, count, width - 7 - tr.getWidth(count), rowY, data.textColor, false);
            rowY += data.compactHud ? 9 : 11;
        }
        ctx.getMatrices().popMatrix();
    }
    private static class HudColorScreen extends Screen {
        private final Screen parent;
        HudColorScreen(Screen parent) { super(Text.literal("HUD Text Color")); this.parent = parent; }
        @Override protected void init() {
            int center = width / 2, firstX = center - 112, firstY = height / 2 - 38;
            for (int i = 0; i < HUD_COLORS.length; i++) {
                final int index = i;
                String marker = data.textColor == HUD_COLORS[i] ? "✓ " : "";
                addDrawableChild(ButtonWidget.builder(Text.literal(marker + HUD_COLOR_NAMES[i]), button -> {
                    data.textColor = HUD_COLORS[index]; save(); MinecraftClient.getInstance().setScreen(parent);
                }).dimensions(firstX + (i % 2) * 118, firstY + (i / 2) * 28, 108, 20).build());
            }
            addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> MinecraftClient.getInstance().setScreen(parent))
                    .dimensions(center - 50, firstY + 88, 100, 20).build());
        
            // Subtle transparency lets the menu background show through; the selected theme supplies the accent colors.
            for (var child : children()) {
                if (child instanceof net.minecraft.client.gui.widget.ClickableWidget button) button.setAlpha(0.82f);
            }
        }
        @Override public void render(DrawContext ctx, int mx, int my, float delta) {
            ctx.fill(0, 0, width, height, 0xC0000000);
            ctx.drawCenteredTextWithShadow(textRenderer, "Choose HUD text color", width / 2, height / 2 - 76, 0xFFFFFFFF);
            String preview = "StreamerCounter MEDIA";
            drawFlowText(ctx, textRenderer, preview, width / 2 - textRenderer.getWidth(preview) / 2, height / 2 - 57, data.textColor, true);
            super.render(ctx, mx, my, delta);
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
                    if (loaded.screenTexts == null) loaded.screenTexts = new ArrayList<>();
                     if (loaded.sessionHistory == null) loaded.sessionHistory = new ArrayList<>();
                    if (loaded.screenTexts == null) loaded.screenTexts = new ArrayList<>();
                    data = loaded;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void save() {
        hudNamesDirty = true;
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
            addDrawableChild(ButtonWidget.builder(Text.literal("Text Color: " + hudColorName()), button ->
                    MinecraftClient.getInstance().setScreen(new HudColorScreen(this)))
                    .dimensions(center - 90, this.height - 82, 180, 20).build());
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

    private static class TextOnScreenScreen extends Screen {
        private final Screen parent;
        private int selected = -1;
        private boolean dragging;
        private double dx, dy;
        private TextFieldWidget textInput;
        TextOnScreenScreen(Screen parent) { super(Text.literal("Text on Screen")); this.parent=parent; }
        @Override protected void init() {
            if (data.screenTexts == null) data.screenTexts = new ArrayList<>();
            int left = 12, top = 100, row = 24;
            for (int i = 0; i < data.screenTexts.size() && i < 10; i++) {
                final int index = i;
                ScreenText item = data.screenTexts.get(i);
                String label = (item.visible ? "§a● " : "§7○ ") + (item.text == null || item.text.isBlank() ? "Text " + (i+1) : item.text);
                if (label.length() > 17) label = label.substring(0, 17) + "…";
                addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                    selected = index; clearAndInit();
                }).dimensions(left, top + i * row, Math.max(112, (int)(width * 0.34) - 30), 20).build());
            }
            addDrawableChild(ButtonWidget.builder(Text.literal("+ Add Text"), b -> {
                ScreenText item = new ScreenText(); item.x=width/2; item.y=height/2;
                data.screenTexts.add(item); selected=data.screenTexts.size()-1; save(); clearAndInit();
            }).dimensions(left, top + Math.min(data.screenTexts.size(),10)*row + 4, 112, 20).build());

            int x = 12 + Math.max(150, (int)(width * 0.34)) + 26;
            int fieldWidth = Math.max(120, width - x - 30);
            textInput = new TextFieldWidget(textRenderer,x,124,fieldWidth,22,Text.literal("Text"));
            textInput.setMaxLength(256);
            ScreenText chosen=current();
            if(chosen!=null) textInput.setText(chosen.text==null?"":chosen.text);
            textInput.setChangedListener(value->{ScreenText t=current();if(t!=null){t.text=value;save();}});
            addDrawableChild(textInput);
            addDrawableChild(ButtonWidget.builder(Text.literal(chosen!=null&&chosen.visible?"Hide":"Show"),b->{
                ScreenText t=current();if(t!=null){t.visible=!t.visible;save();clearAndInit();}
            }).dimensions(x,184,68,20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Delete"),b->{
                if(selected>=0&&selected<data.screenTexts.size()){data.screenTexts.remove(selected);selected=-1;save();clearAndInit();}
            }).dimensions(x+74,184,68,20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Size −"),b->{ScreenText t=current();if(t!=null){t.size=Math.max(8,t.size-2);save();}})
                .dimensions(x,236,68,20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Size +"),b->{ScreenText t=current();if(t!=null){t.size=Math.min(96,t.size+2);save();}})
                .dimensions(x+74,236,68,20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Font / Style"),b->{
                ScreenText t=current(); if(t!=null){String[] styles={"default","bold","italic","bold_italic"};int n=0;for(;n<styles.length;n++)if(styles[n].equals(t.font))break;t.font=styles[(n+1)%styles.length];t.bold=t.font.equals("bold")||t.font.equals("bold_italic");t.italic=t.font.equals("italic")||t.font.equals("bold_italic");save();clearAndInit();}
            }).dimensions(x,288,146,20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Effect: " + (current()==null ? "Flow" : current().effect)),b->{
                ScreenText t=current(); if(t!=null){String[] effects={"flow","pulse","static"};int n=0;for(;n<effects.length;n++)if(effects[n].equals(t.effect))break;t.effect=effects[(n+1)%effects.length];save();clearAndInit();}
            }).dimensions(x,352,146,20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Color"),b->{
                ScreenText t=current();if(t!=null){int[] colors={0xFFFFFFFF,0xFFFFD54F,0xFF69D8FF,0xFFFF6B6B,0xFF78E08F};int n=0;for(;n<colors.length;n++)if(colors[n]==t.color)break;t.color=colors[(n+1)%colors.length];save();}
            }).dimensions(x,320,68,20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Back to Menu"),b->close())
                .dimensions(x+74,320,110,20).build());
            }
        private ScreenText current(){return selected>=0&&selected<data.screenTexts.size()?data.screenTexts.get(selected):null;}
        @Override public boolean mouseClicked(Click click,boolean doubled) {
            if(click.button()==0) for(int i=data.screenTexts.size()-1;i>=0;i--) {
                ScreenText t=data.screenTexts.get(i);
                int scale=Math.max(8,Math.min(96,t.size));
                int textWidth=(int)(MinecraftClient.getInstance().textRenderer.getWidth(t.text==null?"":t.text)*scale/9.0);
                if(t.visible&&click.x()>=t.x-3&&click.x()<=t.x+textWidth+6&&click.y()>=t.y-4&&click.y()<=t.y+scale+3) {
                    selected=i;if(textInput!=null)textInput.setText(t.text==null?"":t.text);
                    dragging=true;dx=click.x()-t.x;dy=click.y()-t.y;return true;
                }
            }
            return super.mouseClicked(click,doubled);
        }
        @Override public boolean mouseDragged(Click click,double ox,double oy) {
            ScreenText t=current();if(dragging&&t!=null&&click.button()==0){t.x=Math.max(0,(int)(click.x()-dx));t.y=Math.max(0,(int)(click.y()-dy));return true;}
            return super.mouseDragged(click,ox,oy);
        }
        @Override public boolean mouseReleased(Click click) {
            if(dragging&&click.button()==0){dragging=false;save();return true;}
            return super.mouseReleased(click);
        }
        @Override public void render(DrawContext ctx,int mx,int my,float delta) {
            int left=12, top=42, split=Math.max(150,(int)(width*0.34)), right=left+split+12;
            ctx.fill(0,0,width,height,0xB0000000);
            ctx.fill(left-2,top-2,left+split,height-10,0xFFFFD54F);
            ctx.fill(left,top,left+split-2,height-12,0xFF18202C);
            ctx.fill(left+2,top+2,left+split-4,top+5,0xFF6C9BCB);
            ctx.fill(right-2,top-2,width-10,height-10,0xFFFFD54F);
            ctx.fill(right,top,width-12,height-12,0xFF18202C);
            ctx.fill(right+2,top+2,width-14,top+5,0xFF6C9BCB);
            ctx.drawText(textRenderer,"TEXT ON SCREEN",left+14,top+14,0xFFFFFFFF,true);
            ctx.drawText(textRenderer,"PLACED TEXTS",left+14,top+38,0xFFFFD54F,true);
            ctx.drawText(textRenderer,"EDIT TEXT",right+16,top+14,0xFFFFFFFF,true);
            ctx.drawText(textRenderer,"Select a text on the left. Hidden texts stay in this list.",right+16,top+38,0xFFB8C7D9,true);
            ctx.drawText(textRenderer,"TEXT",right+16,top+67,0xFFFFD54F,true);
            ctx.drawText(textRenderer,"SIZE",right+16,top+219,0xFFFFD54F,true);
            ctx.drawText(textRenderer,"FONT / STYLE",right+16,top+271,0xFFFFD54F,true);
            ctx.drawText(textRenderer,"COLOR",right+16,top+303,0xFFFFD54F,true);
            if(data.screenTexts!=null) for(ScreenText item:data.screenTexts) if(item!=null&&item.visible&&item.text!=null&&!item.text.isEmpty()) {
                float scale=Math.max(8,Math.min(96,item.size))/9.0f;
                ctx.getMatrices().pushMatrix(); ctx.getMatrices().translate(item.x,item.y); ctx.getMatrices().scale(scale,scale);
                
                if("flow".equals(item.effect)){
                    drawFlowText(ctx,textRenderer,item.text,0,0,0xFFE34B55,item.shadow);
                }else if("pulse".equals(item.effect)){
                    int pulse=(int)(128+127*Math.sin(System.currentTimeMillis()/180.0));
                    Text styled=Text.literal(item.text).styled(st->st.withBold(item.bold).withItalic(item.italic));
                    ctx.drawText(textRenderer,styled,0,0,pulse>185?0xFFFFFFFF:0xFFE34B55,item.shadow);
                }else{
                    Text styled=Text.literal(item.text).styled(st->st.withBold(item.bold).withItalic(item.italic));
                    ctx.drawText(textRenderer,styled,0,0,0xFFFFFFFF,item.shadow);
                } ctx.getMatrices().popMatrix();
            }
            ScreenText chosen=current();
            if(chosen!=null) ctx.drawText(textRenderer,"Selected: "+(chosen.text==null?"":chosen.text)+"   |   Size: "+chosen.size,right+16,top+365,0xFFB8C7D9,true);
            else ctx.drawText(textRenderer,"Add a text to begin editing.",right+16,top+330,0xFFB8C7D9,true);
            ctx.drawText(textRenderer,"Drag visible text to move it.",right+16,height-34,0xFF6C9BCB,true);
            super.render(ctx,mx,my,delta);

        }
    }


    private static class SessionScreen extends Screen {
        private final Screen parent;
        SessionScreen(Screen parent){super(Text.literal("Sessions"));this.parent=parent;}
        @Override protected void init(){
            int panelW=Math.min(1120,width-36),left=(width-panelW)/2,top=18,right=left+panelW;
            int navX=left+330;String[] nav={"Overview","Streamers","HUD","Tools","Themes","Settings","Sessions"};
            for(int i=0;i<nav.length;i++){final int tab=i;addDrawableChild(ButtonWidget.builder(Text.literal(nav[i]),b->{if(tab==6){MinecraftClient.getInstance().setScreen(new SessionScreen(parent));}else MinecraftClient.getInstance().setScreen(new CounterMenuScreen(parent,0,tab));}).dimensions(navX+i*83,top+15,78,24).build());}
            int body=top+72,mid=left+(int)(panelW*.39),panelBottom=height-40;
            addDrawableChild(ButtonWidget.builder(Text.literal(data.sessionActive?"End session":"New session"),b->{if(data.sessionActive)endSession();else startSession();MinecraftClient.getInstance().setScreen(new SessionScreen(parent));}).dimensions(mid+panelW/3,body+10,132,25).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Clear history"),b->{if(data.sessionHistory!=null)data.sessionHistory.clear();save();MinecraftClient.getInstance().setScreen(new SessionScreen(parent));}).dimensions(left+42,panelBottom-38,Math.max(140,mid-left-70),24).build());
            int row=0;if(data.sessionHistory!=null)for(SessionRecord record:data.sessionHistory){if(row>=Math.max(1,(panelBottom-body-100)/34))break;final int idx=row;String label="Session "+(data.sessionHistory.size()-row)+"  •  "+(record.income-record.expenses>=0?"+":"-")+formatAuctionMoney(Math.abs(record.income-record.expenses));addDrawableChild(ButtonWidget.builder(Text.literal(label),b->{}).dimensions(left+42,body+50+row*34,Math.max(140,mid-left-70),26).build());row++;}
            addDrawableChild(ButtonWidget.builder(Text.literal("Back"),b->MinecraftClient.getInstance().setScreen(parent)).dimensions(width-94,height-42,70,22).build());
            for(var child:children())if(child instanceof net.minecraft.client.gui.components.AbstractWidget w)w.setAlpha(0.0f);
        }
        
        @Override public void render(DrawContext g,int mx,int my,float delta){
            renderBackground(g,mx,my,delta);
            int panelW=Math.min(1120,width-36),left=(width-panelW)/2,top=18,right=left+panelW,bottom=height-18;
            g.fill(0,0,width,height,0x70000000);g.fill(left,top,right,bottom,0xE8100C09);
            g.fill(left+1,top+1,right-1,top+3,0xFFFF8A24);g.fill(left+1,top+54,right-1,top+55,0x665F4936);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("StreamerCounter  /  SESSIONS"),left+24,top+20,0xFFFFE8D2);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("TRACK YOUR PLAY SESSION"),left+24,top+36,0xFFBDA58E);
            int navX=left+330;
            String[] nav={"Overview","Streamers","HUD","Tools","Themes","Settings","Sessions"};
            for(int i=0;i<nav.length;i++){int x=navX+i*83;g.fill(x,top+15,x+78,top+39,i==6?0xFF49301E:0xAA1B120D);g.drawCenteredString(MinecraftClient.getInstance().textRenderer,Text.literal(nav[i]),x+39,top+22,0xFFFFEAD8);}
            int body=top+72,mid=left+(int)(panelW*.39),panelBottom=bottom-22;
            g.fill(left+24,body,mid-10,panelBottom,0xA51B130E);g.fill(mid,body,right-24,panelBottom,0xA51B130E);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("HISTORY"),left+42,body+18,0xFFFFE9D6);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal(data.sessionActive?"END SESSION":"NEW SESSION"),mid+24+panelW/3,body+18,0xFFFFEAD8);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("CLEAR HISTORY"),left+42,panelBottom-30,0xFFFF8065);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("BACK"),right-82,bottom-30,0xFFFFEAD8);
            int historyRow=0;if(data.sessionHistory!=null)for(SessionRecord record:data.sessionHistory){if(historyRow>=Math.max(1,(panelBottom-body-100)/34))break;String item="Session "+(data.sessionHistory.size()-historyRow);String amount=(record.income-record.expenses>=0?"+":"-")+formatAuctionMoney(Math.abs(record.income-record.expenses));g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal(item),left+42,body+52+historyRow*34,0xFFFFEAD8);g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal(amount),mid-90,body+52+historyRow*34,record.income>=record.expenses?0xFF8FE35D:0xFFFF6254);historyRow++;}
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("SESSION DETAILS"),mid+24,body+18,0xFFFFE9D6);
            String status=data.sessionActive?"LIVE  •  "+sessionDuration(data.sessionStartedAt,System.currentTimeMillis()):"NO ACTIVE SESSION";
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal(status),mid+24,body+44,data.sessionActive?0xFFFF9B35:0xFFBDA58E);
            double income=data.sessionIncome,spent=data.sessionExpenses,net=income-spent;
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal((net>=0?"+":"-")+formatAuctionMoney(Math.abs(net))),mid+24,body+72,net>=0?0xFF8FE35D:0xFFFF6254);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("INCOME  +"+formatAuctionMoney(income)+"     SPENT  -"+formatAuctionMoney(spent)),mid+24,body+102,0xFFFFD9B0);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("Duration: "+(data.sessionActive?sessionDuration(data.sessionStartedAt,System.currentTimeMillis()):"—")+"   •   Transactions: "+data.sessionTransactions),mid+24,body+122,0xFFBDA58E);
            // Compact net trend visual, based on the available session total.
            int gx=mid+24,gy=body+170,gw=right-mid-64;g.fill(gx,gy,gx+gw,gy+1,0xFF76513B);
            int marker=gx+(int)(gw*.82);g.fill(gx,gy-1,marker,gy+1,net>=0?0xFF77D94D:0xFFFF6254);g.fill(marker-2,gy-4,marker+2,gy+5,0xFFFF9B35);
            g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal("RECENT SESSIONS"),mid+24,gy+24,0xFFC9A98C);
            int row=0;if(data.sessionHistory!=null)for(SessionRecord record:data.sessionHistory){if(row>=5)break;String label="Session "+(data.sessionHistory.size()-row)+"  •  "+sessionDuration(record.startedAt,record.endedAt)+"  •  "+(record.income-record.expenses>=0?"+":"-")+formatAuctionMoney(Math.abs(record.income-record.expenses));g.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,Text.literal(label),mid+24,gy+44+row*17,0xFFE8D6C4);row++;}
            super.render(g,mx,my,delta);
        }
        @Override public void close(){MinecraftClient.getInstance().setScreen(parent);}
    }

    private static class CounterMenuScreen extends Screen {
        private final Screen parent;
        private int page=0;
        private int activeTab=0;
        private static final int ROW_HEIGHT=26;
        private static final String[] TABS={"Overview","Streamers","HUD","Tools","Themes","Settings","Sessions"};
        protected CounterMenuScreen(Screen parent){super(Text.literal("StreamerCounter MEDIA"));this.parent=parent;}
        private java.util.List<String> streamerNames(){
            java.util.Set<String> names=new java.util.TreeSet<>();names.addAll(data.counts.keySet());names.addAll(data.streamerEnabled.keySet());
            if(pending!=null)names.add(pending.toLowerCase(Locale.ROOT));return new java.util.ArrayList<>(names);
        }
        private void openTab(int tab){activeTab=tab;page=0;clearAndInit();}
        private Text hudButtonText(){return Text.literal("HUD: "+(data.showHud?"ON":"OFF"));}
        private static final String[] VIP_THEMES={"GOLD","ICE","CHRISTMAS","HALLOWEEN","NETHER","EMERALD","PURPLE","OCEAN"};
        private String currentTheme(){return data.iceTheme&&"GOLD".equals(data.vipTheme)?"ICE":(data.vipTheme==null?"GOLD":data.vipTheme);}
        private Text themeButtonText(){return Text.literal("Theme: "+currentTheme()+"  →");}
        private int accent(){return 0xFFE34B55;}
        private int textColor(){return 0xFFFFF0F0;}
        private int panelColor(){return 0xB81A1012;}
        private void drawSeasonDecor(DrawContext ctx) {
            int decoX=(width+Math.min(920,width-32))/2-110;
            String theme=currentTheme();
            if(theme.equals("CHRISTMAS")){
                // Candy cane, hanging baubles, holly and little snow accents.
                ctx.fill(decoX+16,42,decoX+22,72,0xFFFFF1D6);
                ctx.fill(decoX+22,42,decoX+34,48,0xFFFFF1D6);
                ctx.fill(decoX+16,50,decoX+22,56,0xFFD93636);
                ctx.fill(decoX+22,60,decoX+28,66,0xFFD93636);
                ctx.fill(decoX+22,48,decoX+28,54,0xFFD93636);
                ctx.fill(decoX+48,42,decoX+49,51,0xFFFFD45F);
                ctx.fill(decoX+44,50,decoX+54,60,0xFFD93636);
                ctx.fill(decoX+46,52,decoX+52,58,0xFFFFD7D7);
                ctx.fill(decoX+67,42,decoX+68,54,0xFFFFD45F);
                ctx.fill(decoX+63,53,decoX+73,63,0xFFFFD45F);
                ctx.fill(decoX+65,55,decoX+71,61,0xFFFFF0B0);
                ctx.fill(decoX+80,64,decoX+90,74,0xFF55D878);
                ctx.fill(decoX+77,60,decoX+83,66,0xFF278A49);
                ctx.fill(decoX+95,48,decoX+101,50,0xFFBCEBFF);
                ctx.fill(decoX+97,44,decoX+99,54,0xFFBCEBFF);
                ctx.fill(decoX+110,65,decoX+116,67,0xFFBCEBFF);
                ctx.fill(decoX+112,61,decoX+114,71,0xFFBCEBFF);
            } else if(theme.equals("HALLOWEEN")){
                // Jack-o-lanterns, a tiny bat and warm candle-like highlights.
                ctx.fill(decoX+22,43,decoX+32,51,0xFF65B84F);
                ctx.fill(decoX+14,50,decoX+40,72,0xFFFF8738);
                ctx.fill(decoX+18,47,decoX+36,75,0xFFFF8738);
                ctx.fill(decoX+20,55,decoX+24,61,0xFF28170F);
                ctx.fill(decoX+30,55,decoX+34,61,0xFF28170F);
                ctx.fill(decoX+23,65,decoX+31,68,0xFF28170F);
                ctx.fill(decoX+49,53,decoX+59,63,0xFFFFB347);
                ctx.fill(decoX+51,55,decoX+57,61,0xFF28170F);
                ctx.fill(decoX+53,47,decoX+55,54,0xFF65B84F);
                ctx.fill(decoX+75,51,decoX+89,55,0xFF24152E);
                ctx.fill(decoX+78,47,decoX+82,54,0xFF24152E);
                ctx.fill(decoX+85,47,decoX+89,54,0xFF24152E);
                ctx.fill(decoX+96,61,decoX+102,63,0xFFFFB347);
                ctx.fill(decoX+98,57,decoX+100,67,0xFFFFB347);
            } else if(theme.equals("ICE")||theme.equals("OCEAN")){
                // Layered snowflake / icy sparkle.
                ctx.fill(decoX+19,43,decoX+21,75,0xFFBCEBFF);
                ctx.fill(decoX+5,57,decoX+35,59,0xFFBCEBFF);
                ctx.fill(decoX+10,48,decoX+30,68,0xFFBCEBFF);
                ctx.fill(decoX+48,48,decoX+52,52,0xFF69D8FF);
                ctx.fill(decoX+46,58,decoX+54,60,0xFF69D8FF);
                ctx.fill(decoX+48,66,decoX+52,70,0xFF69D8FF);
                ctx.fill(decoX+70,50,decoX+76,56,0xFFBCEBFF);
                ctx.fill(decoX+68,58,decoX+78,60,0xFFBCEBFF);
            } else if(theme.equals("NETHER")){
                ctx.fill(decoX+18,48,decoX+32,62,0xFFFF5555);
                ctx.fill(decoX+21,51,decoX+29,59,0xFFFFB347);
                ctx.fill(decoX+48,56,decoX+62,60,0xFF8E263B);
                ctx.fill(decoX+53,48,decoX+57,68,0xFFFF5555);
                ctx.fill(decoX+76,48,decoX+90,52,0xFFFF5555);
                ctx.fill(decoX+82,44,decoX+86,58,0xFFFF8A5B);
            } else if(theme.equals("EMERALD")){
                ctx.fill(decoX+20,44,decoX+34,58,0xFF45D69A);
                ctx.fill(decoX+24,48,decoX+30,54,0xFFB8FFE1);
                ctx.fill(decoX+50,50,decoX+64,54,0xFF45D69A);
                ctx.fill(decoX+55,45,decoX+59,60,0xFF45D69A);
                ctx.fill(decoX+78,48,decoX+92,62,0xFF238B68);
                ctx.fill(decoX+82,52,decoX+88,58,0xFF9BFFD1);
            } else if(theme.equals("PURPLE")){
                ctx.fill(decoX+20,48,decoX+34,52,0xFFB58CFF);
                ctx.fill(decoX+25,43,decoX+29,58,0xFFB58CFF);
                ctx.fill(decoX+52,50,decoX+64,62,0xFF7D5BC7);
                ctx.fill(decoX+55,53,decoX+61,59,0xFFDCCBFF);
                ctx.fill(decoX+80,45,decoX+84,65,0xFFB58CFF);
                ctx.fill(decoX+72,53,decoX+92,57,0xFFB58CFF);
            } else {
                // Gold theme: small crown and shimmering stars.
                ctx.fill(decoX+18,55,decoX+42,68,0xFFFFD45F);
                ctx.fill(decoX+18,48,decoX+23,57,0xFFFFD45F);
                ctx.fill(decoX+28,43,decoX+33,57,0xFFFFD45F);
                ctx.fill(decoX+38,48,decoX+43,57,0xFFFFD45F);
                ctx.fill(decoX+52,50,decoX+58,56,0xFFFFF0B0);
                ctx.fill(decoX+72,60,decoX+78,66,0xFFFFD45F);
                ctx.fill(decoX+90,46,decoX+96,52,0xFFFFF0B0);
            }
            // Fine corner sparkles make the theme visible without cluttering controls.
            int sparkle=accent();
            ctx.fill(22,104,24,112,sparkle); ctx.fill(19,107,27,109,sparkle);
            ctx.fill(width-25,104,width-23,112,sparkle); ctx.fill(width-28,107,width-20,109,sparkle);
            ctx.fill(22,height-88,24,height-80,sparkle); ctx.fill(19,height-85,27,height-83,sparkle);
            ctx.fill(width-25,height-88,width-23,height-80,sparkle); ctx.fill(width-28,height-85,width-20,height-83,sparkle);
        }
        @Override protected void init(){
            int panelW=Math.min(920,width-32),left=(width-panelW)/2,top=24;
            int tabStart=left+218,tabW=Math.min(94,(panelW-238)/6),gap=5;
            for(int t=0;t<TABS.length;t++){final int tab=t;String label=(t==activeTab?"• ":"")+TABS[t];
                addDrawableChild(ButtonWidget.builder(Text.literal(label),b->openTab(tab)).dimensions(tabStart+t*(tabW+gap),top+16,tabW,22).build());}
            addDrawableChild(ButtonWidget.builder(Text.literal("×"),b->close()).dimensions(left+panelW-42,top+16,26,22).build());
            int bodyTop=top+96,bodyBottom=height-74,x=left+26,contentW=panelW-52;
            if(activeTab==1){
                int maxRows=Math.max(1,(bodyBottom-bodyTop-34)/ROW_HEIGHT),pages=Math.max(1,(streamerNames().size()+maxRows-1)/maxRows);
                page=Math.max(0,Math.min(page,pages-1));int from=page*maxRows,to=Math.min(streamerNames().size(),from+maxRows);
                java.util.List<String> names=streamerNames();
                for(int n=from;n<to;n++){String name=names.get(n);int count=data.counts.getOrDefault(name,0),y=bodyTop+(n-from)*ROW_HEIGHT;
                    int aw=58,g2=6,tw=contentW-aw*2-g2*2;
                    addDrawableChild(ButtonWidget.builder(streamerButtonText(name,count),b->{data.streamerEnabled.put(name,!data.streamerEnabled.getOrDefault(name,true));save();clearAndInit();}).dimensions(x,y,tw,21).build());
                    addDrawableChild(ButtonWidget.builder(Text.literal("Reset"),b->{data.counts.put(name,0);save();clearAndInit();}).dimensions(x+tw+g2,y,aw,21).build());
                    addDrawableChild(ButtonWidget.builder(Text.literal("Delete"),b->{data.counts.remove(name);data.streamerEnabled.remove(name);data.displayNames.remove(name);if(data.lastName!=null&&data.lastName.equalsIgnoreCase(name))data.lastName=null;if(pending!=null&&pending.equalsIgnoreCase(name))pending=null;save();clearAndInit();}).dimensions(x+tw+g2+aw+g2,y,aw,21).build());
                }
                if(pages>1){ButtonWidget prev=addDrawableChild(ButtonWidget.builder(Text.literal("‹ Previous"),b->{page--;clearAndInit();}).dimensions(width/2-104,bodyBottom-28,98,22).build());prev.active=page>0;
                    ButtonWidget next=addDrawableChild(ButtonWidget.builder(Text.literal("Next ›"),b->{page++;clearAndInit();}).dimensions(width/2+6,bodyBottom-28,98,22).build());next.active=page<pages-1;}
            }else if(activeTab==2){
                addDrawableChild(ButtonWidget.builder(hudButtonText(),b->{data.showHud=!data.showHud;save();b.setMessage(hudButtonText());}).dimensions(x,bodyTop,190,24).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD"),b->MinecraftClient.getInstance().setScreen(new HudEditScreen(this))).dimensions(x,bodyTop+38,190,24).build());
                            addDrawableChild(ButtonWidget.builder(Text.literal("Compact layout: "+(data.compactHud?"ON":"OFF")),b->{data.compactHud=!data.compactHud;save();clearAndInit();}).dimensions(x,bodyTop+70,190,24).build());
                            addDrawableChild(ButtonWidget.builder(Text.literal("HUD opacity  −"),b->{data.hudOpacity=Math.max(0.15f,data.hudOpacity-0.1f);save();}).dimensions(x,bodyTop+102,92,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("HUD opacity  +"),b->{data.hudOpacity=Math.min(1.0f,data.hudOpacity+0.1f);save();}).dimensions(x+98,bodyTop+102,92,22).build());
            }else if(activeTab==3){
                String[] tools={"Auction","Timer","Text on screen","HUD","Sessions"};
                for(int i=0;i<tools.length;i++){final int tool=i;
                    addDrawableChild(ButtonWidget.builder(Text.literal("›  "+tools[i]),b->{
                        if(tool==0)MinecraftClient.getInstance().setScreen(new AuctionScreen(this));
                        else if(tool==1)MinecraftClient.getInstance().setScreen(new TimerScreen(this));
                        else if(tool==2)MinecraftClient.getInstance().setScreen(new TextOnScreenScreen(this));
                        else if(tool==3)openTab(2);
                         else if(tool==4)MinecraftClient.getInstance().setScreen(new SessionScreen(this));
                    }).dimensions(x,bodyTop+i*42,230,28).build());
                }
            }else if(activeTab==4){
                addDrawableChild(ButtonWidget.builder(themeButtonText(),b->{String[] themes=VIP_THEMES;int n=0;for(;n<themes.length;n++)if(themes[n].equals(currentTheme()))break;data.vipTheme=themes[(n+1)%themes.length];data.iceTheme=data.vipTheme.equals("ICE");save();clearAndInit();}).dimensions(x,bodyTop,250,24).build());
            }else if(activeTab==5){
                addDrawableChild(ButtonWidget.builder(Text.literal("HUD: "+(data.showHud?"ON":"OFF")),b->{data.showHud=!data.showHud;save();clearAndInit();}).dimensions(x,bodyTop,180,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("HUD Size −"),b->{data.hudScale=Math.max(0.5f,data.hudScale-0.1f);save();}).dimensions(x,bodyTop+30,88,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("HUD Size +"),b->{data.hudScale=Math.min(2.0f,data.hudScale+0.1f);save();}).dimensions(x+94,bodyTop+30,88,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Reset HUD Position"),b->{data.hudX=-1;data.hudY=8;save();}).dimensions(x,bodyTop+60,180,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Reset All Counters"),b->{data.counts.clear();data.lastName=null;pending=null;save();clearAndInit();}).dimensions(x,bodyTop+90,180,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Close Menu"),b->close()).dimensions(x,bodyTop+120,180,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Move and resize HUDs"),b->MinecraftClient.getInstance().setScreen(new LayoutEditScreen(this))).dimensions(x,bodyTop+154,230,26).build());












            }
        
            // Apply translucency to the actual top-level VIP menu controls.
            for (var child : children()) {
                if (child instanceof net.minecraft.client.gui.widget.ClickableWidget button) button.setAlpha(0.0f);
            }
        }
        private Text streamerButtonText(String name,int count){return Text.literal(name+"   •   "+(data.streamerEnabled.getOrDefault(name,true)?"ON":"OFF")+"   •   "+count+" TPs");}
        @Override public void render(DrawContext ctx,int mx,int my,float delta){
            int panelW=Math.min(920,width-32),left=(width-panelW)/2,top=24,right=left+panelW,bottom=height-24;
            ctx.fill(0,0,width,height,0x50000000);ctx.fill(left-1,top-1,right+1,bottom+1,0xAA000000);
            ctx.fill(left,top,right,bottom,panelColor());
            drawSeasonDecor(ctx);ctx.fill(left+1,top+1,right-1,top+3,accent());
            ctx.fill(left+1,top+58,right-1,top+59,0x665F7188);
            drawFlowText(ctx,textRenderer,"STREAMERCOUNTER VIP",left+22,top+22,0xFFFFFFFF,true);
            drawFlowText(ctx,textRenderer,"CONTROL CENTER",left+22,top+39,textColor(),true);
            int bodyTop=top+96,bodyBottom=height-74,x=left+26;
            drawFlowText(ctx,textRenderer,TABS[activeTab].toUpperCase(Locale.ROOT),x,top+76,accent(),true);
            ctx.fill(x,bodyTop-12,right-26,bodyBottom+8,0x55232E3D);
            if(activeTab==0){int total=0;for(Integer value:data.counts.values())if(value!=null)total+=value;
                String[] lines={"Your live overview","Tracked streamers: "+streamerNames().size(),"Total TP counter: "+total,"Last detected: "+(data.lastName==null?"—":data.lastName),"HUD: "+(data.showHud?"Enabled":"Disabled"),"Enabled streamers: "+streamerNames().stream().filter(n->data.streamerEnabled.getOrDefault(n,true)).count(),
                    "Auction: "+(data.auctionActive?"Live":"Idle"),"Timer: "+(data.timerActive?"Running":(data.timerFinished?"Finished":"Idle")),
                    "HUD layout: "+(data.compactHud?"Compact":"Standard")+" • "+Math.round(data.hudOpacity*100)+"% opacity",
                    "Use the tabs above to manage streamers, HUD, text and theme."};
                for(int j=0;j<lines.length;j++)drawFlowText(ctx,textRenderer,lines[j],x+18,bodyTop+18+j*24,j==0?0xFFFFFFFF:textColor(),true);
            }else if(activeTab==1&&streamerNames().isEmpty()){drawFlowText(ctx,textRenderer,"No streamers detected yet.",x+18,bodyTop+18,0xFFFFFFFF,true);drawFlowText(ctx,textRenderer,"Streamers appear here after /tpahere <name>.",x+18,bodyTop+42,textColor(),true);
            }else if(activeTab==2)drawFlowText(ctx,textRenderer,"Toggle the HUD or open its editor.",x+220,bodyTop+6,textColor(),true);
            else if(activeTab==3){String[] statuses={"Idle","Idle","Edit your on-screen text","Coming soon","Coming soon","Off",data.showHud?"1 plate on":"Off"};for(int i=0;i<statuses.length;i++)drawFlowText(ctx,textRenderer,statuses[i],x+260,bodyTop+10+i*42,textColor(),true);}
            else if(activeTab==4)drawFlowText(ctx,textRenderer,"Choose from 8 VIP themes: Gold, Ice, Christmas, Halloween, Nether, Emerald, Purple and Ocean.",x+220,bodyTop+6,textColor(),true);
            else if(activeTab==5)drawFlowText(ctx,textRenderer,"Adjust HUD visibility and size, reset its position, or manage counter data.",x+220,bodyTop+6,textColor(),true);
            super.render(ctx,mx,my,delta);
            int buttonIndex=0;
            for (var child : children()) {
                if (child instanceof net.minecraft.client.gui.widget.ClickableWidget button && button.visible) {
                    int bx=button.getX(), by=button.getY(), bw=button.getWidth(), bh=button.getHeight();
                    boolean hovered=mx>=bx&&mx<bx+bw&&my>=by&&my<by+bh;
                    boolean selected=buttonIndex<6&&buttonIndex==activeTab;
                    int bg=selected?0xAA59432D:(hovered?0x99513D2A:0x66120E0A);
                    ctx.fill(bx,by,bx+bw,by+bh,bg);
                    if(selected)ctx.fill(bx,by,bx+bw,by+2,accent());
                    String label=button.getMessage().getString();
                    int color=selected?0xFFFFE4B5:textColor();
                    drawFlowText(ctx,textRenderer,label,bx+(bw-textRenderer.getWidth(label))/2,by+(bh-textRenderer.fontHeight)/2,color,true);
                    buttonIndex++;
                }
            }
            String credit="MEDIA EDITION  •  MADE BY ALPBEY3503";
            drawFlowText(ctx,textRenderer,credit,left+22,bottom-18,textColor(),true);
        }
        @Override public void close(){MinecraftClient.getInstance().setScreen(parent);}
    }





    private static class LayoutEditScreen extends Screen {
        private final Screen parent;
        private int selected=0; private boolean dragging=false; private double offX,offY;
        LayoutEditScreen(Screen parent){super(Text.literal("Move and resize HUDs"));this.parent=parent;}
        private int px(int i){return i==0?(data.hudX<0?width-hudWidth(data.hudScale)-8:data.hudX):i==1?(data.auctionHudX<0?width/2-pw(1)/2:data.auctionHudX-pw(1)/2):(data.timerHudX<0?width/2-pw(2)/2:data.timerHudX-pw(2)/2);}
        private int py(int i){return i==0?visibleHudY(height):i==1?data.auctionHudY:(data.timerHudY<0?height/3- pw(2)/2:data.timerHudY-pw(2)/2);}
        private int pw(int i){return i==0?hudWidth(data.hudScale):i==1?(int)(264*Math.max(.6f,Math.min(1.8f,data.auctionHudScale))):(int)(124*Math.max(.6f,Math.min(1.8f,data.timerHudScale)));}
        private int ph(int i){return i==0?hudHeight(data.hudScale):i==1?(int)((data.auctionSold?104:76)*Math.max(.6f,Math.min(1.8f,data.auctionHudScale))):(int)(124*Math.max(.6f,Math.min(1.8f,data.timerHudScale)));}
        private void move(int i,int x,int y){if(i==0){data.hudX=x;data.hudY=y;}else if(i==1){data.auctionHudX=x+pw(1)/2;data.auctionHudY=y;}else{data.timerHudX=x+pw(2)/2;data.timerHudY=y+pw(2)/2;}}
        private void scale(int i,float d){if(i==0)data.hudScale=Math.max(.5f,Math.min(2f,data.hudScale+d));else if(i==1)data.auctionHudScale=Math.max(.6f,Math.min(1.8f,data.auctionHudScale+d));else data.timerHudScale=Math.max(.6f,Math.min(1.8f,data.timerHudScale+d));}
        @Override protected void init(){addDrawableChild(ButtonWidget.builder(Text.literal("Reset layout"),b->{data.hudX=-1;data.hudY=8;data.auctionHudX=-1;data.auctionHudY=28;data.timerHudX=-1;data.timerHudY=-1;data.hudScale=1.0f;data.auctionHudScale=1.0f;data.timerHudScale=1.0f;save();}).dimensions(width/2-108,height-34,104,22).build());addDrawableChild(ButtonWidget.builder(Text.literal("Done"),b->MinecraftClient.getInstance().setScreen(parent)).dimensions(width/2+4,height-34,100,22).build());}
        @Override public boolean mouseClicked(Click click,boolean doubled){if(click.button()==0){for(int i=2;i>=0;i--){int x=px(i),y=py(i),w=pw(i),h=ph(i);if(click.x()>=x&&click.x()<=x+w&&click.y()>=y&&click.y()<=y+h){selected=i;dragging=true;offX=click.x()-x;offY=click.y()-y;return true;}}}return super.mouseClicked(click,doubled);}
        @Override public boolean mouseDragged(Click click,double dx,double dy){if(dragging&&click.button()==0){move(selected,Math.max(0,Math.min(width-pw(selected),(int)(click.x()-offX))),Math.max(0,Math.min(height-ph(selected),(int)(click.y()-offY))));return true;}return super.mouseDragged(click,dx,dy);}
        @Override public boolean mouseReleased(Click click){if(dragging&&click.button()==0){dragging=false;save();return true;}return super.mouseReleased(click);}
        @Override public boolean mouseScrolled(double x,double y,double horizontalAmount,double verticalAmount){for(int i=2;i>=0;i--){if(x>=px(i)&&x<=px(i)+pw(i)&&y>=py(i)&&y<=py(i)+ph(i)){selected=i;scale(i,verticalAmount>0?.1f:-.1f);save();return true;}}return super.mouseScrolled(x,y,horizontalAmount,verticalAmount);}
        @Override public void render(DrawContext g,int mx,int my,float delta){g.fill(0,0,width,height,0xB0000000);g.drawCenteredTextWithShadow(textRenderer,Text.literal("MOVE & RESIZE HUDS"),width/2,24,0xFFFFA044);g.drawCenteredTextWithShadow(textRenderer,Text.literal("Drag a panel to move it • Scroll to resize • ESC to finish"),width/2,42,0xFFFFFFFF);
            for(int i=0;i<3;i++){int x=px(i),y=py(i),w=pw(i),h=ph(i),col=i==selected?0xFFFF8126:0xAA8A603E;g.fill(x-2,y-2,x+w+2,y+h+2,col);g.fill(x,y,x+w,y+h,0xDD120C08);g.fill(x,y,x+w,y+2,0xFFFF8126);String label=i==0?"STREAMER HUD":i==1?"AUCTION HUD":"TIMER HUD";g.drawCenteredTextWithShadow(textRenderer,Text.literal(label),x+w/2,y+8,0xFFFFFFFF);g.drawCenteredTextWithShadow(textRenderer,Text.literal(i==0?"Player counters":i==1?"Item • countdown • highest bid":"00:45"),x+w/2,y+25,0xFFFFC04A);}
            g.drawCenteredTextWithShadow(textRenderer,Text.literal("Selected: "+(selected==0?"Streamer HUD":selected==1?"Auction HUD":"Timer HUD")+"  •  "+Math.round((selected==0?data.hudScale:selected==1?data.auctionHudScale:data.timerHudScale)*100)+"%"),width/2,height-54,0xFFFFC04A);super.render(g,mx,my,delta);}
        @Override public void close(){save();MinecraftClient.getInstance().setScreen(parent);}
    }

    private static class TimerScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget secondsField;
        TimerScreen(Screen parent){super(Text.literal("VIP Timer"));this.parent=parent;}
        @Override protected void init(){
            int cx=width/2, top=height/2-132;
            secondsField=new TextFieldWidget(textRenderer,cx-90,top+52,180,26,Text.literal("Seconds"));
            secondsField.setText(String.valueOf(data.timerDurationSeconds));addDrawableChild(secondsField);
            int[] vals={10,30,60,120,300,600}; String[] labels={"10 sec","30 sec","1 min","2 min","5 min","10 min"};
            for(int i=0;i<vals.length;i++){final int v=vals[i];addDrawableChild(ButtonWidget.builder(Text.literal(labels[i]),b->{data.timerDurationSeconds=v;secondsField.setText(String.valueOf(v));save();}).dimensions(cx-180+(i%3)*120,top+91+(i/3)*31,110,25).build());}
            addDrawableChild(ButtonWidget.builder(Text.literal(data.timerActive?"Stop timer":"Reset"),b->{data.timerActive=false;data.timerFinished=false;timerFinishedAt=0;data.timerEndTime=0;save();MinecraftClient.getInstance().setScreen(new TimerScreen(parent));}).dimensions(cx-70,top+158,140,25).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Start timer"),b->{try{data.timerDurationSeconds=Math.max(1,Math.min(86400,Integer.parseInt(secondsField.getText().trim())));}catch(Exception ignored){data.timerDurationSeconds=60;}data.timerEndTime=System.currentTimeMillis()+data.timerDurationSeconds*1000L;data.timerActive=true;data.timerFinished=false;timerFinishedAt=0;save();MinecraftClient.getInstance().setScreen(parent);}).dimensions(cx-160,top+194,150,28).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Back to Tools"),b->MinecraftClient.getInstance().setScreen(parent)).dimensions(cx+10,top+194,150,28).build());
        }
        @Override public void render(DrawContext ctx,int mouseX,int mouseY,float delta){
            ctx.fill(0,0,width,height,0x99000000);int cx=width/2,top=height/2-132;
            ctx.fill(cx-205,top-18,cx+205,top+239,0xF20D0B09);ctx.fill(cx-205,top-18,cx+205,top-14,0xFFFF8126);
            ctx.drawCenteredTextWithShadow(textRenderer,Text.literal("TIMER  •  "+(data.timerActive?"RUNNING":data.timerFinished?"FINISHED":"READY")),cx,top+7,0xFFFFC04A);
            ctx.drawCenteredTextWithShadow(textRenderer,Text.literal("Choose a preset or enter a duration in seconds"),cx,top+34,0xFFDDC9B3);
            super.render(ctx,mouseX,mouseY,delta);
        }
    }

    private static class AuctionScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget minBid;
        AuctionScreen(Screen parent){super(Text.literal("VIP Auction"));this.parent=parent;}

        @Override protected void init(){
            int cx=width/2, t=height/2-170, left=cx-318;
            minBid=new TextFieldWidget(textRenderer,left,t+194,280,22,Text.literal("Minimum bid"));
            minBid.setText(String.valueOf(data.auctionMinBid));
            if(!data.auctionActive) addDrawableChild(minBid);
            if(!data.auctionActive){
                addDrawableChild(ButtonWidget.builder(Text.literal("Use held item"),b->{
                    MinecraftClient mc=MinecraftClient.getInstance();
                    if(mc.player!=null&&!mc.player.getMainHandStack().isEmpty())
                        data.auctionItem=mc.player.getMainHandStack().getName().getString();
                    save();mc.setScreen(new AuctionScreen(parent));
                }).dimensions(left,t+90,280,22).build());
                int[] ds={30,60,120,300,600}; String[] labels={"30s","1m","2m","5m","10m"};
                for(int i=0;i<ds.length;i++){final int seconds=ds[i];
                    addDrawableChild(ButtonWidget.builder(Text.literal(labels[i]),b->{data.auctionDurationSeconds=seconds;save();MinecraftClient.getInstance().setScreen(new AuctionScreen(parent));})
                        .dimensions(left+i*56,t+144,52,20).build());
                }
                addDrawableChild(ButtonWidget.builder(Text.literal("Start auction"),b->{
                    try{data.auctionMinBid=Math.max(0,Integer.parseInt(minBid.getText().trim()));}catch(Exception ignored){}
                    data.auctionBids.clear();data.auctionHighestBid=0;data.auctionHighestPlayer="No bids yet";
                    data.auctionSold=false;data.auctionActive=true;data.auctionEndTime=System.currentTimeMillis()+data.auctionDurationSeconds*1000L;
                    save();MinecraftClient.getInstance().setScreen(new AuctionScreen(parent));
                }).dimensions(left,t+226,280,26).build());
                MinecraftClient mc=MinecraftClient.getInstance();
                if(mc.player!=null){int shown=0;
                    for(int slot=0;slot<mc.player.getInventory().size()&&shown<8;slot++){
                        var stack=mc.player.getInventory().getStack(slot);if(stack.isEmpty())continue;
                        final String itemName=stack.getName().getString();final int count=stack.getCount();
                        int col=shown%2,row=shown/2;String label=itemName.length()>15?itemName.substring(0,14)+"…":itemName;
                        addDrawableChild(ButtonWidget.builder(Text.literal(label+" x"+count),b->{data.auctionItem=itemName;save();MinecraftClient.getInstance().setScreen(new AuctionScreen(parent));})
                            .dimensions(cx+22+col*150,t+78+row*27,144,22).build());shown++;
                    }
                }
            }else{
                addDrawableChild(ButtonWidget.builder(Text.literal("+10s"),b->extend(10)).dimensions(left,t+144,82,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("+30s"),b->extend(30)).dimensions(left+88,t+144,82,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("+1m"),b->extend(60)).dimensions(left+176,t+144,82,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("End now"),b->finish()).dimensions(left,t+178,132,22).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"),b->{data.auctionActive=false;data.auctionBids.clear();data.auctionSold=false;save();MinecraftClient.getInstance().setScreen(new AuctionScreen(parent));})
                    .dimensions(left+140,t+178,132,22).build());
            }
            addDrawableChild(ButtonWidget.builder(Text.literal("Back to Tools"),b->MinecraftClient.getInstance().setScreen(parent)).dimensions(left,t+278,160,22).build());
        }
        private void extend(int seconds){data.auctionEndTime+=seconds*1000L;save();}
        private void finish(){data.auctionActive=false;data.auctionSold=true;data.auctionWinner=data.auctionHighestBid>0?data.auctionHighestPlayer:"No bids";data.auctionSoldUntil=System.currentTimeMillis()+10000L;save();}
        @Override public void render(DrawContext g,int mx,int my,float delta){
            int cx=width/2,t=height/2-170,a=data.iceTheme?0xFF69D8FF:0xFFFF8126;
            g.fill(0,0,width,height,0x55000000);g.fill(cx-340,t,cx+340,t+340,0xEE160E0A);g.fill(cx-340,t,cx+340,t+2,a);g.fill(cx,t+42,cx+1,t+310,0x665B4635);
            drawFlowText(g,textRenderer,data.auctionSold?"AUCTION COMPLETE":data.auctionActive?"LIVE AUCTION":"NEW AUCTION",cx-318,t+18,0xFFFFFFFF,true);
            if(data.auctionSold){ /* winner animation is shown in the game HUD */ } else{
                MinecraftClient client=MinecraftClient.getInstance();
                if(client.player!=null){for(int slot=0;slot<client.player.getInventory().size();slot++){var stack=client.player.getInventory().getStack(slot);if(!stack.isEmpty()&&stack.getName().getString().equals(data.auctionItem)){g.drawItem(stack,cx-318,t+52);break;}}}
                drawFlowText(g,textRenderer,"ITEM",cx-286,t+48,a,true);drawFlowText(g,textRenderer,data.auctionItem,cx-286,t+68,0xFFFFFFFF,true);
                if(data.auctionActive){long left=Math.max(0,(data.auctionEndTime-System.currentTimeMillis()+999)/1000);
                    drawFlowText(g,textRenderer,"TIME LEFT",cx-318,t+105,a,true);drawFlowText(g,textRenderer,String.format(Locale.ROOT,"%02d:%02d",left/60,left%60),cx-318,t+125,0xFFFFFFFF,true);
                    drawFlowText(g,textRenderer,"HIGHEST BID",cx-318,t+190,data.textColor,true);drawFlowText(g,textRenderer,data.auctionHighestPlayer+"  $"+formatAuctionMoney(data.auctionHighestBid),cx-318,t+210,0xFFFFFFFF,true);
                    drawFlowText(g,textRenderer,"BIDDERS / PAYMENTS",cx+22,t+48,a,true);int row=0;
                    for(var entry:data.auctionBids.entrySet()){if(row>=8)break;drawFlowText(g,textRenderer,entry.getKey()+"   $"+formatAuctionMoney(entry.getValue()),cx+22,t+78+row*25,data.textColor,true);row++;}
                }else{drawFlowText(g,textRenderer,"DURATION",cx-318,t+124,data.textColor,true);drawFlowText(g,textRenderer,formatAuctionDuration(data.auctionDurationSeconds),cx-318,t+168,0xFFFFFFFF,true);drawFlowText(g,textRenderer,"MINIMUM BID",cx-318,t+176,data.textColor,true);drawFlowText(g,textRenderer,"CHOOSE FROM YOUR INVENTORY",cx+22,t+48,a,true);drawFlowText(g,textRenderer,"Select an item on the right",cx+22,t+63,0xFFB9A28D,true);}
            }
            super.render(g,mx,my,delta);
        }
        @Override public void tick(){if(data.auctionActive&&System.currentTimeMillis()>=data.auctionEndTime)finish();if(data.auctionSold&&System.currentTimeMillis()>data.auctionSoldUntil){data.auctionSold=false;data.auctionWinner="";data.auctionHighestBid=0;data.auctionHighestPlayer="No bids yet";save();MinecraftClient.getInstance().setScreen(parent);}}
        @Override public void close(){MinecraftClient.getInstance().setScreen(parent);}
    }


}
