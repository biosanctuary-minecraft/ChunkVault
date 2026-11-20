package com.chunkvault.save;

import com.chunkvault.state.ChunkVaultSettings;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
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

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorldSaveManager {

    private record EntityRecord(String entitiesFolder, int chunkX, int chunkZ, CompoundTag tag) {}

    private final Path rootDir;
    private final Path gameDir;
    private final String levelName;
    private final Map<String, SimpleRegionFile> openRegions = new HashMap<>();
    private final Set<String> recordedChunks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, EntityRecord> entityRecords = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chunkvault-io");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger chestsSeen = new AtomicInteger();
    private volatile boolean closed;

    public WorldSaveManager(Path rootDir, Path gameDir, String levelName) {
        this.rootDir = rootDir;
        this.gameDir = gameDir;
        this.levelName = levelName;
    }

    public Path rootDir() {
        return rootDir;
    }

    public int chunksSaved() {
        return recordedChunks.size();
    }

    public void notifyChestCaptured() {
        chestsSeen.incrementAndGet();
    }

    public int chestsSeen() {
        return chestsSeen.get();
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
        ContainerDatabase.ensureLoaded(rootDir);
    }

    public void rememberContainer(ClientLevel level, BlockEntity be) {
        ContainerDatabase.put(level, be);
    }

    private CompoundTag containerOverride(ClientLevel level, BlockPos pos, BlockEntity be) {
        return ContainerDatabase.overrideFor(level, pos, be);
    }

    private static String regionFolderFor(Level level) {
        var id = level.dimension().identifier();
        return "dimensions/" + id.getNamespace() + "/" + id.getPath() + "/region";
    }

    private SimpleRegionFile openRegion(String folder, int regionX, int regionZ) throws IOException {
        String key = folder + ":" + regionX + "," + regionZ;
        SimpleRegionFile region = openRegions.get(key);
        if (region == null) {
            Path dir = rootDir.resolve(folder);
            Files.createDirectories(dir);
            region = new SimpleRegionFile(dir.resolve("r." + regionX + "." + regionZ + ".mca"));
            openRegions.put(key, region);
        }
        return region;
    }

    public void saveChunk(ClientLevel level, LevelChunk chunk) {
        if (closed) {
            return;
        }
        try {
            CompoundTag tag = ChunkNbtWriter.write(level, chunk,
                    (pos, be) -> containerOverride(level, pos, be));

            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            String regionFolder = regionFolderFor(level);
            recordedChunks.add(level.dimension().identifier() + ":" + chunkX + "," + chunkZ);

            ioExecutor.execute(() -> writeChunkAsync(regionFolder, chunkX, chunkZ, tag));
        } catch (RejectedExecutionException ignored) {
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to save chunk " + chunk.getPos() + ": " + e);
        }
    }

    private void writeChunkAsync(String regionFolder, int chunkX, int chunkZ, CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            int regionX = Math.floorDiv(chunkX, 32);
            int regionZ = Math.floorDiv(chunkZ, 32);
            SimpleRegionFile region = openRegion(regionFolder, regionX, regionZ);
            region.writeChunk(Math.floorMod(chunkX, 32), Math.floorMod(chunkZ, 32), bytes.toByteArray());
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to write chunk " + chunkX + "," + chunkZ + ": " + e);
        }
    }

    public void snapshotEntities(ClientLevel level) {
        if (closed || !ChunkVaultSettings.get().saveEntities()) {
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
                SimpleRegionFile region = openRegion(first.entitiesFolder(), regionX, regionZ);
                region.writeChunk(Math.floorMod(first.chunkX(), 32), Math.floorMod(first.chunkZ(), 32), bytes.toByteArray());
            } catch (Exception e) {
                System.err.println("[ChunkVault] Failed to write entity chunk: " + e);
            }
        }
    }

    public synchronized void closeAndFinish(BlockPos spawnPos, UUID uuid, CompoundTag playerTag) {
        closed = true;
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        LevelDatWriter.write(rootDir, gameDir, levelName, spawnPos, uuid);
    }
}
