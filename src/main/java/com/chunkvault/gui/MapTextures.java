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

final class MapTextures {

    static final int[] SIZE = {256, 64};
    private static final int[] PX_PER_CHUNK = {16, 4};

    private static final int BUILD_PER_FRAME = 8;
    private static final int UPLOAD_PER_FRAME = 12;
    private static final int DIRTY_CHUNKS_PER_FRAME = 96;
    private static final int EVICT_AFTER_FRAMES = 600;

    static final class Lod {
        Object tex;
        Object img;
        Identifier id;
        boolean needsUpload;
        boolean ready;
    }

    private static final class Region {
        final Lod[] lods = new Lod[2];
        long lastUsed;
    }

    private static final Map<String, Map<Long, Region>> REGIONS = new HashMap<>();

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
            } catch (Throwable t) {
                ok = false;
                System.err.println("[ChunkVault] Map textures unavailable, falling back to fill renderer: " + t);
            }
        }
        return ok;
    }

    private static void fail(Throwable t) {
        if (ok) {
            System.err.println("[ChunkVault] Map texture error, falling back to fill renderer: " + t);
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

        Class<?> img = getPixels.getReturnType();
        String[] names = {"setColorArgb", "setPixelABGR", "setColor", "setPixelRGBA", "setPixel"};
        boolean[] argb = {true, false, false, false, true};
        Method setter = null;
        for (int i = 0; i < names.length && setter == null; i++) {
            try {
                setter = img.getMethod(names[i], int.class, int.class, int.class);
                setterArgb = argb[i];
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (setter == null) {
            throw new NoSuchMethodException("No NativeImage pixel setter found");
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
                    for (int i = 0; i < 2; i++) {
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
                    return null;
                }
                buildLeft -= cost;
                lod = createLod(lodIdx);
                r.lods[lodIdx] = lod;
                fullFill(lod, lodIdx, rx, rz, cache);
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

    private static Lod createLod(int lodIdx) throws Throwable {
        int size = SIZE[lodIdx];
        int n = ++counter;
        String label = "chunkvault_map_" + n;
        Object tex = switch (ctorKind) {
            case 0 -> texCtor.newInstance((Supplier<String>) () -> label, size, size, true);
            case 1 -> texCtor.newInstance(label, size, size, true);
            default -> texCtor.newInstance(size, size, true);
        };
        Object img = getPixels.invoke(tex);
        if (img == null) {
            throw new IllegalStateException("DynamicTexture has no pixels");
        }
        if (setFilter != null) {
            try {
                setFilter.invoke(tex, false, false);
            } catch (Throwable ignored) {
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

    private static void fullFill(Lod lod, int lodIdx, int rx, int rz,
                                 Map<Long, MapCache.ChunkColors> cache) throws Throwable {
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

    private static void writeChunk(Lod lod, int lodIdx, int lx, int lz, MapCache.ChunkColors c) throws Throwable {
        int n = PX_PER_CHUNK[lodIdx];
        int[] src = c == null ? null : (lodIdx == 0 ? c.px : c.l4);
        Object img = lod.img;
        int baseX = lx * n;
        int baseY = lz * n;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int v = src == null ? 0 : src[y * n + x];
                if (!setterArgb) {
                    v = swapRb(v);
                }
                setPixel.invokeExact(img, baseX + x, baseY + y, v);
            }
        }
    }

    private static int swapRb(int c) {
        return (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
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
            if (release != null) {
                release.invoke(Minecraft.getInstance().getTextureManager(), lod.id);
            }
            close.invoke(lod.tex);
        } catch (Throwable ignored) {
        }
    }
}
