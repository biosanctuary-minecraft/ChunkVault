package com.chunkvault.state;

import net.minecraft.core.BlockPos;

public final class LastInteraction {

    private static final long MAX_AGE_MS = 3000L;

    private static volatile BlockPos lastRightClickedBlock;
    private static volatile long lastRightClickTimeMs;

    public static void set(BlockPos pos) {
        if (pos == null) {
            clear();
            return;
        }
        lastRightClickedBlock = pos.immutable();
        lastRightClickTimeMs = System.currentTimeMillis();
    }

    public static BlockPos consumeRecent() {
        BlockPos pos = lastRightClickedBlock;
        long time = lastRightClickTimeMs;
        clear();
        if (pos == null || System.currentTimeMillis() - time > MAX_AGE_MS) {
            return null;
        }
        return pos;
    }

    public static void clear() {
        lastRightClickedBlock = null;
        lastRightClickTimeMs = 0L;
    }

    private LastInteraction() {}
}
