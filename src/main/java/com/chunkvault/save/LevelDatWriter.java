package com.chunkvault.save;

import com.chunkvault.state.ChunkVaultSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class LevelDatWriter {

    private static final String VOID_TEMPLATE_WORLD_NAME = "chunkvault-void-template";

    private LevelDatWriter() {}

    public static void write(Path worldDir, Path gameDir, String levelName, BlockPos spawnPos, CompoundTag playerTag) {
        try {
            CompoundTag root = loadTemplateOrFallback(worldDir, gameDir);

            CompoundTag data = root.getCompound("Data").orElseGet(CompoundTag::new);
            data.putString("LevelName", levelName);
            if (spawnPos != null) {
                data.putInt("SpawnX", spawnPos.getX());
                data.putInt("SpawnY", spawnPos.getY());
                data.putInt("SpawnZ", spawnPos.getZ());
            }
            if (playerTag != null) {
                data.put("Player", playerTag);
            }
            CompoundTag gameRules = data.getCompound("GameRules").orElseGet(CompoundTag::new);
            gameRules.putString("doMobSpawning",
                    ChunkVaultSettings.get().preventMobSpawning() ? "false" : "true");
            data.put("GameRules", gameRules);

            data.putLong("LastPlayed", System.currentTimeMillis());
            root.put("Data", data);

            Path out = worldDir.resolve("level.dat");
            try (OutputStream fos = Files.newOutputStream(out);
                 GZIPOutputStream gz = new GZIPOutputStream(fos);
                 DataOutputStream dos = new DataOutputStream(gz)) {
                NbtIo.write(root, dos);
            }
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to write level.dat: " + e);
        }
    }

    private static boolean hasWorldGenSettings(Path worldDir) {
        return Files.isRegularFile(worldDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat"));
    }

    private static CompoundTag loadTemplateOrFallback(Path worldDir, Path gameDir) throws IOException {
        boolean skipDataCopy = hasWorldGenSettings(worldDir);

        Path manual = worldDir.resolve("template_level.dat");
        if (Files.exists(manual)) {
            try {
                CompoundTag tag = readNbt(manual);
                Path manualData = worldDir.resolve("template_data");
                if (!skipDataCopy && Files.isDirectory(manualData)) {
                    copyDataDir(manualData, worldDir.resolve("data"));
                }
                return tag;
            } catch (IOException e) {
                System.err.println("[ChunkVault] Failed to read template_level.dat: " + e);
            }
        }

        if (gameDir != null) {
            Path savesDir = gameDir.resolve("saves");

            if (!skipDataCopy) {
                Path voidWorld = savesDir.resolve(VOID_TEMPLATE_WORLD_NAME);
                Path voidLevelDat = voidWorld.resolve("level.dat");
                if (Files.isRegularFile(voidLevelDat)) {
                    try {
                        CompoundTag tag = readNbt(voidLevelDat);
                        Path sourceData = voidWorld.resolve("data");
                        if (Files.isDirectory(sourceData)) {
                            copyDataDir(sourceData, worldDir.resolve("data"));
                        }
                        return tag;
                    } catch (IOException e) {
                        System.err.println("[ChunkVault] Failed to read void template world: " + e);
                    }
                }
            }

            CompoundTag fromSaves = findLatestLevelDat(savesDir, worldDir, skipDataCopy);
            if (fromSaves != null) {
                return fromSaves;
            }
        }

        return buildMinimal();
    }

    private static CompoundTag findLatestLevelDat(Path savesDir, Path targetWorldDir, boolean skipDataCopy) {
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
            CompoundTag tag = readNbt(candidate);
            if (!skipDataCopy) {
                Path sourceData = candidate.getParent().resolve("data");
                if (Files.isDirectory(sourceData)) {
                    copyDataDir(sourceData, targetWorldDir.resolve("data"));
                }
            }
            return tag;
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to scan saves/: " + e);
            return null;
        }
    }

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
                    System.err.println("[ChunkVault] Failed copying " + src + ": " + e);
                }
            });
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to copy data/ from " + source + ": " + e);
        }
    }

    private static CompoundTag readNbt(Path file) throws IOException {
        try (InputStream fis = Files.newInputStream(file);
             GZIPInputStream gz = new GZIPInputStream(fis);
             DataInputStream dis = new DataInputStream(gz)) {
            return NbtIo.read(dis);
        }
    }

    private static CompoundTag buildMinimal() {
        CompoundTag data = new CompoundTag();
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        data.putInt("version", 19133);
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

        data.put("GameRules", new CompoundTag());

        CompoundTag dataPacks = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());
        data.put("DataPacks", dataPacks);

        CompoundTag worldGen = new CompoundTag();
        worldGen.putLong("seed", 0L);
        worldGen.putBoolean("generate_features", true);
        worldGen.putBoolean("bonus_chest", false);
        data.put("WorldGenSettings", worldGen);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        return root;
    }
}
