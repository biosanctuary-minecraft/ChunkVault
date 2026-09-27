package com.chunkvault.save;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldSaveManager {

    private final Path rootDir;
    private final Path gameDir;
    private final String levelName;
    private final Map<String, SimpleRegionFile> openRegions = new ConcurrentHashMap<>();

    // Which chunks we've recorded, keyed "dimensionId:chunkX,chunkZ" — used
    // by the in-game map screen to show what's already saved around the
    // player. Not persisted; only matters for the current session's UI.
    private final Set<String> recordedChunks = ConcurrentHashMap.newKeySet();

    // Track how many chunks/chests we've captured, for /chunkvault status.
    private volatile int chunksSaved = 0;
    private volatile int chestsSeen = 0;

    // Remembers whether we've actually seen the player in the Overworld
    // during this session, so closeAndFinish() knows whether the captured
    // spawnPos is meaningful (see class doc on dimension-specific spawn).
    private volatile boolean sawOverworld = false;

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

    public void init() {
        try {
            Files.createDirectories(rootDir.resolve("dimensions/minecraft/overworld/region"));
            Files.createDirectories(rootDir.resolve("data"));
        } catch (IOException e) {
            System.err.println("[ChunkVault] could not create save directory " + rootDir + ": " + e);
        }
    }

    /**
     * Region folder for a dimension. Fixed: 26.1+ dropped the legacy
     * region/, DIM-1/, DIM1/ folders entirely — EVERY dimension, including
     * the Overworld, now lives under the unified
     * dimensions/<namespace>/<path>/region/ layout (e.g.
     * dimensions/minecraft/the_nether/region/). Writing into the old
     * DIM-1/region/ path (what this method used to do) meant the game
     * never even looked there and silently generated fresh terrain instead
     * — no error, just empty-looking "clean" chunks, which is exactly the
     * bug this fixes.
     */
    private static String regionFolderFor(Level level) {
        var id = level.dimension().identifier();
        return "dimensions/" + id.getNamespace() + "/" + id.getPath() + "/region";
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
            if (level.dimension().equals(Level.OVERWORLD)) {
                sawOverworld = true;
            }

            CompoundTag tag = ChunkNbtWriter.write(level, chunk);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));

            // Fixed: ChunkPos.x/z are private now; use the x()/z() accessors.
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
                    Path file = dir.resolve("r." + regionX + "." + regionZ + ".mca");
                    return new SimpleRegionFile(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            int localX = Math.floorMod(chunkX, 32);
            int localZ = Math.floorMod(chunkZ, 32);
            region.writeChunk(localX, localZ, bytes.toByteArray());
            recordedChunks.add(level.dimension().identifier() + ":" + chunkX + "," + chunkZ);
            chunksSaved++;
        } catch (Exception e) {
            System.err.println("[ChunkVault] failed to save chunk " + chunk.getPos() + ": " + e);
        }
    }

    /** Used by the map screen to show which nearby chunks are already recorded. */
    public boolean isRecorded(String dimensionId, int chunkX, int chunkZ) {
        return recordedChunks.contains(dimensionId + ":" + chunkX + "," + chunkZ);
    }

    /**
     * @param spawnPos the player's position when /chunkvault stop was run,
     *                 or null if it isn't known to be an Overworld position
     *                 (level.dat's SpawnX/Y/Z is always interpreted as an
     *                 Overworld coordinate — writing Nether/End coordinates
     *                 there just drops the player in unrelated Overworld
     *                 terrain, which is exactly the bug this parameter
     *                 avoids). Pass null and the template's own spawn point
     *                 is kept instead.
     * @param uuid the local player's UUID, used to name the playerdata file
     *             so the game recognizes it as "the" singleplayer player.
     * @param playerTag full player state (position/rotation/dimension) from
     *                  PlayerDataWriter.buildPlayerTag(...), or null to skip
     *                  writing player position entirely.
     */
    public synchronized void closeAndFinish(BlockPos spawnPos, java.util.UUID uuid, CompoundTag playerTag) {
        for (SimpleRegionFile region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                System.err.println("[ChunkVault] error closing region file: " + e);
            }
        }
        openRegions.clear();
        if (playerTag != null && uuid != null) {
            PlayerDataWriter.writeToWorld(rootDir, uuid, playerTag);
        }
        LevelDatWriter.write(rootDir, gameDir, levelName, spawnPos, playerTag);
    }

    // Overload used by RecordingState when no player position is available.
    public void closeAndFinish() {
        closeAndFinish(null, null, null);
    }
}
