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
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("streamer-counter-vip.json");
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
        int textColor = 0xFFFFC85A;
        int hudX = -1;
        int hudY = 8;
        float hudScale = 1.0f;
        boolean iceTheme = false;
        List<ScreenText> screenTexts = new ArrayList<>();
    }

    private static class ScreenText {
        String text = "Your text";
        int x = 80, y = 80, color = 0xFFFFFFFF, size = 16;
        String font = "default";
        boolean visible = true, bold = false, italic = false, shadow = true;
    }

    @Override
    public void onInitializeClient() {
        load();
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("streamercounter_vip", "general"));
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.streamercounter_vip.open_menu", InputConstants.Type.KEYSYM,
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
                    client.setScreen(new CounterMenuScreen(client.screen, 0));
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
    }

    private static void drawHud(GuiGraphicsExtractor graphics, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        List<String> names = hudNames();
        int width = hudWidth();
        int height = hudHeight();
        graphics.fill(x, y, x + width, y + height, 0x990B111A);
        int accent = data.iceTheme ? 0xFF69D8FF : 0xFFE8B64D;
        graphics.fill(x, y, x + width, y + 2, accent);
        drawFlowText(graphics, mc.font, "STREAMERS VIP", x + 6, y + 6, data.textColor, false);
        int rowY = y + 20;
        for (String key : names) {
            String name = displayName(key);
            String count = String.valueOf(data.counts.getOrDefault(key, 0));
            drawFlowText(graphics, mc.font, name, x + 6, rowY, data.textColor, false);
            drawFlowText(graphics, mc.font, count, x + width - 7 - mc.font.width(count),
                    rowY, data.textColor, false);
            rowY += 11;
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

    // The highlight moves across individual glyphs for a smooth, flowing text effect.
    private static void drawFlowText(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                                     String text, int x, int y, int baseColor, boolean shadow) {
        int cursorX = x;
        int glyphIndex = 0;
        double phase = (System.currentTimeMillis() % 2400L) / 2400.0 * Math.PI * 2.0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String glyph = new String(Character.toChars(codePoint));
            float wave = (float) (0.5 + 0.5 * Math.sin(glyphIndex * 0.72 - phase));
            int color = blendColor(baseColor, 0xFFFFFFFF, 0.12f + wave * 0.48f);
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
                            Minecraft.getInstance().setScreen(parent);
                        }).bounds(firstX + column * 118, firstY + row * 28, 108, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Back"), button ->
                    Minecraft.getInstance().setScreen(parent))
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

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                Data loaded = GSON.fromJson(Files.readString(FILE), Data.class);
                if (loaded != null) {
                    if (loaded.counts == null) loaded.counts = new HashMap<>();
                    if (loaded.streamerEnabled == null) loaded.streamerEnabled = new HashMap<>();
                    if (loaded.displayNames == null) loaded.displayNames = new HashMap<>();
                    if (loaded.screenTexts == null) loaded.screenTexts = new ArrayList<>();
                    if (loaded.screenTexts == null) loaded.screenTexts = new ArrayList<>();
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
                    Minecraft.getInstance().setScreen(new HudColorScreen(this)))
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
            drawHud(graphics, visibleHudX(width), visibleHudY(height));
            String size = "Size: " + Math.round(data.hudScale * 100) + "%";
            drawFlowText(graphics, font, size, width / 2 - font.width(size) / 2,
                    height - 110, data.textColor, true);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }

        @Override
        public void onClose() {
            draggingHud = false;
            save();
            Minecraft.getInstance().setScreen(parent);
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
            }).bounds(x, 212, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Size +"), b -> {
                ScreenText t=current(); if(t!=null){t.size=Math.min(96,t.size+2);save();}
            }).bounds(x+74, 212, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Font / Style"),b->{
                ScreenText t=current(); if(t!=null){String[] styles={"default","bold","italic","bold_italic","flow"};int n=0;for(;n<styles.length;n++)if(styles[n].equals(t.font))break;t.font=styles[(n+1)%styles.length];t.bold=t.font.equals("bold")||t.font.equals("bold_italic");t.italic=t.font.equals("italic")||t.font.equals("bold_italic");save();clearWidgets();init();}
            }).bounds(x, 240, 146, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Color"),b->{
                ScreenText t=current();if(t!=null){int[] colors={0xFFFFFFFF,0xFFFFD54F,0xFF69D8FF,0xFFFF6B6B,0xFF78E08F};int n=0;for(;n<colors.length;n++)if(colors[n]==t.color)break;t.color=colors[(n+1)%colors.length];save();}
            }).bounds(x, 268, 68, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose())
                .bounds(x+74, 268, 68, 20).build());
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
            graphics.text(font,"SIZE / FONT STYLE",right+16,top+105,0xFFFFD54F,true);
            graphics.text(font,"COLOR",right+16,top+185,0xFFFFD54F,true);
            if(data.screenTexts!=null) for(ScreenText item:data.screenTexts) if(item!=null&&item.visible&&item.text!=null&&!item.text.isEmpty()) {
                float scale=Math.max(8,Math.min(96,item.size))/9.0f;
                graphics.pose().pushMatrix(); graphics.pose().translate(item.x,item.y); graphics.pose().scale(scale,scale);
                
                if("flow".equals(item.font)){
                    int[] flow={0xFFFF5C8A,0xFFFFB347,0xFFFFFF66,0xFF75E08A,0xFF62D9FF,0xFFB58CFF};int px=0;
                    for(int ci=0;ci<item.text.length();ci++){String ch=String.valueOf(item.text.charAt(ci));graphics.text(font,ch,px,0,flow[ci%flow.length],item.shadow);px+=font.width(ch);}
                }else{Component styled=Component.literal(item.text).withStyle(st->st.withBold(item.bold).withItalic(item.italic));graphics.text(font,styled,0,0,item.color,item.shadow);} graphics.pose().popMatrix();
            }
            ScreenText chosen=current();
            if(chosen!=null) graphics.text(font,"Selected: "+(chosen.text==null?"":chosen.text)+"   |   Size: "+chosen.size,right+16,top+330,0xFFB8C7D9,true;
            else graphics.text(font,"Add a text to begin editing.",right+16,top+330,0xFFB8C7D9,true;
            graphics.text(font,"Drag visible text to move it.",right+16,height-34,0xFF6C9BCB,true);
            super.extractRenderState(graphics,mx,my,delta);

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
            Minecraft.getInstance().setScreen(new CounterMenuScreen(parent, newPage));
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

            int halfWidth = (buttonWidth - 12) / 3;
            addRenderableWidget(Button.builder(hudButtonText(), button -> {
                data.showHud = !data.showHud;
                save();
                button.setMessage(hudButtonText());
            }).bounds(left, height - 98, halfWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Edit HUD"), button ->
                    Minecraft.getInstance().setScreen(new HudEditScreen(this)))
                    .bounds(left + halfWidth + 6, height - 98, halfWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Text on Screen"), b -> Minecraft.getInstance().setScreen(new TextOnScreenScreen(this)))
                    .bounds(left + 2 * (halfWidth + 6), height - 98, halfWidth, 20).build());
            addRenderableWidget(Button.builder(themeButtonText(), button -> {
                data.iceTheme = !data.iceTheme;
                save();
                button.setMessage(themeButtonText());
            }).bounds(left, height - 70, halfWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Reset All"), button -> {
                data.counts.clear();
                data.lastName = null;
                pending = null;
                save();
                reopen(0);
            }).bounds(left + halfWidth + 6, height - 70, halfWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                    .bounds(left, height - 42, buttonWidth, 20).build());
        }

        private Component streamerButtonText(String name, int count) {
            boolean enabled = data.streamerEnabled.getOrDefault(name, true);
            return Component.literal(name + "  |  " + (enabled ? "ON" : "OFF") + "  |  " + count + " TPs");
        }

        private int menuAccentColor() {
            return data.iceTheme ? 0xFF69D8FF : 0xFFFFD54F;
        }

        private int menuTextColor() {
            return data.iceTheme ? 0xFF9BE8FF : 0xFFFFD878;
        }

        private int menuPanelColor() {
            return data.iceTheme ? 0xFF101F2B : 0xFF241D12;
        }

        private Component themeButtonText() {
            return Component.literal("VIP Theme: " + (data.iceTheme ? "ICE" : "GOLD"));
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
            graphics.fill(left - 3, top - 3, right + 3, bottom + 3, menuAccentColor());
            graphics.fill(left, top, right, bottom, menuPanelColor());
            graphics.fill(left + 2, top + 2, right - 2, top + 5, menuAccentColor());
            String heading = "Streamer Counter VIP";
            drawFlowText(graphics, font, heading, width / 2 - font.width(heading) / 2,
                    top + 16, menuTextColor(), true);
            String subtitle = "Enable or disable streamers individually";
            drawFlowText(graphics, font, subtitle, width / 2 - font.width(subtitle) / 2,
                    top + 40, menuTextColor(), true);
            if (streamerNames().isEmpty()) {
                String line1 = "No streamers detected yet.";
                String line2 = "Streamers will appear here after /tpahere <name>.";
                graphics.text(font, line1, width / 2 - font.width(line1) / 2, top + 78, menuTextColor(), true);
                graphics.text(font, line2, width / 2 - font.width(line2) / 2, top + 96, menuTextColor(), true);
            }
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            String credit = "VIP Edition | Made by AlpBey3503";
            drawFlowText(graphics, font, credit, width / 2 - font.width(credit) / 2,
                    height - 14, menuTextColor(), true);
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreen(parent);
        }
    }
}
