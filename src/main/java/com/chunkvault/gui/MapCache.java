package com.chunkvault.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MapCache {

    public static final class ChunkColors {
        public final int[] px = new int[256];
        public final int[] l8 = new int[64];
        public final int[] l4 = new int[16];
        public final int[] l2 = new int[4];
        public int avg;
        public Mesh m16;
        public Mesh m8;
        public Mesh m4;
        public Mesh m2;
    }

    public static final class Mesh {
        public final int[] rects;
        public final int[] colors;
        public final int count;

        Mesh(int[] rects, int[] colors, int count) {
            this.rects = rects;
            this.colors = colors;
            this.count = count;
        }
    }

    private static final Map<String, Map<Long, ChunkColors>> DIMENSIONS = new ConcurrentHashMap<>();
    private static Object lastConnection;

    private MapCache() {}

    public static long key(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    private static void checkSession() {
        Object connection = Minecraft.getInstance().getConnection();
        if (connection != lastConnection) {
            DIMENSIONS.clear();
            lastConnection = connection;
        }
    }

    public static Map<Long, ChunkColors> forDimension(String dimensionId) {
        checkSession();
        return DIMENSIONS.computeIfAbsent(dimensionId, k -> new ConcurrentHashMap<>());
    }

    public static void update(ClientLevel level, LevelChunk chunk) {
        try {
            String dimensionId = level.dimension().identifier().toString();
            boolean hasCeiling = dimensionId.equals("minecraft:the_nether");
            ChunkColors colors = build(level, chunk, hasCeiling);
            long k = key(chunk.getPos().x(), chunk.getPos().z());
            Map<Long, ChunkColors> map = forDimension(dimensionId);
            ChunkColors old = map.get(k);
            if (old != null && Arrays.equals(old.px, colors.px)) {
                return;
            }
            map.put(k, colors);
        } catch (Throwable ignored) {
        }
    }

    public static ChunkColors fromPixels(int[] px) {
        ChunkColors out = new ChunkColors();
        System.arraycopy(px, 0, out.px, 0, 256);
        finishDerived(out);
        return out;
    }

    private static LevelChunk loadedChunk(ClientLevel level, int cx, int cz) {
        var c = level.getChunk(cx, cz);
        return c instanceof LevelChunk lc && !(c instanceof EmptyLevelChunk) ? lc : null;
    }

    private static ChunkColors build(ClientLevel level, LevelChunk chunk, boolean hasCeiling) {
        ChunkColors out = new ChunkColors();
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        int minY = chunk.getMinSectionY() * 16;
        int maxY = (chunk.getMaxSectionY() + 1) * 16 - 1;

        LevelChunk northChunk = loadedChunk(level, chunkX, chunkZ - 1);
        LevelChunk westChunk = loadedChunk(level, chunkX - 1, chunkZ);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        int[] tops = new int[256];

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int index = lz * 16 + lx;
                int top = findSurfaceY(level, chunk, lx, lz, minY, maxY, hasCeiling, pos);
                tops[index] = top;
                if (top < minY) {
                    continue;
                }

                pos.set(baseX + lx, top, baseZ + lz);
                BlockState state = chunk.getBlockState(pos);

                if (state.getFluidState().is(FluidTags.WATER)) {
                    int waterRgb = BiomeColors.getAverageWaterColor(level, pos);
                    int floorY = top - 1;
                    BlockState floorState = state;
                    while (floorY >= minY) {
                        pos.set(baseX + lx, floorY, baseZ + lz);
                        BlockState s = chunk.getBlockState(pos);
                        if (!s.getFluidState().is(FluidTags.WATER) && !s.isAir() && !isUnderwaterPlant(s)) {
                            floorState = s;
                            break;
                        }
                        floorY--;
                    }
                    int depth = Math.max(1, top - floorY);
                    int floorRgb = floorY >= minY ? blockColor(level, floorState, pos) : 0;
                    if (floorRgb == 0) {
                        floorRgb = 0x3F4B58;
                    }
                    double waterMix = Math.min(0.92, 0.28 + (depth - 1) / 14.0);
                    double depthShade = Math.max(0.56, 1.0 - depth * 0.025);
                    int r = clamp((int) (lerp((floorRgb >> 16) & 0xFF, (waterRgb >> 16) & 0xFF, waterMix) * depthShade));
                    int g = clamp((int) (lerp((floorRgb >> 8) & 0xFF, (waterRgb >> 8) & 0xFF, waterMix) * depthShade));
                    int b = clamp((int) (lerp(floorRgb & 0xFF, waterRgb & 0xFF, waterMix) * depthShade));
                    out.px[index] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    continue;
                }

                int rgb = blockColor(level, state, pos);
                if (rgb == 0) {
                    continue;
                }

                int northTop;
                if (lz > 0) {
                    northTop = tops[(lz - 1) * 16 + lx];
                } else if (northChunk != null) {
                    northTop = findSurfaceY(level, northChunk, lx, 15, minY, maxY, hasCeiling, neighborPos);
                } else {
                    northTop = top;
                }

                int westTop;
                if (lx > 0) {
                    westTop = tops[index - 1];
                } else if (westChunk != null) {
                    westTop = findSurfaceY(level, westChunk, 15, lz, minY, maxY, hasCeiling, neighborPos);
                } else {
                    westTop = top;
                }

                int relief = (top - northTop) * 2 + (top - westTop);
                double shade = 0.92 + Math.tanh(relief * 0.22) * 0.22;
                int r = clamp((int) (((rgb >> 16) & 0xFF) * shade));
                int g = clamp((int) (((rgb >> 8) & 0xFF) * shade));
                int b = clamp((int) ((rgb & 0xFF) * shade));

                out.px[index] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        finishDerived(out);
        return out;
    }

    private static int findSurfaceY(ClientLevel level, LevelChunk chunk, int lx, int lz,
                                    int minY, int maxY, boolean hasCeiling, BlockPos.MutableBlockPos pos) {
        int baseX = chunk.getPos().x() * 16;
        int baseZ = chunk.getPos().z() * 16;
        int y = hasCeiling
                ? netherFloor(chunk, baseX + lx, baseZ + lz, minY, maxY, pos)
                : Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1);
        int skipped = 0;
        while (y >= minY && skipped < 3) {
            pos.set(baseX + lx, y, baseZ + lz);
            BlockState s = chunk.getBlockState(pos);
            if (!isSkippableSurface(level, s, pos)) {
                break;
            }
            y--;
            skipped++;
        }
        return y;
    }

    private static boolean isSkippableSurface(ClientLevel level, BlockState state, BlockPos pos) {
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.isAir() || state.is(Blocks.BARRIER) || state.getMapColor(level, pos) == MapColor.NONE) {
            return true;
        }
        if (state.is(BlockTags.FLOWERS) || state.is(BlockTags.RAILS)) {
            return true;
        }
        return state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH);
    }

    private static boolean isUnderwaterPlant(BlockState state) {
        return state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT);
    }

    private static int blockColor(ClientLevel level, BlockState state, BlockPos pos) {
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN) || state.is(Blocks.SUGAR_CANE)) {
            return BiomeColors.getAverageGrassColor(level, pos) & 0xFFFFFF;
        }
        if (state.is(BlockTags.LEAVES)) {
            if (state.is(Blocks.CHERRY_LEAVES) || state.is(Blocks.PALE_OAK_LEAVES)) {
                return scaleRgb(state.getMapColor(level, pos).col, 0.9);
            }
            int leafRgb;
            if (state.is(Blocks.SPRUCE_LEAVES)) {
                leafRgb = 0x619961;
            } else if (state.is(Blocks.BIRCH_LEAVES)) {
                leafRgb = 0x80A755;
            } else {
                leafRgb = BiomeColors.getAverageFoliageColor(level, pos) & 0xFFFFFF;
            }
            return scaleRgb(leafRgb, 0.85);
        }
        if (state.is(Blocks.VINE)) {
            return scaleRgb(BiomeColors.getAverageFoliageColor(level, pos) & 0xFFFFFF, 0.85);
        }
        return state.getMapColor(level, pos).col;
    }

    private static int scaleRgb(int rgb, double factor) {
        int r = clamp((int) (((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int) (((rgb >> 8) & 0xFF) * factor));
        int b = clamp((int) ((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static void finishDerived(ChunkColors out) {
        downsample(out.px, 16, out.l8, 8);
        downsample(out.l8, 8, out.l4, 4);
        downsample(out.l4, 4, out.l2, 2);
        out.m16 = mesh(out.px, 16);
        out.m8 = mesh(out.l8, 8);
        out.m4 = mesh(out.l4, 4);
        out.m2 = mesh(out.l2, 2);

        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        int count = 0;
        for (int c : out.px) {
            if (c != 0) {
                sumR += (c >> 16) & 0xFF;
                sumG += (c >> 8) & 0xFF;
                sumB += c & 0xFF;
                count++;
            }
        }
        if (count > 0) {
            out.avg = 0xFF000000
                    | ((int) (sumR / count) << 16)
                    | ((int) (sumG / count) << 8)
                    | (int) (sumB / count);
        }
    }

    private static Mesh mesh(int[] cells, int side) {
        boolean[] used = new boolean[cells.length];
        int[] rects = new int[cells.length];
        int[] colors = new int[cells.length];
        int n = 0;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int i = z * side + x;
                int c = cells[i];
                if (c == 0 || used[i]) {
                    continue;
                }
                int w = 1;
                while (x + w < side && !used[i + w] && cells[i + w] == c) {
                    w++;
                }
                int h = 1;
                grow:
                while (z + h < side) {
                    int row = (z + h) * side + x;
                    for (int k = 0; k < w; k++) {
                        if (used[row + k] || cells[row + k] != c) {
                            break grow;
                        }
                    }
                    h++;
                }
                for (int dz = 0; dz < h; dz++) {
                    for (int dx = 0; dx < w; dx++) {
                        used[(z + dz) * side + x + dx] = true;
                    }
                }
                rects[n] = x | (z << 8) | (w << 16) | (h << 24);
                colors[n] = c;
                n++;
            }
        }
        return new Mesh(Arrays.copyOf(rects, n), Arrays.copyOf(colors, n), n);
    }

    private static void downsample(int[] src, int srcSide, int[] dst, int dstSide) {
        int factor = srcSide / dstSide;
        for (int dz = 0; dz < dstSide; dz++) {
            for (int dx = 0; dx < dstSide; dx++) {
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int oz = 0; oz < factor; oz++) {
                    for (int ox = 0; ox < factor; ox++) {
                        int c = src[(dz * factor + oz) * srcSide + (dx * factor + ox)];
                        if (c != 0) {
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                            n++;
                        }
                    }
                }
                dst[dz * dstSide + dx] = n == 0 ? 0
                        : 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
    }

    private static int netherFloor(LevelChunk chunk, int worldX, int worldZ, int minY, int maxY,
                                   BlockPos.MutableBlockPos pos) {
        int y = Math.min(maxY, 127);
        while (y >= minY) {
            pos.set(worldX, y, worldZ);
            if (chunk.getBlockState(pos).isAir()) {
                break;
            }
            y--;
        }
        while (y >= minY) {
            pos.set(worldX, y, worldZ);
            if (!chunk.getBlockState(pos).isAir()) {
                return y;
            }
            y--;
        }
        return minY - 1;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
