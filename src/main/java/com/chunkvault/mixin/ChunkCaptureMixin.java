package com.chunkvault.mixin;

import com.chunkvault.gui.MapCache;
import com.chunkvault.gui.ProjectMap;
import com.chunkvault.state.DirtyChunkTracker;
import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ChunkCaptureMixin {

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void chunkvault$onChunkLoaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !RecordingState.get().isActive()) {
            return;
        }
        var manager = RecordingState.get().saveManager();
        if (manager == null) {
            return;
        }
        var chunk = level.getChunk(packet.getX(), packet.getZ());
        if (chunk instanceof LevelChunk levelChunk && !(chunk instanceof EmptyLevelChunk)) {
            String dim = level.dimension().identifier().toString();
            DirtyChunkTracker.consume(dim, levelChunk.getPos().x(), levelChunk.getPos().z());
            if (manager.saveChunk(level, levelChunk)) {
                MapCache.update(level, levelChunk);
                ProjectMap.onChunkRecorded(dim, levelChunk.getPos().x(), levelChunk.getPos().z());
            }
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void chunkvault$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !RecordingState.get().isActive()) {
            return;
        }
        BlockPos pos = packet.getPos();
        DirtyChunkTracker.mark(level.dimension().identifier().toString(), pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void chunkvault$onSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !RecordingState.get().isActive()) {
            return;
        }
        String dim = level.dimension().identifier().toString();
        boolean[] marked = {false};
        packet.runUpdates((pos, state) -> {
            if (!marked[0]) {
                marked[0] = true;
                DirtyChunkTracker.mark(dim, pos.getX() >> 4, pos.getZ() >> 4);
            }
        });
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"))
    private void chunkvault$onChunkUnloading(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            return;
        }
        ClientLevel level = mc.level;
        if (level == null || !RecordingState.get().isActive()) {
            return;
        }
        var manager = RecordingState.get().saveManager();
        if (manager == null) {
            return;
        }
        int cx = packet.pos().x();
        int cz = packet.pos().z();
        String dim = level.dimension().identifier().toString();
        if (!DirtyChunkTracker.consume(dim, cx, cz)) {
            return;
        }
        var chunk = level.getChunk(cx, cz);
        if (chunk instanceof LevelChunk levelChunk && !(chunk instanceof EmptyLevelChunk)) {
            if (manager.saveChunk(level, levelChunk)) {
                MapCache.update(level, levelChunk);
                ProjectMap.onChunkRecorded(dim, cx, cz);
            }
        }
    }
}
