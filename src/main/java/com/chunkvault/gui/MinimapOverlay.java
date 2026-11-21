package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.save.ContainerDatabase;
import com.chunkvault.state.AreaPlan;
import com.chunkvault.state.ChunkVaultSettings;
import com.chunkvault.state.MapPinFilter;
import com.chunkvault.state.RecordingState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class MinimapOverlay {

    static final int FOOTER_H = 14;
    static final int PAD = 8;

    private static long frame;
    private static int clipX0;
    private static int clipY0;
    private static int clipX1;
    private static int clipY1;

    private static int[] zoneX0 = new int[256];
    private static int[] zoneX1 = new int[256];
    private static int[] zoneZ = new int[256];
    private static int zoneCount;
    private static boolean[] zoneGrid = new boolean[0];
    private static int zoneMinX;
    private static int zoneMinZ;
    private static int zoneSide;

    private MinimapOverlay() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("chunkvault", "minimap"),
                MinimapOverlay::extract
        );
    }

    static int resolveX(int screenW, int mapSize, double rx) {
        int avail = Math.max(0, screenW - mapSize - PAD * 2);
        return PAD + (int) Math.round(Math.max(0.0, Math.min(1.0, rx)) * avail);
    }

    static int resolveY(int screenH, int mapSize, double ry) {
        int totalH = mapSize + FOOTER_H;
        int avail = Math.max(0, screenH - totalH - PAD * 2);
        return PAD + (int) Math.round(Math.max(0.0, Math.min(1.0, ry)) * avail);
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) {
            return;
        }
        ChunkVaultSettings settings = ChunkVaultSettings.get();
        if (!settings.minimapEnabled() || !ProjectMap.isOpen()) {
            return;
        }
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int mapSize = settings.minimapSize();
        int x0 = resolveX(screenW, mapSize, settings.minimapX());
        int y0 = resolveY(screenH, mapSize, settings.minimapY());
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        renderAt(g, partialTick, x0, y0, mapSize, settings.minimapZoom(), false);
    }

    static void renderAt(GuiGraphicsExtractor g, float partialTick, int x0, int y0,
                         int mapSize, double scale, boolean previewMode) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }

        frame++;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int x1 = x0 + mapSize;
        int y1 = y0 + mapSize;
        int fy1 = y1 + FOOTER_H;

        int borderCol = previewMode ? 0xFF38D8FF : 0xFF3A3A48;
        g.fill(x0 - 1, y0 - 1, x1 + 1, fy1 + 1, borderCol);
        g.fill(x0, y0, x1, y1, 0xE80B0B0F);

        double dev = Math.max(1.0, (double) mc.getWindow().getWidth() / (double) screenW);
        clipX0 = (int) Math.round(x0 * dev);
        clipY0 = (int) Math.round(y0 * dev);
        clipX1 = (int) Math.round(x1 * dev);
        clipY1 = (int) Math.round(y1 * dev);

        var pos = player.getPosition(partialTick);
        double centerX = pos.x();
        double centerZ = pos.z();
        double mapCX = x0 + mapSize / 2.0;
        double mapCY = y0 + mapSize / 2.0;

        String dimensionId = level.dimension().identifier().toString();
        boolean hasProject = ProjectMap.isOpen();
        Map<Long, MapCache.ChunkColors> cache = ProjectMap.forDimension(dimensionId);

        var manager = RecordingState.get().saveManager();
        boolean recording = RecordingState.get().isActive() && manager != null;
        if (!recording) {
            zoneCount = 0;
            zoneSide = 0;
        } else if (frame % 5 == 0 || zoneSide == 0) {
            updateZone(mc, level, player);
        }

        MapTextures.beginFrame(frame, dimensionId, cache);
        boolean useTex = MapTextures.available();

        double half = (mapSize / 2.0) / scale;
        int minCx = (int) Math.floor((centerX - half) / 16.0);
        int maxCx = (int) Math.floor((centerX + half) / 16.0);
        int minCz = (int) Math.floor((centerZ - half) / 16.0);
        int maxCz = (int) Math.floor((centerZ + half) / 16.0);

        g.enableScissor(x0, y0, x1, y1);
        float inv = (float) (1.0 / dev);
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);

        if (hasProject) {
            if (useTex) {
                drawTerrain(g, dimensionId, cache, mapCX, mapCY, centerX, centerZ, scale, dev, minCx, maxCx, minCz, maxCz);
            } else {
                double ppcDev = scale * 16.0 * dev;
                for (int cz = minCz; cz <= maxCz; cz++) {
                    for (int cx = minCx; cx <= maxCx; cx++) {
                        MapCache.ChunkColors c = cache.get(MapCache.key(cx, cz));
                        if (c != null && c.avg != 0) {
                            int sx0 = (int) Math.round((mapCX + (cx * 16.0 - centerX) * scale) * dev);
                            int sy0 = (int) Math.round((mapCY + (cz * 16.0 - centerZ) * scale) * dev);
                            int sx1 = (int) Math.round(sx0 + ppcDev);
                            int sy1 = (int) Math.round(sy0 + ppcDev);
                            fillI(g, sx0, sy0, Math.max(sx0 + 1, sx1), Math.max(sy0 + 1, sy1), c.avg);
                        }
                    }
                }
            }

            if (AreaPlan.isActive() && dimensionId.equals(AreaPlan.dimension())) {
                int bx0 = (int) Math.round((mapCX + (AreaPlan.minCx() * 16.0 - centerX) * scale) * dev);
                int bx1 = (int) Math.round((mapCX + ((AreaPlan.maxCx() + 1) * 16.0 - centerX) * scale) * dev);
                int by0 = (int) Math.round((mapCY + (AreaPlan.minCz() * 16.0 - centerZ) * scale) * dev);
                int by1 = (int) Math.round((mapCY + ((AreaPlan.maxCz() + 1) * 16.0 - centerZ) * scale) * dev);
                int t = Math.max(1, (int) Math.round(dev));
                fillI(g, bx0, by0, bx1, by0 + t, 0xEE38D8FF);
                fillI(g, bx0, by1 - t, bx1, by1, 0xEE38D8FF);
                fillI(g, bx0, by0, bx0 + t, by1, 0xEE38D8FF);
                fillI(g, bx1 - t, by0, bx1, by1, 0xEE38D8FF);
            }
        }

        if (recording && zoneCount > 0) {
            for (int i = 0; i < zoneCount; i++) {
                int zx0 = (int) Math.round((mapCX + (zoneX0[i] * 16.0 - centerX) * scale) * dev);
                int zx1 = (int) Math.round((mapCX + (zoneX1[i] * 16.0 - centerX) * scale) * dev);
                int zy0 = (int) Math.round((mapCY + (zoneZ[i] * 16.0 - centerZ) * scale) * dev);
                int zy1 = (int) Math.round((mapCY + ((zoneZ[i] + 1) * 16.0 - centerZ) * scale) * dev);
                fillI(g, zx0, zy0, zx1, zy1, 0x40FF1A1A);
            }
            if (zoneSide > 0) {
                int t = Math.max(1, (int) Math.round(dev));
                int borderColor = 0xEEFF4040;
                for (int iz = 0; iz < zoneSide; iz++) {
                    int cz = zoneMinZ + iz;
                    int zy0 = (int) Math.round((mapCY + (cz * 16.0 - centerZ) * scale) * dev);
                    int zy1 = (int) Math.round((mapCY + ((cz + 1) * 16.0 - centerZ) * scale) * dev);
                    int rowOff = iz * zoneSide;
                    for (int ix = 0; ix < zoneSide; ix++) {
                        if (!zoneGrid[rowOff + ix]) {
                            continue;
                        }
                        int cx = zoneMinX + ix;
                        int zx0 = (int) Math.round((mapCX + (cx * 16.0 - centerX) * scale) * dev);
                        int zx1 = (int) Math.round((mapCX + ((cx + 1) * 16.0 - centerX) * scale) * dev);
                        if (iz == 0 || !zoneGrid[rowOff - zoneSide + ix]) {
                            fillI(g, zx0, zy0, zx1, zy0 + t, borderColor);
                        }
                        if (iz == zoneSide - 1 || !zoneGrid[rowOff + zoneSide + ix]) {
                            fillI(g, zx0, zy1 - t, zx1, zy1, borderColor);
                        }
                        if (ix == 0 || !zoneGrid[rowOff + ix - 1]) {
                            fillI(g, zx0, zy0, zx0 + t, zy1, borderColor);
                        }
                        if (ix == zoneSide - 1 || !zoneGrid[rowOff + ix + 1]) {
                            fillI(g, zx1 - t, zy0, zx1, zy1, borderColor);
                        }
                    }
                }
            }
        }

        if (hasProject) {
            drawPins(g, dimensionId, mapCX, mapCY, centerX, centerZ, scale, dev, minCx, maxCx, minCz, maxCz);
        }

        double pcx = mapCX * dev;
        double pcy = mapCY * dev;
        double yaw = Math.toRadians(player.getViewYRot(partialTick));
        drawArrow(g, pcx, pcy, -Math.sin(yaw), Math.cos(yaw), 2.2 * dev + 3.0);

        g.pose().popMatrix();
        g.disableScissor();

        g.fill(x0, y1, x1, fy1, 0xEB14161C);
        g.fill(x0, y1, x1, y1 + 1, 0xFF2C303D);

        Font font = mc.font;
        String statusText = recording
                ? Lang.s("minimap.status.rec", manager.chunksSaved())
                : Lang.s("minimap.status.off");
        int statusColor = recording ? 0xFFFF6060 : 0xFF8C94A6;
        int statusX = x0 + 4;
        int textY = y1 + 3;
        int maxStatusW = mapSize - 8;
        if (font.width(statusText) > maxStatusW) {
            statusText = recording ? "● REC" : "○ OFF";
        }
        g.text(font, statusText, statusX, textY, statusColor, true);

        String coords = (int) Math.floor(centerX) + ", " + (int) Math.floor(centerZ);
        int coordsW = font.width(coords);
        if (statusX + font.width(statusText) + 6 + coordsW <= x1 - 4) {
            g.text(font, coords, x1 - 4 - coordsW, textY, 0xFFCCCCCC, true);
        }
    }

    private static void drawTerrain(GuiGraphicsExtractor g, String dimensionId,
                                    Map<Long, MapCache.ChunkColors> cache,
                                    double mapCX, double mapCY, double centerX, double centerZ,
                                    double scale, double dev, int minCx, int maxCx, int minCz, int maxCz) {
        double pxPerBlock = scale * dev;
        int lodIdx = pxPerBlock >= 0.5 ? 0 : (pxPerBlock >= 0.25 ? 1 : 2);
        var regionKeys = ProjectMap.regionKeys(dimensionId);
        int rMinX = minCx >> 4;
        int rMaxX = maxCx >> 4;
        int rMinZ = minCz >> 4;
        int rMaxZ = maxCz >> 4;
        double originX = rMinX * 256.0;
        double originZ = rMinZ * 256.0;
        double offX = Math.round((mapCX + (originX - centerX) * scale) * dev);
        double offY = Math.round((mapCY + (originZ - centerZ) * scale) * dev);

        g.pose().pushMatrix();
        g.pose().translate((float) offX, (float) offY);
        float texScale = (float) pxPerBlock;
        g.pose().scale(texScale, texScale);
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            int relZ = (rz - rMinZ) * 256;
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                if (!regionKeys.contains(MapCache.key(rx, rz))) {
                    continue;
                }
                MapTextures.Lod lod = MapTextures.acquire(dimensionId, rx, rz, lodIdx, cache, frame);
                if (lod == null) {
                    continue;
                }
                int relX = (rx - rMinX) * 256;
                int size = lod.size;
                g.blit(RenderPipelines.GUI_TEXTURED, lod.id, relX, relZ, 0, 0, 256, 256,
                        size, size, size, size);
            }
        }
        g.pose().popMatrix();
    }

    private static void drawPins(GuiGraphicsExtractor g, String dimensionId,
                                 double mapCX, double mapCY, double centerX, double centerZ,
                                 double scale, double dev, int minCx, int maxCx, int minCz, int maxCz) {
        List<ContainerDatabase.ContainerEntry> visible = ContainerDatabase.inChunkRange(dimensionId, minCx, maxCx, minCz, maxCz);
        if (visible.isEmpty()) {
            return;
        }
        int r = Math.max(1, (int) Math.round(1.6 * dev));
        int o = Math.max(1, (int) Math.round(0.8 * dev));
        for (ContainerDatabase.ContainerEntry e : visible) {
            if (ContainerDatabase.isSecondaryHalf(e) || !MapPinFilter.matches(e)) {
                continue;
            }
            int sx = (int) Math.round((mapCX + (e.x() + 0.5 - centerX) * scale) * dev);
            int sy = (int) Math.round((mapCY + (e.z() + 0.5 - centerZ) * scale) * dev);
            if (sx < clipX0 - r || sx > clipX1 + r || sy < clipY0 - r || sy > clipY1 + r) {
                continue;
            }
            fillI(g, sx - r - o, sy - r - o, sx + r + o, sy + r + o, 0xD0000000);
            fillI(g, sx - r, sy - r, sx + r, sy + r, ContainerDatabase.colorFor(e));
        }
    }

    private static void updateZone(Minecraft mc, ClientLevel level, LocalPlayer player) {
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

    private static void fillI(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int ax0 = Math.max(x0, clipX0);
        int ay0 = Math.max(y0, clipY0);
        int ax1 = Math.min(x1, clipX1);
        int ay1 = Math.min(y1, clipY1);
        if (ax1 > ax0 && ay1 > ay0) {
            g.fill(ax0, ay0, ax1, ay1, color);
        }
    }

    private static void drawArrow(GuiGraphicsExtractor g, double cx, double cy, double fx, double fy, double len) {
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
}
