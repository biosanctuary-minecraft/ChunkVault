package com.chunkvault.save;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.io.DataInputStream;

/**
 * Writes a minimal but hopefully-loadable level.dat.
 *
 * Honest caveat: the trickiest part of a from-scratch level.dat is
 * WorldGenSettings (dimension/biome-source/noise config) — get one field
 * wrong and singleplayer may refuse to open the world or silently ignore
 * our region files and regenerate terrain instead. To sidestep guessing
 * this, if a file named "template_level.dat" is placed next to the target
 * world (copied from ANY freshly-created 26.2 singleplayer world — the
 * seed/settings don't matter, we already have the actual chunks), we reuse
 * its WorldGenSettings verbatim and only patch identity/spawn fields.
 * Without a template we fall back to a hand-built minimal version — more
 * likely to need a manual fix, see README.
 */
public final class LevelDatWriter {

    public static void write(Path worldDir, String levelName, int spawnX, int spawnY, int spawnZ) {
        try {
            Path template = worldDir.resolve("template_level.dat");
            CompoundTag root = Files.exists(template)
                    ? loadTemplate(template)
                    : buildFromScratch();

            // Fixed: getCompound(...) now returns Optional<CompoundTag> instead
            // of the tag directly (or an empty compound if missing).
            CompoundTag data = root.getCompound("Data").orElseGet(CompoundTag::new);
            data.putString("LevelName", levelName);
            data.putInt("SpawnX", spawnX);
            data.putInt("SpawnY", spawnY);
            data.putInt("SpawnZ", spawnZ);
            data.putLong("LastPlayed", System.currentTimeMillis());
            root.put("Data", data);

            Path out = worldDir.resolve("level.dat");
            try (OutputStream fos = Files.newOutputStream(out);
                 GZIPOutputStream gz = new GZIPOutputStream(fos);
                 DataOutputStream dos = new DataOutputStream(gz)) {
                NbtIo.write(root, dos);
            }
        } catch (IOException e) {
            System.err.println("[ChunkVault] failed to write level.dat: " + e);
        }
    }

    private static CompoundTag loadTemplate(Path template) throws IOException {
        try (InputStream fis = Files.newInputStream(template);
             GZIPInputStream gz = new GZIPInputStream(fis);
             DataInputStream dis = new DataInputStream(gz)) {
            // CHECK: NbtIo.read(DataInput) is the stable low-level entrypoint;
            // some versions gate it behind a NbtAccounter size-limit parameter.
            return NbtIo.read(dis);
        }
    }

    private static CompoundTag buildFromScratch() {
        CompoundTag data = new CompoundTag();
        data.putInt("version", 19133); // legacy "this is an Anvil-format world" marker
        // Fixed: WorldVersion/DataVersion are records now -> dataVersion().version().
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        data.putInt("DataVersion", dataVersion);

        CompoundTag versionTag = new CompoundTag();
        versionTag.putInt("Id", dataVersion);
        versionTag.putString("Name", "26.2");
        versionTag.putString("Series", "main");
        versionTag.putBoolean("Snapshot", false);
        data.put("Version", versionTag);

        data.putInt("GameType", 0);
        data.putByte("Difficulty", (byte) 2);
        data.putByte("DifficultyLocked", (byte) 0);
        data.putBoolean("hardcore", false);
        data.putBoolean("allowCommands", true);
        data.putBoolean("initialized", true);
        data.putLong("Time", 0L);
        data.putLong("DayTime", 0L);
        data.putFloat("SpawnAngle", 0f);
        data.putDouble("BorderCenterX", 0.0);
        data.putDouble("BorderCenterZ", 0.0);
        data.putDouble("BorderSize", 60000000.0);
        data.putDouble("BorderSafeZone", 5.0);
        data.putDouble("BorderDamagePerBlock", 0.2);
        data.putDouble("BorderWarningBlocks", 5.0);
        data.putDouble("BorderWarningTime", 15.0);

        data.put("GameRules", new CompoundTag()); // vanilla fills in defaults for missing keys

        CompoundTag dataPacks = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());
        data.put("DataPacks", dataPacks);

        // CHECK (highest risk in this whole file): minimal best-effort world-gen
        // config so the dimension registries have *something* valid to read.
        // If singleplayer rejects this world or regenerates terrain in areas
        // we already saved, drop a "template_level.dat" (see class doc)
        // instead of relying on this block.
        CompoundTag worldGen = new CompoundTag();
        worldGen.putLong("seed", 0L);
        worldGen.putBoolean("generate_features", true);
        worldGen.putBoolean("bonus_chest", false);
        data.put("WorldGenSettings", worldGen);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        return root;
    }

    private LevelDatWriter() {}
}
