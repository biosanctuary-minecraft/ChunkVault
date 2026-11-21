package com.chunkvault.mixin;

import com.chunkvault.Lang;
import com.chunkvault.save.WorldSaveManager;
import com.chunkvault.state.AreaPlan;
import com.chunkvault.state.ChunkVaultSettings;
import com.chunkvault.state.LastInteraction;
import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerCaptureMixin {

    @Shadow
    protected AbstractContainerMenu menu;

    @Unique
    private boolean chunkvault$checked;

    @Unique
    private BlockPos chunkvault$targetPos;

    @Inject(method = "init", at = @At("HEAD"))
    private void chunkvault$onContainerOpened(CallbackInfo ci) {
        if (chunkvault$checked) {
            return;
        }
        chunkvault$checked = true;
        ChunkVaultSettings settings = ChunkVaultSettings.get();
        ChunkVaultSettings.ContainerCategory category = categoryOf(this.menu);
        if (!RecordingState.get().isActive() || category == null
                || !settings.saveContainers() || !settings.isContainerEnabled(category)) {
            return;
        }
        BlockPos pos = LastInteraction.consumeRecent();
        if (pos == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null
                || client.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
            return;
        }
        chunkvault$targetPos = pos;
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void chunkvault$onContainerClosed(CallbackInfo ci) {
        try {
            chunkvault$capture();
        } catch (Throwable t) {
            System.err.println("[ChunkVault] Container capture failed: " + t);
            t.printStackTrace();
        }
    }

    private void chunkvault$capture() {
        BlockPos pos = chunkvault$targetPos;
        chunkvault$targetPos = null;
        ChunkVaultSettings settings = ChunkVaultSettings.get();
        ChunkVaultSettings.ContainerCategory category = categoryOf(this.menu);
        if (!RecordingState.get().isActive() || pos == null || category == null
                || !settings.saveContainers() || !settings.isContainerEnabled(category)) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        WorldSaveManager manager = RecordingState.get().saveManager();
        if (level == null || manager == null) {
            return;
        }

        Container source = sourceContainer(this.menu);
        if (source == null) {
            return;
        }
        int sourceSize = source.getContainerSize();

        BlockEntity primary = level.getBlockEntity(pos);
        if (!(primary instanceof Container primaryContainer)) {
            return;
        }
        int half = primaryContainer.getContainerSize();
        BlockState state = level.getBlockState(pos);
        String dim = level.dimension().identifier().toString();

        if (sourceSize <= half || !(state.getBlock() instanceof ChestBlock)) {
            if (!AreaPlan.allowsBlock(dim, pos.getX(), pos.getZ())) {
                return;
            }
            copyItems(source, 0, primaryContainer, 0, Math.min(sourceSize, half));
            manager.rememberContainer(level, primary);
        } else {
            BlockPos otherPos = findOtherHalf(level, state, pos);
            BlockEntity other = otherPos == null ? null : level.getBlockEntity(otherPos);
            if (other instanceof Container otherContainer) {
                boolean allowPrimary = AreaPlan.allowsBlock(dim, pos.getX(), pos.getZ());
                boolean allowOther = AreaPlan.allowsBlock(dim, otherPos.getX(), otherPos.getZ());
                if (!allowPrimary && !allowOther) {
                    return;
                }
                boolean primaryIsFirst = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT;
                BlockEntity firstBe = primaryIsFirst ? primary : other;
                BlockEntity secondBe = primaryIsFirst ? other : primary;
                Container firstContainer = primaryIsFirst ? primaryContainer : otherContainer;
                Container secondContainer = primaryIsFirst ? otherContainer : primaryContainer;
                copyItems(source, 0, firstContainer, 0, half);
                copyItems(source, half, secondContainer, 0, sourceSize - half);
                manager.rememberContainer(level, firstBe);
                manager.rememberContainer(level, secondBe);
                resaveChunkContaining(level, manager, otherPos);
            } else {
                if (!AreaPlan.allowsBlock(dim, pos.getX(), pos.getZ())) {
                    return;
                }
                copyItems(source, 0, primaryContainer, 0, half);
                manager.rememberContainer(level, primary);
            }
        }

        manager.notifyChestCaptured();
        resaveChunkContaining(level, manager, pos);

        if (client.player != null) {
            int filled = 0;
            for (int i = 0; i < sourceSize; i++) {
                if (!source.getItem(i).isEmpty()) {
                    filled++;
                }
            }
            client.player.sendSystemMessage(Component.literal("§a[ChunkVault] ").append(
                    Lang.c("chest.saved", state.getBlock().getName(),
                            pos.getX(), pos.getY(), pos.getZ(), filled, sourceSize)));
        }
    }

    private static void copyItems(Container from, int fromStart, Container to, int toStart, int count) {
        for (int i = 0; i < count; i++) {
            try {
                to.setItem(toStart + i, from.getItem(fromStart + i).copy());
            } catch (Throwable t) {
                System.err.println("[ChunkVault] Failed to copy slot " + (toStart + i) + ": " + t);
            }
        }
    }

    private static ChunkVaultSettings.ContainerCategory categoryOf(AbstractContainerMenu m) {
        if (m instanceof ChestMenu) {
            return ChunkVaultSettings.ContainerCategory.CHESTS;
        }
        if (m instanceof ShulkerBoxMenu) {
            return ChunkVaultSettings.ContainerCategory.SHULKERS;
        }
        if (m instanceof HopperMenu) {
            return ChunkVaultSettings.ContainerCategory.HOPPERS;
        }
        if (m instanceof DispenserMenu) {
            return ChunkVaultSettings.ContainerCategory.DISPENSERS;
        }
        if (m instanceof AbstractFurnaceMenu) {
            return ChunkVaultSettings.ContainerCategory.FURNACES;
        }
        if (m instanceof BrewingStandMenu) {
            return ChunkVaultSettings.ContainerCategory.BREWING;
        }
        if (m instanceof CrafterMenu) {
            return ChunkVaultSettings.ContainerCategory.CRAFTERS;
        }
        return null;
    }

    private static Container sourceContainer(AbstractContainerMenu m) {
        if (m instanceof ChestMenu chestMenu) {
            return chestMenu.getContainer();
        }
        for (Slot slot : m.slots) {
            if (!(slot.container instanceof Inventory)) {
                return slot.container;
            }
        }
        return null;
    }

    private static BlockPos findOtherHalf(ClientLevel level, BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return pos.relative(ChestBlock.getConnectedDirection(state));
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(dir);
            if (level.getBlockEntity(neighborPos) instanceof ChestBlockEntity) {
                return neighborPos;
            }
        }
        return null;
    }

    private static void resaveChunkContaining(ClientLevel level, WorldSaveManager manager, BlockPos pos) {
        var chunk = level.getChunk(pos);
        if (chunk instanceof LevelChunk levelChunk) {
            manager.saveChunk(level, levelChunk);
        }
    }
}
