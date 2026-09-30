package com.chunkvault.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * GPU textures for the map: the terrain is packed into region textures
 * (16x16 chunks = 256x256 blocks) so the whole map costs a few blit() calls
 * per frame instead of thousands of fill() calls.
 *
 * Two levels of detail per region, created lazily:
 *   LOD0: 256x256 px, 1 px per block   (used when zoomed in)
 *   LOD1:  64x64  px, 1 px per 4 blocks (used when zoomed out — stays sharp and
 *                                        avoids shimmering from skipped pixels)
 *
 * DynamicTexture / NativeImage / TextureManager are reached through reflection
 * on purpose: their method names have been renamed several times between
 * versions, and a missing name must never break the mod. If anything about
 * this setup fails, available() turns false and MapScreen falls back to the
 * older fill()-based renderer.
 */
final class MapTextures {

    /** Flip to true if terrain colours ever show up with red and blue swapped. */
    static final boolean SWAP_RED_BLUE = false;

    static final int[] SIZE = {256, 64};
    private static final int[] PX_PER_CHUNK = {16, 4};

    /** Per frame limits, so opening the map or loading many chunks never causes a hitch. */
    private static final int BUILD_PER_FRAME = 8;   // cost units: a 256x256 texture costs 2, a 64x64 one costs 1
    private static final int UPLOAD_PER_FRAME = 12;
    private static final int DIRTY_CHUNKS_PER_FRAME = 96;
    private static final int EVICT_AFTER_FRAMES = 600;

    static final class Lod {
        Object tex;
        Object img;
        Identifier id;
        boolean needsUpload;
        boolean ready;
        int tint;
    }

    private static final class Region {
        final Lod[] lods = new Lod[2];
        long lastUsed;
    }

    private static final Map<String, Map<Long, Region>> REGIONS = new HashMap<>();

    // ---- reflection state ----
    private static boolean initDone;
    private static boolean ok;
    private static int ctorKind;
    private static Constructor<?> texCtor;
    private static Method getPixels;
    private static Method upload;
    private static Method close;
    private static Method register;
    private static Method release;
    private static Method setFilter;
    private static MethodHandle setPixel;
    private static boolean setterArgb;
    private static int counter;
    private static int session = -1;
    private static int buildLeft;
    private static int uploadLeft;

    private MapTextures() {}

    static boolean available() {
        if (!initDone) {
            initDone = true;
            try {
                init();
                ok = true;
                System.out.println("[ChunkVault] map textures enabled (setter " + (setterArgb ? "ARGB" : "ABGR") + ")");
            } catch (Throwable t) {
                ok = false;
                System.err.println("[ChunkVault] map textures unavailable, using slow fill() renderer: " + t);
            }
        }
        return ok;
    }

    private static void fail(Throwable t) {
        if (ok) {
            System.err.println("[ChunkVault] map textures failed at runtime, switching to fill() renderer: " + t);
        }
        ok = false;
    }

    private static void init() throws Throwable {
        Class<?> dyn = Class.forName("net.minecraft.client.renderer.texture.DynamicTexture");

        try {
            texCtor = dyn.getConstructor(Supplier.class, int.class, int.class, boolean.class);
            ctorKind = 0;
        } catch (NoSuchMethodException e) {
            try {
                texCtor = dyn.getConstructor(String.class, int.class, int.class, boolean.class);
                ctorKind = 1;
            } catch (NoSuchMethodException e2) {
                texCtor = dyn.getConstructor(int.class, int.class, boolean.class);
                ctorKind = 2;
            }
        }

        getPixels = dyn.getMethod("getPixels");
        upload = dyn.getMethod("upload");
        close = dyn.getMethod("close");

        for (Method m : dyn.getMethods()) {
            if (m.getName().equals("setFilter") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == boolean.class && m.getParameterTypes()[1] == boolean.class) {
                setFilter = m;
            }
        }

        // pixel setter on NativeImage: name tells the channel order
        Class<?> img = getPixels.getReturnType();
        String[] names = {"setColorArgb", "setPixelABGR", "setColor", "setPixelRGBA", "setPixel"};
        boolean[] argb = {true, false, false, false, true};
        Method setter = null;
        for (int i = 0; i < names.length && setter == null; i++) {
            try {
                Method m = img.getMethod(names[i], int.class, int.class, int.class);
                setter = m;
                setterArgb = argb[i];
            } catch (NoSuchMethodException ignored) {
                // try the next name
            }
        }
        if (setter == null) {
            throw new NoSuchMethodException("no NativeImage pixel setter found");
        }
        setPixel = MethodHandles.publicLookup().unreflect(setter)
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class, int.class));

        Class<?> tmClass = Minecraft.getInstance().getTextureManager().getClass();
        for (Method m : tmClass.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("register") && p.length == 2
                    && p[0].isAssignableFrom(Identifier.class) && p[1].isAssignableFrom(dyn)) {
                register = m;
            }
            if (m.getName().equals("release") && p.length == 1 && p[0].isAssignableFrom(Identifier.class)) {
                release = m;
            }
        }
        if (register == null) {
            throw new NoSuchMethodException("TextureManager.register(Identifier, texture) not found");
        }
    }

    // ------------------------------------------------------------------
    // per-frame entry points
    // ------------------------------------------------------------------

    /** Resets the per-frame budgets and writes recently changed chunks into the textures that exist. */
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
                boolean show = c != null;
                try {
                    for (int i = 0; i < 2; i++) {
                        Lod lod = r.lods[i];
                        if (lod != null) {
                            writeChunk(lod, i, cx & 15, cz & 15, c, show);
                            lod.needsUpload = true;
                        }
                    }
                } catch (Throwable t) {
                    fail(t);
                    return;
                }
            }
        } else if (regions == null && !dirty.isEmpty()) {
            dirty.clear(); // nothing to update yet; textures are built from the cache when first needed
        }

        if (frame % 120 == 0) {
            evict(frame);
        }
    }

    /**
     * The texture of one region at the given level of detail, or null if it
     * cannot be shown yet (per-frame budget exhausted — it will appear on a
     * following frame) or textures are unavailable.
     */
    static Lod acquire(String dimension, int rx, int rz, int lodIdx,
                       Map<Long, MapCache.ChunkColors> cache, long frame) {
        if (!ok) {
            return null;
        }
        try {
            Map<Long, Region> regions = REGIONS.computeIfAbsent(dimension, k -> new HashMap<>());
            long rkey = MapCache.key(rx, rz);
            Region r = regions.get(rkey);
            if (r == null) {
                r = new Region();
                regions.put(rkey, r);
            }
            r.lastUsed = frame;

            Lod lod = r.lods[lodIdx];
            int tintNow = MapCache.tintVersion();
            int cost = lodIdx == 0 ? 2 : 1;
            if (lod == null) {
                if (buildLeft < cost) {
                    return null;
                }
                buildLeft -= cost;
                lod = createLod(lodIdx);
                r.lods[lodIdx] = lod;
                fullFill(lod, lodIdx, dimension, rx, rz, cache);
            } else if (lod.tint != tintNow) {
                if (buildLeft < cost) {
                    return null; // stale (e.g. from an earlier recording) — don't show it, refill next frame
                }
                buildLeft -= cost;
                fullFill(lod, lodIdx, dimension, rx, rz, cache);
            }

            if (lod.needsUpload && uploadLeft > 0) {
                uploadLeft--;
                upload.invoke(lod.tex);
                lod.needsUpload = false;
                lod.ready = true;
            }
            return lod.ready ? lod : null;
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // building
    // ------------------------------------------------------------------

    private static Lod createLod(int lodIdx) throws Throwable {
        int size = SIZE[lodIdx];
        int n = ++counter;
        String label = "chunkvault_map_" + n;
        Object tex;
        switch (ctorKind) {
            case 0 -> {
                Supplier<String> name = () -> label;
                tex = texCtor.newInstance(name, size, size, true);
            }
            case 1 -> tex = texCtor.newInstance(label, size, size, true);
            default -> tex = texCtor.newInstance(size, size, true);
        }
        Object img = getPixels.invoke(tex);
        if (img == null) {
            throw new IllegalStateException("DynamicTexture has no pixels");
        }
        if (setFilter != null) {
            try {
                setFilter.invoke(tex, false, false); // crisp pixels, no blur
            } catch (Throwable ignored) {
                // cosmetic only
            }
        }
        Identifier id = Identifier.fromNamespaceAndPath("chunkvault", "map/" + n);
        register.invoke(Minecraft.getInstance().getTextureManager(), id, tex);

        Lod lod = new Lod();
        lod.tex = tex;
        lod.img = img;
        lod.id = id;
        return lod;
    }

    private static void fullFill(Lod lod, int lodIdx, String dimension, int rx, int rz,
                                 Map<Long, MapCache.ChunkColors> cache) throws Throwable {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int cx = rx * 16 + lx;
                int cz = rz * 16 + lz;
                MapCache.ChunkColors c = cache.get(MapCache.key(cx, cz));
                boolean show = c != null;
                if (!show && !lod.ready) {
                    continue; // fresh textures are zero-filled = transparent already
                }
                writeChunk(lod, lodIdx, lx, lz, c, show);
            }
        }
        lod.needsUpload = true;
        lod.tint = MapCache.tintVersion();
    }

    /** Writes one chunk's picture (or transparent pixels when it must not be shown) into a region texture. */
    private static void writeChunk(Lod lod, int lodIdx, int lx, int lz, MapCache.ChunkColors c, boolean show)
            throws Throwable {
        int n = PX_PER_CHUNK[lodIdx];
        int[] src = (c == null || !show) ? null : (lodIdx == 0 ? c.px : c.l4);
        Object img = lod.img;
        int baseX = lx * n;
        int baseY = lz * n;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int argb = src == null ? 0 : src[y * n + x];
                int v = argb;
                if (!setterArgb) {
                    v = swapRb(v);
                }
                if (SWAP_RED_BLUE) {
                    v = swapRb(v);
                }
                setPixel.invokeExact(img, baseX + x, baseY + y, v);
            }
        }
    }

    private static int swapRb(int c) {
        return (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
    }

    // ------------------------------------------------------------------
    // cleanup
    // ------------------------------------------------------------------

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
            if (release != null) {
                release.invoke(Minecraft.getInstance().getTextureManager(), lod.id);
            }
            close.invoke(lod.tex);
        } catch (Throwable ignored) {
            // best effort
        }
    }
}
