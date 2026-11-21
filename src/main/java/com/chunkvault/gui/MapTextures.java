package com.chunkvault.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

final class MapTextures {

    static final int[] SIZE = {256, 128, 64};
    private static final int[] PX_PER_CHUNK = {16, 8, 4};

    private static final int BUILD_PER_FRAME = 8;
    private static final int UPLOAD_PER_FRAME = 12;
    private static final int DIRTY_CHUNKS_PER_FRAME = 96;
    private static final int EVICT_AFTER_FRAMES = 600;

    static final class Lod {
        DynamicTexture tex;
        NativeImage img;
        Identifier id;
        int size;
        boolean needsUpload;
        boolean ready;
    }

    private static final class Region {
        final Lod[] lods = new Lod[3];
        long lastUsed;
    }

    private static final Map<String, Map<Long, Region>> REGIONS = new HashMap<>();

    private static boolean ok = true;
    private static int counter;
    private static int session = -1;
    private static int buildLeft;
    private static int uploadLeft;

    private MapTextures() {}

    static boolean available() {
        return ok;
    }

    private static void fail(Throwable t) {
        if (ok) {
            System.err.println("[ChunkVault] Map texture error, falling back to fill renderer: " + t);
        }
        ok = false;
    }

    static void beginFrame(long frame, String dimension, Map<Long, MapCache.ChunkColors> cache) {
        buildLeft = BUILD_PER_FRAME;
        uploadLeft = UPLOAD_PER_FRAME;

        int s = ProjectMap.sessionCounter();
        if (s != session) {
            closeAll();
            session = s;
        }

        Map<Long, Region> regions = REGIONS.get(dimension);
        Set<Long> dirty = ProjectMap.dirtyChunks(dimension);
        if (regions != null && !dirty.isEmpty()) {
            int n = 0;
            Iterator<Long> it = dirty.iterator();
            while (it.hasNext() && n < DIRTY_CHUNKS_PER_FRAME) {
                long key = it.next();
                it.remove();
                n++;
                int cx = MapCache.keyX(key);
                int cz = MapCache.keyZ(key);
                Region r = regions.get(MapCache.key(cx >> 4, cz >> 4));
                if (r == null) {
                    continue;
                }
                MapCache.ChunkColors c = cache.get(key);
                try {
                    for (int i = 0; i < 3; i++) {
                        Lod lod = r.lods[i];
                        if (lod != null) {
                            writeChunk(lod, i, cx & 15, cz & 15, c);
                            lod.needsUpload = true;
                        }
                    }
                } catch (Throwable t) {
                    fail(t);
                    return;
                }
            }
        } else if (regions == null && !dirty.isEmpty()) {
            dirty.clear();
        }

        if (frame % 120 == 0) {
            evict(frame);
        }
    }

    static Lod acquire(String dimension, int rx, int rz, int lodIdx,
                       Map<Long, MapCache.ChunkColors> cache, long frame) {
        if (!ok) {
            return null;
        }
        try {
            Map<Long, Region> regions = REGIONS.computeIfAbsent(dimension, k -> new HashMap<>());
            Region r = regions.computeIfAbsent(MapCache.key(rx, rz), k -> new Region());
            r.lastUsed = frame;

            Lod lod = r.lods[lodIdx];
            if (lod == null) {
                int cost = lodIdx == 0 ? 2 : 1;
                if (buildLeft < cost) {
                    return fallbackReady(r, lodIdx);
                }
                buildLeft -= cost;
                lod = createLod(lodIdx);
                r.lods[lodIdx] = lod;
                fullFill(lod, lodIdx, rx, rz, cache);
            }

            if (lod.needsUpload && uploadLeft > 0) {
                uploadLeft--;
                lod.tex.upload();
                lod.needsUpload = false;
                lod.ready = true;
            }
            return lod.ready ? lod : fallbackReady(r, lodIdx);
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    private static Lod fallbackReady(Region r, int preferred) {
        for (int dist = 1; dist < 3; dist++) {
            int lower = preferred - dist;
            if (lower >= 0 && r.lods[lower] != null && r.lods[lower].ready) {
                return r.lods[lower];
            }
            int higher = preferred + dist;
            if (higher < 3 && r.lods[higher] != null && r.lods[higher].ready) {
                return r.lods[higher];
            }
        }
        return null;
    }

    private static Lod createLod(int lodIdx) {
        int size = SIZE[lodIdx];
        int n = ++counter;
        DynamicTexture tex = new DynamicTexture("chunkvault_map_" + n, size, size, true);
        NativeImage img = tex.getPixels();
        if (img == null) {
            tex.close();
            throw new IllegalStateException("DynamicTexture has no pixels");
        }
        Identifier id = Identifier.fromNamespaceAndPath("chunkvault", "map/" + n);
        Minecraft.getInstance().getTextureManager().register(id, tex);

        Lod lod = new Lod();
        lod.tex = tex;
        lod.img = img;
        lod.id = id;
        lod.size = size;
        return lod;
    }

    private static void fullFill(Lod lod, int lodIdx, int rx, int rz,
                                 Map<Long, MapCache.ChunkColors> cache) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                MapCache.ChunkColors c = cache.get(MapCache.key(rx * 16 + lx, rz * 16 + lz));
                if (c != null) {
                    writeChunk(lod, lodIdx, lx, lz, c);
                }
            }
        }
        lod.needsUpload = true;
    }

    private static void writeChunk(Lod lod, int lodIdx, int lx, int lz, MapCache.ChunkColors c) {
        int n = PX_PER_CHUNK[lodIdx];
        int[] src = c == null ? null : (lodIdx == 0 ? c.px : lodIdx == 1 ? c.l8 : c.l4);
        NativeImage img = lod.img;
        int baseX = lx * n;
        int baseY = lz * n;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int v = src == null ? 0 : src[y * n + x];
                img.setPixel(baseX + x, baseY + y, v);
            }
        }
    }

    private static void evict(long frame) {
        for (Map<Long, Region> regions : REGIONS.values()) {
            Iterator<Region> it = regions.values().iterator();
            while (it.hasNext()) {
                Region r = it.next();
                if (frame - r.lastUsed > EVICT_AFTER_FRAMES) {
                    for (Lod lod : r.lods) {
                        closeLod(lod);
                    }
                    it.remove();
                }
            }
        }
    }

    private static void closeAll() {
        for (Map<Long, Region> regions : REGIONS.values()) {
            for (Region r : regions.values()) {
                for (Lod lod : r.lods) {
                    closeLod(lod);
                }
            }
        }
        REGIONS.clear();
    }

    private static void closeLod(Lod lod) {
        if (lod == null) {
            return;
        }
        try {
            Minecraft.getInstance().getTextureManager().release(lod.id);
            lod.tex.close();
        } catch (Throwable ignored) {
        }
    }
}
