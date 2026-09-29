package com.chunkvault.gui;

import com.chunkvault.state.RecordingState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Top-down world map:
 *  - real block colours of everything the client has loaded this session
 *    (works with or without a recording running), averaged into 16/8/4/2-cell
 *    detail levels so it stays sharp at every zoom;
 *  - left mouse drag = pan, mouse wheel = zoom towards the cursor,
 *    right click = jump back to the player;
 *  - chunks already saved by /chunkvault are tinted green;
 *  - shows the recording's name, player coordinates and the block / chunk
 *    under the cursor.
 *
 * Mouse handling uses the Screen mouse events (MouseButtonEvent) — polling
 * MouseHandler.isLeftPressed() does not work while a screen is open, which
 * is why the first version could not be dragged.
 *
 * Colours passed to drawing calls must carry an alpha byte (0xFFRRGGBB).
 */
public class MapScreen extends Screen {

    private static final int TOP_BAR = 22;
    private static final int BOTTOM_BAR = 18;
    private static final double MIN_SCALE = 0.125; // GUI pixels per block
    private static final double MAX_SCALE = 16.0;
    /** Upper bound of fill() draw calls per frame — each one is a real GPU draw, not just a rectangle.
     * Counted from the REAL number of same-colour runs per chunk (MapCache.ChunkColors.runsN), not a
     * worst case, so far more detail fits into the same budget. Lower it if the map ever lags. */
    private static final int FILL_BUDGET = 7000;

    private static double lastScale = 1.0;

    private double centerX;
    private double centerZ;
    private double scale = lastScale;
    private int refreshCursor;

    // stop-recording button (drawn by hand, hit-tested in mouseClicked)
    private int btnX0;
    private int btnY0;
    private int btnX1;
    private int btnY1;
    private boolean btnEnabled;
    private String toast;
    private long toastUntil;

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

        String dimensionId = level.dimension().identifier().toString();
        var cache = MapCache.forDimension(dimensionId);
        refreshLoadedChunks(mc, level, player);

        double ppc = scale * 16.0; // pixels per chunk
        var manager = RecordingState.get().saveManager();

        // ---- pass 1: real fill() cost per detail level of what is on screen -> pick the detail ----
        long[] totals = new long[4]; // 16x16, 8x8, 4x4, 2x2
        for (var entry : cache.entrySet()) {
            long key = entry.getKey();
            double sx = mapCX + (MapCache.keyX(key) * 16.0 - centerX) * scale;
            double sy = mapCY + (MapCache.keyZ(key) * 16.0 - centerZ) * scale;
            if (!(sx + ppc < mapX0 || sx > mapX1 || sy + ppc < mapY0 || sy > mapY1)) {
                MapCache.ChunkColors c = entry.getValue();
                totals[0] += c.runs16;
                totals[1] += c.runs8;
                totals[2] += c.runs4;
                totals[3] += c.runs2;
            }
        }
        int detail = 1;
        int[] levels = {16, 8, 4, 2};
        for (int i = 0; i < levels.length; i++) {
            // A cell narrower than one GUI pixel can't be drawn anyway.
            if (ppc / levels[i] >= 1.0 && totals[i] <= FILL_BUDGET) {
                detail = levels[i];
                break;
            }
        }

        // ---- pass 2: draw ----
        for (var entry : cache.entrySet()) {
            long key = entry.getKey();
            int cx = MapCache.keyX(key);
            int cz = MapCache.keyZ(key);
            double sx = mapCX + (cx * 16.0 - centerX) * scale;
            double sy = mapCY + (cz * 16.0 - centerZ) * scale;
            if (sx + ppc < mapX0 || sx > mapX1 || sy + ppc < mapY0 || sy > mapY1) {
                continue;
            }
            MapCache.ChunkColors colors = entry.getValue();

            if (detail == 1) {
                if (colors.avg != 0) {
                    fillClipped(g, sx, sy, sx + ppc, sy + ppc, colors.avg, mapX0, mapY0, mapX1, mapY1);
                }
            } else {
                int[] cells = detail == 16 ? colors.px : detail == 8 ? colors.l8 : detail == 4 ? colors.l4 : colors.l2;
                double cellSize = ppc / detail;
                // Merge horizontally-adjacent same-colour cells into one wider
                // fill() call instead of one call per cell — real chunk
                // terrain is mostly uniform blocks of colour row by row, so
                // this alone cuts draw calls drastically and is what fixed
                // the frame-rate crash when the map first opened.
                for (int cz2 = 0; cz2 < detail; cz2++) {
                    int runStart = 0;
                    int runColor = cells[cz2 * detail];
                    for (int cx2 = 1; cx2 <= detail; cx2++) {
                        int color = cx2 < detail ? cells[cz2 * detail + cx2] : Integer.MIN_VALUE;
                        if (color != runColor) {
                            if (runColor != 0) {
                                fillClipped(g, sx + runStart * cellSize, sy + cz2 * cellSize,
                                        sx + cx2 * cellSize, sy + (cz2 + 1) * cellSize,
                                        runColor, mapX0, mapY0, mapX1, mapY1);
                            }
                            runStart = cx2;
                            runColor = color;
                        }
                    }
                }
            }

            if (manager != null && manager.isRecorded(dimensionId, cx, cz)) {
                fillClipped(g, sx, sy, sx + ppc, sy + ppc, 0x2600FF66, mapX0, mapY0, mapX1, mapY1);
            }
        }

        // ---- chunk grid when zoomed in enough ----
        if (ppc >= 48) {
            double halfW = (mapW / 2.0) / scale;
            double halfH = (mapH / 2.0) / scale;
            int firstCx = (int) Math.floor((centerX - halfW) / 16.0);
            int lastCx = (int) Math.ceil((centerX + halfW) / 16.0);
            int firstCz = (int) Math.floor((centerZ - halfH) / 16.0);
            int lastCz = (int) Math.ceil((centerZ + halfH) / 16.0);
            for (int cx = firstCx; cx <= lastCx; cx++) {
                double sx = mapCX + (cx * 16.0 - centerX) * scale;
                fillClipped(g, sx, mapY0, sx + 1, mapY1, 0x30FFFFFF, mapX0, mapY0, mapX1, mapY1);
            }
            for (int cz = firstCz; cz <= lastCz; cz++) {
                double sy = mapCY + (cz * 16.0 - centerZ) * scale;
                fillClipped(g, mapX0, sy, mapX1, sy + 1, 0x30FFFFFF, mapX0, mapY0, mapX1, mapY1);
            }
        }

        // ---- hovered chunk highlight (like Xaero's) ----
        if (inMap) {
            int hbx = (int) Math.floor(centerX + (mouseX - mapCX) / scale);
            int hbz = (int) Math.floor(centerZ + (mouseY - mapCY) / scale);
            double hx = mapCX + ((hbx >> 4) * 16.0 - centerX) * scale;
            double hy = mapCY + ((hbz >> 4) * 16.0 - centerZ) * scale;
            fillClipped(g, hx, hy, hx + ppc, hy + ppc, 0x33FFFFFF, mapX0, mapY0, mapX1, mapY1);
            int bw = 1;
            fillClipped(g, hx, hy, hx + ppc, hy + bw, 0xCCFFFFFF, mapX0, mapY0, mapX1, mapY1);
            fillClipped(g, hx, hy + ppc - bw, hx + ppc, hy + ppc, 0xCCFFFFFF, mapX0, mapY0, mapX1, mapY1);
            fillClipped(g, hx, hy, hx + bw, hy + ppc, 0xCCFFFFFF, mapX0, mapY0, mapX1, mapY1);
            fillClipped(g, hx + ppc - bw, hy, hx + ppc, hy + ppc, 0xCCFFFFFF, mapX0, mapY0, mapX1, mapY1);
        }

        // ---- player marker: an arrow pointing where the player looks ----
        double playerSX = mapCX + (player.getX() - centerX) * scale;
        double playerSY = mapCY + (player.getZ() - centerZ) * scale;
        double yaw = Math.toRadians(player.getYRot());
        // Minecraft yaw 0 = south (+Z, screen down), 90 = west (-X, screen left)
        double fx = -Math.sin(yaw);
        double fy = Math.cos(yaw);
        drawArrow(g, playerSX, playerSY, fx, fy, 1.4, 0xFF101015, mapX0, mapY0, mapX1, mapY1);
        drawArrow(g, playerSX, playerSY, fx, fy, 1.0, 0xFFFFFFFF, mapX0, mapY0, mapX1, mapY1);

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

        // ---- ЗАПИСЬ СТОП button (active only while a recording runs) ----
        String btnText = "ЗАПИСЬ СТОП";
        btnEnabled = RecordingState.get().isActive();
        btnX0 = x + this.font.width(nameText) + 10;
        btnX1 = btnX0 + this.font.width(btnText) + 14;
        btnY0 = 3;
        btnY1 = TOP_BAR - 3;
        boolean btnHover = btnEnabled && mouseX >= btnX0 && mouseX < btnX1 && mouseY >= btnY0 && mouseY < btnY1;
        int btnBg = !btnEnabled ? 0xFF2A2A32 : btnHover ? 0xFFD03A3A : 0xFF9E2A2A;
        g.fill(btnX0, btnY0, btnX1, btnY1, btnEnabled ? 0xFFFFFFFF : 0xFF3A3A44);
        g.fill(btnX0 + 1, btnY0 + 1, btnX1 - 1, btnY1 - 1, btnBg);
        g.text(this.font, btnText, btnX0 + 7, 7, btnEnabled ? 0xFFFFFFFF : 0xFF77777F, true);

        String dimText = dimensionId;
        g.text(this.font, dimText, this.width - this.font.width(dimText) - 8, 7, 0xFFCCCCCC, true);

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

        // ---- short result message after pressing the button ----
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            int tw = this.font.width(toast) + 16;
            int tx0 = (this.width - tw) / 2;
            g.fill(tx0, TOP_BAR + 6, tx0 + tw, TOP_BAR + 24, 0xE0101015);
            g.text(this.font, toast, tx0 + 8, TOP_BAR + 11, 0xFF70FF90, true);
        }
    }

    /**
     * Draws the player arrow with fill() only: the arrow polygon (tip, two
     * back corners, notch) is rotated to the player's facing and rasterised
     * row by row into horizontal runs. {@code grow} scales it (used for the outline).
     */
    private static void drawArrow(GuiGraphicsExtractor g, double cx, double cy, double fx, double fy,
                                  double grow, int color, int minX, int minY, int maxX, int maxY) {
        double len = 8.0 * grow;
        double wid = 6.0 * grow;
        int r = (int) Math.ceil(len) + 2;
        int icx = (int) Math.floor(cx);
        int icy = (int) Math.floor(cy);
        for (int iy = icy - r; iy <= icy + r; iy++) {
            int runStart = Integer.MIN_VALUE;
            for (int ix = icx - r; ix <= icx + r + 1; ix++) {
                boolean in = false;
                if (ix <= icx + r) {
                    double px = ix + 0.5 - cx;
                    double py = iy + 0.5 - cy;
                    double u = px * fx + py * fy;   // along the facing direction
                    double v = -px * fy + py * fx;  // sideways
                    in = inTri(u, v, len, 0, -0.8 * len, wid, -0.3 * len, 0)
                            || inTri(u, v, len, 0, -0.3 * len, 0, -0.8 * len, -wid);
                }
                if (in && runStart == Integer.MIN_VALUE) {
                    runStart = ix;
                } else if (!in && runStart != Integer.MIN_VALUE) {
                    fillClipped(g, runStart, iy, ix, iy + 1, color, minX, minY, maxX, maxY);
                    runStart = Integer.MIN_VALUE;
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

    // ------------------------------------------------------------------
    // Mouse input (Screen events; the old polling approach never fired)
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && btnEnabled
                && event.x() >= btnX0 && event.x() < btnX1 && event.y() >= btnY0 && event.y() < btnY1) {
            var result = com.chunkvault.ChunkVaultClient.stopRecording();
            if (result != null) {
                toast = "Запись остановлена: " + result.chunks() + " чанков, "
                        + result.chests() + " контейнеров, " + result.entities() + " сущностей";
                toastUntil = System.currentTimeMillis() + 6000;
            }
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

        // keep the block under the cursor where it was
        centerX = blockX - (mouseX - mapCX) / scale;
        centerZ = blockZ - (mouseY - mapCY) / scale;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Re-reads a few chunks per frame around the player so the picture stays fresh without a lag spike. */
    private void refreshLoadedChunks(Minecraft mc, net.minecraft.client.multiplayer.ClientLevel level,
                                     net.minecraft.client.player.LocalPlayer player) {
        int playerChunkX = (int) Math.floor(player.getX() / 16.0);
        int playerChunkZ = (int) Math.floor(player.getZ() / 16.0);
        int radius = mc.options.renderDistance().get() + 1;
        int side = radius * 2 + 1;
        int total = side * side;
        // Capped low on purpose: each refreshed chunk costs up to 256
        // getMapColor() lookups, and this runs every single frame the map is
        // open — 24/frame was the other half of the original lag spike.
        for (int i = 0; i < 6; i++) {
            refreshCursor = (refreshCursor + 1) % total;
            int cx = playerChunkX - radius + refreshCursor % side;
            int cz = playerChunkZ - radius + refreshCursor / side;
            if (level.hasChunk(cx, cz) && level.getChunk(cx, cz) instanceof LevelChunk chunk) {
                MapCache.update(level, chunk);
            }
        }
    }

    /** fill() clamped to a rectangle, with floor/ceil rounding so neighbouring cells leave no seams. */
    private static void fillClipped(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color,
                                    int minX, int minY, int maxX, int maxY) {
        int ix0 = (int) Math.floor(Math.max(x0, minX));
        int iy0 = (int) Math.floor(Math.max(y0, minY));
        int ix1 = (int) Math.ceil(Math.min(x1, maxX));
        int iy1 = (int) Math.ceil(Math.min(y1, maxY));
        if (ix1 <= ix0 || iy1 <= iy0) {
            return;
        }
        g.fill(ix0, iy0, ix1, iy1, color);
    }
}
