package com.chunkvault.save;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Builds and writes the player's saved state (position, rotation, dimension)
 * so that opening the recorded world drops the player exactly where
 * /chunkvault stop was run, in whichever dimension that was — not just the
 * Overworld, which is all level.dat's SpawnX/Y/Z can ever address.
 *
 * CHECK: as of 26.x, singleplayer worlds may have moved from embedding the
 * player directly in level.dat's Data.Player compound to a standalone file
 * (one changelog described the new path as players/data/<uuid>.dat rather
 * than the classic playerdata/<uuid>.dat used by multiplayer worlds/
 * servers). Since we can't verify which one 26.2 actually reads for a
 * freshly-created singleplayer world, we write to ALL three places —
 * whichever one is real gets picked up, the others are just unused
 * leftover files.
 */
public final class PlayerDataWriter {

    public static CompoundTag buildPlayerTag(UUID uuid, double x, double y, double z,
                                              float yaw, float pitch, String dimensionId) {
        CompoundTag player = new CompoundTag();

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        player.put("Pos", pos);

        ListTag motion = new ListTag();
        motion.add(DoubleTag.valueOf(0.0));
        motion.add(DoubleTag.valueOf(0.0));
        motion.add(DoubleTag.valueOf(0.0));
        player.put("Motion", motion);

        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(yaw));
        rotation.add(FloatTag.valueOf(pitch));
        player.put("Rotation", rotation);

        player.putString("Dimension", dimensionId);
        player.putFloat("FallDistance", 0f);
        player.putShort("Fire", (short) -1);
        player.putShort("Air", (short) 300);
        player.putBoolean("OnGround", true);
        player.putFloat("Health", 20f);
        player.putInt("foodLevel", 20);
        player.putFloat("foodSaturationLevel", 5f);
        player.putFloat("foodExhaustionLevel", 0f);
        player.putInt("Score", 0);
        player.putInt("SelectedItemSlot", 0);
        player.putInt("playerGameType", 0);
        player.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());

        return player;
    }

    /** Writes the same player tag to every plausible player-data location. */
    public static void writeToWorld(Path worldDir, UUID uuid, CompoundTag playerTag) {
        writeCompressed(worldDir.resolve("playerdata").resolve(uuid + ".dat"), playerTag);
        writeCompressed(worldDir.resolve("players").resolve("data").resolve(uuid + ".dat"), playerTag);
    }

    private static void writeCompressed(Path file, CompoundTag tag) {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream fos = Files.newOutputStream(file);
                 GZIPOutputStream gz = new GZIPOutputStream(fos);
                 DataOutputStream dos = new DataOutputStream(gz)) {
                NbtIo.write(tag, dos);
            }
        } catch (IOException e) {
            System.err.println("[ChunkVault] failed to write player data to " + file + ": " + e);
        }
    }

    private PlayerDataWriter() {}
}
