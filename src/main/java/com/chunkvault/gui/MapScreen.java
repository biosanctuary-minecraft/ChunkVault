package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.chunkvault.save.ContainerDatabase;
import com.chunkvault.state.AreaPlan;
import com.chunkvault.state.AutoRecord;
import com.chunkvault.state.MapPinFilter;
import com.chunkvault.state.RecordingState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MapScreen extends Screen {

    private static final int TOP_BAR = 22;
    private static final int BOTTOM_BAR = 18;
    private static final double MIN_SCALE = 0.125;
    private static final double MAX_SCALE = 16.0;
    private static final int RECT_BUDGET = 9000;
    private static final int FILTER_PANEL_W = 230;
    private static final int FILTER_PANEL_H = 190;

    private static double lastScale = 1.0;
    private static boolean showPins = true;
    private static boolean followPlayer = false;
    private static ItemStack chestIcon;

    private record PinGroup(ContainerDatabase.ContainerEntry top, int count, int sxDev, int syDev) {}

    private final long openedAtMs = System.currentTimeMillis();

    private double centerX;
    private double centerZ;
    private double scale = lastScale;
    private int frame;

    private double dev = 1.0;
    private int clipX0;
    private int clipY0;
    private int clipX1;
    private int clipY1;

    private int[] visCx = new int[512];
    private int[] visCz = new int[512];
    private MapCache.ChunkColors[] visCol = new MapCache.ChunkColors[512];
    private int visCount;

    private int btnX0;
    private int btnY0;
    private int btnX1;
    private int btnY1;
    private int newX0;
    private int newX1;
    private int expX0;
    private int expX1;
    private int dbX0;
    private int dbX1;
    private int areaX0;
    private int areaX1;
    private int setX0;
    private int setX1;
    private String toast;
    private long toastUntil;

    private int pinBtnX0;
    private int pinBtnY0;
    private int pinBtnX1;
    private int pinBtnY1;

    private int filterBtnX0;
    private int filterBtnY0;
    private int filterBtnX1;
    private int filterBtnY1;

    private int dimBtnX0;
    private int dimBtnY0;
    private int dimBtnX1;
    private int dimBtnY1;

    private int followBtnX0;
    private int followBtnY0;
    private int followBtnX1;
    private int followBtnY1;

    private int zoomOutBtnX0;
    private int zoomOutBtnY0;
    private int zoomOutBtnX1;
    private int zoomOutBtnY1;

    private int zoomInBtnX0;
    private int zoomInBtnY0;
    private int zoomInBtnX1;
    private int zoomInBtnY1;

    private boolean filterPanelOpen;
    private EditBox filterSearchBox;
    private int fpX0;
    private int fpY0;
    private int fpX1;
    private int fpY1;
    private int fpResetX0;
    private int fpResetX1;
    private int fpResetY0;
    private int fpResetY1;
    private int fpStatusY0;
    private int fpStatusY1;
    private int fpTypesY0;
    private int fpShulkerAllX0;
    private int fpShulkerAllX1;
    private int fpShulkerNoneX0;
    private int fpShulkerNoneX1;
    private int fpShulkerBtnY0;
    private int fpShulkerBtnY1;
    private int fpSwatchesY0;
    private MapPinFilter.ShulkerColor hoveredColor;

    private int areaBarY0;
    private int areaBarY1;
    private int abCoordsX0;
    private int abCoordsX1;
    private int abRedrawX0;
    private int abRedrawX1;
    private int abClipX0;
    private int abClipX1;
    private int abTrimX0;
    private int abTrimX1;
    private int abHudX0;
    private int abHudX1;
    private int abAutoX0;
    private int abAutoX1;
    private int abClearX0;
    private int abClearX1;

    private boolean areaPanelOpen;
    private boolean selectingArea;
    private boolean draggingArea;
    private int areaStartCx;
    private int areaStartCz;
    private int areaEndCx;
    private int areaEndCz;
    private long trimConfirmUntil;

    private boolean draggingMap;
    private boolean dragged;
    private double dragStartX;
    private double dragStartY;

    private PinGroup hoveredPin;
    private int focusX;
    private int focusZ;
    private long focusUntil;

    private int[] zoneX0 = new int[256];
    private int[] zoneX1 = new int[256];
    private int[] zoneZ = new int[256];
    private int zoneCount;
    private boolean[] zoneGrid = new boolean[0];
    private int zoneMinX;
    private int zoneMinZ;
    private int zoneSide;

    public MapScreen() {
        super(Lang.c("map.title"));
        recenterOnPlayer();
    }

    @Override
    protected void init() {
        super.init();
        filterSearchBox = new EditBox(this.font, 0, 0, FILTER_PANEL_W - 16, 15, Lang.c("map.filter.search"));
        filterSearchBox.setMaxLength(80);
        filterSearchBox.setValue(MapPinFilter.searchQuery());
        filterSearchBox.setHint(Lang.c("map.filter.search_hint"));
        filterSearchBox.setResponder(MapPinFilter::setSearchQuery);
        filterSearchBox.setVisible(filterPanelOpen && ProjectMap.isOpen());
        this.addWidget(filterSearchBox);
    }

    private void unfocusFilterSearch() {
        if (filterSearchBox != null) {
            filterSearchBox.setFocused(false);
        }
        if (this.getFocused() == filterSearchBox) {
            this.setFocused(null);
        }
    }

    private static ItemStack chestButtonIcon() {
        if (chestIcon == null) {
            chestIcon = new ItemStack(Items.CHEST);
        }
        return chestIcon;
    }

    private void recenterOnPlayer() {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            centerX = player.getX();
            centerZ = player.getZ();
        }
    }

    private void zoomCenter(double factor) {
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        lastScale = scale;
    }

    public void focusOn(int x, int z) {
        followPlayer = false;
        centerX = x + 0.5;
        centerZ = z + 0.5;
        if (scale < 2.0) {
            scale = 2.0;
            lastScale = scale;
        }
        focusX = x;
        focusZ = z;
        focusUntil = System.currentTimeMillis() + 3000;
        showPins = true;
    }

    void onAreaInputApplied() {
        selectingArea = false;
        draggingArea = false;
        areaPanelOpen = true;
        trimConfirmUntil = 0;
        if (AreaPlan.isActive()) {
            followPlayer = false;
            centerX = (AreaPlan.minCx() + AreaPlan.maxCx() + 1) * 8.0;
            centerZ = (AreaPlan.minCz() + AreaPlan.maxCz() + 1) * 8.0;
            showToast(Lang.s("area.set_toast", AreaPlan.totalChunks()));
        }
    }

    private int screenToChunkX(double mouseX) {
        double mapCX = this.width / 2.0;
        return ((int) Math.floor(centerX + (mouseX - mapCX) / scale)) >> 4;
    }

    private int screenToChunkZ(double mouseY) {
        double mapCY = TOP_BAR + (this.height - TOP_BAR - BOTTOM_BAR) / 2.0;
        return ((int) Math.floor(centerZ + (mouseY - mapCY) / scale)) >> 4;
    }

    private void updateFilterPanelBounds(boolean hasProject, boolean showDimBtn) {
        if (!hasProject || !filterPanelOpen) {
            fpX0 = 0;
            fpY0 = 0;
            fpX1 = 0;
            fpY1 = 0;
            if (filterSearchBox != null) {
                filterSearchBox.setVisible(false);
            }
            return;
        }
        int anchorTop = showDimBtn ? dimBtnY0 : pinBtnY0;
        fpX1 = this.width - 8;
        fpX0 = Math.max(8, fpX1 - FILTER_PANEL_W);
        fpY1 = anchorTop - 6;
        fpY0 = Math.max(TOP_BAR + 4, fpY1 - FILTER_PANEL_H);
        fpY1 = Math.min(this.height - BOTTOM_BAR - 4, fpY0 + FILTER_PANEL_H);
        if (filterSearchBox != null) {
            filterSearchBox.setVisible(true);
            filterSearchBox.setX(fpX0 + 8);
            filterSearchBox.setY(fpY0 + 24);
            filterSearchBox.setWidth((fpX1 - fpX0) - 16);
            filterSearchBox.setHeight(15);
            if (!filterSearchBox.isFocused() && !filterSearchBox.getValue().equals(MapPinFilter.searchQuery())) {
                filterSearchBox.setValue(MapPinFilter.searchQuery());
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        frame++;

        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        var level = mc.level;

        g.fill(0, 0, this.width, this.height, 0xFF0B0B0F);

        if (player == null || level == null) {
            g.centeredText(this.font, Lang.s("map.no_world"), this.width / 2, this.height / 2, 0xFFFFFFFF);
            return;
        }

        if (followPlayer && !draggingMap) {
            centerX = player.getX();
            centerZ = player.getZ();
        }

        final int mapX0 = 0;
        final int mapY0 = TOP_BAR;
        final int mapX1 = this.width;
        final int mapY1 = this.height - BOTTOM_BAR;
        final int mapW = mapX1 - mapX0;
        final int mapH = mapY1 - mapY0;
        final double mapCX = mapX0 + mapW / 2.0;
        final double mapCY = mapY0 + mapH / 2.0;

        String dimensionId = level.dimension().identifier().toString();
        var cache = ProjectMap.forDimension(dimensionId);
        boolean hasProject = ProjectMap.isOpen();
        if (!hasProject && filterPanelOpen) {
            filterPanelOpen = false;
            unfocusFilterSearch();
        }
        boolean showAreaBar = hasProject && (selectingArea || (areaPanelOpen && AreaPlan.isActive()));
        boolean showDimBtn = hasProject && areaPanelOpen && AreaPlan.isActive();

        pinBtnX1 = hasProject ? this.width - 8 : 0;
        pinBtnX0 = hasProject ? pinBtnX1 - 22 : 0;
        pinBtnY1 = hasProject ? this.height - BOTTOM_BAR - 8 : 0;
        pinBtnY0 = hasProject ? pinBtnY1 - 22 : 0;

        filterBtnX1 = hasProject ? pinBtnX0 - 6 : 0;
        filterBtnX0 = hasProject ? filterBtnX1 - 22 : 0;
        filterBtnY0 = pinBtnY0;
        filterBtnY1 = pinBtnY1;

        if (showDimBtn) {
            dimBtnX0 = pinBtnX0;
            dimBtnX1 = pinBtnX1;
            dimBtnY1 = pinBtnY0 - 6;
            dimBtnY0 = dimBtnY1 - 22;
        } else {
            dimBtnX0 = 0;
            dimBtnX1 = 0;
            dimBtnY0 = 0;
            dimBtnY1 = 0;
        }

        followBtnX0 = 8;
        followBtnX1 = followBtnX0 + 22;
        followBtnY1 = this.height - BOTTOM_BAR - 8;
        followBtnY0 = followBtnY1 - 22;

        zoomOutBtnX0 = 8;
        zoomOutBtnX1 = zoomOutBtnX0 + 22;
        zoomOutBtnY1 = followBtnY0 - 6;
        zoomOutBtnY0 = zoomOutBtnY1 - 22;

        zoomInBtnX0 = 8;
        zoomInBtnX1 = zoomInBtnX0 + 22;
        zoomInBtnY1 = zoomOutBtnY0 - 4;
        zoomInBtnY0 = zoomInBtnY1 - 22;

        updateFilterPanelBounds(hasProject, showDimBtn);

        boolean overPinBtn = hasProject && mouseX >= pinBtnX0 && mouseX < pinBtnX1
                && mouseY >= pinBtnY0 && mouseY < pinBtnY1;
        boolean overFilterBtn = hasProject && mouseX >= filterBtnX0 && mouseX < filterBtnX1
                && mouseY >= filterBtnY0 && mouseY < filterBtnY1;
        boolean overDimBtn = showDimBtn && mouseX >= dimBtnX0 && mouseX < dimBtnX1
                && mouseY >= dimBtnY0 && mouseY < dimBtnY1;
        boolean overFollowBtn = mouseX >= followBtnX0 && mouseX < followBtnX1
                && mouseY >= followBtnY0 && mouseY < followBtnY1;
        boolean overZoomOutBtn = mouseX >= zoomOutBtnX0 && mouseX < zoomOutBtnX1
                && mouseY >= zoomOutBtnY0 && mouseY < zoomOutBtnY1;
        boolean overZoomInBtn = mouseX >= zoomInBtnX0 && mouseX < zoomInBtnX1
                && mouseY >= zoomInBtnY0 && mouseY < zoomInBtnY1;
        boolean overFilterPanel = hasProject && filterPanelOpen
                && mouseX >= fpX0 && mouseX < fpX1 && mouseY >= fpY0 && mouseY < fpY1;
        boolean inAreaBar = showAreaBar && mouseY >= areaBarY0 && mouseY < areaBarY1;
        boolean inMap = mouseY >= mapY0 && mouseY < mapY1
                && !inAreaBar && !overPinBtn && !overFilterBtn && !overDimBtn
                && !overFollowBtn && !overZoomOutBtn && !overZoomInBtn && !overFilterPanel;

        dev = Math.max(1.0, (double) mc.getWindow().getWidth() / (double) this.width);
        clipX0 = (int) Math.round(mapX0 * dev);
        clipY0 = (int) Math.round(mapY0 * dev);
        clipX1 = (int) Math.round(mapX1 * dev);
        clipY1 = (int) Math.round(mapY1 * dev);

        double ppc = scale * 16.0;
        double ppcDev = ppc * dev;
        var manager = RecordingState.get().saveManager();

        boolean recording = RecordingState.get().isActive() && manager != null;
        if (!recording) {
            zoneCount = 0;
            zoneSide = 0;
        } else if (frame % 3 == 0 || zoneSide == 0) {
            updateZone(mc, level, player);
        }

        MapTextures.beginFrame(frame, dimensionId, cache);
        boolean useTex = MapTextures.available();

        double halfW = (mapW / 2.0) / scale;
        double halfH = (mapH / 2.0) / scale;
        int minCx = (int) Math.floor((centerX - halfW) / 16.0);
        int maxCx = (int) Math.floor((centerX + halfW) / 16.0);
        int minCz = (int) Math.floor((centerZ - halfH) / 16.0);
        int maxCz = (int) Math.floor((centerZ + halfH) / 16.0);
        visCount = 0;
        int detail = 1;

        if (!useTex && hasProject) {
            long area = (long) (maxCx - minCx + 1) * (long) (maxCz - minCz + 1);
            if (area <= cache.size() * 2L) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    for (int cx = minCx; cx <= maxCx; cx++) {
                        MapCache.ChunkColors c = cache.get(MapCache.key(cx, cz));
                        if (c != null) {
                            addVisible(cx, cz, c);
                        }
                    }
                }
            } else {
                for (var entry : cache.entrySet()) {
                    int cx = MapCache.keyX(entry.getKey());
                    int cz = MapCache.keyZ(entry.getKey());
                    if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) {
                        addVisible(cx, cz, entry.getValue());
                    }
                }
            }

            long[] totals = new long[4];
            for (int i = 0; i < visCount; i++) {
                MapCache.ChunkColors c = visCol[i];
                totals[0] += c.m16.count;
                totals[1] += c.m8.count;
                totals[2] += c.m4.count;
                totals[3] += c.m2.count;
            }
            int[] levels = {16, 8, 4, 2};
            for (int i = 0; i < levels.length; i++) {
                if (ppcDev / levels[i] >= 1.0 && totals[i] <= RECT_BUDGET) {
                    detail = levels[i];
                    break;
                }
            }
        }

        g.enableScissor(mapX0, mapY0, mapX1, mapY1);
        float inv = (float) (1.0 / dev);
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);

        if (useTex && hasProject) {
            drawTerrainTextured(g, dimensionId, cache, mapCX, mapCY, minCx, maxCx, minCz, maxCz);
        }

        for (int i = 0; i < visCount; i++) {
            int cx = visCx[i];
            int cz = visCz[i];
            MapCache.ChunkColors colors = visCol[i];
            double sx = (mapCX + (cx * 16.0 - centerX) * scale) * dev;
            double sy = (mapCY + (cz * 16.0 - centerZ) * scale) * dev;

            if (detail == 1) {
                if (colors.avg != 0) {
                    fillDev(g, sx, sy, sx + ppcDev, sy + ppcDev, colors.avg);
                }
            } else {
                MapCache.Mesh mesh = detail == 16 ? colors.m16 : detail == 8 ? colors.m8
                        : detail == 4 ? colors.m4 : colors.m2;
                double cell = ppcDev / detail;
                for (int k = 0; k < mesh.count; k++) {
                    int p = mesh.rects[k];
                    int rx = p & 0xFF;
                    int rz = (p >> 8) & 0xFF;
                    int rw = (p >> 16) & 0xFF;
                    int rh = (p >>> 24) & 0xFF;
                    fillDev(g, sx + rx * cell, sy + rz * cell, sx + (rx + rw) * cell, sy + (rz + rh) * cell,
                            mesh.colors[k]);
                }
            }
        }

        if (hasProject && AreaPlan.isActive() && dimensionId.equals(AreaPlan.dimension())) {
            drawPlannedAreaDev(g, cache, player, mapCX, mapCY, minCx, maxCx, minCz, maxCz);
        }

        if (selectingArea && draggingArea) {
            int sx0 = Math.min(areaStartCx, areaEndCx);
            int sx1 = Math.max(areaStartCx, areaEndCx);
            int sz0 = Math.min(areaStartCz, areaEndCz);
            int sz1 = Math.max(areaStartCz, areaEndCz);
            int px0 = (int) Math.round((mapCX + (sx0 * 16.0 - centerX) * scale) * dev);
            int px1 = (int) Math.round((mapCX + ((sx1 + 1) * 16.0 - centerX) * scale) * dev);
            int py0 = (int) Math.round((mapCY + (sz0 * 16.0 - centerZ) * scale) * dev);
            int py1 = (int) Math.round((mapCY + ((sz1 + 1) * 16.0 - centerZ) * scale) * dev);
            fillI(g, px0, py0, px1, py1, 0x3838D8FF);
            int t = Math.max(2, (int) Math.round(dev));
            fillI(g, px0, py0, px1, py0 + t, 0xFF38D8FF);
            fillI(g, px0, py1 - t, px1, py1, 0xFF38D8FF);
            fillI(g, px0, py0, px0 + t, py1, 0xFF38D8FF);
            fillI(g, px1 - t, py0, px1, py1, 0xFF38D8FF);
        }

        if (recording && zoneCount > 0) {
            for (int i = 0; i < zoneCount; i++) {
                int x0 = (int) Math.round((mapCX + (zoneX0[i] * 16.0 - centerX) * scale) * dev);
                int x1 = (int) Math.round((mapCX + (zoneX1[i] * 16.0 - centerX) * scale) * dev);
                int y0 = (int) Math.round((mapCY + (zoneZ[i] * 16.0 - centerZ) * scale) * dev);
                int y1 = (int) Math.round((mapCY + ((zoneZ[i] + 1) * 16.0 - centerZ) * scale) * dev);
                fillI(g, x0, y0, x1, y1, 0x46FF1A1A);
            }
            if (zoneSide > 0) {
                int t = Math.max(2, (int) Math.round(dev));
                int borderColor = 0xEEFF4040;
                for (int iz = 0; iz < zoneSide; iz++) {
                    int cz = zoneMinZ + iz;
                    int y0 = (int) Math.round((mapCY + (cz * 16.0 - centerZ) * scale) * dev);
                    int y1 = (int) Math.round((mapCY + ((cz + 1) * 16.0 - centerZ) * scale) * dev);
                    int rowOff = iz * zoneSide;
                    for (int ix = 0; ix < zoneSide; ix++) {
                        if (!zoneGrid[rowOff + ix]) {
                            continue;
                        }
                        int cx = zoneMinX + ix;
                        int x0 = (int) Math.round((mapCX + (cx * 16.0 - centerX) * scale) * dev);
                        int x1 = (int) Math.round((mapCX + ((cx + 1) * 16.0 - centerX) * scale) * dev);
                        if (iz == 0 || !zoneGrid[rowOff - zoneSide + ix]) {
                            fillI(g, x0, y0, x1, y0 + t, borderColor);
                        }
                        if (iz == zoneSide - 1 || !zoneGrid[rowOff + zoneSide + ix]) {
                            fillI(g, x0, y1 - t, x1, y1, borderColor);
                        }
                        if (ix == 0 || !zoneGrid[rowOff + ix - 1]) {
                            fillI(g, x0, y0, x0 + t, y1, borderColor);
                        }
                        if (ix == zoneSide - 1 || !zoneGrid[rowOff + ix + 1]) {
                            fillI(g, x1 - t, y0, x1, y1, borderColor);
                        }
                    }
                }
            }
        }

        if (ppc >= 48) {
            for (int cx = minCx; cx <= maxCx + 1; cx++) {
                int x = (int) Math.round((mapCX + (cx * 16.0 - centerX) * scale) * dev);
                fillI(g, x, clipY0, x + 1, clipY1, 0x28FFFFFF);
            }
            for (int cz = minCz; cz <= maxCz + 1; cz++) {
                int y = (int) Math.round((mapCY + (cz * 16.0 - centerZ) * scale) * dev);
                fillI(g, clipX0, y, clipX1, y + 1, 0x28FFFFFF);
            }
        }

        if (inMap) {
            int hbx = (int) Math.floor(centerX + (mouseX - mapCX) / scale);
            int hbz = (int) Math.floor(centerZ + (mouseY - mapCY) / scale);
            double hx = (mapCX + ((hbx >> 4) * 16.0 - centerX) * scale) * dev;
            double hy = (mapCY + ((hbz >> 4) * 16.0 - centerZ) * scale) * dev;
            int x0 = (int) Math.round(hx);
            int y0 = (int) Math.round(hy);
            int x1 = (int) Math.round(hx + ppcDev);
            int y1 = (int) Math.round(hy + ppcDev);
            fillI(g, x0, y0, x1, y1, selectingArea ? 0x3538D8FF : 0x1EFFFFFF);
            int borderC = selectingArea ? 0xFF38D8FF : 0xB0FFFFFF;
            fillI(g, x0, y0, x1, y0 + 1, borderC);
            fillI(g, x0, y1 - 1, x1, y1, borderC);
            fillI(g, x0, y0, x0 + 1, y1, borderC);
            fillI(g, x1 - 1, y0, x1, y1, borderC);
        }

        double pcx = (mapCX + (player.getX() - centerX) * scale) * dev;
        double pcy = (mapCY + (player.getZ() - centerZ) * scale) * dev;
        double yaw = Math.toRadians(player.getYRot());
        drawArrow(g, pcx, pcy, -Math.sin(yaw), Math.cos(yaw), 3.2 * dev + 5.0);

        hoveredPin = null;
        if (hasProject && showPins && !selectingArea) {
            drawContainerPins(g, dimensionId, mapCX, mapCY, minCx, maxCx, minCz, maxCz, mouseX, mouseY, inMap);
        }

        long now = System.currentTimeMillis();
        if (focusUntil > now) {
            int fxDev = (int) Math.round((mapCX + (focusX + 0.5 - centerX) * scale) * dev);
            int fyDev = (int) Math.round((mapCY + (focusZ + 0.5 - centerZ) * scale) * dev);
            boolean pulse = ((now / 180) & 1) == 0;
            int ringR = (int) Math.round((pulse ? 9.0 : 7.0) * dev);
            int ringT = Math.max(2, (int) Math.round(2.0 * dev));
            int ringColor = pulse ? 0xFFFFFFFF : 0xFFFFD070;
            fillI(g, fxDev - ringR, fyDev - ringR, fxDev + ringR, fyDev - ringR + ringT, ringColor);
            fillI(g, fxDev - ringR, fyDev + ringR - ringT, fxDev + ringR, fyDev + ringR, ringColor);
            fillI(g, fxDev - ringR, fyDev - ringR, fxDev - ringR + ringT, fyDev + ringR, ringColor);
            fillI(g, fxDev + ringR - ringT, fyDev - ringR, fxDev + ringR, fyDev + ringR, ringColor);
        }

        g.pose().popMatrix();

        if (selectingArea && draggingArea) {
            int wChunks = Math.abs(areaEndCx - areaStartCx) + 1;
            int hChunks = Math.abs(areaEndCz - areaStartCz) + 1;
            String dimLabel = Lang.s("area.drag_size", wChunks, hChunks, wChunks * 16, hChunks * 16);
            int lw = this.font.width(dimLabel) + 12;
            int lx = Math.max(6, Math.min(this.width - lw - 6, mouseX + 12));
            int ly = Math.max(TOP_BAR + 26, Math.min(this.height - BOTTOM_BAR - 22, mouseY + 12));
            g.fill(lx - 1, ly - 1, lx + lw + 1, ly + 17, 0xFF38D8FF);
            g.fill(lx, ly, lx + lw, ly + 16, 0xF014161C);
            g.text(this.font, dimLabel, lx + 6, ly + 4, 0xFFFFFFFF, true);
        }

        g.disableScissor();

        drawZoomButtonIcon(g, zoomInBtnX0, zoomInBtnY0, zoomInBtnX1, zoomInBtnY1, true, overZoomInBtn, scale < MAX_SCALE);
        drawZoomButtonIcon(g, zoomOutBtnX0, zoomOutBtnY0, zoomOutBtnX1, zoomOutBtnY1, false, overZoomOutBtn, scale > MIN_SCALE);
        drawFollowToggleIcon(g, overFollowBtn);

        if (showDimBtn) {
            drawDimToggleIcon(g, overDimBtn);
        }
        if (hasProject) {
            drawFilterToggleIcon(g, overFilterBtn);
            drawPinToggleIcon(g, overPinBtn);
        }

        hoveredColor = null;
        if (hasProject && filterPanelOpen) {
            drawFilterPanel(g, mouseX, mouseY, partialTick);
        }

        g.fill(0, 0, this.width, TOP_BAR, 0xFF1A1A22);
        g.fill(0, this.height - BOTTOM_BAR, this.width, this.height, 0xFF1A1A22);

        int x = 8;
        g.text(this.font, "ChunkVault", x, 7, 0xFFFFFFFF, true);
        x += this.font.width("ChunkVault") + 6;
        g.text(this.font, "—", x, 7, 0xFF808090, true);
        x += this.font.width("—") + 6;
        String projectLabel = hasProject ? ProjectMap.name() : Lang.s("map.no_project");
        g.text(this.font, projectLabel, x, 7, hasProject ? 0xFFFFD070 : 0xFF909090, true);
        x += this.font.width(projectLabel) + 12;

        btnY0 = 3;
        btnY1 = TOP_BAR - 3;

        newX0 = x;
        newX1 = drawTopButton(g, newX0, Lang.s("button.new_file"), 0xFF2E8B4E, 0xFF3FB365, mouseX, mouseY);
        expX0 = newX1 + 5;
        expX1 = drawTopButton(g, expX0, Lang.s("button.open"), 0xFFB8901A, 0xFFD9AE2C, mouseX, mouseY);
        int endX = expX1;

        boolean hoverDbBtn = false;
        if (hasProject) {
            btnX0 = endX + 5;
            btnX1 = drawTopButton(g, btnX0, recording ? Lang.s("button.stop") : Lang.s("button.record"),
                    recording ? 0xFF7A2020 : 0xFFB03030, recording ? 0xFF9E2C2C : 0xFFD84848, mouseX, mouseY);
            dbX0 = btnX1 + 5;
            dbX1 = dbX0 + 22;
            hoverDbBtn = mouseX >= dbX0 && mouseX < dbX1 && mouseY >= btnY0 && mouseY < btnY1;
            g.fill(dbX0, btnY0, dbX1, btnY1, 0xFFFFFFFF);
            g.fill(dbX0 + 1, btnY0 + 1, dbX1 - 1, btnY1 - 1, hoverDbBtn ? 0xFF9E7022 : 0xFF7A5618);
            g.item(chestButtonIcon(), dbX0 + 3, btnY0);
            areaX0 = dbX1 + 5;
            boolean areaHighlighted = areaPanelOpen || selectingArea || AreaPlan.isActive();
            areaX1 = drawTopButton(g, areaX0, Lang.s("button.area"),
                    areaHighlighted ? 0xFF1F7A8C : 0xFF3A4B5C, areaHighlighted ? 0xFF2A9BB0 : 0xFF4E647A, mouseX, mouseY);
            endX = areaX1;
        } else {
            btnX0 = 0;
            btnX1 = 0;
            dbX0 = 0;
            dbX1 = 0;
            areaX0 = 0;
            areaX1 = 0;
        }

        setX0 = endX + 5;
        setX1 = drawTopButton(g, setX0, Lang.s("button.settings"), 0xFF4E5F82, 0xFF6B7FA8, mouseX, mouseY);

        String status;
        int statusColor;
        if (recording) {
            status = Lang.s("map.status.recording", manager.chunksSaved(), manager.entitiesSeen());
            statusColor = 0xFFFF7070;
            if (this.width - this.font.width(status) - 8 <= setX1 + 8) {
                status = "● " + manager.chunksSaved() + " / " + manager.entitiesSeen();
            }
        } else if (hasProject) {
            status = Lang.s("map.status.idle");
            statusColor = 0xFF909090;
        } else {
            status = "";
            statusColor = 0;
        }
        int statusX = this.width - this.font.width(status) - 8;
        if (!status.isEmpty() && statusX > setX1 + 6) {
            g.text(this.font, status, statusX, 7, statusColor, true);
        }

        if (showAreaBar) {
            drawAreaBar(g, player, mouseX, mouseY, now);
        } else {
            areaBarY0 = 0;
            areaBarY1 = 0;
        }

        if (!hasProject) {
            g.centeredText(this.font, Lang.s("map.hint_no_project"),
                    this.width / 2, this.height / 2 - 4, 0xFF808090);
        }

        String info = Lang.s("map.you", (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()), (int) Math.floor(player.getZ())) + "  |";
        if (inMap) {
            int bx = (int) Math.floor(centerX + (mouseX - mapCX) / scale);
            int bz = (int) Math.floor(centerZ + (mouseY - mapCY) / scale);
            info += "  " + Lang.s("map.cursor", bx, bz, bx >> 4, bz >> 4) + "  |";
        }
        g.text(this.font, info, 8, this.height - BOTTOM_BAR + 5, 0xFFFFFFFF, true);
        g.text(this.font, dimensionId, this.width - this.font.width(dimensionId) - 8,
                this.height - BOTTOM_BAR + 5, 0xFFCCCCCC, true);

        if (toast != null && now < toastUntil) {
            int toastY = showAreaBar ? areaBarY1 + 6 : TOP_BAR + 6;
            int tw = this.font.width(toast) + 16;
            int tx0 = (this.width - tw) / 2;
            g.fill(tx0, toastY, tx0 + tw, toastY + 18, 0xE0101015);
            g.text(this.font, toast, tx0 + 8, toastY + 5, 0xFF70FF90, true);
        }

        if (hoverDbBtn) {
            drawSimpleTooltip(g, List.of(Lang.s("db.title_short")), List.of(0xFFFFFFFF), mouseX, mouseY);
        } else if (overZoomInBtn) {
            drawSimpleTooltip(g, List.of(Lang.s("map.zoom_in_tip")), List.of(0xFFFFFFFF), mouseX, mouseY);
        } else if (overZoomOutBtn) {
            drawSimpleTooltip(g, List.of(Lang.s("map.zoom_out_tip")), List.of(0xFFFFFFFF), mouseX, mouseY);
        } else if (overFollowBtn) {
            String tip = Lang.s("map.follow_tip", followPlayer ? Lang.s("common.on") : Lang.s("common.off"));
            drawSimpleTooltip(g, List.of(tip), List.of(followPlayer ? 0xFF38D8FF : 0xFFFFFFFF), mouseX, mouseY);
        } else if (overDimBtn) {
            String tip = Lang.s("map.dim_toggle_tip",
                    AreaPlan.isDimOutside() ? Lang.s("common.on") : Lang.s("common.off"));
            drawSimpleTooltip(g, List.of(tip), List.of(0xFFFFFFFF), mouseX, mouseY);
        } else if (overFilterBtn) {
            String tip = Lang.s("map.filter_btn_tip");
            if (MapPinFilter.isRestricting()) {
                drawSimpleTooltip(g, List.of(tip, Lang.s("map.filter_active_tip")),
                        List.of(0xFFFFFFFF, 0xFFFFD040), mouseX, mouseY);
            } else {
                drawSimpleTooltip(g, List.of(tip), List.of(0xFFFFFFFF), mouseX, mouseY);
            }
        } else if (overPinBtn) {
            String l1 = Lang.s("map.pins_toggle_tip", showPins ? Lang.s("common.on") : Lang.s("common.off"));
            String l2 = Lang.s("map.pins_unopened_tip",
                    ContainerDatabase.showUnopened() ? Lang.s("common.on") : Lang.s("common.off"));
            drawSimpleTooltip(g, List.of(l1, l2), List.of(0xFFFFFFFF, 0xFF9AA0B0), mouseX, mouseY);
        } else if (hoveredColor != null) {
            drawSimpleTooltip(g,
                    List.of(Lang.s(hoveredColor.langKey()), Lang.s("map.filter.color_hint")),
                    List.of(0xFFFFFFFF, 0xFF9AA0B0), mouseX, mouseY);
        } else if (hoveredPin != null) {
            drawPinTooltip(g, hoveredPin, mouseX, mouseY);
        }
    }

    private void drawZoomButtonIcon(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                                    boolean plus, boolean hover, boolean enabled) {
        int border = hover && enabled ? 0xFFFFFFFF : 0xFF3A3A48;
        int bg = hover && enabled ? 0xF0262A34 : 0xE014161C;
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, border);
        g.fill(x0, y0, x1, y1, bg);

        int cx = (x0 + x1) / 2;
        int cy = (y0 + y1) / 2;
        int col = enabled ? 0xFFFFFFFF : 0xFF555B6A;
        g.fill(cx - 5, cy - 1, cx + 5, cy + 1, col);
        if (plus) {
            g.fill(cx - 1, cy - 5, cx + 1, cy + 5, col);
        }
    }

    private void drawFollowToggleIcon(GuiGraphicsExtractor g, boolean hover) {
        int border = followPlayer ? 0xFF38D8FF : hover ? 0xFFFFFFFF : 0xFF3A3A48;
        int bg = followPlayer ? 0xF01C2836 : hover ? 0xF0262A34 : 0xE014161C;
        g.fill(followBtnX0 - 1, followBtnY0 - 1, followBtnX1 + 1, followBtnY1 + 1, border);
        g.fill(followBtnX0, followBtnY0, followBtnX1, followBtnY1, bg);

        int cx = (followBtnX0 + followBtnX1) / 2;
        int cy = (followBtnY0 + followBtnY1) / 2;
        int col = followPlayer ? 0xFF38D8FF : hover ? 0xFFFFFFFF : 0xFF9AA0B0;

        g.fill(cx - 4, cy - 5, cx + 4, cy - 4, col);
        g.fill(cx - 4, cy + 4, cx + 4, cy + 5, col);
        g.fill(cx - 5, cy - 4, cx - 4, cy + 4, col);
        g.fill(cx + 4, cy - 4, cx + 5, cy + 4, col);

        g.fill(cx - 1, cy - 7, cx + 1, cy - 5, col);
        g.fill(cx - 1, cy + 5, cx + 1, cy + 7, col);
        g.fill(cx - 7, cy - 1, cx - 5, cy + 1, col);
        g.fill(cx + 5, cy - 1, cx + 7, cy + 1, col);

        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, followPlayer ? 0xFFFFFFFF : col);
    }

    private void drawFilterToggleIcon(GuiGraphicsExtractor g, boolean hover) {
        int border = filterPanelOpen ? 0xFF38D8FF : hover ? 0xFFFFFFFF : 0xFF3A3A48;
        int bg = filterPanelOpen ? 0xF01C2836 : hover ? 0xF0262A34 : 0xE014161C;
        g.fill(filterBtnX0 - 1, filterBtnY0 - 1, filterBtnX1 + 1, filterBtnY1 + 1, border);
        g.fill(filterBtnX0, filterBtnY0, filterBtnX1, filterBtnY1, bg);

        int cx = (filterBtnX0 + filterBtnX1) / 2;
        int cy = (filterBtnY0 + filterBtnY1) / 2;
        int col = filterPanelOpen ? 0xFF38D8FF : 0xFFFFFFFF;

        g.fill(cx - 6, cy - 5, cx + 6, cy - 3, col);
        g.fill(cx - 4, cy - 3, cx + 4, cy - 1, col);
        g.fill(cx - 2, cy - 1, cx + 2, cy + 1, col);
        g.fill(cx - 1, cy + 1, cx + 1, cy + 5, col);
        g.fill(cx, cy + 5, cx + 1, cy + 6, col);

        if (MapPinFilter.isRestricting()) {
            g.fill(filterBtnX1 - 6, filterBtnY0 + 2, filterBtnX1 - 2, filterBtnY0 + 6, 0xFFFFD040);
        }
    }

    private void drawFilterPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(fpX0 - 1, fpY0 - 1, fpX1 + 1, fpY1 + 1, 0xFF3A3A48);
        g.fill(fpX0, fpY0, fpX1, fpY1, 0xF214161C);

        int innerX0 = fpX0 + 8;
        int innerX1 = fpX1 - 8;
        int innerW = innerX1 - innerX0;

        g.text(this.font, Lang.s("map.filter.title"), innerX0, fpY0 + 7, 0xFFFFFFFF, true);

        String resetTxt = Lang.s("map.filter.reset");
        int rw = this.font.width(resetTxt) + 8;
        fpResetX1 = innerX1;
        fpResetX0 = fpResetX1 - rw;
        fpResetY0 = fpY0 + 5;
        fpResetY1 = fpY0 + 18;
        boolean resetHover = mouseX >= fpResetX0 && mouseX < fpResetX1 && mouseY >= fpResetY0 && mouseY < fpResetY1;
        g.fill(fpResetX0, fpResetY0, fpResetX1, fpResetY1, 0xFF5A6074);
        g.fill(fpResetX0 + 1, fpResetY0 + 1, fpResetX1 - 1, fpResetY1 - 1,
                resetHover ? 0xFF7A3838 : MapPinFilter.isRestricting() ? 0xFF5A2A2A : 0xFF262A34);
        g.text(this.font, resetTxt, fpResetX0 + 4, fpResetY0 + 3, 0xFFFFFFFF, true);

        if (filterSearchBox != null) {
            filterSearchBox.extractRenderState(g, mouseX, mouseY, partialTick);
        }

        fpStatusY0 = fpY0 + 44;
        fpStatusY1 = fpStatusY0 + 15;
        int halfW = (innerW - 4) / 2;
        drawFilterPill(g, innerX0, innerX0 + halfW, fpStatusY0, fpStatusY1,
                Lang.s("map.filter.opened"), MapPinFilter.showOpened(), 0xFF2E6B48, mouseX, mouseY);
        drawFilterPill(g, innerX0 + halfW + 4, innerX1, fpStatusY0, fpStatusY1,
                Lang.s("map.filter.unopened"), MapPinFilter.showUnopened(), 0xFF8A6A1A, mouseX, mouseY);

        fpTypesY0 = fpStatusY1 + 5;
        String[] typeLabels = {
                Lang.s("map.filter.type.chests"),
                Lang.s("map.filter.type.barrels"),
                Lang.s("map.filter.type.shulkers"),
                Lang.s("map.filter.type.hoppers"),
                Lang.s("map.filter.type.dispensers"),
                Lang.s("map.filter.type.furnaces"),
                Lang.s("map.filter.type.brewing"),
                Lang.s("map.filter.type.crafters")
        };
        boolean[] typeActive = {
                MapPinFilter.chests(),
                MapPinFilter.barrels(),
                MapPinFilter.shulkers(),
                MapPinFilter.hoppers(),
                MapPinFilter.dispensers(),
                MapPinFilter.furnaces(),
                MapPinFilter.brewing(),
                MapPinFilter.crafters()
        };
        for (int i = 0; i < 8; i++) {
            int col = i & 1;
            int row = i >> 1;
            int px0 = col == 0 ? innerX0 : innerX0 + halfW + 4;
            int px1 = col == 0 ? innerX0 + halfW : innerX1;
            int py0 = fpTypesY0 + row * 17;
            int py1 = py0 + 15;
            drawFilterPill(g, px0, px1, py0, py1, typeLabels[i], typeActive[i], 0xFF1F6A7A, mouseX, mouseY);
        }

        int shulkerHeaderY = fpTypesY0 + 4 * 17 + 3;
        g.text(this.font, Lang.s("map.filter.shulker_colors"), innerX0, shulkerHeaderY + 2, 0xFF9AA0B0, true);

        String noneTxt = Lang.s("map.filter.none");
        String allTxt = Lang.s("map.filter.all");
        int noneW = this.font.width(noneTxt) + 8;
        int allW = this.font.width(allTxt) + 8;
        fpShulkerBtnY0 = shulkerHeaderY;
        fpShulkerBtnY1 = shulkerHeaderY + 12;
        fpShulkerNoneX1 = innerX1;
        fpShulkerNoneX0 = fpShulkerNoneX1 - noneW;
        fpShulkerAllX1 = fpShulkerNoneX0 - 3;
        fpShulkerAllX0 = fpShulkerAllX1 - allW;

        drawTinyAction(g, fpShulkerAllX0, fpShulkerAllX1, fpShulkerBtnY0, fpShulkerBtnY1, allTxt, mouseX, mouseY);
        drawTinyAction(g, fpShulkerNoneX0, fpShulkerNoneX1, fpShulkerBtnY0, fpShulkerBtnY1, noneTxt, mouseX, mouseY);

        fpSwatchesY0 = fpShulkerBtnY1 + 4;
        int swW = 20;
        int swH = 14;
        int swGap = 4;
        List<MapPinFilter.ShulkerColor> colors = MapPinFilter.SHULKER_COLORS;
        for (int i = 0; i < colors.size(); i++) {
            int row = i < 9 ? 0 : 1;
            int col = i < 9 ? i : i - 9;
            int sx0 = innerX0 + col * (swW + swGap);
            int sy0 = fpSwatchesY0 + row * (swH + 4);
            int sx1 = sx0 + swW;
            int sy1 = sy0 + swH;
            MapPinFilter.ShulkerColor sc = colors.get(i);
            boolean enabled = MapPinFilter.isShulkerColorEnabled(sc.id());
            boolean hover = mouseX >= sx0 && mouseX < sx1 && mouseY >= sy0 && mouseY < sy1;
            if (hover) {
                hoveredColor = sc;
            }
            int border = hover ? 0xFFFFFFFF : enabled ? 0xFFB0B8CC : 0xFF343844;
            g.fill(sx0, sy0, sx1, sy1, border);
            int fill = sc.rgb();
            if (!enabled) {
                int r = ((fill >> 16) & 0xFF) / 3;
                int gr = ((fill >> 8) & 0xFF) / 3;
                int b = (fill & 0xFF) / 3;
                fill = 0xFF000000 | (r << 16) | (gr << 8) | b;
            }
            g.fill(sx0 + 1, sy0 + 1, sx1 - 1, sy1 - 1, fill);
            if (!enabled) {
                for (int d = 2; d < swH - 2; d++) {
                    int lx = sx0 + 2 + (d - 2) * (swW - 5) / Math.max(1, swH - 5);
                    g.fill(lx, sy0 + d, lx + 2, sy0 + d + 1, 0xDDFF5050);
                }
            }
        }
    }

    private void drawFilterPill(GuiGraphicsExtractor g, int x0, int x1, int y0, int y1,
                                String label, boolean active, int activeBg, int mouseX, int mouseY) {
        boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        int border = hover ? 0xFFFFFFFF : active ? 0xFF70849A : 0xFF343844;
        int bg = active ? activeBg : hover ? 0xFF282C36 : 0xFF1A1D24;
        g.fill(x0, y0, x1, y1, border);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, bg);
        int textColor = active ? 0xFFFFFFFF : 0xFF808696;
        String fit = fitText(label, (x1 - x0) - 8);
        int tx = x0 + ((x1 - x0) - this.font.width(fit)) / 2;
        g.text(this.font, fit, tx, y0 + 4, textColor, true);
    }

    private void drawTinyAction(GuiGraphicsExtractor g, int x0, int x1, int y0, int y1,
                                String label, int mouseX, int mouseY) {
        boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        g.fill(x0, y0, x1, y1, hover ? 0xFFFFFFFF : 0xFF444A5C);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, hover ? 0xFF343A4A : 0xFF1E222C);
        g.text(this.font, label, x0 + 4, y0 + 2, 0xFFE0E4EE, true);
    }

    private String fitText(String text, int maxW) {
        if (maxW <= 0) {
            return "";
        }
        if (this.font.width(text) <= maxW) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && this.font.width(cut + "…") > maxW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private boolean handleFilterPanelClick(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x();
        double my = event.y();
        int btn = event.button();

        if (filterSearchBox != null
                && mx >= filterSearchBox.getX() && mx < filterSearchBox.getX() + filterSearchBox.getWidth()
                && my >= filterSearchBox.getY() && my < filterSearchBox.getY() + filterSearchBox.getHeight()) {
            this.setFocused(filterSearchBox);
            filterSearchBox.setFocused(true);
            filterSearchBox.mouseClicked(event, isDoubleClick);
            return true;
        }
        unfocusFilterSearch();

        if (btn == InputConstants.MOUSE_BUTTON_LEFT
                && mx >= fpResetX0 && mx < fpResetX1 && my >= fpResetY0 && my < fpResetY1) {
            MapPinFilter.reset();
            if (filterSearchBox != null) {
                filterSearchBox.setValue("");
            }
            return true;
        }

        int innerX0 = fpX0 + 8;
        int innerX1 = fpX1 - 8;
        int innerW = innerX1 - innerX0;
        int halfW = (innerW - 4) / 2;

        if (btn == InputConstants.MOUSE_BUTTON_LEFT && my >= fpStatusY0 && my < fpStatusY1) {
            if (mx >= innerX0 && mx < innerX0 + halfW) {
                MapPinFilter.toggleShowOpened();
                return true;
            }
            if (mx >= innerX0 + halfW + 4 && mx < innerX1) {
                MapPinFilter.toggleShowUnopened();
                return true;
            }
        }

        if (btn == InputConstants.MOUSE_BUTTON_LEFT && my >= fpTypesY0 && my < fpTypesY0 + 4 * 17) {
            int row = (int) ((my - fpTypesY0) / 17);
            double localY = (my - fpTypesY0) - row * 17;
            if (row >= 0 && row < 4 && localY < 15) {
                int col = -1;
                if (mx >= innerX0 && mx < innerX0 + halfW) {
                    col = 0;
                } else if (mx >= innerX0 + halfW + 4 && mx < innerX1) {
                    col = 1;
                }
                if (col >= 0) {
                    int idx = row * 2 + col;
                    switch (idx) {
                        case 0 -> MapPinFilter.toggleChests();
                        case 1 -> MapPinFilter.toggleBarrels();
                        case 2 -> MapPinFilter.toggleShulkers();
                        case 3 -> MapPinFilter.toggleHoppers();
                        case 4 -> MapPinFilter.toggleDispensers();
                        case 5 -> MapPinFilter.toggleFurnaces();
                        case 6 -> MapPinFilter.toggleBrewing();
                        case 7 -> MapPinFilter.toggleCrafters();
                        default -> {}
                    }
                    return true;
                }
            }
        }

        if (btn == InputConstants.MOUSE_BUTTON_LEFT && my >= fpShulkerBtnY0 && my < fpShulkerBtnY1) {
            if (mx >= fpShulkerAllX0 && mx < fpShulkerAllX1) {
                MapPinFilter.setAllShulkerColors(true);
                return true;
            }
            if (mx >= fpShulkerNoneX0 && mx < fpShulkerNoneX1) {
                MapPinFilter.setAllShulkerColors(false);
                return true;
            }
        }

        if (btn == InputConstants.MOUSE_BUTTON_LEFT || btn == InputConstants.MOUSE_BUTTON_RIGHT) {
            int swW = 20;
            int swH = 14;
            int swGap = 4;
            List<MapPinFilter.ShulkerColor> colors = MapPinFilter.SHULKER_COLORS;
            for (int i = 0; i < colors.size(); i++) {
                int row = i < 9 ? 0 : 1;
                int col = i < 9 ? i : i - 9;
                int sx0 = innerX0 + col * (swW + swGap);
                int sy0 = fpSwatchesY0 + row * (swH + 4);
                if (mx >= sx0 && mx < sx0 + swW && my >= sy0 && my < sy0 + swH) {
                    String colorId = colors.get(i).id();
                    if (btn == InputConstants.MOUSE_BUTTON_RIGHT) {
                        MapPinFilter.soloShulkerColor(colorId);
                    } else {
                        MapPinFilter.toggleShulkerColor(colorId);
                    }
                    return true;
                }
            }
        }

        return true;
    }

    private void drawDimToggleIcon(GuiGraphicsExtractor g, boolean hover) {
        int border = hover ? 0xFFFFFFFF : 0xFF3A3A48;
        int bg = hover ? 0xF0262A34 : 0xE014161C;
        g.fill(dimBtnX0 - 1, dimBtnY0 - 1, dimBtnX1 + 1, dimBtnY1 + 1, border);
        g.fill(dimBtnX0, dimBtnY0, dimBtnX1, dimBtnY1, bg);

        int cx = (dimBtnX0 + dimBtnX1) / 2;
        int cy = (dimBtnY0 + dimBtnY1) / 2;
        boolean on = AreaPlan.isDimOutside();
        int col = on ? 0xFFFFFFFF : 0xFF6E7382;

        g.fill(cx - 6, cy - 6, cx - 2, cy - 5, col);
        g.fill(cx - 6, cy - 6, cx - 5, cy - 2, col);
        g.fill(cx + 2, cy - 6, cx + 6, cy - 5, col);
        g.fill(cx + 5, cy - 6, cx + 6, cy - 2, col);
        g.fill(cx - 6, cy + 5, cx - 2, cy + 6, col);
        g.fill(cx - 6, cy + 2, cx - 5, cy + 6, col);
        g.fill(cx + 2, cy + 5, cx + 6, cy + 6, col);
        g.fill(cx + 5, cy + 2, cx + 6, cy + 6, col);

        g.fill(cx - 3, cy - 3, cx + 3, cy + 3, on ? 0xFFFFFFFF : 0xFF555B6A);
        if (!on) {
            for (int i = -6; i <= 6; i++) {
                g.fill(cx + i, cy + i, cx + i + 2, cy + i + 1, 0xFFFF7070);
            }
        }
    }

    private void drawPinToggleIcon(GuiGraphicsExtractor g, boolean hover) {
        int border = hover ? 0xFFFFFFFF : 0xFF3A3A48;
        int bg = hover ? 0xF0262A34 : 0xE014161C;
        g.fill(pinBtnX0 - 1, pinBtnY0 - 1, pinBtnX1 + 1, pinBtnY1 + 1, border);
        g.fill(pinBtnX0, pinBtnY0, pinBtnX1, pinBtnY1, bg);

        int cx = (pinBtnX0 + pinBtnX1) / 2;
        int cy = (pinBtnY0 + pinBtnY1) / 2;
        int col = showPins ? 0xFFFFFFFF : 0xFF6E7382;

        g.fill(cx - 3, cy - 6, cx + 3, cy - 5, col);
        g.fill(cx - 4, cy - 5, cx + 4, cy - 1, col);
        g.fill(cx - 3, cy - 1, cx + 3, cy + 1, col);
        g.fill(cx - 2, cy + 1, cx + 2, cy + 3, col);
        g.fill(cx - 1, cy + 3, cx + 1, cy + 5, col);
        g.fill(cx, cy + 5, cx + 1, cy + 6, col);
        g.fill(cx - 1, cy - 4, cx + 1, cy - 2, bg);

        if (!showPins) {
            for (int i = -6; i <= 6; i++) {
                g.fill(cx + i, cy + i, cx + i + 2, cy + i + 1, 0xFFFF7070);
            }
        }
    }

    private void drawSimpleTooltip(GuiGraphicsExtractor g, List<String> lines, List<Integer> colors, int mouseX, int mouseY) {
        int maxW = 0;
        for (String line : lines) {
            maxW = Math.max(maxW, this.font.width(line));
        }
        int boxW = maxW + 12;
        int boxH = lines.size() * 11 + 8;
        int tx = mouseX + 10;
        int ty = mouseY + 12;
        if (tx + boxW > this.width - 4) {
            tx = Math.max(4, mouseX - boxW - 8);
        }
        if (ty + boxH > this.height - BOTTOM_BAR - 4) {
            ty = Math.max(TOP_BAR + 4, mouseY - boxH - 8);
        }
        g.fill(tx - 1, ty - 1, tx + boxW + 1, ty + boxH + 1, 0xFF3A3A48);
        g.fill(tx, ty, tx + boxW, ty + boxH, 0xF214161C);
        for (int i = 0; i < lines.size(); i++) {
            g.text(this.font, lines.get(i), tx + 6, ty + 5 + i * 11, colors.get(i), true);
        }
    }

    private void drawPlannedAreaDev(GuiGraphicsExtractor g, Map<Long, MapCache.ChunkColors> cache,
                                    LocalPlayer player, double mapCX, double mapCY,
                                    int minCx, int maxCx, int minCz, int maxCz) {
        int aMinX = AreaPlan.minCx();
        int aMaxX = AreaPlan.maxCx();
        int aMinZ = AreaPlan.minCz();
        int aMaxZ = AreaPlan.maxCz();

        int bx0 = (int) Math.round((mapCX + (aMinX * 16.0 - centerX) * scale) * dev);
        int bx1 = (int) Math.round((mapCX + ((aMaxX + 1) * 16.0 - centerX) * scale) * dev);
        int by0 = (int) Math.round((mapCY + (aMinZ * 16.0 - centerZ) * scale) * dev);
        int by1 = (int) Math.round((mapCY + ((aMaxZ + 1) * 16.0 - centerZ) * scale) * dev);

        if (AreaPlan.isDimOutside()) {
            int dimColor = 0x9A080A0F;
            fillI(g, clipX0, clipY0, clipX1, by0, dimColor);
            fillI(g, clipX0, by1, clipX1, clipY1, dimColor);
            int midY0 = Math.max(clipY0, by0);
            int midY1 = Math.min(clipY1, by1);
            if (midY1 > midY0) {
                fillI(g, clipX0, midY0, bx0, midY1, dimColor);
                fillI(g, bx1, midY0, clipX1, midY1, dimColor);
            }
        }

        int vMinX = Math.max(aMinX, minCx);
        int vMaxX = Math.min(aMaxX, maxCx);
        int vMinZ = Math.max(aMinZ, minCz);
        int vMaxZ = Math.min(aMaxZ, maxCz);
        long visArea = (long) (vMaxX - vMinX + 1) * (long) (vMaxZ - vMinZ + 1);

        if (vMinX <= vMaxX && vMinZ <= vMaxZ && visArea > 0L && visArea <= 16384L) {
            for (int cz = vMinZ; cz <= vMaxZ; cz++) {
                int runStart = Integer.MIN_VALUE;
                for (int cx = vMinX; cx <= vMaxX + 1; cx++) {
                    boolean missing = cx <= vMaxX && !cache.containsKey(MapCache.key(cx, cz));
                    if (missing && runStart == Integer.MIN_VALUE) {
                        runStart = cx;
                    } else if (!missing && runStart != Integer.MIN_VALUE) {
                        int x0 = (int) Math.round((mapCX + (runStart * 16.0 - centerX) * scale) * dev);
                        int x1 = (int) Math.round((mapCX + (cx * 16.0 - centerX) * scale) * dev);
                        int y0 = (int) Math.round((mapCY + (cz * 16.0 - centerZ) * scale) * dev);
                        int y1 = (int) Math.round((mapCY + ((cz + 1) * 16.0 - centerZ) * scale) * dev);
                        fillI(g, x0, y0, x1, y1, 0x3620C8FF);
                        runStart = Integer.MIN_VALUE;
                    }
                }
            }
        }

        int t = Math.max(2, (int) Math.round(dev));
        fillI(g, bx0, by0, bx1, by0 + t, 0xEE38D8FF);
        fillI(g, bx0, by1 - t, bx1, by1, 0xEE38D8FF);
        fillI(g, bx0, by0, bx0 + t, by1, 0xEE38D8FF);
        fillI(g, bx1 - t, by0, bx1, by1, 0xEE38D8FF);

        AreaPlan.Progress prog = AreaPlan.computeProgress(player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
        if (prog.hasMissing()) {
            int nx0 = (int) Math.round((mapCX + (prog.nearestCx() * 16.0 - centerX) * scale) * dev);
            int nx1 = (int) Math.round((mapCX + ((prog.nearestCx() + 1) * 16.0 - centerX) * scale) * dev);
            int ny0 = (int) Math.round((mapCY + (prog.nearestCz() * 16.0 - centerZ) * scale) * dev);
            int ny1 = (int) Math.round((mapCY + ((prog.nearestCz() + 1) * 16.0 - centerZ) * scale) * dev);
            int pulseColor = (((System.currentTimeMillis() / 300) & 1) == 0) ? 0xFFFFD070 : 0xFFFFFFFF;
            fillI(g, nx0, ny0, nx1, ny0 + t, pulseColor);
            fillI(g, nx0, ny1 - t, nx1, ny1, pulseColor);
            fillI(g, nx0, ny0, nx0 + t, ny1, pulseColor);
            fillI(g, nx1 - t, ny0, nx1, ny1, pulseColor);
        }
    }

    private void drawAreaBar(GuiGraphicsExtractor g, LocalPlayer player, int mouseX, int mouseY, long now) {
        areaBarY0 = TOP_BAR;
        areaBarY1 = TOP_BAR + 20;
        g.fill(0, areaBarY0, this.width, areaBarY1, 0xE814161C);
        g.fill(0, areaBarY1 - 1, this.width, areaBarY1, 0xFF3A3A48);

        abCoordsX0 = 0;
        abCoordsX1 = 0;
        abRedrawX0 = 0;
        abRedrawX1 = 0;
        abClipX0 = 0;
        abClipX1 = 0;
        abTrimX0 = 0;
        abTrimX1 = 0;
        abHudX0 = 0;
        abHudX1 = 0;
        abAutoX0 = 0;
        abAutoX1 = 0;
        abClearX0 = 0;
        abClearX1 = 0;

        int cy = areaBarY0 + 6;
        int by0 = areaBarY0 + 2;
        int by1 = areaBarY1 - 3;

        if (selectingArea) {
            String cancelLabel = Lang.s("common.cancel");
            abClearX1 = this.width - 8;
            abClearX0 = abClearX1 - this.font.width(cancelLabel) - 10;
            drawMiniButton(g, abClearX0, abClearX1, by0, by1, cancelLabel,
                    0xFF5A2A2A, 0xFF7A3838, mouseX, mouseY);

            String coordsLabel = Lang.s("area.btn_coords");
            abCoordsX1 = abClearX0 - 4;
            abCoordsX0 = abCoordsX1 - this.font.width(coordsLabel) - 10;
            drawMiniButton(g, abCoordsX0, abCoordsX1, by0, by1, coordsLabel,
                    0xFF1F6A7A, 0xFF2B8AA0, mouseX, mouseY);

            String hint = Lang.s("area.select_hint");
            if (this.font.width(hint) <= abCoordsX0 - 14) {
                g.text(this.font, hint, 8, cy, 0xFF38D8FF, true);
            } else {
                g.text(this.font, Lang.s("area.select_hint_short"), 8, cy, 0xFF38D8FF, true);
            }
            return;
        }

        int rx = this.width - 8;

        String clearTxt = Lang.s("area.btn_clear");
        abClearX1 = rx;
        abClearX0 = abClearX1 - this.font.width(clearTxt) - 10;
        drawMiniButton(g, abClearX0, abClearX1, by0, by1, clearTxt, 0xFF5A2A2A, 0xFF7A3838, mouseX, mouseY);
        rx = abClearX0 - 4;

        String autoTxt = Lang.s("area.btn_autostop", AreaPlan.isAutoStop() ? Lang.s("common.on") : Lang.s("common.off"));
        abAutoX1 = rx;
        abAutoX0 = abAutoX1 - this.font.width(autoTxt) - 10;
        drawMiniButton(g, abAutoX0, abAutoX1, by0, by1, autoTxt,
                AreaPlan.isAutoStop() ? 0xFF2E6B48 : 0xFF262A34,
                AreaPlan.isAutoStop() ? 0xFF3E8C5E : 0xFF363C4A, mouseX, mouseY);
        rx = abAutoX0 - 4;

        String hudTxt = Lang.s("area.btn_hud", AreaPlan.isHudVisible() ? Lang.s("common.on") : Lang.s("common.off"));
        abHudX1 = rx;
        abHudX0 = abHudX1 - this.font.width(hudTxt) - 10;
        drawMiniButton(g, abHudX0, abHudX1, by0, by1, hudTxt,
                AreaPlan.isHudVisible() ? 0xFF1F6A7A : 0xFF262A34,
                AreaPlan.isHudVisible() ? 0xFF2B8AA0 : 0xFF363C4A, mouseX, mouseY);
        rx = abHudX0 - 4;

        boolean confirmingTrim = trimConfirmUntil > now;
        String trimTxt = confirmingTrim ? Lang.s("area.btn_trim_confirm") : Lang.s("area.btn_trim");
        abTrimX1 = rx;
        abTrimX0 = abTrimX1 - this.font.width(trimTxt) - 10;
        drawMiniButton(g, abTrimX0, abTrimX1, by0, by1, trimTxt,
                confirmingTrim ? 0xFFA82C2C : 0xFF6B4A1E,
                confirmingTrim ? 0xFFD03838 : 0xFF8C6228, mouseX, mouseY);
        rx = abTrimX0 - 4;

        String clipTxt = Lang.s("area.btn_clip", AreaPlan.isClipToArea() ? Lang.s("common.on") : Lang.s("common.off"));
        abClipX1 = rx;
        abClipX0 = abClipX1 - this.font.width(clipTxt) - 10;
        drawMiniButton(g, abClipX0, abClipX1, by0, by1, clipTxt,
                AreaPlan.isClipToArea() ? 0xFF8A6A1A : 0xFF262A34,
                AreaPlan.isClipToArea() ? 0xFFB08924 : 0xFF363C4A, mouseX, mouseY);
        rx = abClipX0 - 4;

        String redrawTxt = Lang.s("area.btn_redraw");
        abRedrawX1 = rx;
        abRedrawX0 = abRedrawX1 - this.font.width(redrawTxt) - 10;
        drawMiniButton(g, abRedrawX0, abRedrawX1, by0, by1, redrawTxt, 0xFF2E384D, 0xFF42516E, mouseX, mouseY);

        AreaPlan.Progress prog = AreaPlan.computeProgress(player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4);
        String pct = String.format(Locale.ROOT, "%.1f%%", prog.percent());
        String summary = Lang.s("area.bar_summary", prog.recorded(), prog.total(), pct, prog.remaining());
        int maxSummaryW = abRedrawX0 - 14;
        if (this.font.width(summary) > maxSummaryW) {
            summary = prog.recorded() + "/" + prog.total() + " (" + pct + ")";
        }
        int summaryColor = prog.remaining() == 0 && prog.total() > 0 ? 0xFF70FF90 : 0xFFFFFFFF;
        if (maxSummaryW > 30) {
            g.text(this.font, summary, 8, cy, summaryColor, true);
        }
    }

    private void drawMiniButton(GuiGraphicsExtractor g, int x0, int x1, int y0, int y1,
                                String text, int bg, int bgHover, int mouseX, int mouseY) {
        boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        g.fill(x0, y0, x1, y1, 0xFF5A6074);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, hover ? bgHover : bg);
        g.text(this.font, text, x0 + 5, y0 + 4, 0xFFFFFFFF, true);
    }

    private void drawContainerPins(GuiGraphicsExtractor g, String dimensionId,
                                   double mapCX, double mapCY,
                                   int minCx, int maxCx, int minCz, int maxCz,
                                   int mouseX, int mouseY, boolean inMap) {
        List<ContainerDatabase.ContainerEntry> visible = ContainerDatabase.inChunkRange(dimensionId, minCx, maxCx, minCz, maxCz);
        if (visible.isEmpty()) {
            return;
        }
        Map<Long, List<ContainerDatabase.ContainerEntry>> grouped = new LinkedHashMap<>();
        for (ContainerDatabase.ContainerEntry e : visible) {
            if (ContainerDatabase.isSecondaryHalf(e)) {
                continue;
            }
            if (!MapPinFilter.matches(e)) {
                continue;
            }
            long colKey = (((long) e.x()) << 32) | (e.z() & 0xFFFFFFFFL);
            grouped.computeIfAbsent(colKey, k -> new ArrayList<>()).add(e);
        }

        int pinR = Math.max((int) Math.round(2.5 * dev),
                Math.min((int) Math.round(5.0 * dev), (int) Math.round((scale * 0.5 + 2.5) * dev)));
        int outline = Math.max(1, (int) Math.round(dev));
        int innerDot = Math.max(1, (int) Math.round(dev));
        int hitR = Math.max(pinR + outline, (int) Math.round(5.0 * dev));
        int pad = (int) Math.round(16.0 * dev);
        double mxDev = mouseX * dev;
        double myDev = mouseY * dev;

        List<PinGroup> pins = new ArrayList<>(grouped.size());
        PinGroup bestHover = null;
        double bestDistSq = Double.MAX_VALUE;

        for (List<ContainerDatabase.ContainerEntry> col : grouped.values()) {
            ContainerDatabase.ContainerEntry top = col.get(0);
            for (int i = 1; i < col.size(); i++) {
                ContainerDatabase.ContainerEntry cand = col.get(i);
                if (cand.opened() != top.opened()) {
                    if (cand.opened()) {
                        top = cand;
                    }
                } else if (cand.y() > top.y()) {
                    top = cand;
                }
            }
            int sxDev = (int) Math.round((mapCX + (top.x() + 0.5 - centerX) * scale) * dev);
            int syDev = (int) Math.round((mapCY + (top.z() + 0.5 - centerZ) * scale) * dev);
            if (sxDev < clipX0 - pad || sxDev > clipX1 + pad || syDev < clipY0 - pad || syDev > clipY1 + pad) {
                continue;
            }
            PinGroup pg = new PinGroup(top, col.size(), sxDev, syDev);
            pins.add(pg);

            if (inMap && Math.abs(mxDev - sxDev) <= hitR && Math.abs(myDev - syDev) <= hitR) {
                double dx = mxDev - sxDev;
                double dy = myDev - syDev;
                double d2 = dx * dx + dy * dy;
                if (d2 < bestDistSq) {
                    bestDistSq = d2;
                    bestHover = pg;
                }
            }
        }

        for (PinGroup pg : pins) {
            int sxDev = pg.sxDev();
            int syDev = pg.syDev();
            boolean isHovered = pg == bestHover;
            int color = ContainerDatabase.colorFor(pg.top());
            int outlineColor = isHovered ? 0xFFFFFFFF : 0xE0000000;
            fillI(g, sxDev - pinR - outline, syDev - pinR - outline,
                    sxDev + pinR + outline, syDev + pinR + outline, outlineColor);
            fillI(g, sxDev - pinR, syDev - pinR, sxDev + pinR, syDev + pinR, color);
            if (!pg.top().opened()) {
                fillI(g, sxDev - innerDot, syDev - innerDot, sxDev + innerDot, syDev + innerDot, 0xFF14161C);
            }
            if (pg.count() > 1 && scale >= 0.75) {
                String badge = String.valueOf(pg.count());
                int bx = sxDev + pinR;
                int by = syDev + pinR - (int) Math.round(3.0 * dev);
                int bwDev = (int) Math.round(this.font.width(badge) * dev);
                fillI(g, bx - (int) Math.round(2.0 * dev), by - (int) Math.round(dev),
                        bx + bwDev + (int) Math.round(dev), by + (int) Math.round(9.0 * dev), 0xE014161C);
                g.pose().pushMatrix();
                g.pose().translate(bx, by);
                g.pose().scale((float) dev, (float) dev);
                g.text(this.font, badge, 0, 0, 0xFFFFFFFF, false);
                g.pose().popMatrix();
            }
        }
        hoveredPin = bestHover;
    }

    private void drawPinTooltip(GuiGraphicsExtractor g, PinGroup pin, int mouseX, int mouseY) {
        ContainerDatabase.ContainerEntry e = pin.top();
        ContainerDatabase.ContainerEntry partner = ContainerDatabase.partnerOf(e);
        boolean dbl = partner != null && e.slotCount() == 27 && partner.slotCount() == 27;

        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();

        String title = e.displayTitle() + (dbl ? " (" + Lang.s("viewer.double") + ")" : "");
        lines.add(title);
        colors.add(0xFFFFFFFF);

        if (e.opened()) {
            int filled = dbl ? (e.filledSlots() + partner.filledSlots()) : e.filledSlots();
            int total = dbl ? 54 : e.slotCount();
            lines.add("X:" + e.x() + " Y:" + e.y() + " Z:" + e.z() + "  •  " + filled + "/" + total);
            colors.add(0xFF9AA0B0);
        } else {
            lines.add("X:" + e.x() + " Y:" + e.y() + " Z:" + e.z() + "  •  " + Lang.s("db.unopened_tag"));
            colors.add(0xFFFFD070);
        }

        if (pin.count() > 1) {
            lines.add(Lang.s("map.pin.stack", pin.count()));
            colors.add(0xFFFFD070);
        }
        lines.add(Lang.s("map.pin.click"));
        colors.add(0xFF70FF90);

        drawSimpleTooltip(g, lines, colors, mouseX, mouseY);
    }

    private void drawTerrainTextured(GuiGraphicsExtractor g, String dimensionId,
                                     Map<Long, MapCache.ChunkColors> cache,
                                     double mapCX, double mapCY, int minCx, int maxCx, int minCz, int maxCz) {
        int lodIdx = scale * dev >= 0.6 ? 0 : 1;
        int size = MapTextures.SIZE[lodIdx];
        var regionKeys = ProjectMap.regionKeys(dimensionId);
        int rMinX = minCx >> 4;
        int rMaxX = maxCx >> 4;
        int rMinZ = minCz >> 4;
        int rMaxZ = maxCz >> 4;
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            int y0 = (int) Math.round((mapCY + (rz * 256.0 - centerZ) * scale) * dev);
            int y1 = (int) Math.round((mapCY + ((rz + 1) * 256.0 - centerZ) * scale) * dev);
            if (y1 <= y0) {
                continue;
            }
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                if (!regionKeys.contains(MapCache.key(rx, rz))) {
                    continue;
                }
                MapTextures.Lod lod = MapTextures.acquire(dimensionId, rx, rz, lodIdx, cache, frame);
                if (lod == null) {
                    continue;
                }
                int x0 = (int) Math.round((mapCX + (rx * 256.0 - centerX) * scale) * dev);
                int x1 = (int) Math.round((mapCX + ((rx + 1) * 256.0 - centerX) * scale) * dev);
                if (x1 <= x0) {
                    continue;
                }
                g.blit(RenderPipelines.GUI_TEXTURED, lod.id, x0, y0, 0, 0, x1 - x0, y1 - y0,
                        size, size, size, size);
            }
        }
    }

    private void updateZone(Minecraft mc, ClientLevel level, LocalPlayer player) {
        int effR = Math.max(2, mc.options.getEffectiveRenderDistance());
        int scanR = effR + 1;
        long effRSq = (long) effR * effR;
        int pcx = (int) Math.floor(player.getX() / 16.0);
        int pcz = (int) Math.floor(player.getZ() / 16.0);
        int side = scanR * 2 + 1;
        if (zoneGrid.length != side * side) {
            zoneGrid = new boolean[side * side];
        } else {
            Arrays.fill(zoneGrid, false);
        }
        zoneMinX = pcx - scanR;
        zoneMinZ = pcz - scanR;
        zoneSide = side;
        zoneCount = 0;

        for (int iz = 0; iz < side; iz++) {
            int dz = iz - scanR;
            int cz = zoneMinZ + iz;
            int rowOff = iz * side;
            int runStart = Integer.MIN_VALUE;
            for (int ix = 0; ix <= side; ix++) {
                boolean loaded = false;
                if (ix < side) {
                    int dx = ix - scanR;
                    long lx = Math.max(0, Math.abs(dx) - 2);
                    long lz = Math.max(0, Math.abs(dz) - 2);
                    if (lx * lx + lz * lz < effRSq && isLoaded(level, zoneMinX + ix, cz)) {
                        loaded = true;
                        zoneGrid[rowOff + ix] = true;
                    }
                }
                if (loaded && runStart == Integer.MIN_VALUE) {
                    runStart = zoneMinX + ix;
                } else if (!loaded && runStart != Integer.MIN_VALUE) {
                    if (zoneCount == zoneX0.length) {
                        int n = zoneCount * 2;
                        zoneX0 = Arrays.copyOf(zoneX0, n);
                        zoneX1 = Arrays.copyOf(zoneX1, n);
                        zoneZ = Arrays.copyOf(zoneZ, n);
                    }
                    zoneX0[zoneCount] = runStart;
                    zoneX1[zoneCount] = zoneMinX + ix;
                    zoneZ[zoneCount] = cz;
                    zoneCount++;
                    runStart = Integer.MIN_VALUE;
                }
            }
        }
    }

    private static boolean isLoaded(ClientLevel level, int cx, int cz) {
        var chunk = level.getChunk(cx, cz);
        return chunk instanceof LevelChunk && !(chunk instanceof EmptyLevelChunk);
    }

    private void addVisible(int cx, int cz, MapCache.ChunkColors colors) {
        if (visCount == visCx.length) {
            int n = visCount * 2;
            visCx = Arrays.copyOf(visCx, n);
            visCz = Arrays.copyOf(visCz, n);
            visCol = Arrays.copyOf(visCol, n);
        }
        visCx[visCount] = cx;
        visCz[visCount] = cz;
        visCol[visCount] = colors;
        visCount++;
    }

    private void fillDev(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color) {
        int ix0 = (int) Math.round(x0);
        int iy0 = (int) Math.round(y0);
        int ix1 = (int) Math.round(x1);
        int iy1 = (int) Math.round(y1);
        if (ix1 <= ix0 && x1 > x0) {
            ix1 = ix0 + 1;
        }
        if (iy1 <= iy0 && y1 > y0) {
            iy1 = iy0 + 1;
        }
        fillI(g, ix0, iy0, ix1, iy1, color);
    }

    private void fillI(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int ax0 = Math.max(x0, clipX0);
        int ay0 = Math.max(y0, clipY0);
        int ax1 = Math.min(x1, clipX1);
        int ay1 = Math.min(y1, clipY1);
        if (ax1 > ax0 && ay1 > ay0) {
            g.fill(ax0, ay0, ax1, ay1, color);
        }
    }

    private void drawArrow(GuiGraphicsExtractor g, double cx, double cy, double fx, double fy, double len) {
        if (cx < clipX0 - len || cx > clipX1 + len || cy < clipY0 - len || cy > clipY1 + len) {
            return;
        }
        double wid = len * 0.72;
        double tipU = len;
        double backU = -0.8 * len;
        double notchU = -0.3 * len;
        double reach = len + 3;
        int xMin = (int) Math.floor(cx - reach);
        int xMax = (int) Math.ceil(cx + reach);
        int yMin = (int) Math.floor(cy - reach);
        int yMax = (int) Math.ceil(cy + reach);
        for (int iy = yMin; iy <= yMax; iy++) {
            int runStart = xMin;
            int runClass = 0;
            for (int ix = xMin; ix <= xMax + 1; ix++) {
                int cls = 0;
                if (ix <= xMax) {
                    double px = ix + 0.5 - cx;
                    double py = iy + 0.5 - cy;
                    double u = px * fx + py * fy;
                    double v = -px * fy + py * fx;
                    if (u > backU - 2 && u < tipU + 2 && Math.abs(v) < wid + 2) {
                        if (inTri(u, v, tipU, 0, backU, wid, notchU, 0)
                                || inTri(u, v, tipU, 0, notchU, 0, backU, -wid)) {
                            cls = 2;
                        } else {
                            double d = Math.min(
                                    Math.min(segDist(u, v, tipU, 0, backU, wid), segDist(u, v, backU, wid, notchU, 0)),
                                    Math.min(segDist(u, v, notchU, 0, backU, -wid), segDist(u, v, backU, -wid, tipU, 0)));
                            if (d <= 1.1) {
                                cls = 1;
                            }
                        }
                    }
                }
                if (cls != runClass) {
                    if (runClass != 0) {
                        fillI(g, runStart, iy, ix, iy + 1, runClass == 2 ? 0xFFFFFFFF : 0xE0000000);
                    }
                    runStart = ix;
                    runClass = cls;
                }
            }
        }
    }

    private static boolean inTri(double px, double py, double ax, double ay, double bx, double by,
                                 double cx, double cy) {
        double d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        double d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        double d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double qx = ax + t * dx - px;
        double qy = ay + t * dy - py;
        return Math.sqrt(qx * qx + qy * qy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (filterPanelOpen && event.isEscape()) {
            filterPanelOpen = false;
            unfocusFilterSearch();
            return true;
        }
        if (filterSearchBox != null && filterSearchBox.isFocused() && filterSearchBox.canConsumeInput()) {
            return super.keyPressed(event);
        }
        if (!(this.getFocused() instanceof EditBox eb && eb.canConsumeInput())) {
            int key = event.key();
            if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
                zoomCenter(1.25);
                return true;
            }
            if (key == InputConstants.KEY_MINUS || key == InputConstants.KEY_SUBTRACT) {
                zoomCenter(0.8);
                return true;
            }
            if (System.currentTimeMillis() - openedAtMs >= 180L) {
                if (ChunkVaultClient.matchesOpenMap(event)) {
                    this.onClose();
                    return true;
                }
                if (ChunkVaultClient.matchesToggleRecord(event)) {
                    toggleRecording();
                    return true;
                }
                if (ChunkVaultClient.matchesToggleHud(event)) {
                    boolean on = AreaPlan.toggleHud();
                    showToast(Lang.s("area.btn_hud", on ? Lang.s("common.on") : Lang.s("common.off")));
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x();
        double my = event.y();

        if (filterPanelOpen && fpX1 > fpX0 && mx >= fpX0 && mx < fpX1 && my >= fpY0 && my < fpY1) {
            return handleFilterPanelClick(event, isDoubleClick);
        }

        unfocusFilterSearch();

        if (mx >= zoomInBtnX0 && mx < zoomInBtnX1 && my >= zoomInBtnY0 && my < zoomInBtnY1) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                zoomCenter(1.25);
                return true;
            }
        }

        if (mx >= zoomOutBtnX0 && mx < zoomOutBtnX1 && my >= zoomOutBtnY0 && my < zoomOutBtnY1) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                zoomCenter(0.8);
                return true;
            }
        }

        if (mx >= followBtnX0 && mx < followBtnX1 && my >= followBtnY0 && my < followBtnY1) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                followPlayer = !followPlayer;
                if (followPlayer) {
                    recenterOnPlayer();
                }
                return true;
            }
        }

        if (filterBtnX1 > filterBtnX0 && mx >= filterBtnX0 && mx < filterBtnX1 && my >= filterBtnY0 && my < filterBtnY1) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                filterPanelOpen = !filterPanelOpen;
                if (!filterPanelOpen) {
                    unfocusFilterSearch();
                }
                return true;
            }
        }

        if (dimBtnX1 > dimBtnX0 && mx >= dimBtnX0 && mx < dimBtnX1 && my >= dimBtnY0 && my < dimBtnY1) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                AreaPlan.toggleDimOutside();
                return true;
            }
        }

        if (pinBtnX1 > pinBtnX0 && mx >= pinBtnX0 && mx < pinBtnX1 && my >= pinBtnY0 && my < pinBtnY1) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                showPins = !showPins;
                return true;
            }
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                boolean on = ContainerDatabase.toggleShowUnopened();
                showToast(Lang.s("db.filter_unopened", on ? Lang.s("common.on") : Lang.s("common.off")));
                return true;
            }
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && my >= btnY0 && my < btnY1) {
            if (mx >= newX0 && mx < newX1) {
                if (recordingBlocks()) {
                    return true;
                }
                Minecraft.getInstance().setScreenAndShow(new NewProjectScreen(this));
                return true;
            }
            if (mx >= expX0 && mx < expX1) {
                if (recordingBlocks()) {
                    return true;
                }
                Minecraft.getInstance().setScreenAndShow(new ExplorerScreen(this));
                return true;
            }
            if (btnX1 > btnX0 && mx >= btnX0 && mx < btnX1) {
                toggleRecording();
                return true;
            }
            if (dbX1 > dbX0 && mx >= dbX0 && mx < dbX1) {
                Minecraft.getInstance().setScreenAndShow(new ContainerDatabaseScreen(this));
                return true;
            }
            if (areaX1 > areaX0 && mx >= areaX0 && mx < areaX1) {
                trimConfirmUntil = 0;
                if (!AreaPlan.isActive()) {
                    selectingArea = !selectingArea;
                    areaPanelOpen = selectingArea;
                    draggingArea = false;
                } else {
                    areaPanelOpen = !areaPanelOpen;
                    if (!areaPanelOpen) {
                        selectingArea = false;
                        draggingArea = false;
                    }
                }
                return true;
            }
            if (mx >= setX0 && mx < setX1) {
                Minecraft.getInstance().setScreenAndShow(new SettingsScreen(this));
                return true;
            }
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && areaBarY1 > areaBarY0
                && my >= areaBarY0 && my < areaBarY1) {
            if (selectingArea) {
                if (abCoordsX1 > abCoordsX0 && mx >= abCoordsX0 && mx < abCoordsX1) {
                    Minecraft.getInstance().setScreenAndShow(new AreaInputScreen(this));
                    return true;
                }
                if (mx >= abClearX0 && mx < abClearX1) {
                    selectingArea = false;
                    draggingArea = false;
                    if (!AreaPlan.isActive()) {
                        areaPanelOpen = false;
                    }
                }
                return true;
            }
            if (mx >= abRedrawX0 && mx < abRedrawX1) {
                trimConfirmUntil = 0;
                selectingArea = true;
                draggingArea = false;
                return true;
            }
            if (mx >= abClipX0 && mx < abClipX1) {
                trimConfirmUntil = 0;
                AreaPlan.toggleClipToArea();
                return true;
            }
            if (mx >= abTrimX0 && mx < abTrimX1) {
                if (recordingBlocks()) {
                    trimConfirmUntil = 0;
                    return true;
                }
                long now = System.currentTimeMillis();
                if (trimConfirmUntil > now) {
                    trimConfirmUntil = 0;
                    var res = AreaPlan.trimProjectToArea();
                    if (res.removedChunks() == 0 && res.removedContainers() == 0) {
                        showToast(Lang.s("area.trim_empty"));
                    } else {
                        showToast(Lang.s("area.trimmed", res.removedChunks(), res.removedContainers()));
                    }
                } else {
                    trimConfirmUntil = now + 4000;
                }
                return true;
            }
            if (mx >= abHudX0 && mx < abHudX1) {
                trimConfirmUntil = 0;
                AreaPlan.toggleHud();
                return true;
            }
            if (mx >= abAutoX0 && mx < abAutoX1) {
                trimConfirmUntil = 0;
                AreaPlan.toggleAutoStop();
                return true;
            }
            if (mx >= abClearX0 && mx < abClearX1) {
                trimConfirmUntil = 0;
                AreaPlan.clearPlan();
                areaPanelOpen = false;
                showToast(Lang.s("area.cleared"));
                return true;
            }
            return true;
        }

        trimConfirmUntil = 0;

        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (selectingArea) {
                selectingArea = false;
                draggingArea = false;
                if (!AreaPlan.isActive()) {
                    areaPanelOpen = false;
                }
                return true;
            }
            boolean inAreaBar = areaBarY1 > areaBarY0 && my >= areaBarY0 && my < areaBarY1;
            if (my >= TOP_BAR && my < this.height - BOTTOM_BAR && !inAreaBar) {
                recenterOnPlayer();
                return true;
            }
            return false;
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && my >= TOP_BAR && my < this.height - BOTTOM_BAR) {
            if (selectingArea && ProjectMap.isOpen()) {
                draggingArea = true;
                areaStartCx = screenToChunkX(mx);
                areaStartCz = screenToChunkZ(my);
                areaEndCx = areaStartCx;
                areaEndCz = areaStartCz;
                return true;
            }
            draggingMap = true;
            dragged = false;
            dragStartX = mx;
            dragStartY = my;
            return true;
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    private boolean recordingBlocks() {
        if (RecordingState.get().isActive()) {
            showToast(Lang.s("toast.stop_first"));
            return true;
        }
        return false;
    }

    private void toggleRecording() {
        if (RecordingState.get().isActive()) {
            var result = ChunkVaultClient.stopRecording();
            if (result != null) {
                showToast(Lang.s("toast.stopped", result.chunks(), result.chests(), result.entities()));
            }
            return;
        }
        if (Minecraft.getInstance().level == null || !ProjectMap.isOpen()) {
            return;
        }
        int captured = ChunkVaultClient.startRecording(ProjectMap.name());
        if (captured >= 0) {
            AutoRecord.setWorld(AutoRecord.serverKey(), ProjectMap.name());
            showToast(Lang.s("toast.started", captured));
        }
    }

    private int drawTopButton(GuiGraphicsExtractor g, int x0, String text, int bg, int bgHover,
                              int mouseX, int mouseY) {
        int x1 = x0 + this.font.width(text) + 14;
        boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= btnY0 && mouseY < btnY1;
        g.fill(x0, btnY0, x1, btnY1, 0xFFFFFFFF);
        g.fill(x0 + 1, btnY0 + 1, x1 - 1, btnY1 - 1, hover ? bgHover : bg);
        g.text(this.font, text, x0 + 7, 7, 0xFFFFFFFF, true);
        return x1;
    }

    void showToast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 5000;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && draggingArea) {
            areaEndCx = screenToChunkX(event.x());
            areaEndCz = screenToChunkZ(event.y());
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && draggingMap) {
            if (!dragged && Math.hypot(event.x() - dragStartX, event.y() - dragStartY) >= 4.0) {
                dragged = true;
                followPlayer = false;
            }
            centerX -= dragX / scale;
            centerZ -= dragY / scale;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && draggingArea) {
            draggingArea = false;
            selectingArea = false;
            areaEndCx = screenToChunkX(event.x());
            areaEndCz = screenToChunkZ(event.y());
            var level = Minecraft.getInstance().level;
            if (level != null && ProjectMap.isOpen()) {
                String dim = level.dimension().identifier().toString();
                AreaPlan.setFromChunks(dim, areaStartCx, areaStartCz, areaEndCx, areaEndCz);
                areaPanelOpen = true;
                showToast(Lang.s("area.set_toast", AreaPlan.totalChunks()));
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && draggingMap) {
            draggingMap = false;
            if (!dragged && hoveredPin != null && ProjectMap.isOpen()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) {
                    String dim = mc.level.dimension().identifier().toString();
                    ContainerDatabase.ContainerEntry top = hoveredPin.top();
                    mc.setScreenAndShow(new ContainerViewerScreen(
                            this, dim, top.x(), top.z(), top.key(), MapPinFilter::matches));
                    return true;
                }
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        if (filterPanelOpen && fpX1 > fpX0 && mouseX >= fpX0 && mouseX < fpX1 && mouseY >= fpY0 && mouseY < fpY1) {
            return true;
        }
        if (followPlayer) {
            zoomCenter(scrollY > 0 ? 1.25 : 0.8);
            return true;
        }
        double mapCX = this.width / 2.0;
        double mapCY = TOP_BAR + (this.height - TOP_BAR - BOTTOM_BAR) / 2.0;
        double blockX = centerX + (mouseX - mapCX) / scale;
        double blockZ = centerZ + (mouseY - mapCY) / scale;

        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * (scrollY > 0 ? 1.25 : 0.8)));
        lastScale = scale;

        centerX = blockX - (mouseX - mapCX) / scale;
        centerZ = blockZ - (mouseY - mapCY) / scale;
        return true;
    }

    @Override
    public void onClose() {
        if (filterPanelOpen) {
            filterPanelOpen = false;
            unfocusFilterSearch();
            return;
        }
        if (selectingArea) {
            selectingArea = false;
            draggingArea = false;
            if (!AreaPlan.isActive()) {
                areaPanelOpen = false;
            }
            return;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
