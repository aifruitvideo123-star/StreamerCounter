package com.example.streamercounter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("streamer-counter-owner-26.2.json");
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
    private static KeyMapping openMenuKey;
    private static String pending;
    private static long deadline;
    private static boolean primed;
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
        String lastName;
        boolean showHud = true;
        int textColor = 0xFFFFC85A;
        int hudX = -1;
        int hudY = 8;
        float hudScale = 1.0f;
        boolean compactHud = false;
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
        boolean ownerGlow = true;
        boolean ownerParticles = true;
        boolean ownerAnimations = true;
        boolean ownerAuctionStats = true;
        boolean ownerTimerPulse = true;
        float ownerHudOpacity = 0.88f;
        float ownerAnimationSpeed = 1.0f;
        int ownerTimerStyle = 0;
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
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("streamercounter_owner", "general"));
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.streamercounter_owner.open_menu", InputConstants.Type.KEYSYM,
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
        });;

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
                Identifier.fromNamespaceAndPath("streamercounter_vip", "hud"),
                StreamerCounterClient::renderHud);
    }

    private static void arrived(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (data.streamerEnabled.getOrDefault(key, true)) {
            data.counts.merge(key, 1, Integer::sum);
            data.displayNames.put(key, name);
            data.lastName = name;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendOverlayMessage(Component.literal("VIP: " + name + " joined your TPAHERE!"));
        }
        pending = null;
        save();
    }

    private static List<String> hudNames() {
        if (hudNamesDirty) {
            cachedHudNames = data.counts.keySet().stream()
                    .filter(name -> data.streamerEnabled.getOrDefault(name, true))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            hudNamesDirty = false;
        }
        return cachedHudNames;
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
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        if (data.showHud && !hudNames().isEmpty()) {
            int x = visibleHudX(width);
            drawHud(graphics, x, visibleHudY(height));
        }
        if (data.screenTexts == null) data.screenTexts = new ArrayList<>();
        for (ScreenText entry : data.screenTexts) {
            if (entry != null && entry.visible && entry.text != null && !entry.text.isEmpty()) {
                float scale = Math.max(8, Math.min(96, entry.size)) / 9.0f;
                graphics.pose().pushMatrix();
                graphics.pose().translate(entry.x, entry.y);
                graphics.pose().scale(scale, scale);
                
                if ("flow".equals(entry.font)) {
                    int[] flow = {0xFFFF5C8A,0xFFFFB347,0xFFFFFF66,0xFF75E08A,0xFF62D9FF,0xFFB58CFF};
                    int px=0;
                    for(int ci=0;ci<entry.text.length();ci++){
                        String ch=String.valueOf(entry.text.charAt(ci));
                        graphics.text(mc.font,ch,px,0,flow[ci%flow.length],entry.shadow);
                        px+=mc.font.width(ch);
                    }
                } else {
                    Component styled=Component.literal(entry.text).withStyle(st->st.withBold(entry.bold).withItalic(entry.italic));
                    graphics.text(mc.font,styled,0,0,entry.color,entry.shadow);
                }
                graphics.pose().popMatrix();
            }
        }
    
        if (data.auctionSold && System.currentTimeMillis() > data.auctionSoldUntil) { data.auctionSold=false; data.auctionWinner=""; data.auctionHighestBid=0; data.auctionHighestPlayer="No bids yet"; save(); }
        if (data.auctionShowWorld && (data.auctionActive || (data.auctionSold && System.currentTimeMillis() <= data.auctionSoldUntil))) {
            int hudW=mc.getWindow().getGuiScaledWidth(), hudH=mc.getWindow().getGuiScaledHeight();
            float auctionScale=Math.max(0.6f,Math.min(1.8f,data.auctionHudScale)); int aw=(int)(132*auctionScale), ah=(int)((data.auctionSold?104:76)*auctionScale);
            int cx=data.auctionHudX<0?hudW/2:data.auctionHudX, y=data.auctionHudY;
            long left = data.auctionActive ? Math.max(0,(data.auctionEndTime-System.currentTimeMillis()+999)/1000) : 0;
            if(data.ownerGlow)graphics.fill(cx-aw-3,y-3,cx+aw+3,y+ah+3,0x88FF8126); graphics.fill(cx-aw,y,cx+aw,y+ah,withOpacity(0x120C08,data.ownerHudOpacity)); graphics.fill(cx-aw,y,cx+aw,y+2,0xFFFF8126);
            if (data.auctionActive && mc.player != null) {
                for (int slot=0; slot<mc.player.getInventory().getContainerSize(); slot++) {
                    var stack=mc.player.getInventory().getItem(slot);
                    if (!stack.isEmpty() && stack.getHoverName().getString().equals(data.auctionItem)) { graphics.item(stack,cx-124,y+8); break; }
                }
            }
            drawFlowText(graphics,mc.font,data.auctionSold ? "SOLD" : data.auctionItem,cx-100,y+8,0xFFFFFFFF,true);
            drawFlowText(graphics,mc.font,data.auctionActive ? String.format(Locale.ROOT,"%02d:%02d",left/60,left%60) : data.auctionWinner,cx-100,y+27,0xFFFF8126,true);
            drawFlowText(graphics,mc.font,"DURATION "+formatAuctionDuration(data.auctionDurationSeconds),cx-100,y+39,0xFFFFC04A,true);
            drawFlowText(graphics,mc.font,data.auctionHighestPlayer+"  $"+formatAuctionMoney(data.auctionHighestBid),cx-100,y+49,data.textColor,true);
            if(data.ownerAuctionStats)drawFlowText(graphics,mc.font,"BIDDERS "+data.auctionBids.size(),cx-100,y+61,0xFFFFD36B,true);
            if (data.auctionActive && System.currentTimeMillis() >= data.auctionEndTime) {
            data.auctionActive=false; data.auctionSold=true;
            data.auctionWinner=data.auctionHighestBid>0?data.auctionHighestPlayer:"No bids";
            data.auctionSoldUntil=System.currentTimeMillis()+10000L; save();
        }
        if (data.auctionSold && System.currentTimeMillis() <= data.auctionSoldUntil) {
            long bannerElapsed=10000L-(data.auctionSoldUntil-System.currentTimeMillis());
            int bannerPulse=(int)(Math.sin(bannerElapsed/150.0)*5)+5;
            int bannerCx=mc.getWindow().getGuiScaledWidth()/2, bannerCy=mc.getWindow().getGuiScaledHeight()/2;
            graphics.fill(bannerCx-154-bannerPulse,bannerCy-42,bannerCx+154+bannerPulse,bannerCy+43,0xDD120C08);
            graphics.fill(bannerCx-154-bannerPulse,bannerCy-42,bannerCx+154+bannerPulse,bannerCy-39,0xFFFF8126);
            drawFlowText(graphics,mc.font,"★ AUCTION WON ★",bannerCx-92,bannerCy-29,0xFFFFC04A,true);
            drawFlowText(graphics,mc.font,data.auctionWinner,bannerCx-100,bannerCy-7,0xFFFFFFFF,true);
            drawFlowText(graphics,mc.font,"WON FOR $"+formatAuctionMoney(data.auctionHighestBid),bannerCx-100,bannerCy+14,0xFFFF8126,true);
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
            int timerCx=data.timerHudX<0?mc.getWindow().getGuiScaledWidth()/2:data.timerHudX, timerCy=data.timerHudY<0?mc.getWindow().getGuiScaledHeight()/3:data.timerHudY; float timerScale=Math.max(0.6f,Math.min(1.8f,data.timerHudScale)); int timerRadius=(int)(62*timerScale);
            int total=Math.max(1,data.timerDurationSeconds); double progress=data.timerActive?Math.max(0,Math.min(1,(double)remain/total)):0;
            if(data.ownerGlow)graphics.fill(timerCx-timerRadius-4,timerCy-timerRadius-4,timerCx+timerRadius+4,timerCy+timerRadius+4,0x6659CFFF); graphics.fill(timerCx-timerRadius,timerCy-timerRadius,timerCx+timerRadius,timerCy+timerRadius,withOpacity(0x10131B,data.ownerHudOpacity));
            double ownerTimerRotation=data.ownerTimerStyle==1?Math.sin(System.currentTimeMillis()/350.0*data.ownerAnimationSpeed)*0.025:0.0;
            double ownerTimerCos=Math.cos(ownerTimerRotation),ownerTimerSin=Math.sin(ownerTimerRotation);
            for(int i=0;i<60;i++){double ca=TIMER_CIRCLE_COS[i],sa=TIMER_CIRCLE_SIN[i];double rx=ca*ownerTimerCos-sa*ownerTimerSin,ry=sa*ownerTimerCos+ca*ownerTimerSin;int px=timerCx+(int)(timerRadius*0.85*rx),py=timerCy+(int)(timerRadius*0.85*ry);int col=(i<Math.round(progress*60))?(data.ownerTimerStyle==1?0xFF54E8FF:data.ownerTimerStyle==2?0xFFFFFFFF:0xFFFFC04A):(data.ownerTimerStyle==1?0xFF243B4A:0xFF48505D);graphics.fill(px-2,py-2,px+3,py+3,col);}
            if(data.ownerParticles){
                double orbit=System.currentTimeMillis()/420.0*data.ownerAnimationSpeed;
                double orbitCos=Math.cos(orbit),orbitSin=Math.sin(orbit);
                for(int i=0;i<8;i++){double ca=TIMER_PARTICLE_COS[i],sa=TIMER_PARTICLE_SIN[i];int sx=timerCx+(int)((timerRadius+9)*(ca*orbitCos-sa*orbitSin)),sy=timerCy+(int)((timerRadius+9)*(sa*orbitCos+ca*orbitSin));graphics.fill(sx-1,sy-1,sx+2,sy+2,0xFFFFD36B);}
            }
            String time=String.format(Locale.ROOT,"%02d:%02d",remain/60,remain%60);
            graphics.text(mc.font,Component.literal(time),timerCx-mc.font.width(time)/2,timerCy-5,0xFFFFFFFF);
            String status=data.timerFinished?"TIME UP":"TIMER";
            graphics.text(mc.font,Component.literal(status),timerCx-mc.font.width(status)/2,timerCy+13,data.timerFinished?0xFFFF6B6B:0xFFFFC04A);
            if(timerFinishedAt>0 && System.currentTimeMillis()-timerFinishedAt<5200){
                long elapsed=System.currentTimeMillis()-timerFinishedAt;float pulse=data.ownerAnimations?(float)(1.0+0.035*Math.sin(elapsed/110.0*data.ownerAnimationSpeed)):1.0f;int bw=(int)(300*pulse),bh=76;
                int bx=mc.getWindow().getGuiScaledWidth()/2,by=mc.getWindow().getGuiScaledHeight()/2-38;
                if(data.ownerGlow)graphics.fill(bx-bw/2-5,by-5,bx+bw/2+5,by+bh+5,0x88FF8126);graphics.fill(bx-bw/2-2,by-2,bx+bw/2+2,by+bh+2,0xFFFF8126);graphics.fill(bx-bw/2,by,bx+bw/2,by+bh,0xF20D0B09);
                graphics.fill(bx-bw/2,by,bx+bw/2,by+3,0xFFFF8126);
                drawFlowText(graphics,mc.font,"⏱ TIMER FINISHED",bx-mc.font.width("⏱ TIMER FINISHED")/2,by+19,0xFFFFC04A,true);
                String sub="Your countdown is complete";graphics.text(mc.font,Component.literal(sub),bx-mc.font.width(sub)/2,by+43,0xFFFFFFFF,true);
            }
        }
    }

    private static int withOpacity(int rgb,float opacity){int a=Math.max(0,Math.min(255,Math.round(255f*Math.max(0.1f,Math.min(1f,opacity)))));return (a<<24)|(rgb&0x00FFFFFF);}

    private static void drawHud(GuiGraphicsExtractor graphics, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        List<String> names = hudNames();
        int width = hudWidth();
        int height = hudHeight();
        graphics.fill(x, y, x + width, y + height, withOpacity(0x0B111A,data.ownerHudOpacity));
        int accent = data.iceTheme ? 0xFF69D8FF : 0xFFE8B64D;
        graphics.fill(x, y, x + width, y + 2, 0xFFFFFFFF);
        graphics.fill(x, y+2, x+width, y+3, 0x88777777);
        drawFlowText(graphics, mc.font, "STREAMERS OWNER", x + 6, y + 6, data.textColor, false);
        int rowY = y + 20;
        for (String key : names) {
            String name = displayName(key);
            String ownerName = mc.player == null ? "" : mc.player.getName().getString();
            boolean isOwner = name.equalsIgnoreCase(ownerName);
            if(isOwner){graphics.fill(x+3,rowY-2,x+width-3,rowY+10,0x553FFFFF);graphics.fill(x+3,rowY-2,x+width-3,rowY-1,0xFFEEEEEE);}
            String count = String.valueOf(data.counts.getOrDefault(key, 0));
            if(isOwner){drawFlowText(graphics,mc.font,"✦",x+6,rowY,0xFFFFFFFF,true);drawFlowText(graphics,mc.font,name,x+18,rowY,0xFFFFFFFF,true);}
            else drawFlowText(graphics, mc.font, name, x + 6, rowY, data.textColor, false);
            drawFlowText(graphics, mc.font, count, x + width - 7 - mc.font.width(count),
                    rowY, data.textColor, false);
            rowY += data.compactHud ? 9 : 11;
        }
    }


    private static final String[] HUD_COLOR_NAMES = {"Gold", "Ice", "Red", "Green", "Purple", "White"};
    private static final int[] HUD_COLORS = {
            0xFFFFC85A, 0xFF69D8FF, 0xFFFF6B6B,
            0xFF78E08F, 0xFFB98CFF, 0xFFFFFFFF
    };

    private static String hudColorName() {
        for (int i = 0; i < HUD_COLORS.length; i++) {
            if (HUD_COLORS[i] == data.textColor) return HUD_COLOR_NAMES[i];
        }
        return "Custom";
    }

    private static int blendColor(int from, int to, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        int a = Math.round(((from >>> 24) & 0xFF) * (1.0f - t) + ((to >>> 24) & 0xFF) * t);
        int r = Math.round(((from >>> 16) & 0xFF) * (1.0f - t) + ((to >>> 16) & 0xFF) * t);
        int g = Math.round(((from >>> 8) & 0xFF) * (1.0f - t) + ((to >>> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * (1.0f - t) + (to & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // A soft highlight travels across the glyphs while preserving the selected base color.
    private static void drawFlowText(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                                     String text, int x, int y, int baseColor, boolean shadow) {
        int cursorX = x;
        int glyphIndex = 0;
        double phase = (System.currentTimeMillis() % 2400L) / 2400.0 * Math.PI * 2.0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String glyph = new String(Character.toChars(codePoint));
            float wave = (float) (0.5 + 0.5 * Math.sin(glyphIndex * 0.72 - phase));
            int color = blendColor(0xFF777777, 0xFFFFFFFF, 0.08f + wave * 0.82f);
            graphics.text(font, glyph, cursorX, y, color, shadow);
            cursorX += font.width(glyph);
            offset += Character.charCount(codePoint);
            glyphIndex++;
        }
    }

    private static int visibleHudX(int screenWidth) {
        int requestedX = data.hudX < 0 ? screenWidth - hudWidth() - 8 : data.hudX;
        return Math.max(0, Math.min(requestedX, Math.max(0, screenWidth - hudWidth())));
    }

    private static class HudColorScreen extends Screen {
        private final Screen parent;

        HudColorScreen(Screen parent) {
            super(Component.literal("HUD Text Color"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int center = width / 2;
            int firstX = center - 112;
            int firstY = height / 2 - 38;
            for (int i = 0; i < HUD_COLORS.length; i++) {
                final int colorIndex = i;
                int column = i % 2;
                int row = i / 2;
                String marker = data.textColor == HUD_COLORS[i] ? "✓ " : "";
                addRenderableWidget(Button.builder(
                        Component.literal(marker + HUD_COLOR_NAMES[i]),
                        button -> {
                            data.textColor = HUD_COLORS[colorIndex];
                            save();
                            Minecraft.getInstance().gui.setScreen(parent);
                        }).bounds(firstX + column * 118, firstY + row * 28, 108, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Back"), button ->
                    Minecraft.getInstance().gui.setScreen(parent))
                    .bounds(center - 50, firstY + 3 * 28 + 4, 100, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xC0000000);
            String heading = "Choose HUD text color";
            graphics.text(font, heading, width / 2 - font.width(heading) / 2, height / 2 - 76, 0xFFFFFFFF, true);
            String preview = "Streamer Counter";
            drawFlowText(graphics, font, preview, width / 2 - font.width(preview) / 2,
                    height / 2 - 57, data.textColor, true);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }
    }

    private static class HudEditScreen extends Screen {
        private final Screen parent;
        private boolean draggingHud;
        private double dragOffsetX;
        private double dragOffsetY;

        HudEditScreen(Screen parent) {
            super(Component.literal("Edit HUD"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int center = width / 2;
            addRenderableWidget(Button.builder(Component.literal("Text Color: " + hudColorName()), b ->
                    Minecraft.getInstance().gui.setScreen(new HudColorScreen(this)))
                    .bounds(center - 90, height - 86, 180, 20).build());
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
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0) {
                double mouseX = event.x();
                double mouseY = event.y();
                int hudX = visibleHudX(width);
                int hudY = visibleHudY(height);
                if (mouseX >= hudX && mouseX <= hudX + hudWidth()
                        && mouseY >= hudY && mouseY <= hudY + hudHeight()) {
                    draggingHud = true;
                    dragOffsetX = mouseX - hudX;
                    dragOffsetY = mouseY - hudY;
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            if (draggingHud && event.button() == 0) {
                data.hudX = Math.max(0, Math.min((int) Math.round(event.x() - dragOffsetX),
                        Math.max(0, width - hudWidth())));
                data.hudY = Math.max(0, Math.min((int) Math.round(event.y() - dragOffsetY),
                        Math.max(0, height - hudHeight())));
                return true;
            }
            return super.mouseDragged(event, dragX, dragY);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            if (draggingHud && event.button() == 0) {
                draggingHud = false;
                save();
                return true;
            }
            return super.mouseReleased(event);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xB0000000);
            String heading = "HUD Preview";
            graphics.text(font, heading, width / 2 - font.width(heading) / 2, 28, 0xFFFFFFFF, true);
            String instruction = "Drag the HUD preview to move it.";
            graphics.text(font, instruction, width / 2 - font.width(instruction) / 2, 46, 0xFFCCCCCC, true);
            int x = visibleHudX(width);
            drawHud(graphics, x, visibleHudY(height));
            String size = "Size: " + Math.round(data.hudScale * 100) + "%";
            drawFlowText(graphics, font, size, width / 2 - font.width(size) / 2,
                    height - 110, data.textColor, true);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        @Override
        public void onClose() {
            draggingHud = false;
            save();
            Minecraft.getInstance().gui.setScreen(parent);
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

    private static class TextOnScreenScreen extends Screen {
        private final Screen parent;
        private int selected = -1;
        private boolean dragging;
        private double dx, dy;
        private EditBox textInput;
        TextOnScreenScreen(Screen parent) { super(Component.literal("Text on Screen")); this.parent=parent; }
        @Override protected void init() {
            if (data.screenTexts == null) data.screenTexts = new ArrayList<>();
            int left = 12, top = 100, row = 24;
            for (int i = 0; i < data.screenTexts.size() && i < 10; i++) {
                final int index = i;
                ScreenText item = data.screenTexts.get(i);
                String label = (item.visible ? "§a● " : "§7○ ") + (item.text == null || item.text.isBlank() ? "Text " + (i+1) : item.text);
                if (label.length() > 17) label = label.substring(0, 17) + "…";
                addRenderableWidget(Button.builder(Component.literal(label), b -> {
                    selected = index;
                    clearWidgets();
                    init();
                }).bounds(left, top + i * row, Math.max(112, (int)(width * 0.34) - 30), 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("+ Add Text"), b -> {
                ScreenText item = new ScreenText();
                item.x = width / 2; item.y = height / 2;
                data.screenTexts.add(item); selected = data.screenTexts.size()-1; save();
                clearWidgets(); init();
            }).bounds(left, top + Math.min(data.screenTexts.size(),10) * row + 4, 112, 20).build());

            int x = 12 + Math.max(150, (int)(width * 0.34)) + 26;
            int fieldWidth = Math.max(120, width - x - 30);
            textInput = new EditBox(font, x, 124, fieldWidth, 22, Component.literal("Text"));
            textInput.setMaxLength(256);
            ScreenText chosen = current();
            if (chosen != null) textInput.setValue(chosen.text == null ? "" : chosen.text);
            textInput.setResponder(value -> { ScreenText t=current(); if(t!=null){t.text=value;save();} });
            addRenderableWidget(textInput);

            addRenderableWidget(Button.builder(Component.literal(chosen != null && chosen.visible ? "Hide" : "Show"), b -> {
                ScreenText t=current(); if(t!=null){t.visible=!t.visible;save();clearWidgets();init();}
            }).bounds(x, 184, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                if(selected>=0 && selected<data.screenTexts.size()){data.screenTexts.remove(selected);selected=-1;save();clearWidgets();init();}
            }).bounds(x+74, 184, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Size −"), b -> {
                ScreenText t=current(); if(t!=null){t.size=Math.max(8,t.size-2);save();}
            }).bounds(x, 236, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Size +"), b -> {
                ScreenText t=current(); if(t!=null){t.size=Math.min(96,t.size+2);save();}
            }).bounds(x+74, 236, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Font / Style"),b->{
                ScreenText t=current(); if(t!=null){String[] styles={"default","bold","italic","bold_italic"};int n=0;for(;n<styles.length;n++)if(styles[n].equals(t.font))break;t.font=styles[(n+1)%styles.length];t.bold=t.font.equals("bold")||t.font.equals("bold_italic");t.italic=t.font.equals("italic")||t.font.equals("bold_italic");save();clearWidgets();init();}
            }).bounds(x, 288, 146, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Effect: " + (current()==null ? "Flow" : current().effect)),b->{
                ScreenText t=current();if(t!=null){String[] effects={"flow","pulse","static"};int n=0;for(;n<effects.length;n++)if(effects[n].equals(t.effect))break;t.effect=effects[(n+1)%effects.length];save();clearWidgets();init();}
            }).bounds(x, 352, 146, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Color"),b->{
                ScreenText t=current();if(t!=null){int[] colors={0xFFFFFFFF,0xFFFFD54F,0xFF69D8FF,0xFFFF6B6B,0xFF78E08F};int n=0;for(;n<colors.length;n++)if(colors[n]==t.color)break;t.color=colors[(n+1)%colors.length];save();}
            }).bounds(x, 320, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Back to Menu"),b->onClose())
                .bounds(x+74, 320, 110, 20).build());
            
            // Subtle transparency lets the menu background show through; the selected theme supplies the accent colors.
            for (var child : children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget button) button.setAlpha(0.82f);
            }
        }
        private ScreenText current(){return selected>=0&&selected<data.screenTexts.size()?data.screenTexts.get(selected):null;}
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if(event.button()==0) {
                for(int i=data.screenTexts.size()-1;i>=0;i--) {
                    ScreenText t=data.screenTexts.get(i);
                    int scale=Math.max(8,Math.min(96,t.size));
                    int textWidth=(int)(Minecraft.getInstance().font.width(t.text == null ? "" : t.text)*scale/9.0);
                    int textHeight=scale+3;
                    if(t.visible && event.x()>=t.x-3 && event.x()<=t.x+textWidth+6 && event.y()>=t.y-4 && event.y()<=t.y+textHeight) {
                        selected=i; if(textInput!=null)textInput.setValue(t.text == null ? "" : t.text);
                        dragging=true; dx=event.x()-t.x; dy=event.y()-t.y; return true;
                    }
                }
            }
            return super.mouseClicked(event,doubleClick);
        }
        @Override public boolean mouseDragged(MouseButtonEvent event,double ox,double oy) {
            ScreenText t=current();
            if(dragging&&t!=null&&event.button()==0){t.x=Math.max(0,(int)(event.x()-dx));t.y=Math.max(0,(int)(event.y()-dy));return true;}
            return super.mouseDragged(event,ox,oy);
        }
        @Override public boolean mouseReleased(MouseButtonEvent event) {
            if(dragging&&event.button()==0){dragging=false;save();return true;}
            return super.mouseReleased(event);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mx,int my,float delta) {
            int left=12, top=42, split=Math.max(150,(int)(width*0.34)), right=left+split+12;
            graphics.fill(0,0,width,height,0xB0000000);
            graphics.fill(left-2,top-2,left+split,height-10,0xFFFFD54F);
            graphics.fill(left,top,left+split-2,height-12,0xFF18202C);
            graphics.fill(left+2,top+2,left+split-4,top+5,0xFF6C9BCB);
            graphics.fill(right-2,top-2,width-10,height-10,0xFFFFD54F);
            graphics.fill(right,top,width-12,height-12,0xFF18202C);
            graphics.fill(right+2,top+2,width-14,top+5,0xFF6C9BCB);
            graphics.text(font,"TEXT ON SCREEN",left+14,top+14,0xFFFFFFFF,true);
            graphics.text(font,"PLACED TEXTS",left+14,top+38,0xFFFFD54F,true);
            graphics.text(font,"EDIT TEXT",right+16,top+14,0xFFFFFFFF,true);
            graphics.text(font,"Select a text on the left. Hidden texts stay in this list.",right+16,top+38,0xFFB8C7D9,true);
            graphics.text(font,"TEXT",right+16,top+67,0xFFFFD54F,true);
            graphics.text(font,"SIZE",right+16,top+219,0xFFFFD54F,true);
            graphics.text(font,"FONT / STYLE",right+16,top+271,0xFFFFD54F,true);
            graphics.text(font,"COLOR",right+16,top+303,0xFFFFD54F,true);
            if(data.screenTexts!=null) for(ScreenText item:data.screenTexts) if(item!=null&&item.visible&&item.text!=null&&!item.text.isEmpty()) {
                float scale=Math.max(8,Math.min(96,item.size))/9.0f;
                graphics.pose().pushMatrix(); graphics.pose().translate(item.x,item.y); graphics.pose().scale(scale,scale);
                
                if("flow".equals(item.effect)){
                    drawFlowText(graphics,font,item.text,0,0,item.color,item.shadow);
                }else if("pulse".equals(item.effect)){
                    int pulse=(int)(128+127*Math.sin(System.currentTimeMillis()/180.0));Component styled=Component.literal(item.text).withStyle(st->st.withBold(item.bold).withItalic(item.italic));graphics.text(font,styled,0,0,pulse>185?0xFFFFFFFF:item.color,item.shadow);
                }else{
                    Component styled=Component.literal(item.text).withStyle(st->st.withBold(item.bold).withItalic(item.italic));graphics.text(font,styled,0,0,item.color,item.shadow);
                } graphics.pose().popMatrix();
            }
            ScreenText chosen=current();
            if(chosen!=null) graphics.text(font,"Selected: "+(chosen.text==null?"":chosen.text)+"   |   Size: "+chosen.size,right+16,top+365,0xFFB8C7D9,true);
            else graphics.text(font,"Add a text to begin editing.",right+16,top+330,0xFFB8C7D9,true);
            graphics.text(font,"Drag visible text to move it.",right+16,height-34,0xFF6C9BCB,true);
            super.extractRenderState(graphics,mx,my,delta);

        }
    }


    private static class SessionScreen extends Screen {
        private final Screen parent;
        SessionScreen(Screen parent){super(Component.literal("Sessions"));this.parent=parent;}
        @Override protected void init(){
            int cx=width/2, y=Math.max(34,height/2-112);
            addRenderableWidget(Button.builder(Component.literal(data.sessionActive?"End Session":"Start Session"),b->{
                if(data.sessionActive)endSession();else startSession();
                Minecraft.getInstance().gui.setScreen(new SessionScreen(parent));
            }).bounds(cx-100,y,200,22).build());
            String status=data.sessionActive?"LIVE  •  "+sessionDuration(data.sessionStartedAt,System.currentTimeMillis()):"No active session";
            addRenderableWidget(Button.builder(Component.literal(status),b->{}).bounds(cx-150,y+32,300,20).build());
            double net=data.sessionIncome-data.sessionExpenses;
            addRenderableWidget(Button.builder(Component.literal("Income  +"+formatAuctionMoney(data.sessionIncome)+"   |   Spent  -"+formatAuctionMoney(data.sessionExpenses)),b->{}).bounds(cx-190,y+60,380,20).build());
            addRenderableWidget(Button.builder(Component.literal("NET  "+(net>=0?"+":"-")+formatAuctionMoney(Math.abs(net))+"    •    Transactions: "+data.sessionTransactions),b->{}).bounds(cx-190,y+86,380,20).build());
            int row=0;
            if(data.sessionHistory!=null)for(SessionRecord record:data.sessionHistory){
                if(row>=4)break;
                String label="Session  •  "+sessionDuration(record.startedAt,record.endedAt)+"  •  "+(record.income-record.expenses>=0?"+":"-")+formatAuctionMoney(Math.abs(record.income-record.expenses));
                addRenderableWidget(Button.builder(Component.literal(label),b->{}).bounds(cx-190,y+116+row*23,380,20).build());
                row++;
            }
            addRenderableWidget(Button.builder(Component.literal("Back"),b->Minecraft.getInstance().gui.setScreen(parent)).bounds(cx-55,height-42,110,20).build());
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){super.extractRenderState(g,mouseX,mouseY,delta);g.drawCenteredString(font,"SESSION TRACKER",width/2,Math.max(16,height/2-130),0xFFFFD45F);}
        @Override public void onClose(){Minecraft.getInstance().gui.setScreen(parent);}
    }

    private static class CounterMenuScreen extends Screen {
        private final Screen parent;
        private final int page;
        private final int activeTab;
        private static final int ROW_HEIGHT = 26;
        private static final String[] TABS = {"Overview","Streamers","HUD","Tools","Themes","Settings","OWNER"};

        CounterMenuScreen(Screen parent, int page) { this(parent,page,0); }
        CounterMenuScreen(Screen parent, int page, int activeTab) {
            super(Component.literal("StreamerCounter OWNER"));
            this.parent=parent; this.page=page; this.activeTab=activeTab;
        }
        private List<String> streamerNames() {
            Set<String> names=new TreeSet<>();
            names.addAll(data.counts.keySet()); names.addAll(data.streamerEnabled.keySet());
            if(pending!=null) names.add(pending.toLowerCase(Locale.ROOT));
            return new ArrayList<>(names);
        }
        private void openTab(int tab) { Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,0,tab)); }
        private Component hudButtonText(){return Component.literal("HUD: "+(data.showHud?"ON":"OFF"));}
        private static final String[] VIP_THEMES={"GOLD","ICE","CHRISTMAS","HALLOWEEN","NETHER","EMERALD","PURPLE","OCEAN"};
        private String currentTheme(){return data.iceTheme&&"GOLD".equals(data.vipTheme)?"ICE":(data.vipTheme==null?"GOLD":data.vipTheme);}
        private Component themeButtonText(){return Component.literal("Theme: "+currentTheme()+"  →");}
        private int accent(){if(true)return 0xFFEAEAEA; return switch(currentTheme()){case "ICE","OCEAN"->0xFF69D8FF;case "CHRISTMAS"->0xFF55D878;case "HALLOWEEN"->0xFFFF8738;case "NETHER"->0xFFFF5555;case "EMERALD"->0xFF45D69A;case "PURPLE"->0xFFB58CFF;default->0xFFFFD54F;};}
        private int textColor(){if(true)return 0xFFF4F4F4; return switch(currentTheme()){case "ICE","OCEAN"->0xFF9BE8FF;case "CHRISTMAS"->0xFFFFD8D8;case "HALLOWEEN"->0xFFFFC18A;case "NETHER"->0xFFFFA0A0;case "EMERALD"->0xFF9BFFD1;case "PURPLE"->0xFFDCCBFF;default->0xFFFFD878;};}
        private int panelColor(){if(true)return 0xD0090909; return switch(currentTheme()){case "CHRISTMAS"->0xB817241B;case "HALLOWEEN"->0xB8201712;case "ICE","OCEAN"->0xB8111D29;case "NETHER"->0xB8201118;case "EMERALD"->0xB812211D;case "PURPLE"->0xB81C1728;default->0xB8141A24;};}
        private void drawSeasonDecor(GuiGraphicsExtractor g) {
            int decoX=(width+Math.min(920,width-32))/2-110;
            String theme=currentTheme();
            if(theme.equals("CHRISTMAS")){
                // Candy cane, hanging baubles, holly and little snow accents.
                g.fill(decoX+16,42,decoX+22,72,0xFFFFF1D6);
                g.fill(decoX+22,42,decoX+34,48,0xFFFFF1D6);
                g.fill(decoX+16,50,decoX+22,56,0xFFD93636);
                g.fill(decoX+22,60,decoX+28,66,0xFFD93636);
                g.fill(decoX+22,48,decoX+28,54,0xFFD93636);
                g.fill(decoX+48,42,decoX+49,51,0xFFFFD45F);
                g.fill(decoX+44,50,decoX+54,60,0xFFD93636);
                g.fill(decoX+46,52,decoX+52,58,0xFFFFD7D7);
                g.fill(decoX+67,42,decoX+68,54,0xFFFFD45F);
                g.fill(decoX+63,53,decoX+73,63,0xFFFFD45F);
                g.fill(decoX+65,55,decoX+71,61,0xFFFFF0B0);
                g.fill(decoX+80,64,decoX+90,74,0xFF55D878);
                g.fill(decoX+77,60,decoX+83,66,0xFF278A49);
                g.fill(decoX+95,48,decoX+101,50,0xFFBCEBFF);
                g.fill(decoX+97,44,decoX+99,54,0xFFBCEBFF);
                g.fill(decoX+110,65,decoX+116,67,0xFFBCEBFF);
                g.fill(decoX+112,61,decoX+114,71,0xFFBCEBFF);
            } else if(theme.equals("HALLOWEEN")){
                // Jack-o-lanterns, a tiny bat and warm candle-like highlights.
                g.fill(decoX+22,43,decoX+32,51,0xFF65B84F);
                g.fill(decoX+14,50,decoX+40,72,0xFFFF8738);
                g.fill(decoX+18,47,decoX+36,75,0xFFFF8738);
                g.fill(decoX+20,55,decoX+24,61,0xFF28170F);
                g.fill(decoX+30,55,decoX+34,61,0xFF28170F);
                g.fill(decoX+23,65,decoX+31,68,0xFF28170F);
                g.fill(decoX+49,53,decoX+59,63,0xFFFFB347);
                g.fill(decoX+51,55,decoX+57,61,0xFF28170F);
                g.fill(decoX+53,47,decoX+55,54,0xFF65B84F);
                g.fill(decoX+75,51,decoX+89,55,0xFF24152E);
                g.fill(decoX+78,47,decoX+82,54,0xFF24152E);
                g.fill(decoX+85,47,decoX+89,54,0xFF24152E);
                g.fill(decoX+96,61,decoX+102,63,0xFFFFB347);
                g.fill(decoX+98,57,decoX+100,67,0xFFFFB347);
            } else if(theme.equals("ICE")||theme.equals("OCEAN")){
                // Layered snowflake / icy sparkle.
                g.fill(decoX+19,43,decoX+21,75,0xFFBCEBFF);
                g.fill(decoX+5,57,decoX+35,59,0xFFBCEBFF);
                g.fill(decoX+10,48,decoX+30,68,0xFFBCEBFF);
                g.fill(decoX+48,48,decoX+52,52,0xFF69D8FF);
                g.fill(decoX+46,58,decoX+54,60,0xFF69D8FF);
                g.fill(decoX+48,66,decoX+52,70,0xFF69D8FF);
                g.fill(decoX+70,50,decoX+76,56,0xFFBCEBFF);
                g.fill(decoX+68,58,decoX+78,60,0xFFBCEBFF);
            } else if(theme.equals("NETHER")){
                g.fill(decoX+18,48,decoX+32,62,0xFFFF5555);
                g.fill(decoX+21,51,decoX+29,59,0xFFFFB347);
                g.fill(decoX+48,56,decoX+62,60,0xFF8E263B);
                g.fill(decoX+53,48,decoX+57,68,0xFFFF5555);
                g.fill(decoX+76,48,decoX+90,52,0xFFFF5555);
                g.fill(decoX+82,44,decoX+86,58,0xFFFF8A5B);
            } else if(theme.equals("EMERALD")){
                g.fill(decoX+20,44,decoX+34,58,0xFF45D69A);
                g.fill(decoX+24,48,decoX+30,54,0xFFB8FFE1);
                g.fill(decoX+50,50,decoX+64,54,0xFF45D69A);
                g.fill(decoX+55,45,decoX+59,60,0xFF45D69A);
                g.fill(decoX+78,48,decoX+92,62,0xFF238B68);
                g.fill(decoX+82,52,decoX+88,58,0xFF9BFFD1);
            } else if(theme.equals("PURPLE")){
                g.fill(decoX+20,48,decoX+34,52,0xFFB58CFF);
                g.fill(decoX+25,43,decoX+29,58,0xFFB58CFF);
                g.fill(decoX+52,50,decoX+64,62,0xFF7D5BC7);
                g.fill(decoX+55,53,decoX+61,59,0xFFDCCBFF);
                g.fill(decoX+80,45,decoX+84,65,0xFFB58CFF);
                g.fill(decoX+72,53,decoX+92,57,0xFFB58CFF);
            } else {
                // Gold theme: small crown and shimmering stars.
                g.fill(decoX+18,55,decoX+42,68,0xFFFFD45F);
                g.fill(decoX+18,48,decoX+23,57,0xFFFFD45F);
                g.fill(decoX+28,43,decoX+33,57,0xFFFFD45F);
                g.fill(decoX+38,48,decoX+43,57,0xFFFFD45F);
                g.fill(decoX+52,50,decoX+58,56,0xFFFFF0B0);
                g.fill(decoX+72,60,decoX+78,66,0xFFFFD45F);
                g.fill(decoX+90,46,decoX+96,52,0xFFFFF0B0);
            }
            // Fine corner sparkles make the theme visible without cluttering controls.
            int sparkle=accent();
            g.fill(22,104,24,112,sparkle); g.fill(19,107,27,109,sparkle);
            g.fill(width-25,104,width-23,112,sparkle); g.fill(width-28,107,width-20,109,sparkle);
            g.fill(22,height-88,24,height-80,sparkle); g.fill(19,height-85,27,height-83,sparkle);
            g.fill(width-25,height-88,width-23,height-80,sparkle); g.fill(width-28,height-85,width-20,height-83,sparkle);
        }
        private int pageCount(int rows){return Math.max(1,(streamerNames().size()+rows-1)/rows);}
        @Override protected void init() {
            int panelW=Math.min(920,width-32), left=(width-panelW)/2, top=24;
            int tabStart=left+210, tabW=Math.min(82,(panelW-270)/7), gap=4;
            for(int t=0;t<TABS.length;t++){
                final int tab=t;
                String label=(t==activeTab?"• ":"")+TABS[t];
                addRenderableWidget(Button.builder(Component.literal(label),b->openTab(tab))
                    .bounds(tabStart+t*(tabW+gap),top+16,tabW,22).build());
            }
            addRenderableWidget(Button.builder(Component.literal("×"),b->onClose())
                .bounds(left+panelW-42,top+16,26,22).build());
            int bodyTop=top+96, bodyBottom=height-74, contentX=left+26, contentW=panelW-52;
            if(activeTab==1){
                int maxRows=Math.max(1,(bodyBottom-bodyTop-34)/ROW_HEIGHT);
                int pages=pageCount(maxRows), current=Math.max(0,Math.min(page,pages-1));
                List<String> names=streamerNames();
                int from=current*maxRows,to=Math.min(names.size(),from+maxRows);
                for(int n=from;n<to;n++){
                    String name=names.get(n); int count=data.counts.getOrDefault(name,0);
                    int y=bodyTop+(n-from)*ROW_HEIGHT, actionW=58, gap2=6, toggleW=contentW-actionW*2-gap2*2;
                    addRenderableWidget(Button.builder(streamerButtonText(name,count),b->{
                        data.streamerEnabled.put(name,!data.streamerEnabled.getOrDefault(name,true));save();Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,current,1));
                    }).bounds(contentX,y,toggleW,21).build());
                    addRenderableWidget(Button.builder(Component.literal("Reset"),b->{data.counts.put(name,0);save();Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,current,1));})
                        .bounds(contentX+toggleW+gap2,y,actionW,21).build());
                    addRenderableWidget(Button.builder(Component.literal("Delete"),b->{
                        data.counts.remove(name);data.streamerEnabled.remove(name);data.displayNames.remove(name);
                        if(data.lastName!=null&&data.lastName.equalsIgnoreCase(name))data.lastName=null;
                        if(pending!=null&&pending.equalsIgnoreCase(name))pending=null;save();Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,current,1));
                    }).bounds(contentX+toggleW+gap2+actionW+gap2,y,actionW,21).build());
                }
                if(pages>1){
                    Button prev=addRenderableWidget(Button.builder(Component.literal("‹ Previous"),b->Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,current-1,1))).bounds(width/2-104,bodyBottom-28,98,22).build());
                    prev.active=current>0;
                    Button next=addRenderableWidget(Button.builder(Component.literal("Next ›"),b->Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,current+1,1))).bounds(width/2+6,bodyBottom-28,98,22).build());
                    next.active=current<pages-1;
                }
            } else if(activeTab==2){
                addRenderableWidget(Button.builder(hudButtonText(),b->{data.showHud=!data.showHud;save();b.setMessage(hudButtonText());})
                    .bounds(contentX,bodyTop,190,24).build());
                addRenderableWidget(Button.builder(Component.literal("Edit HUD"),b->Minecraft.getInstance().gui.setScreen(new HudEditScreen(this)))
                    .bounds(contentX,bodyTop+38,190,24).build());
                            addRenderableWidget(Button.builder(Component.literal("Compact layout: "+(data.compactHud?"ON":"OFF")),b->{data.compactHud=!data.compactHud;save();Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,0,2));})
                    .bounds(contentX,bodyTop+70,190,24).build());
            } else if(activeTab==3){
                String[] tools={"Auction","Timer","Text on screen","HUD","Sessions"};
                for(int i=0;i<tools.length;i++){final int tool=i;
                    addRenderableWidget(Button.builder(Component.literal("›  "+tools[i]),b->{
                        if(tool==0)Minecraft.getInstance().gui.setScreen(new AuctionScreen(this));
                        else if(tool==1)Minecraft.getInstance().gui.setScreen(new TimerScreen(this));
                        else if(tool==2)Minecraft.getInstance().gui.setScreen(new TextOnScreenScreen(this));
                        else if(tool==3)openTab(2);\n                         else if(tool==4)Minecraft.getInstance().gui.setScreen(new SessionScreen(this));
                    }).bounds(contentX,bodyTop+i*42,230,28).build());
                }
            } else if(activeTab==4){
                addRenderableWidget(Button.builder(themeButtonText(),b->{String[] themes=VIP_THEMES;int n=0;for(;n<themes.length;n++)if(themes[n].equals(currentTheme()))break;data.vipTheme=themes[(n+1)%themes.length];data.iceTheme=data.vipTheme.equals("ICE");save();Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,0,4));})
                    .bounds(contentX,bodyTop,250,24).build());
            } else if(activeTab==5){
                addRenderableWidget(Button.builder(Component.literal("HUD: "+(data.showHud?"ON":"OFF")),b->{data.showHud=!data.showHud;save();b.setMessage(Component.literal("HUD: "+(data.showHud?"ON":"OFF")));}).bounds(contentX,bodyTop,180,22).build());
                addRenderableWidget(Button.builder(Component.literal("HUD Size −"),b->{data.hudScale=Math.max(0.5f,data.hudScale-0.1f);save();}).bounds(contentX,bodyTop+30,88,22).build());
                addRenderableWidget(Button.builder(Component.literal("HUD Size +"),b->{data.hudScale=Math.min(2.0f,data.hudScale+0.1f);save();}).bounds(contentX+94,bodyTop+30,88,22).build());
                addRenderableWidget(Button.builder(Component.literal("Reset HUD Position"),b->{data.hudX=-1;data.hudY=8;save();}).bounds(contentX,bodyTop+60,180,22).build());
                addRenderableWidget(Button.builder(Component.literal("Reset All Counters"),b->{data.counts.clear();data.lastName=null;pending=null;save();Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,0,5));}).bounds(contentX,bodyTop+90,180,22).build());
                addRenderableWidget(Button.builder(Component.literal("Close Menu"),b->onClose()).bounds(contentX,bodyTop+120,180,22).build());
                addRenderableWidget(Button.builder(Component.literal("Move and resize HUDs"),b->Minecraft.getInstance().gui.setScreen(new LayoutEditScreen(this))).bounds(contentX,bodyTop+154,230,26).build());
                            }
        
            else if(activeTab==6){
                addRenderableWidget(Button.builder(Component.literal("✦ OWNER EFFECTS: "+(data.ownerGlow?"ON":"OFF")),b->{data.ownerGlow=!data.ownerGlow;save();b.setMessage(Component.literal("✦ OWNER EFFECTS: "+(data.ownerGlow?"ON":"OFF")));}).bounds(contentX,bodyTop,230,24).build());
                addRenderableWidget(Button.builder(Component.literal("Particles: "+(data.ownerParticles?"ON":"OFF")),b->{data.ownerParticles=!data.ownerParticles;save();b.setMessage(Component.literal("Particles: "+(data.ownerParticles?"ON":"OFF")));}).bounds(contentX,bodyTop+32,230,24).build());
                addRenderableWidget(Button.builder(Component.literal("Smooth animations: "+(data.ownerAnimations?"ON":"OFF")),b->{data.ownerAnimations=!data.ownerAnimations;save();b.setMessage(Component.literal("Smooth animations: "+(data.ownerAnimations?"ON":"OFF")));}).bounds(contentX,bodyTop+64,230,24).build());
                addRenderableWidget(Button.builder(Component.literal("Auction stats: "+(data.ownerAuctionStats?"ON":"OFF")),b->{data.ownerAuctionStats=!data.ownerAuctionStats;save();b.setMessage(Component.literal("Auction stats: "+(data.ownerAuctionStats?"ON":"OFF")));}).bounds(contentX,bodyTop+96,230,24).build());
                addRenderableWidget(Button.builder(Component.literal("Timer style: "+new String[]{"ORBIT","NEON","MINIMAL"}[Math.floorMod(data.ownerTimerStyle,3)]),b->{data.ownerTimerStyle=(data.ownerTimerStyle+1)%3;save();b.setMessage(Component.literal("Timer style: "+new String[]{"ORBIT","NEON","MINIMAL"}[data.ownerTimerStyle]));}).bounds(contentX+260,bodyTop,230,24).build());
                addRenderableWidget(Button.builder(Component.literal("HUD opacity −"),b->{data.ownerHudOpacity=Math.max(0.25f,data.ownerHudOpacity-0.08f);save();}).bounds(contentX+260,bodyTop+32,110,24).build());
                addRenderableWidget(Button.builder(Component.literal("HUD opacity +"),b->{data.ownerHudOpacity=Math.min(1f,data.ownerHudOpacity+0.08f);save();}).bounds(contentX+378,bodyTop+32,110,24).build());
                addRenderableWidget(Button.builder(Component.literal("Animation speed −"),b->{data.ownerAnimationSpeed=Math.max(0.5f,data.ownerAnimationSpeed-0.1f);save();}).bounds(contentX+260,bodyTop+64,110,24).build());
                addRenderableWidget(Button.builder(Component.literal("Animation speed +"),b->{data.ownerAnimationSpeed=Math.min(2.5f,data.ownerAnimationSpeed+0.1f);save();}).bounds(contentX+378,bodyTop+64,110,24).build());
                addRenderableWidget(Button.builder(Component.literal("OWNER: "+(data.ownerGlow?"GLOW ON":"GLOW OFF")),b->{data.ownerGlow=!data.ownerGlow;save();b.setMessage(Component.literal("OWNER: "+(data.ownerGlow?"GLOW ON":"GLOW OFF")));}).bounds(contentX,bodyTop+130,230,24).build());
                addRenderableWidget(Button.builder(Component.literal("Reset owner effects"),b->{data.ownerGlow=true;data.ownerParticles=true;data.ownerAnimations=true;data.ownerAuctionStats=true;data.ownerTimerPulse=true;data.ownerHudOpacity=0.88f;data.ownerAnimationSpeed=1f;data.ownerTimerStyle=0;save();Minecraft.getInstance().gui.setScreen(new CounterMenuScreen(parent,0,6));}).bounds(contentX+260,bodyTop+96,230,24).build());
                addRenderableWidget(Button.builder(Component.literal("Move / resize HUDs"),b->Minecraft.getInstance().gui.setScreen(new LayoutEditScreen(this))).bounds(contentX,bodyTop+146,230,26).build());
            }
            // Apply translucency to the actual top-level VIP menu controls.
            for (var child : children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget button) button.setAlpha(0.0f);
            }
        }
        private Component streamerButtonText(String name,int count){
            return Component.literal(name+"   •   "+(data.streamerEnabled.getOrDefault(name,true)?"ON":"OFF")+"   •   "+count+" TPs");
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
            int panelW=Math.min(920,width-32),left=(width-panelW)/2,top=24,right=left+panelW,bottom=height-24;
            g.fill(0,0,width,height,0x50000000);
            g.fill(left-1,top-1,right+1,bottom+1,0xAA000000);
            g.fill(left,top,right,bottom,panelColor());
            drawSeasonDecor(g);
            if(data.ownerGlow){long tick=System.currentTimeMillis();int shimmer=(int)(Math.sin(tick/240.0*data.ownerAnimationSpeed)*24)+32;int glint=0xFF000000|((255)<<16)|((130+shimmer)<<8)|40;g.fill(left+2,top+3,left+panelW-2,top+5,glint);if(data.ownerParticles){for(int i=0;i<10;i++){int sx=left+24+(int)((tick/18+i*97)%(panelW-48));int sy=top+66+(i*17)%Math.max(1,height-140);g.fill(sx,sy,sx+2,sy+2,0x99FFD36B);}}}
            g.fill(left+1,top+1,right-1,top+3,accent());
            g.fill(left+1,top+58,right-1,top+59,0x665F7188);
            String title="STREAMERCOUNTER OWNER";
            drawFlowText(g,font,title,left+22,top+22,0xFFFFFFFF,true);
            drawFlowText(g,font,"✦ OWNER CONTROL CENTER  •  26.2",left+22,top+39,textColor(),true);
            drawFlowText(g,font,"BLACK / WHITE  •  LIVE EFFECTS",left+panelW-210,top+39,0xFFFFFFFF,true);
            int bodyTop=top+96, bodyBottom=height-74, x=left+26;
            String heading=TABS[activeTab].toUpperCase(Locale.ROOT);
            drawFlowText(g,font,heading,x,top+76,accent(),true);
            g.fill(x,bodyTop-12,right-26,bodyBottom+8,0x55232E3D);
            if(activeTab==0){
                int total=0;for(Integer value:data.counts.values())if(value!=null)total+=value;
                String[] lines={"Your live overview","Tracked streamers: "+streamerNames().size(),"Total TP counter: "+total,
                    "Last detected: "+(data.lastName==null?"—":data.lastName),"HUD: "+(data.showHud?"Enabled":"Disabled"),
                    "Enabled streamers: "+streamerNames().stream().filter(n->data.streamerEnabled.getOrDefault(n,true)).count(),
                    "Auction: "+(data.auctionActive?"Live":"Idle"),"Timer: "+(data.timerActive?"Running":(data.timerFinished?"Finished":"Idle")),
                    "HUD layout: "+(data.compactHud?"Compact":"Standard")+" • "+Math.round(data.ownerHudOpacity*100)+"% opacity",
                    "Use the tabs above to manage streamers, HUD, text and theme."};
                for(int j=0;j<lines.length;j++)drawFlowText(g,font,lines[j],x+18,bodyTop+18+j*24,j==0?0xFFFFFFFF:textColor(),true);
            }else if(activeTab==1&&streamerNames().isEmpty()){
                drawFlowText(g,font,"No streamers detected yet.",x+18,bodyTop+18,0xFFFFFFFF,true);
                drawFlowText(g,font,"Streamers appear here after /tpahere <name>.",x+18,bodyTop+42,textColor(),true);
            }else if(activeTab==2){
                drawFlowText(g,font,"Toggle the HUD or open its editor.",x+220,bodyTop+6,textColor(),true);
            }else if(activeTab==4){
                drawFlowText(g,font,"OWNER MODE  •  MONOCHROME FLOW",x+220,bodyTop+6,textColor(),true);
            }else if(activeTab==5){
                drawFlowText(g,font,"Adjust HUD visibility and size, reset its position, or manage counter data.",x+220,bodyTop+6,textColor(),true);
            }
            super.extractRenderState(g,mx,my,delta);
            int buttonIndex=0;
            for (var child : children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget button && button.visible) {
                    int bx=button.getX(), by=button.getY(), bw=button.getWidth(), bh=button.getHeight();
                    boolean hovered=mx>=bx&&mx<bx+bw&&my>=by&&my<by+bh;
                    boolean selected=buttonIndex<6&&buttonIndex==activeTab;
                    int bg=selected?0xAA59432D:(hovered?0x99513D2A:0x66120E0A);
                    g.fill(bx,by,bx+bw,by+bh,bg);
                    if(selected)g.fill(bx,by,bx+bw,by+2,accent());
                    String label=button.getMessage().getString();
                    int color=selected?0xFFFFE4B5:textColor();
                    drawFlowText(g,font,label,bx+(bw-font.width(label))/2,by+(bh-font.lineHeight)/2,color,true);
                    buttonIndex++;
                }
            }
            String credit="OWNER EDITION  •  MADE BY ALPBEY3503";
            drawFlowText(g,font,credit,left+22,bottom-18,textColor(),true);
        }
        @Override public void onClose(){Minecraft.getInstance().gui.setScreen(parent);}
    }



 

    private static class LayoutEditScreen extends Screen {
        private final Screen parent; private int selected=0; private boolean dragging=false; private double offX,offY;
        LayoutEditScreen(Screen parent){super(Component.literal("Move and resize HUDs"));this.parent=parent;}
        private int px(int i){return i==0?(data.hudX<0?width-hudWidth()-8:data.hudX):i==1?(data.auctionHudX<0?width/2-pw(1)/2:data.auctionHudX-pw(1)/2):(data.timerHudX<0?width/2-pw(2)/2:data.timerHudX-pw(2)/2);}
        private int py(int i){return i==0?visibleHudY(height):i==1?data.auctionHudY:(data.timerHudY<0?height/3- pw(2)/2:data.timerHudY-pw(2)/2);}
        private int pw(int i){return i==0?hudWidth():i==1?(int)(264*Math.max(.6f,Math.min(1.8f,data.auctionHudScale))):(int)(124*Math.max(.6f,Math.min(1.8f,data.timerHudScale)));}
        private int ph(int i){return i==0?hudHeight():i==1?(int)((data.auctionSold?104:76)*Math.max(.6f,Math.min(1.8f,data.auctionHudScale))):(int)(124*Math.max(.6f,Math.min(1.8f,data.timerHudScale)));}
        private void move(int i,int x,int y){if(i==0){data.hudX=x;data.hudY=y;}else if(i==1){data.auctionHudX=x+pw(1)/2;data.auctionHudY=y;}else{data.timerHudX=x+pw(2)/2;data.timerHudY=y+pw(2)/2;}}
        private void scale(int i,float d){if(i==0)data.hudScale=Math.max(.5f,Math.min(2f,data.hudScale+d));else if(i==1)data.auctionHudScale=Math.max(.6f,Math.min(1.8f,data.auctionHudScale+d));else data.timerHudScale=Math.max(.6f,Math.min(1.8f,data.timerHudScale+d));}
        @Override protected void init(){addRenderableWidget(Button.builder(Component.literal("Reset layout"),b->{data.hudX=-1;data.hudY=8;data.auctionHudX=-1;data.auctionHudY=28;data.timerHudX=-1;data.timerHudY=-1;data.hudScale=1.0f;data.auctionHudScale=1.0f;data.timerHudScale=1.0f;save();}).bounds(width/2-108,height-34,104,22).build());addRenderableWidget(Button.builder(Component.literal("Done"),b->Minecraft.getInstance().gui.setScreen(parent)).bounds(width/2+4,height-34,100,22).build());}
        @Override public boolean mouseClicked(MouseButtonEvent e,boolean doubled){if(e.button()==0){for(int i=2;i>=0;i--){int x=px(i),y=py(i),w=pw(i),h=ph(i);if(e.x()>=x&&e.x()<=x+w&&e.y()>=y&&e.y()<=y+h){selected=i;dragging=true;offX=e.x()-x;offY=e.y()-y;return true;}}}return super.mouseClicked(e,doubled);}
        @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){if(dragging&&e.button()==0){move(selected,Math.max(0,Math.min(width-pw(selected),(int)(e.x()-offX))),Math.max(0,Math.min(height-ph(selected),(int)(e.y()-offY))));return true;}return super.mouseDragged(e,dx,dy);}
        @Override public boolean mouseReleased(MouseButtonEvent e){if(dragging&&e.button()==0){dragging=false;save();return true;}return super.mouseReleased(e);}
        @Override public boolean mouseScrolled(double x,double y,double horizontalAmount,double verticalAmount){for(int i=2;i>=0;i--){if(x>=px(i)&&x<=px(i)+pw(i)&&y>=py(i)&&y<=py(i)+ph(i)){selected=i;scale(i,verticalAmount>0?.1f:-.1f);save();return true;}}return super.mouseScrolled(x,y,horizontalAmount,verticalAmount);}
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){g.fill(0,0,width,height,0xB0000000);String title="MOVE & RESIZE HUDS",help="Drag a panel to move it • Scroll to resize • ESC to finish";g.text(font,title,width/2-font.width(title)/2,24,0xFFFFA044,true);g.text(font,help,width/2-font.width(help)/2,42,0xFFFFFFFF,true);
            for(int i=0;i<3;i++){int x=px(i),y=py(i),w=pw(i),h=ph(i);g.fill(x-2,y-2,x+w+2,y+h+2,i==selected?0xFFFF8126:0xAA8A603E);g.fill(x,y,x+w,y+h,0xDD120C08);g.fill(x,y,x+w,y+2,0xFFFF8126);String label=i==0?"STREAMER HUD":i==1?"AUCTION HUD":"TIMER HUD";g.text(font,label,x+w/2-font.width(label)/2,y+8,0xFFFFFFFF,true);String desc=i==0?"Player counters":i==1?"Item • countdown • highest bid":"00:45";g.text(font,desc,x+w/2-font.width(desc)/2,y+25,0xFFFFC04A,true);}
            String status="Selected: "+(selected==0?"Streamer HUD":selected==1?"Auction HUD":"Timer HUD")+" • "+Math.round((selected==0?data.hudScale:selected==1?data.auctionHudScale:data.timerHudScale)*100)+"%";g.text(font,status,width/2-font.width(status)/2,height-54,0xFFFFC04A,true);}
        @Override public void onClose(){save();Minecraft.getInstance().gui.setScreen(parent);}
    }

    private static class TimerScreen extends Screen {
        private final Screen parent;
        private EditBox secondsField;
        TimerScreen(Screen parent){super(Component.literal("OWNER TIMER"));this.parent=parent;}
        @Override protected void init(){
            int cx=width/2, top=height/2-132;
            secondsField=new EditBox(font,cx-90,top+52,180,26,Component.literal("Seconds"));
            secondsField.setValue(String.valueOf(data.timerDurationSeconds));addRenderableWidget(secondsField);
            int[] vals={10,30,60,120,300,600}; String[] labels={"10 sec","30 sec","1 min","2 min","5 min","10 min"};
            for(int i=0;i<vals.length;i++){final int v=vals[i];addRenderableWidget(Button.builder(Component.literal(labels[i]),b->{data.timerDurationSeconds=v;secondsField.setValue(String.valueOf(v));save();}).bounds(cx-180+(i%3)*120,top+91+(i/3)*31,110,25).build());}
            addRenderableWidget(Button.builder(Component.literal(data.timerActive?"Stop timer":"Reset"),b->{data.timerActive=false;data.timerFinished=false;timerFinishedAt=0;data.timerEndTime=0;save();Minecraft.getInstance().gui.setScreen(new TimerScreen(parent));}).bounds(cx-70,top+158,140,25).build());
            addRenderableWidget(Button.builder(Component.literal("Start timer"),b->{try{data.timerDurationSeconds=Math.max(1,Math.min(86400,Integer.parseInt(secondsField.getValue().trim())));}catch(Exception ignored){data.timerDurationSeconds=60;}data.timerEndTime=System.currentTimeMillis()+data.timerDurationSeconds*1000L;data.timerActive=true;data.timerFinished=false;timerFinishedAt=0;save();Minecraft.getInstance().gui.setScreen(parent);}).bounds(cx-160,top+194,150,28).build());
            addRenderableWidget(Button.builder(Component.literal("Back to Tools"),b->Minecraft.getInstance().gui.setScreen(parent)).bounds(cx+10,top+194,150,28).build());
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){
            g.fill(0,0,width,height,0x99000000);int cx=width/2,top=height/2-132;
            g.fill(cx-205,top-18,cx+205,top+239,0xF20D0B09);g.fill(cx-205,top-18,cx+205,top-14,0xFFFF8126);
            String state="TIMER  •  "+(data.timerActive?"RUNNING":data.timerFinished?"FINISHED":"READY");
            g.text(font,state,cx-font.width(state)/2,top+7,0xFFFFC04A,true);
            String help="Choose a preset or enter a duration in seconds";
            g.text(font,help,cx-font.width(help)/2,top+34,0xFFDDC9B3,true);
            super.extractRenderState(g,mouseX,mouseY,delta);
        }
    }

    private static class AuctionScreen extends Screen {
        private final Screen parent;
        private EditBox minBid;
        AuctionScreen(Screen parent){super(Component.literal("OWNER AUCTION"));this.parent=parent;}

        @Override protected void init(){
            int cx=width/2, t=height/2-170, left=cx-318;
            minBid=new EditBox(font,left,t+194,280,22,Component.literal("Minimum bid"));
            minBid.setValue(String.valueOf(data.auctionMinBid));
            if(!data.auctionActive) addRenderableWidget(minBid);

            if(!data.auctionActive){
                addRenderableWidget(Button.builder(Component.literal("Use held item"),b->{
                    Minecraft mc=Minecraft.getInstance();
                    if(mc.player!=null&&!mc.player.getMainHandItem().isEmpty())
                        data.auctionItem=mc.player.getMainHandItem().getHoverName().getString();
                    save();mc.gui.setScreen(new AuctionScreen(parent));
                }).bounds(left,t+90,280,22).build());

                int[] ds={30,60,120,300,600}; String[] labels={"30s","1m","2m","5m","10m"};
                for(int i=0;i<ds.length;i++){
                    final int seconds=ds[i];
                    addRenderableWidget(Button.builder(Component.literal(labels[i]),b->{
                        data.auctionDurationSeconds=seconds;save();Minecraft.getInstance().gui.setScreen(new AuctionScreen(parent));
                    }).bounds(left+i*56,t+144,52,20).build());
                }
                addRenderableWidget(Button.builder(Component.literal("Start auction"),b->{
                    try{data.auctionMinBid=Math.max(0,Integer.parseInt(minBid.getValue().trim()));}catch(Exception ignored){}
                    data.auctionBids.clear();data.auctionHighestBid=0;data.auctionHighestPlayer="No bids yet";
                    data.auctionSold=false;data.auctionActive=true;
                    data.auctionEndTime=System.currentTimeMillis()+data.auctionDurationSeconds*1000L;
                    save();Minecraft.getInstance().gui.setScreen(new AuctionScreen(parent));
                }).bounds(left,t+226,280,26).build());

                Minecraft mc=Minecraft.getInstance();
                if(mc.player!=null){
                    int shown=0, size=mc.player.getInventory().getContainerSize();
                    for(int slot=0;slot<size&&shown<8;slot++){
                        var stack=mc.player.getInventory().getItem(slot);
                        if(stack.isEmpty())continue;
                        final String itemName=stack.getHoverName().getString();
                        final int count=stack.getCount();
                        int col=shown%2,row=shown/2;
                        String label=itemName.length()>15?itemName.substring(0,14)+"…":itemName;
                        addRenderableWidget(Button.builder(Component.literal(label+" x"+count),b->{
                            data.auctionItem=itemName;save();Minecraft.getInstance().gui.setScreen(new AuctionScreen(parent));
                        }).bounds(cx+22+col*150,t+78+row*27,144,22).build());
                        shown++;
                    }
                }
            } else {
                addRenderableWidget(Button.builder(Component.literal("+10s"),b->extend(10)).bounds(left,t+144,82,22).build());
                addRenderableWidget(Button.builder(Component.literal("+30s"),b->extend(30)).bounds(left+88,t+144,82,22).build());
                addRenderableWidget(Button.builder(Component.literal("+1m"),b->extend(60)).bounds(left+176,t+144,82,22).build());
                addRenderableWidget(Button.builder(Component.literal("End now"),b->finish()).bounds(left,t+178,132,22).build());
                addRenderableWidget(Button.builder(Component.literal("Cancel"),b->{
                    data.auctionActive=false;data.auctionBids.clear();data.auctionSold=false;save();
                    Minecraft.getInstance().gui.setScreen(new AuctionScreen(parent));
                }).bounds(left+140,t+178,132,22).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Back to Tools"),b->Minecraft.getInstance().gui.setScreen(parent))
                .bounds(left,t+278,160,22).build());
        }

        private void extend(int seconds){data.auctionEndTime+=seconds*1000L;save();}
        private void finish(){
            data.auctionActive=false;data.auctionSold=true;
            data.auctionWinner=data.auctionHighestBid>0?data.auctionHighestPlayer:"No bids";
            data.auctionSoldUntil=System.currentTimeMillis()+10000L;save();
            Minecraft.getInstance().gui.setScreen(new AuctionScreen(parent));
        }

        @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
            int cx=width/2,t=height/2-170,a=data.iceTheme?0xFF69D8FF:0xFFFF8126;
            g.fill(0,0,width,height,0x55000000);
            g.fill(cx-340,t,cx+340,t+340,0xEE160E0A);
            g.fill(cx-340,t,cx+340,t+2,a);
            g.fill(cx,t+42,cx+1,t+310,0x665B4635);
            drawFlowText(g,font,data.auctionSold?"AUCTION COMPLETE":data.auctionActive?"LIVE AUCTION":"NEW AUCTION",cx-318,t+18,0xFFFFFFFF,true);
            if(data.auctionSold){
                long elapsed=System.currentTimeMillis();
                int pulse=(int)(Math.sin(elapsed/180.0)*5)+12;
                g.fill(cx-310,t+82,cx+310,t+230,0x552A1608);
                g.fill(cx-310,t+82,cx-310+pulse,t+230,a);
                g.fill(cx+310-pulse,t+82,cx+310,t+230,a);
                drawFlowText(g,font,"★  SOLD  ★",cx-65,t+104,a,true);
                drawFlowText(g,font,data.auctionWinner,cx-130,t+142,0xFFFFFFFF,true);
                drawFlowText(g,font,"WINNING BID  $"+String.format(Locale.ROOT,"%.2f",data.auctionHighestBid),cx-145,t+174,data.textColor,true);
                drawFlowText(g,font,"This result closes in 10 seconds",cx-125,t+205,0xFFB9A28D,true);
                for(int i=0;i<18;i++){
                    int px=cx-280+(int)((i*47+elapsed/18)%560);
                    int py=t+65+(int)((i*31+elapsed/12)%190);
                    g.fill(px,py,px+4,py+4,(i%2==0)?a:0xFFFFD76A);
                }
            } else {
                var client=Minecraft.getInstance();
                if(client.player!=null){
                    for(int slot=0;slot<client.player.getInventory().getContainerSize();slot++){
                        var stack=client.player.getInventory().getItem(slot);
                        if(!stack.isEmpty()&&stack.getHoverName().getString().equals(data.auctionItem)){
                            g.fill(cx-318,t+52,cx-294,t+76,0xFFFF8126); break;
                        }
                    }
                }
                drawFlowText(g,font,"ITEM",cx-286,t+48,a,true);
                drawFlowText(g,font,data.auctionItem,cx-286,t+68,0xFFFFFFFF,true);
                if(data.auctionActive){
                    long left=Math.max(0,(data.auctionEndTime-System.currentTimeMillis()+999)/1000);
                    drawFlowText(g,font,"TIME LEFT",cx-318,t+105,a,true);
                    drawFlowText(g,font,String.format(Locale.ROOT,"%02d:%02d",left/60,left%60),cx-318,t+125,0xFFFFFFFF,true);
                    drawFlowText(g,font,"HIGHEST BID",cx-318,t+190,data.textColor,true);
                    drawFlowText(g,font,data.auctionHighestPlayer+"  $"+String.format(Locale.ROOT,"%.2f",data.auctionHighestBid),cx-318,t+210,0xFFFFFFFF,true);
                    drawFlowText(g,font,"BIDDERS / PAYMENTS",cx+22,t+48,a,true);
                    int row=0;
                    for(var entry:data.auctionBids.entrySet()){
                        if(row>=8)break;
                        drawFlowText(g,font,entry.getKey()+"   $"+formatAuctionMoney(entry.getValue()),cx+22,t+78+row*25,data.textColor,true);
                        row++;
                    }
                } else {
                    drawFlowText(g,font,"DURATION",cx-318,t+124,data.textColor,true);
                    drawFlowText(g,font,formatAuctionDuration(data.auctionDurationSeconds),cx-318,t+168,0xFFFFFFFF,true);
                    drawFlowText(g,font,"MINIMUM BID",cx-318,t+176,data.textColor,true);
                    drawFlowText(g,font,"CHOOSE FROM YOUR INVENTORY",cx+22,t+48,a,true);
                    drawFlowText(g,font,"Select an item on the right",cx+22,t+63,0xFFB9A28D,true);
                }
            }
            super.extractRenderState(g,mx,my,delta);
        }

        @Override public void tick(){
            if(data.auctionActive&&System.currentTimeMillis()>=data.auctionEndTime)finish();
            if(data.auctionSold&&System.currentTimeMillis()>data.auctionSoldUntil){
                data.auctionSold=false;data.auctionWinner="";data.auctionHighestBid=0;data.auctionHighestPlayer="No bids yet";save();
                Minecraft.getInstance().gui.setScreen(parent);
            }
        }
        @Override public void onClose(){Minecraft.getInstance().gui.setScreen(parent);}
    } 

}
