package com.chunkvault.gui;

import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A ChunkBase/WorldDownloader-style overview: a grid of squares around the
 * player, colored by whether that chunk has already been recorded this
 * session. Opened with /chunkvault map. Read-only — Esc closes it, nothing
 * here writes any game state.
 *
 * CHECK: this version's Screen rendering was reworked into a two-phase
 * extraction model — Screen.render(GuiGraphics) became
 * Screen.extractRenderState(GuiGraphicsExtractor), and
 * drawCenteredString(...) became centeredText(...) (same 5 args, no
 * hasShadow param, unlike text()). fill()
 * itself is unchanged. If this file doesn't compile, check for exactly
 * these renames first.
 */
public class MapScreen extends Screen {

    private static final int CELL_SIZE = 6;
    private static final int COLOR_RECORDED = 0xFF3DDC3D;
    private static final int COLOR_UNRECORDED = 0xFF3A3A3A;
    private static final int COLOR_PLAYER = 0xFFFF5050;
    private static final int COLOR_BORDER = 0xFF808080;
    private static final int COLOR_BACKDROP = 0xE0101010;

    public MapScreen() {
        super(Component.literal("ChunkVault — карта прогруженного"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately not calling the vanilla blurred-panorama background
        // helper here — its exact signature has moved around between
        // versions more than once, and a flat backdrop does the job fine.
        graphics.fill(0, 0, this.width, this.height, COLOR_BACKDROP);

        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        var level = mc.level;
        var manager = RecordingState.get().saveManager();

        if (player == null || level == null) {
            graphics.centeredText(this.font, "Нет активного мира.", this.width / 2, this.height / 2, 0xFFFFFF);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }

        int playerChunkX = player.blockPosition().getX() >> 4;
        int playerChunkZ = player.blockPosition().getZ() >> 4;

        int radiusX = Math.max(4, (this.width - 60) / (2 * CELL_SIZE));
        int radiusZ = Math.max(4, (this.height - 100) / (2 * CELL_SIZE));

        int gridPixelW = (radiusX * 2 + 1) * CELL_SIZE;
        int gridPixelH = (radiusZ * 2 + 1) * CELL_SIZE;
        int startX = (this.width - gridPixelW) / 2;
        int startY = (this.height - gridPixelH) / 2;

        String dimensionId = level.dimension().identifier().toString();

        int recordedCount = 0;
        for (int dz = -radiusZ; dz <= radiusZ; dz++) {
            for (int dx = -radiusX; dx <= radiusX; dx++) {
                int cx = playerChunkX + dx;
                int cz = playerChunkZ + dz;
                boolean recorded = manager != null && manager.isRecorded(dimensionId, cx, cz);
                if (recorded) {
                    recordedCount++;
                }
                int px = startX + (dx + radiusX) * CELL_SIZE;
                int py = startY + (dz + radiusZ) * CELL_SIZE;
                int color = recorded ? COLOR_RECORDED : COLOR_UNRECORDED;
                graphics.fill(px, py, px + CELL_SIZE - 1, py + CELL_SIZE - 1, color);
            }
        }

        int centerPx = startX + radiusX * CELL_SIZE;
        int centerPy = startY + radiusZ * CELL_SIZE;
        graphics.fill(centerPx, centerPy, centerPx + CELL_SIZE - 1, centerPy + CELL_SIZE - 1, COLOR_PLAYER);

        graphics.fill(startX - 1, startY - 1, startX + gridPixelW + 1, startY, COLOR_BORDER);
        graphics.fill(startX - 1, startY + gridPixelH, startX + gridPixelW + 1, startY + gridPixelH + 1, COLOR_BORDER);
        graphics.fill(startX - 1, startY - 1, startX, startY + gridPixelH + 1, COLOR_BORDER);
        graphics.fill(startX + gridPixelW, startY - 1, startX + gridPixelW + 1, startY + gridPixelH + 1, COLOR_BORDER);

        graphics.centeredText(this.font, this.title.getString(), this.width / 2, 12, 0xFFFFFF);

        String status = manager != null
                ? "Всего записано: " + manager.chunksSaved() + " | в кадре: " + recordedCount + " из " + ((radiusX * 2 + 1) * (radiusZ * 2 + 1))
                : "Запись не активна — показаны только уже сохранённые чанки, если они попадают в кадр";
        graphics.centeredText(this.font, status, this.width / 2, startY - 16, 0xCCCCCC);

        graphics.centeredText(this.font,
                "Измерение: " + dimensionId + "   Esc — закрыть",
                this.width / 2, startY + gridPixelH + 12, 0xAAAAAA);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
