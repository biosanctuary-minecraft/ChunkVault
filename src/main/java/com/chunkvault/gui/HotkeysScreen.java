package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;

public class HotkeysScreen extends Screen {

    private static final int ROW_H = 28;
    private static final int PANEL_MAX_W = 460;
    private static final int KEY_BTN_W = 112;
    private static final int RESET_BTN_W = 22;

    private final Screen parent;
    private List<ChunkVaultClient.HotkeyEntry> entries = List.of();
    private KeyMapping listening;

    private int listX0;
    private int listY0;
    private int listX1;
    private int listY1;
    private int scroll;

    public HotkeysScreen(Screen parent) {
        super(Lang.c("hotkeys.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        entries = ChunkVaultClient.hotkeys();
        int panelW = Math.min(PANEL_MAX_W, this.width - 32);
        listX0 = (this.width - panelW) / 2;
        listX1 = listX0 + panelW;
        listY0 = 46;
        listY1 = this.height - 44;
        clampScroll();

        int btnY = this.height - 30;
        int half = (panelW - 8) / 2;
        this.addRenderableWidget(Button.builder(Lang.c("hotkeys.reset_all"), b -> resetAll())
                .bounds(listX0, btnY, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("common.back"), b -> this.onClose())
                .bounds(listX0 + half + 8, btnY, half, 20)
                .build());
    }

    private void resetAll() {
        listening = null;
        for (ChunkVaultClient.HotkeyEntry entry : entries) {
            entry.mapping().setKey(entry.mapping().getDefaultKey());
        }
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    private int maxScroll() {
        int contentH = entries.size() * ROW_H;
        return Math.max(0, contentH - (listY1 - listY0));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    private boolean hasConflict(KeyMapping target) {
        if (target.isUnbound()) {
            return false;
        }
        String keyStr = target.saveString();
        int count = 0;
        for (ChunkVaultClient.HotkeyEntry e : entries) {
            if (!e.mapping().isUnbound() && keyStr.equals(e.mapping().saveString())) {
                count++;
                if (count > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private String keyBadgeText(ChunkVaultClient.HotkeyEntry entry) {
        KeyMapping m = entry.mapping();
        if (m == listening) {
            return Lang.s("hotkeys.press_key");
        }
        if (m.isUnbound()) {
            return Lang.s("hotkeys.unbound");
        }
        if (entry.comboDefault() && m.isDefault()) {
            return "C + " + m.getTranslatedKeyMessage().getString();
        }
        return m.getTranslatedKeyMessage().getString();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);
        g.centeredText(this.font, Lang.s("hotkeys.title"), this.width / 2, 12, 0xFFFFFFFF);
        g.centeredText(this.font, Lang.s("hotkeys.hint"), this.width / 2, 26, 0xFF9AA0B0);

        g.fill(listX0 - 1, listY0 - 1, listX1 + 1, listY1 + 1, 0xFF3A3A48);
        g.fill(listX0, listY0, listX1, listY1, 0xFF14161C);

        int maxS = maxScroll();
        int contentRight = maxS > 0 ? listX1 - 8 : listX1;

        g.enableScissor(listX0, listY0, listX1, listY1);
        for (int i = 0; i < entries.size(); i++) {
            int ry0 = listY0 + i * ROW_H - scroll;
            int ry1 = ry0 + ROW_H;
            if (ry1 <= listY0 || ry0 >= listY1) {
                continue;
            }
            ChunkVaultClient.HotkeyEntry entry = entries.get(i);
            KeyMapping m = entry.mapping();
            boolean isListening = m == listening;
            boolean rowHover = mouseX >= listX0 && mouseX < contentRight
                    && mouseY >= Math.max(listY0, ry0) && mouseY < Math.min(listY1, ry1);

            int rowBg = isListening ? 0xFF282418 : (rowHover ? 0xFF262A34 : ((i & 1) == 0 ? 0xFF1C1F27 : 0xFF181B22));
            g.fill(listX0, ry0, contentRight, ry1 - 1, rowBg);
            g.fill(listX0, ry0, listX0 + 3, ry1 - 1, entry.accentColor());

            int resetX1 = contentRight - 6;
            int resetX0 = resetX1 - RESET_BTN_W;
            int keyX1 = resetX0 - 4;
            int keyX0 = keyX1 - KEY_BTN_W;
            int btnY0 = ry0 + 4;
            int btnY1 = ry1 - 5;

            g.text(this.font, Lang.s(entry.labelKey()), listX0 + 11, ry0 + 5, 0xFFFFFFFF, true);
            String desc = Lang.s(entry.descKey());
            int maxDescW = keyX0 - (listX0 + 16);
            if (maxDescW > 20) {
                if (this.font.width(desc) > maxDescW) {
                    desc = this.font.plainSubstrByWidth(desc, Math.max(10, maxDescW - 8)) + "…";
                }
                g.text(this.font, desc, listX0 + 11, ry0 + 16, 0xFF8890A2, false);
            }

            boolean keyHover = rowHover && mouseX >= keyX0 && mouseX < keyX1 && mouseY >= btnY0 && mouseY < btnY1;
            boolean conflict = hasConflict(m);
            int keyBorder = isListening ? 0xFFFFD070 : (conflict ? 0xFFFF8830 : (keyHover ? 0xFFFFFFFF : 0xFF4A5064));
            int keyBg = isListening ? 0xFF5A4618 : (keyHover ? 0xFF343A4A : 0xFF1E222C);
            g.fill(keyX0, btnY0, keyX1, btnY1, keyBorder);
            g.fill(keyX0 + 1, btnY0 + 1, keyX1 - 1, btnY1 - 1, keyBg);

            String badge = keyBadgeText(entry);
            int badgeCol = isListening ? 0xFFFFE080 : (m.isUnbound() ? 0xFF7A8090 : (conflict ? 0xFFFFB060 : 0xFFFFFFFF));
            g.centeredText(this.font, badge, (keyX0 + keyX1) / 2, btnY0 + 5, badgeCol);

            boolean canReset = !m.isDefault();
            boolean resetHover = canReset && rowHover && mouseX >= resetX0 && mouseX < resetX1
                    && mouseY >= btnY0 && mouseY < btnY1;
            int resetBorder = canReset ? (resetHover ? 0xFFFFFFFF : 0xFF4A5064) : 0xFF282C36;
            int resetBg = canReset ? (resetHover ? 0xFF3A4050 : 0xFF222630) : 0xFF181A22;
            g.fill(resetX0, btnY0, resetX1, btnY1, resetBorder);
            g.fill(resetX0 + 1, btnY0 + 1, resetX1 - 1, btnY1 - 1, resetBg);
            g.centeredText(this.font, "↺", (resetX0 + resetX1) / 2, btnY0 + 5, canReset ? 0xFFFFFFFF : 0xFF4E5362);
        }
        g.disableScissor();

        if (maxS > 0) {
            int trackX0 = listX1 - 6;
            int trackH = listY1 - listY0;
            g.fill(trackX0, listY0, listX1, listY1, 0xFF0E1015);
            int thumbH = Math.max(20, (int) ((long) trackH * trackH / (entries.size() * ROW_H)));
            int thumbY = listY0 + (int) ((long) (trackH - thumbH) * scroll / maxS);
            g.fill(trackX0 + 1, thumbY, listX1 - 1, thumbY + thumbH, 0xFF5A6074);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening != null) {
            if (event.isEscape()) {
                listening.setKey(InputConstants.UNKNOWN);
            } else {
                listening.setKey(InputConstants.getKey(event));
            }
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
            listening = null;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (listening != null) {
            listening.setKey(InputConstants.Type.MOUSE.getOrCreate(event.button()));
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
            listening = null;
            return true;
        }

        double mx = event.x();
        double my = event.y();
        int maxS = maxScroll();
        int contentRight = maxS > 0 ? listX1 - 8 : listX1;

        if (mx >= listX0 && mx < contentRight && my >= listY0 && my < listY1) {
            int idx = (int) ((my - listY0 + scroll) / ROW_H);
            if (idx >= 0 && idx < entries.size()) {
                int ry0 = listY0 + idx * ROW_H - scroll;
                int ry1 = ry0 + ROW_H;
                int btnY0 = ry0 + 4;
                int btnY1 = ry1 - 5;
                int resetX1 = contentRight - 6;
                int resetX0 = resetX1 - RESET_BTN_W;
                int keyX1 = resetX0 - 4;
                int keyX0 = keyX1 - KEY_BTN_W;
                KeyMapping m = entries.get(idx).mapping();

                if (my >= btnY0 && my < btnY1) {
                    if (mx >= keyX0 && mx < keyX1) {
                        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                            listening = m;
                            return true;
                        }
                        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                            m.setKey(m.getDefaultKey());
                            KeyMapping.resetMapping();
                            Minecraft.getInstance().options.save();
                            return true;
                        }
                    }
                    if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                            && mx >= resetX0 && mx < resetX1 && !m.isDefault()) {
                        m.setKey(m.getDefaultKey());
                        KeyMapping.resetMapping();
                        Minecraft.getInstance().options.save();
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && scrollY != 0) {
            scroll -= (int) Math.round(scrollY * 22);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        listening = null;
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
