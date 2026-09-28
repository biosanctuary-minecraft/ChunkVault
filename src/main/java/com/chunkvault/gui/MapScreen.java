package com.chunkvault.gui;

import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Top-down world map in the spirit of WorldDownloader / Xaero's map:
 *  - shows real block colours of everything the client has loaded this session
 *    (works with or without a recording running);
 *  - drag with the left mouse button to pan, mouse wheel to zoom towards the
 *    cursor, right mouse button to jump back to the player;
 *  - chunks that are already saved by /chunkvault are tinted green;
 *  - shows player coordinates and the block / chunk under the cursor.
 *
 * NOTE: colours passed to the drawing calls must carry an alpha byte
 * (0xFFRRGGBB). In this version 0xRRGGBB with alpha 0 is fully transparent —
 * that is why the very first version of this screen showed no text at all.
 */
public class MapScreen extends Screen {

    private static final int TOP_BAR = 22;
    private static final int BOTTOM_BAR = 34;
    private static final double MIN_SCALE = 0.125; // pixels per block
    private static final double MAX_SCALE = 16.0;

    private static double lastScale = 1.0;

    private double centerX;
    private double centerZ;
    private double scale = lastScale;

    private boolean wasLeftDown;
    private int lastMouseX;
    private int lastMouseY;
    private int refreshCursor;

    public MapScreen() {
        super(Component.literal("ChunkVault — карта"));
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

        // ---- input: drag to pan, right button = back to the player ----
        var mouse = mc.mouseHandler;
        boolean leftDown = mouse.isLeftPressed();
        boolean inMap = mouseY >= mapY0 && mouseY < mapY1;
        if (leftDown && wasLeftDown && inMap) {
            centerX -= (mouseX - lastMouseX) / scale;
            centerZ -= (mouseY - lastMouseY) / scale;
        }
        wasLeftDown = leftDown;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (mouse.isRightPressed()) {
            centerX = player.getX();
            centerZ = player.getZ();
        }

        String dimensionId = level.dimension().identifier().toString();
        var cache = MapCache.forDimension(dimensionId);
        refreshLoadedChunks(mc, level, player, cache);

        // ---- draw the chunks ----
        double ppc = scale * 16.0; // pixels per chunk
        int detail = ppc >= 192 ? 16 : ppc >= 96 ? 8 : ppc >= 48 ? 4 : ppc >= 24 ? 2 : 1;
        var manager = RecordingState.get().saveManager();
        int chunksInView = 0;
        int recordedInView = 0;

        for (var entry : cache.entrySet()) {
            long key = entry.getKey();
            int cx = MapCache.keyX(key);
            int cz = MapCache.keyZ(key);
            double sx = mapCX + (cx * 16.0 - centerX) * scale;
            double sy = mapCY + (cz * 16.0 - centerZ) * scale;
            if (sx + ppc < mapX0 || sx > mapX1 || sy + ppc < mapY0 || sy > mapY1) {
                continue;
            }
            chunksInView++;
            MapCache.ChunkColors colors = entry.getValue();

            if (detail == 1) {
                if (colors.avg != 0) {
                    fillClipped(g, sx, sy, sx + ppc, sy + ppc, colors.avg, mapX0, mapY0, mapX1, mapY1);
                }
            } else {
                int cell = 16 / detail;
                double cellSize = ppc / detail;
                for (int cz2 = 0; cz2 < detail; cz2++) {
                    for (int cx2 = 0; cx2 < detail; cx2++) {
                        int color = colors.px[(cz2 * cell + cell / 2) * 16 + (cx2 * cell + cell / 2)];
                        if (color != 0) {
                            fillClipped(g, sx + cx2 * cellSize, sy + cz2 * cellSize,
                                    sx + (cx2 + 1) * cellSize, sy + (cz2 + 1) * cellSize,
                                    color, mapX0, mapY0, mapX1, mapY1);
                        }
                    }
                }
            }

            if (manager != null && manager.isRecorded(dimensionId, cx, cz)) {
                recordedInView++;
                fillClipped(g, sx, sy, sx + ppc, sy + ppc, 0x3300FF66, mapX0, mapY0, mapX1, mapY1);
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

        // ---- player marker ----
        double playerSX = mapCX + (player.getX() - centerX) * scale;
        double playerSY = mapCY + (player.getZ() - centerZ) * scale;
        fillClipped(g, playerSX - 3, playerSY - 3, playerSX + 3, playerSY + 3, 0xFFFFFFFF, mapX0, mapY0, mapX1, mapY1);
        fillClipped(g, playerSX - 2, playerSY - 2, playerSX + 2, playerSY + 2, 0xFFFF3030, mapX0, mapY0, mapX1, mapY1);

        // ---- bars with text ----
        g.fill(0, 0, this.width, TOP_BAR, 0xFF1A1A22);
        g.fill(0, this.height - BOTTOM_BAR, this.width, this.height, 0xFF1A1A22);

        g.text(this.font, "ChunkVault — карта", 8, 7, 0xFFFFFFFF, true);
        String recState = manager != null
                ? "● ИДЁТ ЗАПИСЬ: " + manager.chunksSaved() + " чанков"
                : "запись не идёт — просмотр";
        g.text(this.font, recState, 140, 7, manager != null ? 0xFFFF6060 : 0xFFAAAAAA, true);
        String dimText = "Измерение: " + dimensionId;
        g.text(this.font, dimText, this.width - this.font.width(dimText) - 8, 7, 0xFFCCCCCC, true);

        String cursorText = "";
        if (inMap) {
            int bx = (int) Math.floor(centerX + (mouseX - mapCX) / scale);
            int bz = (int) Math.floor(centerZ + (mouseY - mapCY) / scale);
            cursorText = "Курсор: X " + bx + "  Z " + bz + "   чанк " + (bx >> 4) + ", " + (bz >> 4);
            if (manager != null && manager.isRecorded(dimensionId, bx >> 4, bz >> 4)) {
                cursorText += "   [записан]";
            }
        }
        String playerText = "Ты: X " + (int) Math.floor(player.getX())
                + "  Y " + (int) Math.floor(player.getY())
                + "  Z " + (int) Math.floor(player.getZ());
        int bottomY = this.height - BOTTOM_BAR;
        g.text(this.font, playerText, 8, bottomY + 5, 0xFFFF9090, true);
        g.text(this.font, cursorText, 8 + this.font.width(playerText) + 24, bottomY + 5, 0xFFFFFFFF, true);
        String help = "ЛКМ — двигать   Колесо — масштаб   ПКМ — к игроку   Esc — закрыть   |   "
                + String.format("%.2f", scale) + " пикс/блок   в кадре чанков: " + chunksInView
                + (manager != null ? " (записано: " + recordedInView + ")" : "");
        g.text(this.font, help, 8, bottomY + 19, 0xFF9A9AA5, true);
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
                                     net.minecraft.client.player.LocalPlayer player,
                                     java.util.Map<Long, MapCache.ChunkColors> cache) {
        int playerChunkX = (int) Math.floor(player.getX() / 16.0);
        int playerChunkZ = (int) Math.floor(player.getZ() / 16.0);
        int radius = mc.options.renderDistance().get() + 1;
        int side = radius * 2 + 1;
        int total = side * side;
        for (int i = 0; i < 24; i++) {
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
