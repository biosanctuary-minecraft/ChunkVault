package com.chunkvault.mixin;

import com.chunkvault.gui.MapCache;
import com.chunkvault.gui.ProjectMap;
import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
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
        if (chunk instanceof LevelChunk levelChunk) {
            MapCache.update(level, levelChunk);
            manager.saveChunk(level, levelChunk);
            ProjectMap.onChunkRecorded(level.dimension().identifier().toString(),
                    levelChunk.getPos().x(), levelChunk.getPos().z());
        }
    }
}
