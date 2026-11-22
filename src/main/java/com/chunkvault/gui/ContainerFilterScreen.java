package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.ChunkVaultSettings;
import com.chunkvault.state.ChunkVaultSettings.ContainerCategory;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Map;

public class ContainerFilterScreen extends Screen {

    private static final int ROW_H = 28;
    private static final int PANEL_MAX_W = 440;
    private static final int BADGE_W = 72;

    private final Screen parent;
    private final Map<ContainerCategory, ItemStack> icons = new EnumMap<>(ContainerCategory.class);
    private final ContainerCategory[] categories = ContainerCategory.values();

    private int listX0;
    private int listY0;
    private int listX1;
    private int listY1;
    private int scroll;
    private int btnY0;
    private int btnY1;
    private int enX0;
    private int enX1;
    private int disX0;
    private int disX1;
    private int backX0;
    private int backX1;

    public ContainerFilterScreen(Screen parent) {
        super(Lang.c("containers.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int panelW = Math.min(PANEL_MAX_W, this.width - 32);
        listX0 = (this.width - panelW) / 2;
        listX1 = listX0 + panelW;
        listY0 = 44;
        listY1 = this.height - 44;
        clampScroll();

        btnY0 = this.height - 32;
        btnY1 = btnY0 + 22;
        int w3 = (panelW - 12) / 3;
        enX0 = listX0;
        enX1 = enX0 + w3;
        disX0 = enX1 + 6;
        disX1 = disX0 + w3;
        backX0 = disX1 + 6;
        backX1 = listX1;
    }

    private ItemStack iconFor(ContainerCategory cat) {
        return icons.computeIfAbsent(cat, c -> switch (c.key()) {
            case "shulkers" -> new ItemStack(Items.SHULKER_BOX);
            case "hoppers" -> new ItemStack(Items.HOPPER);
            case "dispensers" -> new ItemStack(Items.DISPENSER);
            case "furnaces" -> new ItemStack(Items.FURNACE);
            case "brewing" -> new ItemStack(Items.BREWING_STAND);
            case "crafters" -> new ItemStack(Items.CRAFTER);
            default -> new ItemStack(Items.CHEST);
        });
    }

    private static int accentFor(ContainerCategory cat) {
        return switch (cat.key()) {
            case "shulkers" -> 0xFFC864FF;
            case "hoppers" -> 0xFF7A889A;
            case "dispensers" -> 0xFF98A2B0;
            case "furnaces" -> 0xFFFF8840;
            case "brewing" -> 0xFF55CCFF;
            case "crafters" -> 0xFF70D890;
            default -> 0xFFFFB830;
        };
    }

    private int maxScroll() {
        return Math.max(0, categories.length * ROW_H - (listY1 - listY0));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);
        g.centeredText(this.font, Lang.s("containers.title"), this.width / 2, 12, 0xFFFFFFFF);
        g.centeredText(this.font, Lang.s("containers.subtitle"), this.width / 2, 26, 0xFF9AA0B0);

        g.fill(listX0 - 1, listY0 - 1, listX1 + 1, listY1 + 1, 0xFF3A3A48);
        g.fill(listX0, listY0, listX1, listY1, 0xFF14161C);

        int maxS = maxScroll();
        int contentRight = maxS > 0 ? listX1 - 8 : listX1;
        ChunkVaultSettings s = ChunkVaultSettings.get();

        g.enableScissor(listX0, listY0, listX1, listY1);
        for (int i = 0; i < categories.length; i++) {
            int ry0 = listY0 + i * ROW_H - scroll;
            int ry1 = ry0 + ROW_H;
            if (ry1 <= listY0 || ry0 >= listY1) {
                continue;
            }
            ContainerCategory cat = categories[i];
            boolean on = s.isContainerEnabled(cat);
            boolean hover = mouseX >= listX0 && mouseX < contentRight
                    && mouseY >= Math.max(listY0, ry0) && mouseY < Math.min(listY1, ry1);
            int bg = hover ? 0xFF262A34 : ((i & 1) == 0 ? 0xFF1C1F27 : 0xFF181B22);
            g.fill(listX0, ry0, contentRight, ry1 - 1, bg);
            g.fill(listX0, ry0, listX0 + 3, ry1 - 1, on ? accentFor(cat) : 0xFF3A3F4D);

            g.item(iconFor(cat), listX0 + 10, ry0 + 6);
            g.text(this.font, Lang.s("containers." + cat.key()), listX0 + 32, ry0 + 10,
                    on ? 0xFFFFFFFF : 0xFF8890A2, true);

            int bx1 = contentRight - 8;
            int bx0 = bx1 - BADGE_W;
            int by0 = ry0 + 4;
            int by1 = ry1 - 5;
            int border = hover ? 0xFFFFFFFF : (on ? 0xFF50C878 : 0xFF4A5064);
            int pBg = on ? (hover ? 0xFF2D6B48 : 0xFF1F5236) : (hover ? 0xFF343A4A : 0xFF1E222C);
            g.fill(bx0, by0, bx1, by1, border);
            g.fill(bx0 + 1, by0 + 1, bx1 - 1, by1 - 1, pBg);
            g.centeredText(this.font, on ? Lang.s("common.on") : Lang.s("common.off"),
                    (bx0 + bx1) / 2, by0 + 5, on ? 0xFF90FFB0 : 0xFFCCCCCC);
        }
        g.disableScissor();

        if (maxS > 0) {
            int trackX0 = listX1 - 6;
            int trackH = listY1 - listY0;
            g.fill(trackX0, listY0, listX1, listY1, 0xFF0E1015);
            int thumbH = Math.max(20, (int) ((long) trackH * trackH / (categories.length * ROW_H)));
            int thumbY = listY0 + (int) ((long) (trackH - thumbH) * scroll / maxS);
            g.fill(trackX0 + 1, thumbY, listX1 - 1, thumbY + thumbH, 0xFF5A6074);
        }

        drawStyledButton(g, enX0, enX1, btnY0, btnY1, Lang.s("containers.enable_all"), mouseX, mouseY,
                0xFF50C878, 0xFF1F5236, 0xFF2D6B48, 0xFF90FFB0);
        drawStyledButton(g, disX0, disX1, btnY0, btnY1, Lang.s("containers.disable_all"), mouseX, mouseY,
                0xFF9A3838, 0xFF4A2020, 0xFF6B2C2C, 0xFFFFB0B0);
        drawStyledButton(g, backX0, backX1, btnY0, btnY1, Lang.s("common.back"), mouseX, mouseY,
                0xFF4A5064, 0xFF1E222C, 0xFF343A4A, 0xFFFFFFFF);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawStyledButton(GuiGraphicsExtractor g, int x0, int x1, int y0, int y1,
                                  String label, int mouseX, int mouseY,
                                  int border, int bg, int bgHover, int textColor) {
        boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        g.fill(x0, y0, x1, y1, hover ? 0xFFFFFFFF : border);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, hover ? bgHover : bg);
        g.centeredText(this.font, label, (x0 + x1) / 2, y0 + 7, textColor);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x();
        double my = event.y();
        int contentRight = maxScroll() > 0 ? listX1 - 8 : listX1;

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && my >= btnY0 && my < btnY1) {
            if (mx >= enX0 && mx < enX1) {
                UiSound.click();
                ChunkVaultSettings.get().setAllContainers(true);
                return true;
            }
            if (mx >= disX0 && mx < disX1) {
                UiSound.click();
                ChunkVaultSettings.get().setAllContainers(false);
                return true;
            }
            if (mx >= backX0 && mx < backX1) {
                UiSound.click();
                this.onClose();
                return true;
            }
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && mx >= listX0 && mx < contentRight && my >= listY0 && my < listY1) {
            int idx = (int) ((my - listY0 + scroll) / ROW_H);
            if (idx >= 0 && idx < categories.length) {
                UiSound.click();
                ContainerCategory cat = categories[idx];
                ChunkVaultSettings s = ChunkVaultSettings.get();
                s.setContainerEnabled(cat, !s.isContainerEnabled(cat));
                return true;
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
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
