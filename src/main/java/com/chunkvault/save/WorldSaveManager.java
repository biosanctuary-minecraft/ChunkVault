package com.chunkvault.save;

import com.chunkvault.gui.MapCache;
import com.chunkvault.gui.ProjectMap;
import com.chunkvault.state.AreaPlan;
import com.chunkvault.state.ChunkVaultSettings;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
        String dim = level.dimension().identifier().toString();
        BlockPos pos = be.getBlockPos();
        if (!AreaPlan.allowsBlock(dim, pos.getX(), pos.getZ())) {
            return;
        }
        ContainerDatabase.put(level, be);
    }

    public void rememberEnderChest(ClientLevel level, BlockEntity be, Container source) {
        String dim = level.dimension().identifier().toString();
        BlockPos pos = be.getBlockPos();
        if (!AreaPlan.allowsBlock(dim, pos.getX(), pos.getZ())) {
            return;
        }
        ContainerDatabase.putEnderChest(level, be, source);
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

    public boolean saveChunk(ClientLevel level, LevelChunk chunk) {
        if (closed) {
            return false;
        }
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        String dim = level.dimension().identifier().toString();
        if (!AreaPlan.allowsChunk(dim, chunkX, chunkZ)) {
            return false;
        }
        try {
            ContainerDatabase.syncChunkContainers(level, chunk);
            CompoundTag tag = ChunkNbtWriter.write(level, chunk,
                    (pos, be) -> containerOverride(level, pos, be));

            String regionFolder = regionFolderFor(level);
            recordedChunks.add(dim + ":" + chunkX + "," + chunkZ);

            ioExecutor.execute(() -> writeChunkAsync(regionFolder, chunkX, chunkZ, tag));
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to save chunk " + chunk.getPos() + ": " + e);
            return false;
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
        Map<Long, MapCache.ChunkColors> projectChunks = ProjectMap.forDimension(dimensionId);
        for (Entity entity : level.entitiesForRendering()) {
            try {
                if (entity instanceof Player || entity.isPassenger() || entity.isRemoved()
                        || !entity.getType().canSerialize()) {
                    continue;
                }
                int chunkX = (int) Math.floor(entity.getX() / 16.0);
                int chunkZ = (int) Math.floor(entity.getZ() / 16.0);
                if (!AreaPlan.allowsChunk(dimensionId, chunkX, chunkZ)) {
                    continue;
                }
                boolean chunkRecorded = recordedChunks.contains(dimensionId + ":" + chunkX + "," + chunkZ)
                        || projectChunks.containsKey(MapCache.key(chunkX, chunkZ));
                if (!chunkRecorded) {
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

    private static String entityUuidKey(CompoundTag tag) {
        if (tag.get("UUID") instanceof IntArrayTag arr) {
            int[] vals = arr.getAsIntArray();
            if (vals.length == 4) {
                return vals[0] + ":" + vals[1] + ":" + vals[2] + ":" + vals[3];
            }
        }
        return null;
    }

    private void flushEntities() {
        Map<String, List<EntityRecord>> byChunk = new HashMap<>();
        Set<String> updatedGlobalUuids = new HashSet<>();
        for (EntityRecord record : entityRecords.values()) {
            String key = entityUuidKey(record.tag());
            if (key != null) {
                updatedGlobalUuids.add(key);
            }
            byChunk.computeIfAbsent(record.entitiesFolder() + "|" + record.chunkX() + "|" + record.chunkZ(),
                    k -> new ArrayList<>()).add(record);
        }
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        for (List<EntityRecord> group : byChunk.values()) {
            try {
                EntityRecord first = group.get(0);
                int regionX = Math.floorDiv(first.chunkX(), 32);
                int regionZ = Math.floorDiv(first.chunkZ(), 32);
                int localX = Math.floorMod(first.chunkX(), 32);
                int localZ = Math.floorMod(first.chunkZ(), 32);
                SimpleRegionFile region = openRegion(first.entitiesFolder(), regionX, regionZ);

                Set<String> chunkUuids = new HashSet<>();
                ListTag list = new ListTag();
                for (EntityRecord record : group) {
                    String uuidKey = entityUuidKey(record.tag());
                    if (uuidKey != null) {
                        chunkUuids.add(uuidKey);
                    }
                    list.add(record.tag());
                }

                byte[] existingBytes = region.readChunk(localX, localZ);
                if (existingBytes != null) {
                    try {
                        CompoundTag existingRoot = NbtIo.read(new DataInputStream(new ByteArrayInputStream(existingBytes)));
                        ListTag oldList = existingRoot.getList("Entities").orElse(null);
                        if (oldList != null) {
                            for (Tag entry : oldList) {
                                if (entry instanceof CompoundTag oldTag) {
                                    String oldUuid = entityUuidKey(oldTag);
                                    if (oldUuid != null && (chunkUuids.contains(oldUuid) || updatedGlobalUuids.contains(oldUuid))) {
                                        continue;
                                    }
                                    if (oldUuid != null) {
                                        chunkUuids.add(oldUuid);
                                    }
                                    list.add(oldTag);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                CompoundTag root = new CompoundTag();
                root.putInt("DataVersion", dataVersion);
                root.put("Position", new IntArrayTag(new int[]{first.chunkX(), first.chunkZ()}));
                root.put("Entities", list);

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.write(root, new DataOutputStream(bytes));
                region.writeChunk(localX, localZ, bytes.toByteArray());
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
        finishOnIoThread(spawnPos, uuid, playerTag, null);
    }

    public synchronized void closeAndFinishAsync(BlockPos spawnPos, UUID uuid, CompoundTag playerTag, Runnable onDone) {
        closed = true;
        try {
            ioExecutor.execute(() -> finishOnIoThread(spawnPos, uuid, playerTag, onDone));
        } catch (RejectedExecutionException e) {
            finishOnIoThread(spawnPos, uuid, playerTag, onDone);
        }
        ioExecutor.shutdown();
    }

    public void awaitTermination() {
        try {
            ioExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void finishOnIoThread(BlockPos spawnPos, UUID uuid, CompoundTag playerTag, Runnable onDone) {
        try {
            flushEntities();
            for (SimpleRegionFile region : openRegions.values()) {
                try {
                    region.close();
                } catch (IOException e) {
                    System.err.println("[ChunkVault] Error closing region file: " + e);
                }
            }
            openRegions.clear();
            ContainerDatabase.flushNow();
            if (playerTag != null && uuid != null) {
                PlayerDataWriter.writeToWorld(rootDir, uuid, playerTag);
            }
            LevelDatWriter.write(rootDir, gameDir, levelName, spawnPos, uuid);
        } finally {
            if (onDone != null) {
                onDone.run();
            }
        }
    }
}
