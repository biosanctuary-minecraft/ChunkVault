package com.chunkvault.state;

import net.minecraft.core.BlockPos;

public final class LastInteraction {

    private static volatile BlockPos lastRightClickedBlock;

    public static void set(BlockPos pos) {
        lastRightClickedBlock = pos;
    }

    public static BlockPos get() {
        return lastRightClickedBlock;
    }

    private LastInteraction() {}
}
