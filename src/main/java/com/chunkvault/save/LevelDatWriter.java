package com.chunkvault.save;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.io.DataInputStream;

/**
 * Writes a minimal but hopefully-loadable level.dat.
 *
 * Honest caveat: the trickiest part of a from-scratch level.dat is
 * WorldGenSettings (dimension/biome-source/noise config). As of 26.2 this
 * isn't even stored inside level.dat anymore — it's a separate file at
 * saves/<world>/data/minecraft/world_gen_settings.dat, and a level.dat
 * without a matching one fails to load with "Overworld settings missing".
 * To sidestep hand-building that file, we copy BOTH level.dat and the
 * data/ folder from any world that already exists in the player's own
 * saves/ directory (no manual copying needed — every player has at least
 * one world already, and its seed/settings don't matter since we already
 * have the real chunks). A manually placed "template_level.dat" (plus an
 * optional sibling "template_data/" folder) next to the target world still
 * takes priority if present. Only if neither exists do we fall back to a
 * hand-built minimal version, which is the option most likely to need a
 * manual fix.
 */
public final class LevelDatWriter {

    public static void write(Path worldDir, String levelName, BlockPos spawnPos) {
        write(worldDir, null, levelName, spawnPos);
    }

    public static void write(Path worldDir, Path gameDir, String levelName, BlockPos spawnPos) {
        try {
            CompoundTag root = loadBestAvailableTemplate(worldDir, gameDir);

            // Fixed: getCompound(...) now returns Optional<CompoundTag> instead
            // of the tag directly (or an empty compound if missing).
            CompoundTag data = root.getCompound("Data").orElseGet(CompoundTag::new);
            data.putString("LevelName", levelName);
            // spawnPos is null when the recorded position wasn't in the
            // Overworld (level.dat's SpawnX/Y/Z is always an Overworld
            // coordinate — writing Nether/End coordinates there just drops
            // the player somewhere unrelated). In that case we keep
            // whatever spawn the template world already had.
            if (spawnPos != null) {
                data.putInt("SpawnX", spawnPos.getX());
                data.putInt("SpawnY", spawnPos.getY());
                data.putInt("SpawnZ", spawnPos.getZ());
            }
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

    private static CompoundTag loadBestAvailableTemplate(Path worldDir, Path gameDir) throws IOException {
        // 1) explicit manual override, if the user placed one
        Path manual = worldDir.resolve("template_level.dat");
        if (Files.exists(manual)) {
            try {
                CompoundTag tag = loadTemplate(manual);
                Path manualData = worldDir.resolve("template_data");
                if (Files.isDirectory(manualData)) {
                    copyDataDir(manualData, worldDir.resolve("data"));
                }
                return tag;
            } catch (IOException e) {
                System.err.println("[ChunkVault] template_level.dat unreadable, falling back: " + e);
            }
        }

        // 2) auto-discovered: any existing world in the player's own saves/
        if (gameDir != null) {
            Path savesDir = gameDir.resolve("saves");
            CompoundTag fromSaves = findAnyLevelDatUnder(savesDir, worldDir);
            if (fromSaves != null) {
                return fromSaves;
            }
        }

        // 3) last resort: hand-built, riskiest option (see class doc)
        return buildFromScratch();
    }

    /** Returns the parsed level.dat of the most recently played world found, and copies its data/ folder alongside it. */
    private static CompoundTag findAnyLevelDatUnder(Path savesDir, Path targetWorldDir) {
        if (!Files.isDirectory(savesDir)) {
            return null;
        }
        try (var worlds = Files.list(savesDir)) {
            Path candidate = worlds
                    .filter(Files::isDirectory)
                    .map(dir -> dir.resolve("level.dat"))
                    .filter(Files::isRegularFile)
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElse(null);
            if (candidate == null) {
                return null;
            }
            CompoundTag tag = loadTemplate(candidate);
            Path sourceData = candidate.getParent().resolve("data");
            if (Files.isDirectory(sourceData)) {
                copyDataDir(sourceData, targetWorldDir.resolve("data"));
            }
            return tag;
        } catch (IOException e) {
            System.err.println("[ChunkVault] could not scan saves/ for a level.dat template: " + e);
            return null;
        }
    }

    /** Recursively copies a world's data/ folder (world_gen_settings.dat and friends). */
    private static void copyDataDir(Path source, Path target) {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    System.err.println("[ChunkVault] failed copying " + src + ": " + e);
                }
            });
        } catch (IOException e) {
            System.err.println("[ChunkVault] failed to copy data/ folder from " + source + ": " + e);
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
