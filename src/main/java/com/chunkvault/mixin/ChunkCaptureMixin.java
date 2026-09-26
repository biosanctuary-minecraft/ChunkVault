package com.chunkvault.mixin;

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

/**
 * CHECK: method name / packet class confirmed for the 1.20-1.21 era under
 * Mojmap; if genSources shows something different, look for whichever
 * ClientPacketListener method takes a chunk-with-light packet and finishes
 * by calling level.getChunkSource().replaceWithPacketData(...) or similar —
 * that's this hook.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChunkCaptureMixin {

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void chunkvault$onChunkLoaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!RecordingState.get().isActive()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        var chunk = level.getChunk(packet.getX(), packet.getZ());
        if (chunk instanceof LevelChunk levelChunk) {
            RecordingState.get().saveManager().saveChunk(level, levelChunk);
        }
    }
}
