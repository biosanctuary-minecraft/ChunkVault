package com.chunkvault.gui;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class ProjectMap {

    private static final int MAGIC = 0x43564D31; // CVM1

    private static volatile String name;
    private static final Map<String, Map<Long, MapCache.ChunkColors>> CHUNKS = new ConcurrentHashMap<>();
    private static final Map<String, Set<Long>> DIRTY = new ConcurrentHashMap<>();
    private static final Map<String, Set<Long>> REGION_KEYS = new ConcurrentHashMap<>();
    private static volatile int session;
    private static volatile boolean storeDirty;
    private static int tickCounter;
    private static final Object SAVE_LOCK = new Object();

    private ProjectMap() {}

    public static Path root() {
        return FabricLoader.getInstance().getGameDir().resolve("chunkvault");
    }

    public static boolean isOpen() {
        return name != null;
    }

    public static String name() {
        return name;
    }

    public static int sessionCounter() {
        return session;
    }

    public static Map<Long, MapCache.ChunkColors> forDimension(String dimensionId) {
        return CHUNKS.computeIfAbsent(dimensionId, k -> new ConcurrentHashMap<>());
    }

    public static Set<Long> dirtyChunks(String dimensionId) {
        return DIRTY.computeIfAbsent(dimensionId, k -> ConcurrentHashMap.newKeySet());
    }

    public static Set<Long> regionKeys(String dimensionId) {
        return REGION_KEYS.computeIfAbsent(dimensionId, k -> ConcurrentHashMap.newKeySet());
    }

    public static boolean createNew(String folder) {
        Path dir = root().resolve(folder);
        if (Files.exists(dir)) {
            return false;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to create " + dir + ": " + e);
            return false;
        }
        open(folder);
        return true;
    }

    public static void open(String folder) {
        synchronized (SAVE_LOCK) {
            if (name != null) {
                save();
            }
            CHUNKS.clear();
            DIRTY.clear();
            REGION_KEYS.clear();
            session++;
            name = folder;
            storeDirty = false;
            load(root().resolve(folder).resolve("chunkvault_map"));
        }
    }

    public static void ensureOpen(String folder) {
        if (!folder.equals(name)) {
            open(folder);
        }
    }

    public static void close() {
        synchronized (SAVE_LOCK) {
            if (name != null) {
                save();
            }
            CHUNKS.clear();
            DIRTY.clear();
            REGION_KEYS.clear();
            session++;
            name = null;
        }
    }

    public static void onChunkRecorded(String dimensionId, int cx, int cz) {
        if (name == null) {
            return;
        }
        long k = MapCache.key(cx, cz);
        MapCache.ChunkColors c = MapCache.forDimension(dimensionId).get(k);
        if (c == null) {
            return;
        }
        forDimension(dimensionId).put(k, c);
        dirtyChunks(dimensionId).add(k);
        regionKeys(dimensionId).add(MapCache.key(cx >> 4, cz >> 4));
        storeDirty = true;
    }

    public static void tick() {
        if (name == null || !storeDirty) {
            return;
        }
        if (++tickCounter % 600 != 0) {
            return;
        }
        Thread t = new Thread(ProjectMap::save, "chunkvault-mapsave");
        t.setDaemon(true);
        t.start();
    }

    private static String fileFor(String dimensionId) {
        return dimensionId.replace(':', '_').replace('/', '_') + ".cvm";
    }

    public static void save() {
        synchronized (SAVE_LOCK) {
            String project = name;
            if (project == null) {
                return;
            }
            try {
                Path dir = root().resolve(project).resolve("chunkvault_map");
                Files.createDirectories(dir);
                for (Map.Entry<String, Map<Long, MapCache.ChunkColors>> e : CHUNKS.entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        write(dir.resolve(fileFor(e.getKey())), e.getKey(), e.getValue());
                    }
                }
                storeDirty = false;
            } catch (Exception ex) {
                System.err.println("[ChunkVault] Failed to save map for " + project + ": " + ex);
            }
        }
    }

    private static void write(Path file, String dimensionId, Map<Long, MapCache.ChunkColors> map) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        ArrayList<Map.Entry<Long, MapCache.ChunkColors>> entries = new ArrayList<>(map.entrySet());
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeUTF(dimensionId);
            out.writeInt(entries.size());
            for (Map.Entry<Long, MapCache.ChunkColors> e : entries) {
                out.writeInt(MapCache.keyX(e.getKey()));
                out.writeInt(MapCache.keyZ(e.getKey()));
                for (int v : e.getValue().px) {
                    out.writeInt(v);
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void load(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (f.getFileName().toString().endsWith(".cvm")) {
                    read(f);
                }
            }
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to read map directory " + dir + ": " + e);
        }
    }

    private static void read(Path file) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) {
                return;
            }
            String dimensionId = in.readUTF();
            int count = in.readInt();
            Map<Long, MapCache.ChunkColors> map = forDimension(dimensionId);
            Set<Long> dirty = dirtyChunks(dimensionId);
            Set<Long> regions = regionKeys(dimensionId);
            for (int i = 0; i < count; i++) {
                int cx = in.readInt();
                int cz = in.readInt();
                int[] px = new int[256];
                for (int k = 0; k < 256; k++) {
                    px[k] = in.readInt();
                }
                long key = MapCache.key(cx, cz);
                map.put(key, MapCache.fromPixels(px));
                dirty.add(key);
                regions.add(MapCache.key(cx >> 4, cz >> 4));
            }
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to read map file " + file + ": " + e);
        }
    }
}
