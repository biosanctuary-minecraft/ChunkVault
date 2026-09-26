package com.chunkvault.mixin;

import com.chunkvault.state.LastInteraction;
import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CHECK: this only handles ChestMenu (chests, trapped chests, ender-chest-
 * style single containers). Barrels/hoppers/dispensers/shulker boxes use
 * their own Menu subclasses in vanilla (GenericContainerMenu isn't actually
 * used by them the way it is in some mod frameworks) — if you want those
 * captured too, add an `instanceof` branch per menu type the same way the
 * ChestMenu one works below; the copy-into-BlockEntity logic is identical,
 * only the menu class and expected slot count differ.
 *
 * Double chests: vanilla stores them as two separate 27-slot ChestBlockEntity
 * instances combined into one 54-slot view. We do a best-effort split here;
 * which half gets the first 27 slots vs the last 27 is NOT guaranteed to
 * match vanilla's internal left/right ordering. Fine for a backup (nothing
 * is lost), just don't assume slot 0 is always the same physical corner.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerCaptureMixin {

    // Generics are erased to the upper bound (AbstractContainerMenu) in the
    // compiled field descriptor, so shadowing at that type matches regardless
    // of the concrete <T> the real class is parameterized with.
    @Shadow
    protected AbstractContainerMenu menu;

    @Inject(method = "removed", at = @At("HEAD"))
    private void chunkvault$onContainerClosed(CallbackInfo ci) {
        if (!RecordingState.get().isActive()) {
            return;
        }
        BlockPos pos = LastInteraction.get();
        if (pos == null) {
            return;
        }
        if (!(this.menu instanceof ChestMenu chestMenu)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }

        Container source = chestMenu.getContainer();
        int sourceSize = source.getContainerSize();

        BlockEntity primary = level.getBlockEntity(pos);
        if (!(primary instanceof Container primaryContainer)) {
            return;
        }

        if (sourceSize <= primaryContainer.getContainerSize()) {
            copyItems(source, 0, primaryContainer, 0, sourceSize);
        } else {
            // Likely a double chest (54 slots) split across two BlockEntities.
            int half = primaryContainer.getContainerSize(); // usually 27
            copyItems(source, 0, primaryContainer, 0, half);

            BlockEntity neighbor = findChestNeighbor(level, pos);
            if (neighbor instanceof Container neighborContainer) {
                copyItems(source, half, neighborContainer, 0, sourceSize - half);
                resaveChunkContaining(level, neighbor.getBlockPos());
            }
        }

        RecordingState.get().saveManager().notifyChestCaptured();
        resaveChunkContaining(level, pos);
    }

    private static void copyItems(Container from, int fromStart, Container to, int toStart, int count) {
        for (int i = 0; i < count; i++) {
            to.setItem(toStart + i, from.getItem(fromStart + i).copy());
        }
    }

    private static BlockEntity findChestNeighbor(ClientLevel level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(neighborPos);
            if (be instanceof ChestBlockEntity) {
                return be;
            }
        }
        return null;
    }

    private static void resaveChunkContaining(ClientLevel level, BlockPos pos) {
        var chunk = level.getChunk(pos);
        if (chunk instanceof LevelChunk levelChunk) {
            RecordingState.get().saveManager().saveChunk(level, levelChunk);
        }
    }
}
