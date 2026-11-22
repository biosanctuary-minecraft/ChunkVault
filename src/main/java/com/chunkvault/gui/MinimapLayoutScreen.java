package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.ChunkVaultSettings;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.Locale;

public class MinimapLayoutScreen extends Screen {

    private final Screen parent;
    private boolean draggingMove;
    private boolean draggingResize;
    private double dragOffX;
    private double dragOffY;
    private int resizeStartSize;
    private double resizeStartX;
    private double resizeStartY;

    private int barX0;
    private int barY0;
    private int barX1;
    private int barY1;
    private int sizeMinusX0;
    private int sizeMinusX1;
    private int sizePlusX0;
    private int sizePlusX1;
    private int zoomMinusX0;
    private int zoomMinusX1;
    private int zoomPlusX0;
    private int zoomPlusX1;
    private int resetX0;
    private int resetX1;
    private int doneX0;
    private int doneX1;
    private int btnY0;
    private int btnY1;

    public MinimapLayoutScreen(Screen parent) {
        super(Lang.c("minimap.layout.title"));
        this.parent = parent;
    }

    private static String formatZoom(double z) {
        if (Math.abs(z - Math.rint(z)) < 0.001) {
            return ((int) Math.rint(z)) + "x";
        }
        if (Math.abs(z * 10.0 - Math.rint(z * 10.0)) < 0.001) {
            return String.format(Locale.ROOT, "%.1fx", z);
        }
        return String.format(Locale.ROOT, "%.2fx", z);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x55080A0F);

        ChunkVaultSettings s = ChunkVaultSettings.get();
        int mapSize = s.minimapSize();
        int barH = MinimapOverlay.currentBarHeight(this.font, mapSize);
        int x0 = MinimapOverlay.resolveX(this.width, mapSize, s.minimapX());
        int y0 = MinimapOverlay.resolveY(this.height, mapSize, barH, s.minimapY());
        int x1 = x0 + mapSize;
        int totalY1 = y0 + mapSize + barH;

        MinimapOverlay.renderAt(g, partialTick, x0, y0, mapSize, s.minimapZoom(), true);

        int handleSize = 8;
        boolean overHandle = mouseX >= x1 - handleSize && mouseX <= x1 + 2
                && mouseY >= totalY1 - handleSize && mouseY <= totalY1 + 2;
        int handleCol = (draggingResize || overHandle) ? 0xFFFFFFFF : 0xFF38D8FF;
        g.fill(x1 - handleSize, totalY1 - 2, x1 + 1, totalY1 + 1, handleCol);
        g.fill(x1 - 2, totalY1 - handleSize, x1 + 1, totalY1 + 1, handleCol);

        int barW = Math.min(this.width - 16, 390);
        int barHPanel = 40;
        barX0 = (this.width - barW) / 2;
        barX1 = barX0 + barW;
        barY1 = this.height - 12;
        barY0 = barY1 - barHPanel;

        g.fill(barX0 - 1, barY0 - 1, barX1 + 1, barY1 + 1, 0xFF3A3A48);
        g.fill(barX0, barY0, barX1, barY1, 0xEB14161C);

        g.centeredText(this.font, Lang.s("minimap.layout.hint"), this.width / 2, barY0 + 5, 0xFFB8C0D0);

        btnY0 = barY0 + 18;
        btnY1 = barY1 - 5;
        int x = barX0 + 8;

        String sizeLbl = Lang.s("minimap.layout.size");
        g.text(this.font, sizeLbl, x, btnY0 + 4, 0xFF9AA0B0, true);
        x += this.font.width(sizeLbl) + 4;

        sizeMinusX0 = x;
        sizeMinusX1 = x + 16;
        drawBtn(g, sizeMinusX0, sizeMinusX1, btnY0, btnY1, "-", mouseX, mouseY,
                mapSize > ChunkVaultSettings.MINIMAP_MIN_SIZE, 0xFF243858, 0xFF365585);
        x = sizeMinusX1 + 2;

        String sizeVal = mapSize + "px";
        int sizeValW = Math.max(34, this.font.width(sizeVal) + 4);
        g.centeredText(this.font, sizeVal, x + sizeValW / 2, btnY0 + 4, 0xFFFFFFFF);
        x += sizeValW + 2;

        sizePlusX0 = x;
        sizePlusX1 = x + 16;
        drawBtn(g, sizePlusX0, sizePlusX1, btnY0, btnY1, "+", mouseX, mouseY,
                mapSize < ChunkVaultSettings.MINIMAP_MAX_SIZE, 0xFF243858, 0xFF365585);
        x = sizePlusX1 + 10;

        String zoomLbl = Lang.s("minimap.layout.zoom");
        g.text(this.font, zoomLbl, x, btnY0 + 4, 0xFF9AA0B0, true);
        x += this.font.width(zoomLbl) + 4;

        zoomMinusX0 = x;
        zoomMinusX1 = x + 16;
        drawBtn(g, zoomMinusX0, zoomMinusX1, btnY0, btnY1, "-", mouseX, mouseY,
                s.minimapZoom() > ChunkVaultSettings.MINIMAP_ZOOMS[0] + 0.001, 0xFF243858, 0xFF365585);
        x = zoomMinusX1 + 2;

        String zoomVal = formatZoom(s.minimapZoom());
        int zoomValW = Math.max(30, this.font.width(zoomVal) + 4);
        g.centeredText(this.font, zoomVal, x + zoomValW / 2, btnY0 + 4, 0xFFFFFFFF);
        x += zoomValW + 2;

        zoomPlusX0 = x;
        zoomPlusX1 = x + 16;
        drawBtn(g, zoomPlusX0, zoomPlusX1, btnY0, btnY1, "+", mouseX, mouseY,
                s.minimapZoom() < ChunkVaultSettings.MINIMAP_ZOOMS[ChunkVaultSettings.MINIMAP_ZOOMS.length - 1] - 0.001,
                0xFF243858, 0xFF365585);

        String doneTxt = Lang.s("common.done");
        int doneW = this.font.width(doneTxt) + 12;
        doneX1 = barX1 - 8;
        doneX0 = doneX1 - doneW;
        drawBtn(g, doneX0, doneX1, btnY0, btnY1, doneTxt, mouseX, mouseY, true, 0xFF1F5236, 0xFF2D6B48);

        String resetTxt = Lang.s("map.filter.reset");
        int resetW = this.font.width(resetTxt) + 10;
        resetX1 = doneX0 - 4;
        resetX0 = resetX1 - resetW;
        drawBtn(g, resetX0, resetX1, btnY0, btnY1, resetTxt, mouseX, mouseY, true, 0xFF4A2626, 0xFF6B3434);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawBtn(GuiGraphicsExtractor g, int x0, int x1, int y0, int y1,
                         String label, int mouseX, int mouseY, boolean enabled, int bg, int bgHover) {
        boolean hover = enabled && mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        g.fill(x0, y0, x1, y1, !enabled ? 0xFF282C36 : (hover ? 0xFFFFFFFF : 0xFF4A5064));
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, !enabled ? 0xFF181A22 : (hover ? bgHover : bg));
        g.centeredText(this.font, label, (x0 + x1) / 2, y0 + 4, enabled ? 0xFFFFFFFF : 0xFF555B6A);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(event, isDoubleClick);
        }
        double mx = event.x();
        double my = event.y();
        ChunkVaultSettings s = ChunkVaultSettings.get();

        if (my >= btnY0 && my < btnY1) {
            if (mx >= sizeMinusX0 && mx < sizeMinusX1) {
                UiSound.click();
                s.stepMinimapSize(-1);
                return true;
            }
            if (mx >= sizePlusX0 && mx < sizePlusX1) {
                UiSound.click();
                s.stepMinimapSize(1);
                return true;
            }
            if (mx >= zoomMinusX0 && mx < zoomMinusX1) {
                UiSound.click();
                s.stepMinimapZoom(-1);
                return true;
            }
            if (mx >= zoomPlusX0 && mx < zoomPlusX1) {
                UiSound.click();
                s.stepMinimapZoom(1);
                return true;
            }
            if (mx >= resetX0 && mx < resetX1) {
                UiSound.click();
                s.resetMinimapLayout();
                return true;
            }
            if (mx >= doneX0 && mx < doneX1) {
                UiSound.click();
                this.onClose();
                return true;
            }
        }

        if (mx >= barX0 && mx < barX1 && my >= barY0 && my < barY1) {
            return true;
        }

        int mapSize = s.minimapSize();
        int barH = MinimapOverlay.currentBarHeight(this.font, mapSize);
        int x0 = MinimapOverlay.resolveX(this.width, mapSize, s.minimapX());
        int y0 = MinimapOverlay.resolveY(this.height, mapSize, barH, s.minimapY());
        int x1 = x0 + mapSize;
        int totalY1 = y0 + mapSize + barH;

        if (mx >= x1 - 10 && mx <= x1 + 4 && my >= totalY1 - 10 && my <= totalY1 + 4) {
            draggingResize = true;
            resizeStartSize = mapSize;
            resizeStartX = mx;
            resizeStartY = my;
            return true;
        }

        if (mx >= x0 - 2 && mx <= x1 + 2 && my >= y0 - 2 && my <= totalY1 + 2) {
            draggingMove = true;
            dragOffX = mx - x0;
            dragOffY = my - y0;
            return true;
        }

        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            ChunkVaultSettings s = ChunkVaultSettings.get();
            if (draggingResize) {
                int delta = (int) Math.round(Math.max(event.x() - resizeStartX, event.y() - resizeStartY));
                s.setMinimapSize(resizeStartSize + delta);
                return true;
            }
            if (draggingMove) {
                int mapSize = s.minimapSize();
                int barH = MinimapOverlay.currentBarHeight(this.font, mapSize);
                int totalH = mapSize + barH;
                int availW = Math.max(1, this.width - mapSize - MinimapOverlay.PAD * 2);
                int availH = Math.max(1, this.height - totalH - MinimapOverlay.PAD * 2);
                double nx = (event.x() - dragOffX - MinimapOverlay.PAD) / availW;
                double ny = (event.y() - dragOffY - MinimapOverlay.PAD) / availH;
                s.setMinimapPosition(nx, ny);
                return true;
            }
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && (draggingMove || draggingResize)) {
            draggingMove = false;
            draggingResize = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            ChunkVaultSettings.get().stepMinimapZoom(scrollY > 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
            ChunkVaultSettings.get().stepMinimapSize(1);
            return true;
        }
        if (key == InputConstants.KEY_MINUS || key == 333) {
            ChunkVaultSettings.get().stepMinimapSize(-1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreenAndShow(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
