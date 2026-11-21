package com.chunkvault.state;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DirtyChunkTracker {

    private static final Map<String, Set<Long>> DIRTY = new ConcurrentHashMap<>();

    private DirtyChunkTracker() {}

    private static long pack(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    public static void mark(String dimension, int cx, int cz) {
        if (dimension == null) {
            return;
        }
        DIRTY.computeIfAbsent(dimension, k -> ConcurrentHashMap.newKeySet()).add(pack(cx, cz));
    }

    public static boolean consume(String dimension, int cx, int cz) {
        if (dimension == null) {
            return false;
        }
        Set<Long> set = DIRTY.get(dimension);
        return set != null && set.remove(pack(cx, cz));
    }

    public static void clear() {
        DIRTY.clear();
    }
}
