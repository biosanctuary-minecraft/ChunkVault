package com.chunkvault.gui;

import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A ChunkBase/WorldDownloader-style overview: a grid of squares around the
 * player, colored by whether that chunk has already been recorded this
 * session. Opened with /chunkvault map. Read-only — Esc closes it, nothing
 * here writes any game state.
 *
 * CHECK: GuiGraphics has been the drawing API since 1.20 and looked stable
 * through every other check this mod hit, but fill()/drawCenteredString()
 * signatures are exactly the kind of thing that's shifted before in this
 * version — if this file doesn't compile, the fix is almost certainly a
 * renamed/re-ordered parameter on one of those two calls, not a logic bug.
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately not calling the vanilla blurred-panorama background
        // helper here — its exact signature has moved around between
        // versions more than once, and a flat backdrop does the job fine.
        graphics.fill(0, 0, this.width, this.height, COLOR_BACKDROP);

        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        var level = mc.level;
        var manager = RecordingState.get().saveManager();

        if (player == null || level == null) {
            graphics.drawCenteredString(this.font, "Нет активного мира.", this.width / 2, this.height / 2, 0xFFFFFF);
            super.render(graphics, mouseX, mouseY, partialTick);
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

        graphics.drawCenteredString(this.font, this.title.getString(), this.width / 2, 12, 0xFFFFFF);

        String status = manager != null
                ? "Всего записано: " + manager.chunksSaved() + " | в кадре: " + recordedCount + " из " + ((radiusX * 2 + 1) * (radiusZ * 2 + 1))
                : "Запись не активна — показаны только уже сохранённые чанки, если они попадают в кадр";
        graphics.drawCenteredString(this.font, status, this.width / 2, startY - 16, 0xCCCCCC);

        graphics.drawCenteredString(this.font,
                "Измерение: " + dimensionId + "   Esc — закрыть",
                this.width / 2, startY + gridPixelH + 12, 0xAAAAAA);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
