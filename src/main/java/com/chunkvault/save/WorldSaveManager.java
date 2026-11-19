package com.chunkvault.save;

import com.chunkvault.state.ChunkVaultSettings;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldSaveManager {

    private record EntityRecord(String entitiesFolder, int chunkX, int chunkZ, CompoundTag tag) {}
    private record ContainerSnapshot(String typeId, CompoundTag tag) {}

    private final Path rootDir;
    private final Path gameDir;
    private final String levelName;
    private final Map<String, SimpleRegionFile> openRegions = new ConcurrentHashMap<>();
    private final Set<String> recordedChunks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, EntityRecord> entityRecords = new ConcurrentHashMap<>();
    private final Map<String, ContainerSnapshot> containers = new ConcurrentHashMap<>();

    private volatile int chunksSaved;
    private volatile int chestsSeen;

    public WorldSaveManager(Path rootDir, Path gameDir, String levelName) {
        this.rootDir = rootDir;
        this.gameDir = gameDir;
        this.levelName = levelName;
    }

    public Path rootDir() {
        return rootDir;
    }

    public int chunksSaved() {
        return chunksSaved;
    }

    public void notifyChestCaptured() {
        chestsSeen++;
    }

    public int chestsSeen() {
        return chestsSeen;
    }

    public int entitiesSeen() {
        return entityRecords.size();
    }

    public void init() {
        try {
            Files.createDirectories(rootDir.resolve("dimensions/minecraft/overworld/region"));
            Files.createDirectories(rootDir.resolve("data"));
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to create save directory " + rootDir + ": " + e);
        }
        loadContainers();
    }

    private static String containerKey(Level level, BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    private static String typeIdOf(BlockEntity be) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()));
    }

    public synchronized void rememberContainer(ClientLevel level, BlockEntity be) {
        try {
            CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
            containers.put(containerKey(level, be.getBlockPos()), new ContainerSnapshot(typeIdOf(be), tag));
            saveContainers();
        } catch (Throwable t) {
            System.err.println("[ChunkVault] Failed to cache container at " + be.getBlockPos() + ": " + t);
        }
    }

    private CompoundTag containerOverride(ClientLevel level, BlockPos pos, BlockEntity be) {
        ContainerSnapshot snapshot = containers.get(containerKey(level, pos));
        if (snapshot == null || !snapshot.typeId().equals(typeIdOf(be))) {
            return null;
        }
        return snapshot.tag().copy();
    }

    private void saveContainers() {
        try {
            CompoundTag root = new CompoundTag();
            for (Map.Entry<String, ContainerSnapshot> e : containers.entrySet()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("type", e.getValue().typeId());
                entry.put("data", e.getValue().tag());
                root.put(e.getKey(), entry);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(root, new DataOutputStream(bytes));
            Files.write(rootDir.resolve("chunkvault_containers.dat"), bytes.toByteArray());
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to write chunkvault_containers.dat: " + e);
        }
    }

    private void loadContainers() {
        Path file = rootDir.resolve("chunkvault_containers.dat");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            CompoundTag root = NbtIo.read(dis);
            for (String key : root.keySet()) {
                CompoundTag entry = root.getCompound(key).orElse(null);
                if (entry == null) {
                    continue;
                }
                String type = entry.getString("type").orElse("");
                CompoundTag data = entry.getCompound("data").orElse(null);
                if (data != null && !type.isEmpty()) {
                    containers.put(key, new ContainerSnapshot(type, data));
                }
            }
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to read chunkvault_containers.dat: " + e);
        }
    }

    private static String regionFolderFor(Level level) {
        var id = level.dimension().identifier();
        return "dimensions/" + id.getNamespace() + "/" + id.getPath() + "/region";
    }

    public synchronized void saveChunk(ClientLevel level, LevelChunk chunk) {
        try {
            CompoundTag tag = ChunkNbtWriter.write(level, chunk,
                    (pos, be) -> containerOverride(level, pos, be));

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));

            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            int regionX = Math.floorDiv(chunkX, 32);
            int regionZ = Math.floorDiv(chunkZ, 32);
            String regionFolder = regionFolderFor(level);
            String regionKey = regionFolder + ":" + regionX + "," + regionZ;

            SimpleRegionFile region = openRegions.computeIfAbsent(regionKey, k -> {
                try {
                    Path dir = rootDir.resolve(regionFolder);
                    Files.createDirectories(dir);
                    return new SimpleRegionFile(dir.resolve("r." + regionX + "." + regionZ + ".mca"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            region.writeChunk(Math.floorMod(chunkX, 32), Math.floorMod(chunkZ, 32), bytes.toByteArray());
            recordedChunks.add(level.dimension().identifier() + ":" + chunkX + "," + chunkZ);
            chunksSaved++;
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to save chunk " + chunk.getPos() + ": " + e);
        }
    }

    public synchronized void snapshotEntities(ClientLevel level) {
        if (!ChunkVaultSettings.get().saveEntities()) {
            return;
        }
        String dimensionId = level.dimension().identifier().toString();
        String entitiesFolder = regionFolderFor(level).replaceAll("region$", "entities");
        for (Entity entity : level.entitiesForRendering()) {
            try {
                if (entity instanceof Player || entity.isPassenger() || entity.isRemoved()
                        || !entity.getType().canSerialize()) {
                    continue;
                }
                int chunkX = (int) Math.floor(entity.getX() / 16.0);
                int chunkZ = (int) Math.floor(entity.getZ() / 16.0);
                if (!recordedChunks.contains(dimensionId + ":" + chunkX + "," + chunkZ)) {
                    continue;
                }
                TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                output.putString("id", EntityType.getKey(entity.getType()).toString());
                entity.saveWithoutId(output);
                entityRecords.put(entity.getUUID(), new EntityRecord(entitiesFolder, chunkX, chunkZ, output.buildResult()));
            } catch (Throwable ignored) {
            }
        }
    }

    private void flushEntities() {
        Map<String, List<EntityRecord>> byChunk = new HashMap<>();
        for (EntityRecord record : entityRecords.values()) {
            byChunk.computeIfAbsent(record.entitiesFolder() + "|" + record.chunkX() + "|" + record.chunkZ(),
                    k -> new ArrayList<>()).add(record);
        }
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        for (List<EntityRecord> group : byChunk.values()) {
            try {
                EntityRecord first = group.get(0);
                CompoundTag root = new CompoundTag();
                root.putInt("DataVersion", dataVersion);
                root.put("Position", new IntArrayTag(new int[]{first.chunkX(), first.chunkZ()}));
                ListTag list = new ListTag();
                for (EntityRecord record : group) {
                    list.add(record.tag());
                }
                root.put("Entities", list);

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.write(root, new DataOutputStream(bytes));

                int regionX = Math.floorDiv(first.chunkX(), 32);
                int regionZ = Math.floorDiv(first.chunkZ(), 32);
                String key = first.entitiesFolder() + ":" + regionX + "," + regionZ;
                SimpleRegionFile region = openRegions.computeIfAbsent(key, k -> {
                    try {
                        Path dir = rootDir.resolve(first.entitiesFolder());
                        Files.createDirectories(dir);
                        return new SimpleRegionFile(dir.resolve("r." + regionX + "." + regionZ + ".mca"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                region.writeChunk(Math.floorMod(first.chunkX(), 32), Math.floorMod(first.chunkZ(), 32), bytes.toByteArray());
            } catch (Exception e) {
                System.err.println("[ChunkVault] Failed to write entity chunk: " + e);
            }
        }
    }

    public synchronized void closeAndFinish(BlockPos spawnPos, UUID uuid, CompoundTag playerTag) {
        flushEntities();
        for (SimpleRegionFile region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                System.err.println("[ChunkVault] Error closing region file: " + e);
            }
        }
        openRegions.clear();
        if (playerTag != null && uuid != null) {
            PlayerDataWriter.writeToWorld(rootDir, uuid, playerTag);
        }
        LevelDatWriter.write(rootDir, gameDir, levelName, spawnPos, playerTag);
    }
}
