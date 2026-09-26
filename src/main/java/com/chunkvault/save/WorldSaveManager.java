package com.chunkvault.save;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldSaveManager {

    private final Path rootDir;
    private final String levelName;
    private final Map<Long, SimpleRegionFile> openRegions = new ConcurrentHashMap<>();

    // Track how many chunks/chests we've captured, for /chunkvault status.
    private volatile int chunksSaved = 0;
    private volatile int chestsSeen = 0;

    public WorldSaveManager(Path rootDir, String levelName) {
        this.rootDir = rootDir;
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

    public void init() {
        try {
            Files.createDirectories(rootDir.resolve("region"));
            Files.createDirectories(rootDir.resolve("data"));
        } catch (IOException e) {
            System.err.println("[ChunkVault] could not create save directory " + rootDir + ": " + e);
        }
    }

    /**
     * Serializes and appends one chunk to the correct region file.
     * Safe to call repeatedly for the same chunk (e.g. re-entering an area) —
     * SimpleRegionFile just appends a new copy and updates the offset table,
     * so the newest write always wins on next load. This wastes disk space
     * over a long session; acceptable for a "backup as you explore" tool.
     */
    public synchronized void saveChunk(ClientLevel level, LevelChunk chunk) {
        try {
            CompoundTag tag = ChunkNbtWriter.write(level, chunk);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));

            // Fixed: ChunkPos.x/z are private now; use the x()/z() accessors.
            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            int regionX = Math.floorDiv(chunkX, 32);
            int regionZ = Math.floorDiv(chunkZ, 32);
            long regionKey = (((long) regionX) << 32) ^ (regionZ & 0xFFFFFFFFL);

            SimpleRegionFile region = openRegions.computeIfAbsent(regionKey, k -> {
                try {
                    Path file = rootDir.resolve("region").resolve("r." + regionX + "." + regionZ + ".mca");
                    return new SimpleRegionFile(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);
            region.writeChunk(localX, localZ, bytes.toByteArray());
            chunksSaved++;
        } catch (Exception e) {
            System.err.println("[ChunkVault] failed to save chunk " + chunk.getPos() + ": " + e);
        }
    }

    public synchronized void closeAndFinish(BlockPos spawnPos) {
        for (SimpleRegionFile region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                System.err.println("[ChunkVault] error closing region file: " + e);
            }
        }
        openRegions.clear();
        LevelDatWriter.write(rootDir, levelName, spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
    }

    // Overload used by RecordingState when no player position is available.
    public void closeAndFinish() {
        closeAndFinish(new BlockPos(0, 64, 0));
    }
}
