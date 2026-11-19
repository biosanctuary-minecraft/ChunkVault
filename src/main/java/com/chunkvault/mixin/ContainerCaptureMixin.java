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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the contents of a chest when its screen is closed.
 *
 * ChestMenu is used by chests, trapped chests AND barrels. Other containers
 * (shulker boxes, hoppers, dispensers, furnaces...) use their own menu
 * classes and are not captured yet.
 *
 * The items are copied into the client's block entity and then stored by
 * WorldSaveManager#rememberContainer, which makes every later save of that
 * chunk include them (the client never gets chest contents from the server,
 * so a plain re-save of the chunk would write empty chests).
 *
 * Double chests: vanilla's 54-slot view is [RIGHT half (27 slots)] followed by
 * [LEFT half (27 slots)], so each half is written to its own block entity.
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
        try {
            chunkvault$capture();
        } catch (Throwable t) {
            // never break closing a screen because of a recording problem
            System.err.println("[ChunkVault] chest capture failed: " + t);
            t.printStackTrace();
        }
    }

    private void chunkvault$capture() {
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
        var manager = RecordingState.get().saveManager();
        if (level == null || manager == null) {
            return;
        }

        Container source = chestMenu.getContainer();
        int sourceSize = source.getContainerSize();

        BlockEntity primary = level.getBlockEntity(pos);
        if (!(primary instanceof Container primaryContainer)) {
            return;
        }
        int half = primaryContainer.getContainerSize(); // 27 for a chest / barrel

        if (sourceSize <= half) {
            // single chest or barrel
            copyItems(source, 0, primaryContainer, 0, sourceSize);
            manager.rememberContainer(level, primary);
        } else {
            // double chest: find the other half
            BlockState state = level.getBlockState(pos);
            BlockPos otherPos = findOtherHalf(level, state, pos);
            BlockEntity other = otherPos == null ? null : level.getBlockEntity(otherPos);
            if (other instanceof Container otherContainer) {
                // the RIGHT half holds the first 27 slots, the LEFT half the last 27
                boolean primaryIsFirst = !(state.getBlock() instanceof ChestBlock)
                        || state.getValue(ChestBlock.TYPE) == ChestType.RIGHT;
                BlockEntity firstBe = primaryIsFirst ? primary : other;
                BlockEntity secondBe = primaryIsFirst ? other : primary;
                copyItems(source, 0, (Container) firstBe, 0, half);
                copyItems(source, half, (Container) secondBe, 0, sourceSize - half);
                manager.rememberContainer(level, firstBe);
                manager.rememberContainer(level, secondBe);
                resaveChunkContaining(level, otherPos);
            } else {
                // other half not found: keep at least the first 27 slots
                copyItems(source, 0, primaryContainer, 0, half);
                manager.rememberContainer(level, primary);
            }
        }

        manager.notifyChestCaptured();
        resaveChunkContaining(level, pos);
    }

    private static void copyItems(Container from, int fromStart, Container to, int toStart, int count) {
        for (int i = 0; i < count; i++) {
            to.setItem(toStart + i, from.getItem(fromStart + i).copy());
        }
    }

    /** Position of the second chest of a double chest (null if it is not one). */
    private static BlockPos findOtherHalf(ClientLevel level, BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return pos.relative(ChestBlock.getConnectedDirection(state));
        }
        // fallback: any chest standing right next to it
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(dir);
            if (level.getBlockEntity(neighborPos) instanceof ChestBlockEntity) {
                return neighborPos;
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
