package com.chunkvault.state;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.chunkvault.gui.MapCache;
import com.chunkvault.gui.ProjectMap;
import com.chunkvault.save.ContainerDatabase;
import com.chunkvault.save.SimpleRegionFile;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class AreaPlan {

    public record Progress(
            long recorded,
            long total,
            long remaining,
            double percent,
            int nearestCx,
            int nearestCz,
            boolean hasMissing
    ) {}

    public record TrimResult(int removedChunks, int removedContainers) {}

    public static final int WORLD_BORDER = 30_000_000;
    private static final int MIN_CHUNK = -WORLD_BORDER >> 4;
    private static final int MAX_CHUNK = WORLD_BORDER >> 4;

    private static final Object LOCK = new Object();
    private static final AtomicInteger PLAN_REV = new AtomicInteger();

    private static volatile Path activeProjectDir;
    private static volatile boolean active;
    private static volatile String dimension = "minecraft:overworld";
    private static volatile int minCx;
    private static volatile int maxCx;
    private static volatile int minCz;
    private static volatile int maxCz;
    private static volatile boolean hudVisible = true;
    private static volatile boolean autoStop;
    private static volatile boolean clipToArea;
    private static volatile boolean dimOutside = true;
    private static volatile boolean autoStopTriggered;

    private static int cachedPlanRev = -1;
    private static int cachedChunkRev = -1;
    private static int cachedPlayerCx = Integer.MIN_VALUE;
    private static int cachedPlayerCz = Integer.MIN_VALUE;
    private static Progress cachedProgress;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chunkvault-area-io");
        t.setDaemon(true);
        return t;
    });

    private AreaPlan() {}

    public static boolean isActive() {
        return active;
    }

    public static String dimension() {
        return dimension;
    }

    public static int minCx() {
        return minCx;
    }

    public static int maxCx() {
        return maxCx;
    }

    public static int minCz() {
        return minCz;
    }

    public static int maxCz() {
        return maxCz;
    }

    public static boolean isHudVisible() {
        return hudVisible;
    }

    public static boolean isAutoStop() {
        return autoStop;
    }

    public static boolean isClipToArea() {
        return clipToArea;
    }

    public static boolean isDimOutside() {
        return dimOutside;
    }

    public static boolean allowsChunk(String dim, int cx, int cz) {
        if (!active || !clipToArea) {
            return true;
        }
        return dimension.equals(dim) && cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz;
    }

    public static boolean allowsBlock(String dim, int x, int z) {
        return allowsChunk(dim, x >> 4, z >> 4);
    }

    public static long totalChunks() {
        if (!active) {
            return 0L;
        }
        return (long) (maxCx - minCx + 1) * (long) (maxCz - minCz + 1);
    }

    public static void resetAutoStopTrigger() {
        autoStopTriggered = false;
    }

    private static int clampChunk(int c) {
        return Math.max(MIN_CHUNK, Math.min(MAX_CHUNK, c));
    }

    private static int clampBlock(long b) {
        return (int) Math.max(-WORLD_BORDER, Math.min(WORLD_BORDER, b));
    }

    public static void loadForProject(Path projectDir) {
        synchronized (LOCK) {
            activeProjectDir = projectDir;
            active = false;
            dimension = "minecraft:overworld";
            minCx = 0;
            maxCx = 0;
            minCz = 0;
            maxCz = 0;
            hudVisible = true;
            autoStop = false;
            clipToArea = false;
            dimOutside = true;
            autoStopTriggered = false;
            Path file = projectDir.resolve("chunkvault_area.properties");
            if (Files.isRegularFile(file)) {
                Properties p = new Properties();
                try (var in = Files.newInputStream(file)) {
                    p.load(in);
                    active = Boolean.parseBoolean(p.getProperty("active", "false"));
                    dimension = p.getProperty("dimension", "minecraft:overworld");
                    int a = clampChunk(Integer.parseInt(p.getProperty("minCx", "0")));
                    int b = clampChunk(Integer.parseInt(p.getProperty("maxCx", "0")));
                    int c = clampChunk(Integer.parseInt(p.getProperty("minCz", "0")));
                    int d = clampChunk(Integer.parseInt(p.getProperty("maxCz", "0")));
                    minCx = Math.min(a, b);
                    maxCx = Math.max(a, b);
                    minCz = Math.min(c, d);
                    maxCz = Math.max(c, d);
                    hudVisible = Boolean.parseBoolean(p.getProperty("hudVisible", "true"));
                    autoStop = Boolean.parseBoolean(p.getProperty("autoStop", "false"));
                    clipToArea = Boolean.parseBoolean(p.getProperty("clipToArea", "false"));
                    dimOutside = Boolean.parseBoolean(p.getProperty("dimOutside", "true"));
                } catch (Exception e) {
                    System.err.println("[ChunkVault] Failed to load chunkvault_area.properties: " + e);
                    active = false;
                }
            }
            invalidateLocked();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            activeProjectDir = null;
            active = false;
            autoStopTriggered = false;
            invalidateLocked();
        }
    }

    public static void setFromChunks(String dim, int cx1, int cz1, int cx2, int cz2) {
        synchronized (LOCK) {
            int a = clampChunk(cx1);
            int b = clampChunk(cx2);
            int c = clampChunk(cz1);
            int d = clampChunk(cz2);
            dimension = dim;
            minCx = Math.min(a, b);
            maxCx = Math.max(a, b);
            minCz = Math.min(c, d);
            maxCz = Math.max(c, d);
            active = true;
            autoStopTriggered = false;
            invalidateLocked();
            saveAsyncLocked();
        }
    }

    public static void setFromBlocks(String dim, int x1, int z1, int x2, int z2) {
        setFromChunks(dim, clampBlock(x1) >> 4, clampBlock(z1) >> 4, clampBlock(x2) >> 4, clampBlock(z2) >> 4);
    }

    public static void setRadiusBlocks(String dim, int centerX, int centerZ, int radiusBlocks) {
        long r = Math.max(16L, Math.min(WORLD_BORDER, radiusBlocks));
        int x1 = clampBlock((long) centerX - r);
        int z1 = clampBlock((long) centerZ - r);
        int x2 = clampBlock((long) centerX + r);
        int z2 = clampBlock((long) centerZ + r);
        setFromChunks(dim, x1 >> 4, z1 >> 4, x2 >> 4, z2 >> 4);
    }

    public static void clearPlan() {
        synchronized (LOCK) {
            active = false;
            autoStopTriggered = false;
            invalidateLocked();
            saveAsyncLocked();
        }
    }

    public static boolean toggleHud() {
        synchronized (LOCK) {
            hudVisible = !hudVisible;
            saveAsyncLocked();
            return hudVisible;
        }
    }

    public static boolean toggleAutoStop() {
        synchronized (LOCK) {
            autoStop = !autoStop;
            autoStopTriggered = false;
            saveAsyncLocked();
            return autoStop;
        }
    }

    public static boolean toggleClipToArea() {
        synchronized (LOCK) {
            clipToArea = !clipToArea;
            saveAsyncLocked();
            return clipToArea;
        }
    }

    public static boolean toggleDimOutside() {
        synchronized (LOCK) {
            dimOutside = !dimOutside;
            saveAsyncLocked();
            return dimOutside;
        }
    }

    public static TrimResult trimProjectToArea() {
        Path dir;
        String dim;
        int x0;
        int x1;
        int z0;
        int z1;
        synchronized (LOCK) {
            if (!active || activeProjectDir == null || RecordingState.get().isActive()) {
                return new TrimResult(0, 0);
            }
            dir = activeProjectDir;
            dim = dimension;
            x0 = minCx;
            x1 = maxCx;
            z0 = minCz;
            z1 = maxCz;
        }

        int colon = dim.indexOf(':');
        String ns = colon >= 0 ? dim.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? dim.substring(colon + 1) : dim;
        Path dimDir = dir.resolve("dimensions").resolve(ns).resolve(path);

        trimRegionFolder(dimDir.resolve("region"), x0, x1, z0, z1);
        trimRegionFolder(dimDir.resolve("entities"), x0, x1, z0, z1);

        int removedChunks = ProjectMap.trimToArea(dim, x0, x1, z0, z1);
        int removedContainers = ContainerDatabase.trimToArea(dim, x0, x1, z0, z1);

        synchronized (LOCK) {
            invalidateLocked();
        }
        return new TrimResult(removedChunks, removedContainers);
    }

    private static void trimRegionFolder(Path folder, int x0, int x1, int z0, int z1) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                files.add(p);
            }
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to list region folder " + folder + ": " + e);
            return;
        }

        for (Path file : files) {
            String name = file.getFileName().toString();
            if (!name.startsWith("r.") || !name.endsWith(".mca")) {
                continue;
            }
            String[] parts = name.split("\\.");
            if (parts.length != 4) {
                continue;
            }
            int rx;
            int rz;
            try {
                rx = Integer.parseInt(parts[1]);
                rz = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            int rMinX = rx * 32;
            int rMaxX = rMinX + 31;
            int rMinZ = rz * 32;
            int rMaxZ = rMinZ + 31;

            if (rMaxX < x0 || rMinX > x1 || rMaxZ < z0 || rMinZ > z1) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception e) {
                    System.err.println("[ChunkVault] Failed to delete region " + file + ": " + e);
                }
                continue;
            }
            if (rMinX >= x0 && rMaxX <= x1 && rMinZ >= z0 && rMaxZ <= z1) {
                continue;
            }

            boolean keep = true;
            try (SimpleRegionFile rf = new SimpleRegionFile(file)) {
                for (int lz = 0; lz < 32; lz++) {
                    int cz = rMinZ + lz;
                    for (int lx = 0; lx < 32; lx++) {
                        int cx = rMinX + lx;
                        if (cx < x0 || cx > x1 || cz < z0 || cz > z1) {
                            rf.deleteChunk(lx, lz);
                        }
                    }
                }
                keep = rf.hasAnyChunks();
            } catch (Exception e) {
                System.err.println("[ChunkVault] Failed to trim region " + file + ": " + e);
            }
            if (!keep) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static Progress computeProgress(int playerCx, int playerCz) {
        if (!active) {
            return new Progress(0, 0, 0, 0.0, 0, 0, false);
        }
        int pRev = PLAN_REV.get();
        int cRev = ProjectMap.chunkRevision();
        synchronized (LOCK) {
            if (cachedProgress != null
                    && cachedPlanRev == pRev
                    && cachedChunkRev == cRev
                    && cachedPlayerCx == playerCx
                    && cachedPlayerCz == playerCz) {
                return cachedProgress;
            }
        }

        Map<Long, MapCache.ChunkColors> recordedMap = ProjectMap.forDimension(dimension);
        int x0 = minCx;
        int x1 = maxCx;
        int z0 = minCz;
        int z1 = maxCz;
        long total = (long) (x1 - x0 + 1) * (long) (z1 - z0 + 1);
        long recorded = 0L;

        for (long key : recordedMap.keySet()) {
            int cx = MapCache.keyX(key);
            int cz = MapCache.keyZ(key);
            if (cx >= x0 && cx <= x1 && cz >= z0 && cz <= z1) {
                recorded++;
            }
        }

        long remaining = Math.max(0L, total - recorded);
        boolean hasMissing = false;
        int nearestCx = 0;
        int nearestCz = 0;

        if (remaining > 0L) {
            int startCx = Math.max(x0, Math.min(x1, playerCx));
            int startCz = Math.max(z0, Math.min(z1, playerCz));
            if (!recordedMap.containsKey(MapCache.key(startCx, startCz))) {
                hasMissing = true;
                nearestCx = startCx;
                nearestCz = startCz;
            } else {
                long bestDistSq = Long.MAX_VALUE;
                int maxRing = Math.max(
                        Math.max(startCx - x0, x1 - startCx),
                        Math.max(startCz - z0, z1 - startCz)
                );
                for (int r = 1; r <= maxRing; r++) {
                    if (hasMissing && (long) r * (long) r >= bestDistSq) {
                        break;
                    }
                    int rx0 = Math.max(x0, startCx - r);
                    int rx1 = Math.min(x1, startCx + r);
                    int rz0 = Math.max(z0, startCz - r);
                    int rz1 = Math.min(z1, startCz + r);

                    if (startCz - r >= z0) {
                        int cz = startCz - r;
                        for (int cx = rx0; cx <= rx1; cx++) {
                            if (!recordedMap.containsKey(MapCache.key(cx, cz))) {
                                long dx = cx - playerCx;
                                long dz = cz - playerCz;
                                long d2 = dx * dx + dz * dz;
                                if (d2 < bestDistSq) {
                                    bestDistSq = d2;
                                    nearestCx = cx;
                                    nearestCz = cz;
                                    hasMissing = true;
                                }
                            }
                        }
                    }
                    if (startCz + r <= z1) {
                        int cz = startCz + r;
                        for (int cx = rx0; cx <= rx1; cx++) {
                            if (!recordedMap.containsKey(MapCache.key(cx, cz))) {
                                long dx = cx - playerCx;
                                long dz = cz - playerCz;
                                long d2 = dx * dx + dz * dz;
                                if (d2 < bestDistSq) {
                                    bestDistSq = d2;
                                    nearestCx = cx;
                                    nearestCz = cz;
                                    hasMissing = true;
                                }
                            }
                        }
                    }
                    if (startCx - r >= x0) {
                        int cx = startCx - r;
                        for (int cz = rz0 + 1; cz <= rz1 - 1; cz++) {
                            if (!recordedMap.containsKey(MapCache.key(cx, cz))) {
                                long dx = cx - playerCx;
                                long dz = cz - playerCz;
                                long d2 = dx * dx + dz * dz;
                                if (d2 < bestDistSq) {
                                    bestDistSq = d2;
                                    nearestCx = cx;
                                    nearestCz = cz;
                                    hasMissing = true;
                                }
                            }
                        }
                    }
                    if (startCx + r <= x1) {
                        int cx = startCx + r;
                        for (int cz = rz0 + 1; cz <= rz1 - 1; cz++) {
                            if (!recordedMap.containsKey(MapCache.key(cx, cz))) {
                                long dx = cx - playerCx;
                                long dz = cz - playerCz;
                                long d2 = dx * dx + dz * dz;
                                if (d2 < bestDistSq) {
                                    bestDistSq = d2;
                                    nearestCx = cx;
                                    nearestCz = cz;
                                    hasMissing = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        double percent = total > 0L ? (recorded * 100.0 / total) : 0.0;
        Progress result = new Progress(recorded, total, remaining, percent, nearestCx, nearestCz, hasMissing);

        synchronized (LOCK) {
            cachedPlanRev = pRev;
            cachedChunkRev = cRev;
            cachedPlayerCx = playerCx;
            cachedPlayerCz = playerCz;
            cachedProgress = result;
        }
        return result;
    }

    public static void checkAutoStop() {
        if (!active || !autoStop || autoStopTriggered || !RecordingState.get().isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        Progress p = computeProgress(pcx, pcz);
        if (p.total() > 0L && p.remaining() == 0L) {
            autoStopTriggered = true;
            var res = ChunkVaultClient.stopRecording();
            if (res != null && mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§a[ChunkVault] "
                        + Lang.s("area.autostop_done", p.total(), res.chunks(), res.chests(), res.entities())));
            }
        }
    }

    private static void invalidateLocked() {
        PLAN_REV.incrementAndGet();
        cachedProgress = null;
    }

    private static void saveAsyncLocked() {
        Path dir = activeProjectDir;
        if (dir == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("active", Boolean.toString(active));
        p.setProperty("dimension", dimension);
        p.setProperty("minCx", Integer.toString(minCx));
        p.setProperty("maxCx", Integer.toString(maxCx));
        p.setProperty("minCz", Integer.toString(minCz));
        p.setProperty("maxCz", Integer.toString(maxCz));
        p.setProperty("hudVisible", Boolean.toString(hudVisible));
        p.setProperty("autoStop", Boolean.toString(autoStop));
        p.setProperty("clipToArea", Boolean.toString(clipToArea));
        p.setProperty("dimOutside", Boolean.toString(dimOutside));
        IO.execute(() -> writeProperties(dir.resolve("chunkvault_area.properties"), p));
    }

    private static void writeProperties(Path file, Properties props) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "ChunkVault Area Plan");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to save chunkvault_area.properties: " + e);
        }
    }
}
