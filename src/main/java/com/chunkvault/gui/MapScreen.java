package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.chunkvault.state.AutoRecord;
import com.chunkvault.state.RecordingState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Arrays;
import java.util.Map;

public class MapScreen extends Screen {

    private static final int TOP_BAR = 22;
    private static final int BOTTOM_BAR = 18;
    private static final double MIN_SCALE = 0.125;
    private static final double MAX_SCALE = 16.0;
    private static final int RECT_BUDGET = 9000;

    private static double lastScale = 1.0;

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
    private int setX0;
    private int setX1;
    private String toast;
    private long toastUntil;

    private int[] zoneX0 = new int[256];
    private int[] zoneX1 = new int[256];
    private int[] zoneZ = new int[256];
    private int zoneCount;
    private int zoneCx;
    private int zoneCz;
    private int zoneR;

    public MapScreen() {
        super(Lang.c("map.title"));
        recenterOnPlayer();
    }

    private void recenterOnPlayer() {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            centerX = player.getX();
            centerZ = player.getZ();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        frame++;

        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        var level = mc.level;

        g.fill(0, 0, this.width, this.height, 0xFF0B0B0F);

        if (player == null || level == null) {
            g.centeredText(this.font, Lang.s("map.no_world"), this.width / 2, this.height / 2, 0xFFFFFFFF);
            return;
        }

        final int mapX0 = 0;
        final int mapY0 = TOP_BAR;
        final int mapX1 = this.width;
        final int mapY1 = this.height - BOTTOM_BAR;
        final int mapW = mapX1 - mapX0;
        final int mapH = mapY1 - mapY0;
        final double mapCX = mapX0 + mapW / 2.0;
        final double mapCY = mapY0 + mapH / 2.0;
        boolean inMap = mouseY >= mapY0 && mouseY < mapY1;

        dev = Math.max(1.0, (double) mc.getWindow().getWidth() / (double) this.width);
        clipX0 = (int) Math.round(mapX0 * dev);
        clipY0 = (int) Math.round(mapY0 * dev);
        clipX1 = (int) Math.round(mapX1 * dev);
        clipY1 = (int) Math.round(mapY1 * dev);

        String dimensionId = level.dimension().identifier().toString();
        var cache = ProjectMap.forDimension(dimensionId);
        boolean hasProject = ProjectMap.isOpen();

        double ppc = scale * 16.0;
        double ppcDev = ppc * dev;
        var manager = RecordingState.get().saveManager();

        boolean recording = RecordingState.get().isActive() && manager != null;
        if (!recording) {
            zoneCount = 0;
        } else if (frame % 3 == 0) {
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

        if (recording && zoneCount > 0) {
            for (int i = 0; i < zoneCount; i++) {
                int x0 = (int) Math.round((mapCX + (zoneX0[i] * 16.0 - centerX) * scale) * dev);
                int x1 = (int) Math.round((mapCX + (zoneX1[i] * 16.0 - centerX) * scale) * dev);
                int y0 = (int) Math.round((mapCY + (zoneZ[i] * 16.0 - centerZ) * scale) * dev);
                int y1 = (int) Math.round((mapCY + ((zoneZ[i] + 1) * 16.0 - centerZ) * scale) * dev);
                fillI(g, x0, y0, x1, y1, 0x55FF1A1A);
            }
            int fx0 = (int) Math.round((mapCX + ((zoneCx - zoneR) * 16.0 - centerX) * scale) * dev);
            int fx1 = (int) Math.round((mapCX + ((zoneCx + zoneR + 1) * 16.0 - centerX) * scale) * dev);
            int fy0 = (int) Math.round((mapCY + ((zoneCz - zoneR) * 16.0 - centerZ) * scale) * dev);
            int fy1 = (int) Math.round((mapCY + ((zoneCz + zoneR + 1) * 16.0 - centerZ) * scale) * dev);
            fillI(g, fx0, fy0, fx1, fy0 + 1, 0xCCFF4040);
            fillI(g, fx0, fy1 - 1, fx1, fy1, 0xCCFF4040);
            fillI(g, fx0, fy0, fx0 + 1, fy1, 0xCCFF4040);
            fillI(g, fx1 - 1, fy0, fx1, fy1, 0xCCFF4040);
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
            fillI(g, x0, y0, x1, y1, 0x1EFFFFFF);
            fillI(g, x0, y0, x1, y0 + 1, 0xB0FFFFFF);
            fillI(g, x0, y1 - 1, x1, y1, 0xB0FFFFFF);
            fillI(g, x0, y0, x0 + 1, y1, 0xB0FFFFFF);
            fillI(g, x1 - 1, y0, x1, y1, 0xB0FFFFFF);
        }

        double pcx = (mapCX + (player.getX() - centerX) * scale) * dev;
        double pcy = (mapCY + (player.getZ() - centerZ) * scale) * dev;
        double yaw = Math.toRadians(player.getYRot());
        drawArrow(g, pcx, pcy, -Math.sin(yaw), Math.cos(yaw), 3.2 * dev + 5.0);

        g.pose().popMatrix();
        g.disableScissor();

        g.fill(0, 0, this.width, TOP_BAR, 0xFF1A1A22);
        g.fill(0, this.height - BOTTOM_BAR, this.width, this.height, 0xFF1A1A22);

        int x = 8;
        g.text(this.font, "ChunkVault", x, 7, 0xFFFFFFFF, true);
        x += this.font.width("ChunkVault") + 6;
        g.text(this.font, "—", x, 7, 0xFF808090, true);
        x += this.font.width("—") + 6;
        String projectLabel = hasProject ? ProjectMap.name() : Lang.s("map.no_project");
        g.text(this.font, projectLabel, x, 7, hasProject ? 0xFFFFD070 : 0xFF909090, true);
        x += this.font.width(projectLabel) + 14;

        btnY0 = 3;
        btnY1 = TOP_BAR - 3;

        newX0 = x;
        newX1 = drawTopButton(g, newX0, Lang.s("button.new_file"), 0xFF2E8B4E, 0xFF3FB365, mouseX, mouseY);
        expX0 = newX1 + 6;
        expX1 = drawTopButton(g, expX0, Lang.s("button.open"), 0xFFB8901A, 0xFFD9AE2C, mouseX, mouseY);
        int endX = expX1;

        if (hasProject) {
            btnX0 = endX + 6;
            btnX1 = drawTopButton(g, btnX0, recording ? Lang.s("button.stop") : Lang.s("button.record"),
                    recording ? 0xFF7A2020 : 0xFFB03030, recording ? 0xFF9E2C2C : 0xFFD84848, mouseX, mouseY);
            endX = btnX1;
        } else {
            btnX0 = 0;
            btnX1 = 0;
        }

        setX0 = endX + 6;
        setX1 = drawTopButton(g, setX0, Lang.s("button.settings"), 0xFF4E5F82, 0xFF6B7FA8, mouseX, mouseY);

        String status;
        int statusColor;
        if (recording) {
            status = Lang.s("map.status.recording", manager.chunksSaved(), manager.entitiesSeen());
            statusColor = 0xFFFF7070;
        } else if (hasProject) {
            status = Lang.s("map.status.idle");
            statusColor = 0xFF909090;
        } else {
            status = "";
            statusColor = 0;
        }
        int statusX = this.width - this.font.width(status) - 8;
        if (!status.isEmpty() && statusX > setX1 + 12) {
            g.text(this.font, status, statusX, 7, statusColor, true);
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

        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = this.font.width(toast) + 16;
            int tx0 = (this.width - tw) / 2;
            g.fill(tx0, TOP_BAR + 6, tx0 + tw, TOP_BAR + 24, 0xE0101015);
            g.text(this.font, toast, tx0 + 8, TOP_BAR + 11, 0xFF70FF90, true);
        }
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
        int r = mc.options.renderDistance().get();
        int pcx = (int) Math.floor(player.getX() / 16.0);
        int pcz = (int) Math.floor(player.getZ() / 16.0);
        zoneR = r;
        zoneCx = pcx;
        zoneCz = pcz;
        zoneCount = 0;
        for (int cz = pcz - r; cz <= pcz + r; cz++) {
            int runStart = Integer.MIN_VALUE;
            for (int cx = pcx - r; cx <= pcx + r + 1; cx++) {
                boolean loaded = cx <= pcx + r && isLoaded(level, cx, cz);
                if (loaded && runStart == Integer.MIN_VALUE) {
                    runStart = cx;
                } else if (!loaded && runStart != Integer.MIN_VALUE) {
                    if (zoneCount == zoneX0.length) {
                        int n = zoneCount * 2;
                        zoneX0 = Arrays.copyOf(zoneX0, n);
                        zoneX1 = Arrays.copyOf(zoneX1, n);
                        zoneZ = Arrays.copyOf(zoneZ, n);
                    }
                    zoneX0[zoneCount] = runStart;
                    zoneX1[zoneCount] = cx;
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
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.y() >= btnY0 && event.y() < btnY1) {
            double mx = event.x();
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
            if (mx >= setX0 && mx < setX1) {
                Minecraft.getInstance().setScreenAndShow(new SettingsScreen(this));
                return true;
            }
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            recenterOnPlayer();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            centerX -= dragX / scale;
            centerZ -= dragY / scale;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
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
    public boolean isPauseScreen() {
        return false;
    }
}
