package com.chunkvault.save;

import com.chunkvault.state.ChunkVaultSettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
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
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class LevelDatWriter {

    private static final String VOID_TEMPLATE_WORLD_NAME = "chunkvault-void-template";

    private LevelDatWriter() {}

    public static void write(Path worldDir, Path gameDir, String levelName, BlockPos spawnPos, UUID uuid) {
        try {
            CompoundTag root = loadTemplateOrFallback(worldDir, gameDir);

            CompoundTag data = root.getCompound("Data").orElseGet(CompoundTag::new);
            data.putString("LevelName", levelName);
            if (spawnPos != null) {
                CompoundTag spawn = new CompoundTag();
                spawn.put("pos", new IntArrayTag(new int[]{spawnPos.getX(), spawnPos.getY(), spawnPos.getZ()}));
                spawn.putString("dimension", "minecraft:overworld");
                spawn.putFloat("yaw", 0.0f);
                spawn.putFloat("pitch", 0.0f);
                data.put("spawn", spawn);
            }
            if (uuid != null) {
                data.put("singleplayer_uuid", new IntArrayTag(uuidToIntArray(uuid)));
                data.remove("Player");
            }

            data.putLong("LastPlayed", System.currentTimeMillis());
            root.put("Data", data);

            writeGzipNbt(worldDir.resolve("level.dat"), root);
            writeGameRules(worldDir, !ChunkVaultSettings.get().preventMobSpawning());
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to write level.dat: " + e);
        }
    }

    private static int[] uuidToIntArray(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new int[]{(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
    }

    private static void writeGameRules(Path worldDir, boolean spawnMobs) throws IOException {
        Path file = worldDir.resolve("data").resolve("minecraft").resolve("game_rules.dat");
        CompoundTag root;
        if (Files.isRegularFile(file)) {
            try {
                root = readNbt(file);
            } catch (IOException e) {
                root = new CompoundTag();
            }
        } else {
            root = new CompoundTag();
        }
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        CompoundTag data = root.getCompound("data").orElseGet(CompoundTag::new);
        CompoundTag rules = data.getCompound("rules").orElse(null);
        if (rules != null) {
            rules.putBoolean("minecraft:spawn_mobs", spawnMobs);
            data.put("rules", rules);
        } else {
            data.putBoolean("minecraft:spawn_mobs", spawnMobs);
        }
        root.put("data", data);
        Files.createDirectories(file.getParent());
        writeGzipNbt(file, root);
    }

    private static void writeGzipNbt(Path file, CompoundTag root) throws IOException {
        try (OutputStream fos = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(gz)) {
            NbtIo.write(root, dos);
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
        CompoundTag difficultySettings = new CompoundTag();
        difficultySettings.putString("difficulty", "normal");
        difficultySettings.putBoolean("locked", false);
        difficultySettings.putBoolean("hardcore", false);
        data.put("difficulty_settings", difficultySettings);

        data.putBoolean("allowCommands", true);
        data.putBoolean("initialized", true);
        data.putLong("Time", 0L);

        CompoundTag spawn = new CompoundTag();
        spawn.put("pos", new IntArrayTag(new int[]{0, 64, 0}));
        spawn.putString("dimension", "minecraft:overworld");
        spawn.putFloat("yaw", 0.0f);
        spawn.putFloat("pitch", 0.0f);
        data.put("spawn", spawn);

        CompoundTag dataPacks = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());
        data.put("DataPacks", dataPacks);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        return root;
    }
}
