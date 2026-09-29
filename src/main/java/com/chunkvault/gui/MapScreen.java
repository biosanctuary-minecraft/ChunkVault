package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.state.RecordingState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Top-down world map.
 *
 * Rendering: terrain is drawn from GPU region textures (MapTextures) — a few
 * blit() calls per frame no matter how far out you zoom, and every block
 * stays visible. If textures are unavailable it falls back to the older
 * fill()-based renderer below.
 *
 * Other notes (why it is both sharper and cheaper than before):
 *  - the map is drawn in real DEVICE pixels (the GUI matrix is scaled by
 *    1/guiScale for the map layer), so terrain is no longer snapped to the
 *    coarse GUI-pixel grid — 2-4x finer at typical GUI scales, and the
 *    hover frame / player arrow can be genuinely thin;
 *  - every chunk keeps pre-merged rectangles per detail level (MapCache.Mesh),
 *    so a chunk costs a handful of fill() calls instead of up to 256;
 *  - visible chunks are found by looking up the on-screen chunk range, not by
 *    walking the whole cache every frame, and chunk re-reads are throttled and
 *    paused while dragging.
 *
 * Controls: LMB drag = pan, wheel = zoom to cursor, RMB = back to player.
 * Top bar button toggles recording: ЗАПИСЬ (start) <-> СТОП (stop).
 */
public class MapScreen extends Screen {

    private static final int TOP_BAR = 22;
    private static final int BOTTOM_BAR = 18;
    private static final double MIN_SCALE = 0.125; // GUI pixels per block
    private static final double MAX_SCALE = 16.0;
    /** Max fill() calls spent on terrain per frame. Lower it if the map ever lags, raise it for more detail. */
    private static final int RECT_BUDGET = 9000;

    private static double lastScale = 1.0;

    private double centerX;
    private double centerZ;
    private double scale = lastScale;
    private int refreshCursor;
    private int frame;
    private int lastDragFrame = -1000;

    // device pixels per GUI pixel, and the map area in device pixels
    private double dev = 1.0;
    private int clipX0;
    private int clipY0;
    private int clipX1;
    private int clipY1;

    // visible-chunk scratch buffers (reused every frame, no per-frame allocation)
    private int[] visCx = new int[512];
    private int[] visCz = new int[512];
    private MapCache.ChunkColors[] visCol = new MapCache.ChunkColors[512];
    private int visCount;

    // record / stop button (drawn by hand, hit-tested in mouseClicked)
    private int btnX0;
    private int btnY0;
    private int btnX1;
    private int btnY1;
    private int setX0;
    private int setX1;
    private String toast;
    private long toastUntil;

    // live load zone (chunks the client really has inside the render distance), stored as horizontal runs
    private int[] zoneX0 = new int[256];
    private int[] zoneX1 = new int[256];
    private int[] zoneZ = new int[256];
    private int zoneCount;
    private int zoneCx;
    private int zoneCz;
    private int zoneR;

    public MapScreen() {
        super(Component.literal("ChunkVault — карта"));
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
            g.centeredText(this.font, "Нет активного мира.", this.width / 2, this.height / 2, 0xFFFFFFFF);
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
        var cache = MapCache.forDimension(dimensionId);
        refreshLoadedChunks(mc, level, player);

        double ppc = scale * 16.0;   // GUI pixels per chunk
        double ppcDev = ppc * dev;   // device pixels per chunk
        var manager = RecordingState.get().saveManager();

        // The map shows only what the running recording has saved; the live load zone is tinted red.
        boolean recording = RecordingState.get().isActive() && manager != null;
        if (!recording) {
            zoneCount = 0;
        } else if (frame % 3 == 0) {
            updateZone(mc, level, player);
        }

        // ---- which renderer: GPU textures (fast) or the fill() fallback ----
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

        if (!useTex && recording) {
            // ---- fallback: collect the recorded chunks on screen ----
            long area = (long) (maxCx - minCx + 1) * (long) (maxCz - minCz + 1);
            if (area <= cache.size() * 2L) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    for (int cx = minCx; cx <= maxCx; cx++) {
                        MapCache.ChunkColors c = cache.get(MapCache.key(cx, cz));
                        if (c != null && manager.isRecorded(dimensionId, cx, cz)) {
                            addVisible(cx, cz, c);
                        }
                    }
                }
            } else {
                for (var entry : cache.entrySet()) {
                    int cx = MapCache.keyX(entry.getKey());
                    int cz = MapCache.keyZ(entry.getKey());
                    if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz
                            && manager.isRecorded(dimensionId, cx, cz)) {
                        addVisible(cx, cz, entry.getValue());
                    }
                }
            }

            // pick the finest detail level that fits the fill() budget
            long[] totals = new long[4]; // 16x16, 8x8, 4x4, 2x2
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

        // ================= map layer, drawn in device pixels =================
        g.enableScissor(mapX0, mapY0, mapX1, mapY1);
        float inv = (float) (1.0 / dev);
        g.pose().pushMatrix();
        g.pose().scale(inv, inv);

        if (useTex && recording) {
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

        // live load zone: translucent red over the chunks the client has right now (within render distance)
        if (recording && zoneCount > 0) {
            for (int i = 0; i < zoneCount; i++) {
                int x0 = (int) Math.round((mapCX + (zoneX0[i] * 16.0 - centerX) * scale) * dev);
                int x1 = (int) Math.round((mapCX + (zoneX1[i] * 16.0 - centerX) * scale) * dev);
                int y0 = (int) Math.round((mapCY + (zoneZ[i] * 16.0 - centerZ) * scale) * dev);
                int y1 = (int) Math.round((mapCY + ((zoneZ[i] + 1) * 16.0 - centerZ) * scale) * dev);
                fillI(g, x0, y0, x1, y1, 0x55FF1A1A);
            }
            // thin bright frame around the whole render-distance square, so it reads even over red terrain
            int fx0 = (int) Math.round((mapCX + ((zoneCx - zoneR) * 16.0 - centerX) * scale) * dev);
            int fx1 = (int) Math.round((mapCX + ((zoneCx + zoneR + 1) * 16.0 - centerX) * scale) * dev);
            int fy0 = (int) Math.round((mapCY + ((zoneCz - zoneR) * 16.0 - centerZ) * scale) * dev);
            int fy1 = (int) Math.round((mapCY + ((zoneCz + zoneR + 1) * 16.0 - centerZ) * scale) * dev);
            fillI(g, fx0, fy0, fx1, fy0 + 1, 0xCCFF4040);
            fillI(g, fx0, fy1 - 1, fx1, fy1, 0xCCFF4040);
            fillI(g, fx0, fy0, fx0 + 1, fy1, 0xCCFF4040);
            fillI(g, fx1 - 1, fy0, fx1, fy1, 0xCCFF4040);
        }

        // chunk grid when zoomed in enough: 1 device pixel wide
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

        // hovered chunk: light fill + a thin (1 device pixel) frame, like Xaero's
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

        // player arrow, rasterised in device pixels, pointing where the player looks
        double pcx = (mapCX + (player.getX() - centerX) * scale) * dev;
        double pcy = (mapCY + (player.getZ() - centerZ) * scale) * dev;
        double yaw = Math.toRadians(player.getYRot());
        // Minecraft yaw 0 = south (+Z, screen down), 90 = west (-X, screen left)
        drawArrow(g, pcx, pcy, -Math.sin(yaw), Math.cos(yaw), 3.2 * dev + 5.0);

        g.pose().popMatrix();
        g.disableScissor();
        // ================= end of device-pixel layer =================

        // ---- bars with text ----
        g.fill(0, 0, this.width, TOP_BAR, 0xFF1A1A22);
        g.fill(0, this.height - BOTTOM_BAR, this.width, this.height, 0xFF1A1A22);

        String title = "ChunkVault";
        g.text(this.font, title, 8, 7, 0xFFFFFFFF, true);
        int x = 8 + this.font.width(title) + 14;

        String active = RecordingState.get().sessionName();
        String last = RecordingState.get().lastSessionName();
        String nameText;
        int nameColor;
        if (active != null && manager != null) {
            nameText = "Карта: " + active + "  ● ЗАПИСЬ  чанков: " + manager.chunksSaved()
                    + "  сущностей: " + manager.entitiesSeen();
            nameColor = 0xFFFF7070;
        } else if (last != null) {
            nameText = "Последняя карта: " + last + " (запись остановлена)";
            nameColor = 0xFFFFD070;
        } else {
            nameText = "запись не начата — просмотр";
            nameColor = 0xFFAAAAAA;
        }
        g.text(this.font, nameText, x, 7, nameColor, true);

        // ---- one toggle button: ЗАПИСЬ <-> СТОП ----
        String btnText = recording ? "СТОП" : "ЗАПИСЬ";
        btnX0 = x + this.font.width(nameText) + 10;
        btnX1 = btnX0 + this.font.width(btnText) + 14;
        btnY0 = 3;
        btnY1 = TOP_BAR - 3;
        boolean hover = mouseX >= btnX0 && mouseX < btnX1 && mouseY >= btnY0 && mouseY < btnY1;
        int btnBg = recording ? (hover ? 0xFFD84848 : 0xFFB03030) : (hover ? 0xFF3FB365 : 0xFF2E8B4E);
        g.fill(btnX0, btnY0, btnX1, btnY1, 0xFFFFFFFF);
        g.fill(btnX0 + 1, btnY0 + 1, btnX1 - 1, btnY1 - 1, btnBg);
        g.text(this.font, btnText, btnX0 + 7, 7, 0xFFFFFFFF, true);

        // ---- НАСТРОЙКИ button: same look, blue-grey ----
        String setText = "НАСТРОЙКИ";
        setX0 = btnX1 + 6;
        setX1 = setX0 + this.font.width(setText) + 14;
        boolean setHover = mouseX >= setX0 && mouseX < setX1 && mouseY >= btnY0 && mouseY < btnY1;
        g.fill(setX0, btnY0, setX1, btnY1, 0xFFFFFFFF);
        g.fill(setX0 + 1, btnY0 + 1, setX1 - 1, btnY1 - 1, setHover ? 0xFF6B7FA8 : 0xFF4E5F82);
        g.text(this.font, setText, setX0 + 7, 7, 0xFFFFFFFF, true);

        // ---- bottom bar: only coordinates and chunks ----
        String info = "Ты: X: " + (int) Math.floor(player.getX())
                + "  Y: " + (int) Math.floor(player.getY())
                + "  Z: " + (int) Math.floor(player.getZ()) + "  |";
        if (inMap) {
            int bx = (int) Math.floor(centerX + (mouseX - mapCX) / scale);
            int bz = (int) Math.floor(centerZ + (mouseY - mapCY) / scale);
            info += "  Курсор: X: " + bx + "  Z: " + bz + "  Чанк: " + (bx >> 4) + ", " + (bz >> 4) + "  |";
        }
        g.text(this.font, info, 8, this.height - BOTTOM_BAR + 5, 0xFFFFFFFF, true);
        g.text(this.font, dimensionId, this.width - this.font.width(dimensionId) - 8,
                this.height - BOTTOM_BAR + 5, 0xFFCCCCCC, true);

        // ---- short result message after pressing the button ----
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = this.font.width(toast) + 16;
            int tx0 = (this.width - tw) / 2;
            g.fill(tx0, TOP_BAR + 6, tx0 + tw, TOP_BAR + 24, 0xE0101015);
            g.text(this.font, toast, tx0 + 8, TOP_BAR + 11, 0xFF70FF90, true);
        }
    }

    /** Draws the terrain as region textures: one blit() per 16x16-chunk region on screen. */
    private void drawTerrainTextured(GuiGraphicsExtractor g, String dimensionId,
                                     java.util.Map<Long, MapCache.ChunkColors> cache,
                                     double mapCX, double mapCY, int minCx, int maxCx, int minCz, int maxCz) {
        // zoomed far out, the coarse texture (1 px per 4 blocks) stays sharp instead of shimmering
        int lodIdx = scale * dev >= 0.6 ? 0 : 1;
        int size = MapTextures.SIZE[lodIdx];
        var regionKeys = MapCache.regionKeys(dimensionId);
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

    /**
     * Rebuilds the live load zone: every chunk the client actually holds inside
     * the render-distance square around the player, merged into horizontal runs
     * (a few dozen fill() calls instead of one per chunk).
     */
    private void updateZone(Minecraft mc, net.minecraft.client.multiplayer.ClientLevel level,
                            net.minecraft.client.player.LocalPlayer player) {
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
                        zoneX0 = java.util.Arrays.copyOf(zoneX0, n);
                        zoneX1 = java.util.Arrays.copyOf(zoneX1, n);
                        zoneZ = java.util.Arrays.copyOf(zoneZ, n);
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

    /**
     * True only for a chunk the client really holds. level.hasChunk() said "yes"
     * for everything inside the render distance, including chunks that never
     * arrived; the client hands out a shared EmptyLevelChunk placeholder for those.
     */
    private static boolean isLoaded(net.minecraft.client.multiplayer.ClientLevel level, int cx, int cz) {
        var chunk = level.getChunk(cx, cz);
        return chunk instanceof LevelChunk && !(chunk instanceof net.minecraft.world.level.chunk.EmptyLevelChunk);
    }

    private void addVisible(int cx, int cz, MapCache.ChunkColors colors) {
        if (visCount == visCx.length) {
            int n = visCount * 2;
            visCx = java.util.Arrays.copyOf(visCx, n);
            visCz = java.util.Arrays.copyOf(visCz, n);
            visCol = java.util.Arrays.copyOf(visCol, n);
        }
        visCx[visCount] = cx;
        visCz[visCount] = cz;
        visCol[visCount] = colors;
        visCount++;
    }

    // ------------------------------------------------------------------
    // Drawing helpers (device-pixel coordinates)
    // ------------------------------------------------------------------

    /** Rounds both edges (neighbouring rectangles share an edge -> no seams, no overlap) and clips to the map. */
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

    /**
     * Player arrow (tip, two back corners, notch) with a 1-device-pixel dark
     * outline, rotated to the facing direction (fx, fy) and rasterised row by
     * row into horizontal runs of one colour.
     */
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
                    double u = px * fx + py * fy;   // along the facing direction
                    double v = -px * fy + py * fx;  // sideways
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

    // ------------------------------------------------------------------
    // Mouse input (Screen events)
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && event.x() >= btnX0 && event.x() < btnX1 && event.y() >= btnY0 && event.y() < btnY1) {
            toggleRecording();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
                && event.x() >= setX0 && event.x() < setX1 && event.y() >= btnY0 && event.y() < btnY1) {
            Minecraft.getInstance().setScreenAndShow(new SettingsScreen(this));
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            recenterOnPlayer();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            return true; // start of a drag
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    /** СТОП stops and saves right away; ЗАПИСЬ opens the world picker (new name or an existing folder). */
    private void toggleRecording() {
        if (RecordingState.get().isActive()) {
            var result = ChunkVaultClient.stopRecording();
            if (result != null) {
                showToast("Запись остановлена: " + result.chunks() + " чанков, "
                        + result.chests() + " контейнеров, " + result.entities() + " сущностей");
            }
            return;
        }
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Minecraft.getInstance().setScreenAndShow(new RecordingPickerScreen(this));
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
            lastDragFrame = frame;
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

        // keep the block under the cursor where it was
        centerX = blockX - (mouseX - mapCX) / scale;
        centerZ = blockZ - (mouseY - mapCY) / scale;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Re-reads one chunk around the player every few frames so the picture
     * stays fresh. Chunk packets already feed the cache (ChunkCaptureMixin),
     * so this is only a slow top-up; it is paused while dragging so panning
     * never pays for chunk scans (the Nether floor search is expensive).
     */
    private void refreshLoadedChunks(Minecraft mc, net.minecraft.client.multiplayer.ClientLevel level,
                                     net.minecraft.client.player.LocalPlayer player) {
        if (frame % 4 != 0 || frame - lastDragFrame < 15) {
            return;
        }
        int playerChunkX = (int) Math.floor(player.getX() / 16.0);
        int playerChunkZ = (int) Math.floor(player.getZ() / 16.0);
        int radius = mc.options.renderDistance().get() + 1;
        int side = radius * 2 + 1;
        int total = side * side;
        refreshCursor = (refreshCursor + 1) % total;
        int cx = playerChunkX - radius + refreshCursor % side;
        int cz = playerChunkZ - radius + refreshCursor / side;
        var got = level.getChunk(cx, cz);
        if (got instanceof LevelChunk chunk && !(got instanceof net.minecraft.world.level.chunk.EmptyLevelChunk)) {
            MapCache.update(level, chunk);
        }
    }
}
