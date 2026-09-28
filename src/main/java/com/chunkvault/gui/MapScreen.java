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
    private static final int BOTTOM_BAR = 34;
    private static final double MIN_SCALE = 0.125; // GUI pixels per block
    private static final double MAX_SCALE = 16.0;
    /** Upper bound of fill() calls per frame; the detail level is lowered until the picture fits. */
    private static final int FILL_BUDGET = 45000;

    private static double lastScale = 1.0;

    private double centerX;
    private double centerZ;
    private double scale = lastScale;
    private int refreshCursor;

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

        // ---- pass 1: how many chunks are on screen -> pick the detail level ----
        int visible = 0;
        for (long key : cache.keySet()) {
            double sx = mapCX + (MapCache.keyX(key) * 16.0 - centerX) * scale;
            double sy = mapCY + (MapCache.keyZ(key) * 16.0 - centerZ) * scale;
            if (!(sx + ppc < mapX0 || sx > mapX1 || sy + ppc < mapY0 || sy > mapY1)) {
                visible++;
            }
        }
        int detail = 1;
        for (int candidate : new int[]{16, 8, 4, 2}) {
            if (ppc / candidate >= 0.75 && (long) visible * candidate * candidate <= FILL_BUDGET) {
                detail = candidate;
                break;
            }
        }

        // ---- pass 2: draw ----
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
            MapCache.ChunkColors colors = entry.getValue();

            if (detail == 1) {
                if (colors.avg != 0) {
                    fillClipped(g, sx, sy, sx + ppc, sy + ppc, colors.avg, mapX0, mapY0, mapX1, mapY1);
                }
            } else {
                int[] cells = detail == 16 ? colors.px : detail == 8 ? colors.l8 : detail == 4 ? colors.l4 : colors.l2;
                double cellSize = ppc / detail;
                for (int cz2 = 0; cz2 < detail; cz2++) {
                    for (int cx2 = 0; cx2 < detail; cx2++) {
                        int color = cells[cz2 * detail + cx2];
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

        // ---- player marker ----
        double playerSX = mapCX + (player.getX() - centerX) * scale;
        double playerSY = mapCY + (player.getZ() - centerZ) * scale;
        fillClipped(g, playerSX - 3, playerSY - 3, playerSX + 3, playerSY + 3, 0xFFFFFFFF, mapX0, mapY0, mapX1, mapY1);
        fillClipped(g, playerSX - 2, playerSY - 2, playerSX + 2, playerSY + 2, 0xFFFF3030, mapX0, mapY0, mapX1, mapY1);

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
        if (active != null) {
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

        String dimText = dimensionId;
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
        String help = "ЛКМ-тащить — двигать   Колесо — масштаб   ПКМ — к игроку   Esc — закрыть   |   "
                + String.format("%.2f", scale) + " пикс/блок   детализация " + detail + "x" + detail
                + (manager != null ? "   записано в кадре: " + recordedInView : "");
        g.text(this.font, help, 8, bottomY + 19, 0xFF9A9AA5, true);
    }

    // ------------------------------------------------------------------
    // Mouse input (Screen events; the old polling approach never fired)
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
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
