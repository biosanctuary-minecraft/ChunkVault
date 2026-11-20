package com.chunkvault.state;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.chunkvault.gui.MapCache;
import com.chunkvault.gui.ProjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AreaPlan {

    public record Progress(
            int recorded,
            int total,
            int remaining,
            double percent,
            int nearestCx,
            int nearestCz,
            boolean hasMissing
    ) {}

    private static final int MAX_SPAN_CHUNKS = 512;

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

    public static int totalChunks() {
        if (!active) {
            return 0;
        }
        return (maxCx - minCx + 1) * (maxCz - minCz + 1);
    }

    public static void resetAutoStopTrigger() {
        autoStopTriggered = false;
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
            autoStopTriggered = false;
            Path file = projectDir.resolve("chunkvault_area.properties");
            if (Files.isRegularFile(file)) {
                Properties p = new Properties();
                try (var in = Files.newInputStream(file)) {
                    p.load(in);
                    active = Boolean.parseBoolean(p.getProperty("active", "false"));
                    dimension = p.getProperty("dimension", "minecraft:overworld");
                    int a = Integer.parseInt(p.getProperty("minCx", "0"));
                    int b = Integer.parseInt(p.getProperty("maxCx", "0"));
                    int c = Integer.parseInt(p.getProperty("minCz", "0"));
                    int d = Integer.parseInt(p.getProperty("maxCz", "0"));
                    minCx = Math.min(a, b);
                    maxCx = Math.max(a, b);
                    minCz = Math.min(c, d);
                    maxCz = Math.max(c, d);
                    hudVisible = Boolean.parseBoolean(p.getProperty("hudVisible", "true"));
                    autoStop = Boolean.parseBoolean(p.getProperty("autoStop", "false"));
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
            int x0 = Math.min(cx1, cx2);
            int x1 = Math.max(cx1, cx2);
            int z0 = Math.min(cz1, cz2);
            int z1 = Math.max(cz1, cz2);
            if (x1 - x0 + 1 > MAX_SPAN_CHUNKS) {
                x1 = x0 + MAX_SPAN_CHUNKS - 1;
            }
            if (z1 - z0 + 1 > MAX_SPAN_CHUNKS) {
                z1 = z0 + MAX_SPAN_CHUNKS - 1;
            }
            dimension = dim;
            minCx = x0;
            maxCx = x1;
            minCz = z0;
            maxCz = z1;
            active = true;
            autoStopTriggered = false;
            invalidateLocked();
            saveAsyncLocked();
        }
    }

    public static void setFromBlocks(String dim, int x1, int z1, int x2, int z2) {
        setFromChunks(dim, x1 >> 4, z1 >> 4, x2 >> 4, z2 >> 4);
    }

    public static void setRadiusBlocks(String dim, int centerX, int centerZ, int radiusBlocks) {
        int r = Math.max(16, radiusBlocks);
        setFromBlocks(dim, centerX - r, centerZ - r, centerX + r, centerZ + r);
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
        int total = (x1 - x0 + 1) * (z1 - z0 + 1);
        int recorded = 0;
        boolean hasMissing = false;
        int nearestCx = 0;
        int nearestCz = 0;
        long bestDistSq = Long.MAX_VALUE;

        for (int cz = z0; cz <= z1; cz++) {
            for (int cx = x0; cx <= x1; cx++) {
                if (recordedMap.containsKey(MapCache.key(cx, cz))) {
                    recorded++;
                } else {
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

        int remaining = Math.max(0, total - recorded);
        double percent = total > 0 ? (recorded * 100.0 / total) : 0.0;
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
        if (p.total() > 0 && p.remaining() == 0) {
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
