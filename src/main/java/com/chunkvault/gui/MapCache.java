package com.chunkvault.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers a top-down colour picture of every chunk the client has seen
 * this session (per dimension), independent of whether anything is being
 * recorded. Filled from incoming chunk packets (see ChunkCaptureMixin) and,
 * while the map screen is open, from chunks currently loaded near the player.
 * Wiped when the connection changes, so two servers never mix.
 */
public final class MapCache {

    public static final class ChunkColors {
        /** ARGB per block column, index = localZ * 16 + localX. 0 = no data (void / air column). */
        public final int[] px = new int[256];
        /** Down-sampled (averaged) versions of px: 8x8, 4x4 and 2x2 cells. Much smoother than picking single blocks. */
        public final int[] l8 = new int[64];
        public final int[] l4 = new int[16];
        public final int[] l2 = new int[4];
        /** Average colour of the whole chunk, used when zoomed far out. 0 = nothing visible. */
        public int avg;
        /**
         * Same pictures as px/l8/l4/l2 but with equal-coloured neighbouring
         * cells greedily merged into big rectangles — one fill() per rectangle
         * instead of one per cell. This is what makes full 16x16 detail affordable.
         */
        public Mesh m16;
        public Mesh m8;
        public Mesh m4;
        public Mesh m2;
    }

    /** rects[i] packs x | z<<8 | w<<16 | h<<24 (cell units), colors[i] is ARGB. */
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

    /** Recomputes and stores the colour picture of one chunk. Never throws — the map must not break chunk loading. */
    public static void update(ClientLevel level, LevelChunk chunk) {
        try {
            String dimensionId = level.dimension().identifier().toString();
            boolean hasCeiling = dimensionId.equals("minecraft:the_nether");
            ChunkColors colors = build(level, chunk, hasCeiling);
            forDimension(dimensionId).put(key(chunk.getPos().x(), chunk.getPos().z()), colors);
        } catch (Throwable ignored) {
            // intentionally swallowed
        }
    }

    private static ChunkColors build(ClientLevel level, LevelChunk chunk, boolean hasCeiling) {
        ChunkColors out = new ChunkColors();
        int baseX = chunk.getPos().x() * 16;
        int baseZ = chunk.getPos().z() * 16;
        int minY = chunk.getMinSectionY() * 16;
        int maxY = (chunk.getMaxSectionY() + 1) * 16 - 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] tops = new int[256];
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        int count = 0;

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int index = lz * 16 + lx;
                int top;
                if (hasCeiling) {
                    top = netherFloor(chunk, baseX + lx, baseZ + lz, minY, maxY, pos);
                } else {
                    top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1;
                }
                tops[index] = top;
                if (top < minY) {
                    continue;
                }

                pos.set(baseX + lx, top, baseZ + lz);
                BlockState state = chunk.getBlockState(pos);
                int rgb = state.getMapColor(level, pos).col;
                if (rgb == 0) {
                    continue;
                }

                // Vanilla-map-style relief with only a few brightness steps: clean
                // edges, and big flat areas that merge into a handful of rectangles.
                int northTop = lz > 0 ? tops[(lz - 1) * 16 + lx] : top;
                int westTop = lx > 0 ? tops[index - 1] : top;
                int relief = (top - northTop) * 2 + (top - westTop);
                double shade = relief >= 3 ? 1.12 : relief > 0 ? 1.0 : relief == 0 ? 0.9 : relief > -3 ? 0.78 : 0.66;
                int r = clamp((int) (((rgb >> 16) & 0xFF) * shade));
                int g = clamp((int) (((rgb >> 8) & 0xFF) * shade));
                int b = clamp((int) ((rgb & 0xFF) * shade));

                out.px[index] = 0xFF000000 | (r << 16) | (g << 8) | b;
                sumR += r;
                sumG += g;
                sumB += b;
                count++;
            }
        }

        downsample(out.px, 16, out.l8, 8);
        downsample(out.l8, 8, out.l4, 4);
        downsample(out.l4, 4, out.l2, 2);
        out.m16 = mesh(out.px, 16);
        out.m8 = mesh(out.l8, 8);
        out.m4 = mesh(out.l4, 4);
        out.m2 = mesh(out.l2, 2);

        if (count > 0) {
            out.avg = 0xFF000000
                    | ((int) (sumR / count) << 16)
                    | ((int) (sumG / count) << 8)
                    | (int) (sumB / count);
        }
        return out;
    }

    /** Greedy 2D merge of equal-coloured, non-empty cells into rectangles. */
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
        return new Mesh(java.util.Arrays.copyOf(rects, n), java.util.Arrays.copyOf(colors, n), n);
    }

    /** Averages square groups of ARGB pixels; groups with no visible pixel stay 0. */
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

    /**
     * For dimensions with a solid roof (the Nether) the heightmap would just
     * show the bedrock ceiling. Instead: go down through the roof until the
     * first open air, then down through the air until the first floor block.
     * Returns minY - 1 when nothing was found.
     */
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

    private MapCache() {}
}
