package com.chunkvault.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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

    private static final int BIOME_WATER = 0;
    private static final int BIOME_GRASS = 1;
    private static final int BIOME_FOLIAGE = 2;

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

    private static Biome biomeAt(LevelChunk chunk, int lx, int y, int lz) {
        LevelChunkSection[] sections = chunk.getSections();
        if (sections.length == 0) {
            return null;
        }
        int idx = Math.max(0, Math.min(sections.length - 1, (y >> 4) - chunk.getMinSectionY()));
        LevelChunkSection sec = sections[idx];
        if (sec == null) {
            return null;
        }
        return sec.getNoiseBiome((lx & 15) >> 2, (y & 15) >> 2, (lz & 15) >> 2).value();
    }

    private static int sampleBiomeColor(ClientLevel level, LevelChunk centerChunk,
                                        int worldX, int y, int worldZ, int mode) {
        int centerCx = centerChunk.getPos().x();
        int centerCz = centerChunk.getPos().z();
        int sumR = 0;
        int sumG = 0;
        int sumB = 0;
        int count = 0;

        for (int dz = -2; dz <= 2; dz += 2) {
            for (int dx = -2; dx <= 2; dx += 2) {
                int sx = worldX + dx;
                int sz = worldZ + dz;
                int scx = sx >> 4;
                int scz = sz >> 4;
                LevelChunk target = (scx == centerCx && scz == centerCz)
                        ? centerChunk
                        : loadedChunk(level, scx, scz);
                if (target == null) {
                    continue;
                }
                Biome biome = biomeAt(target, sx & 15, y, sz & 15);
                if (biome == null) {
                    continue;
                }
                int rgb = switch (mode) {
                    case BIOME_WATER -> biome.getWaterColor();
                    case BIOME_GRASS -> biome.getGrassColor(sx, sz);
                    default -> biome.getFoliageColor();
                };
                sumR += (rgb >> 16) & 0xFF;
                sumG += (rgb >> 8) & 0xFF;
                sumB += rgb & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return mode == BIOME_WATER ? 0x3F76E4 : 0x6AA84F;
        }
        return ((sumR / count) << 16) | ((sumG / count) << 8) | (sumB / count);
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
        int[] floors = new int[256];

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int index = lz * 16 + lx;
                int top = findSurfaceY(level, chunk, lx, lz, minY, maxY, hasCeiling, pos);
                tops[index] = top;
                floors[index] = top;
                if (top < minY) {
                    continue;
                }

                int wx = baseX + lx;
                int wz = baseZ + lz;
                pos.set(wx, top, wz);
                BlockState state = chunk.getBlockState(pos);

                if (state.getFluidState().is(FluidTags.WATER)) {
                    int waterRgb = sampleBiomeColor(level, chunk, wx, top, wz, BIOME_WATER);
                    int floorY = top - 1;
                    BlockState floorState = state;
                    while (floorY >= minY) {
                        pos.set(wx, floorY, wz);
                        BlockState s = chunk.getBlockState(pos);
                        if (!s.getFluidState().is(FluidTags.WATER) && !s.isAir() && !isUnderwaterPlant(s)) {
                            floorState = s;
                            break;
                        }
                        floorY--;
                    }
                    floors[index] = floorY;
                    int depth = Math.max(1, top - floorY);
                    int floorRgb = floorY >= minY ? blockColor(level, chunk, floorState, pos, wx, floorY, wz) : 0;
                    if (floorRgb == 0) {
                        floorRgb = 0x3F4B58;
                    }

                    int northFloor = lz > 0 ? floors[(lz - 1) * 16 + lx] : floorY;
                    int westFloor = lx > 0 ? floors[index - 1] : floorY;
                    int floorRelief = (floorY - northFloor) * 2 + (floorY - westFloor);
                    double floorShade = 0.94 + Math.tanh(floorRelief * 0.22) * 0.18;

                    double fr = ((floorRgb >> 16) & 0xFF) * floorShade;
                    double fg = ((floorRgb >> 8) & 0xFF) * floorShade;
                    double fb = (floorRgb & 0xFF) * floorShade;

                    double visibility = Math.exp(-depth * 0.145);
                    double waterMix = 1.0 - visibility;
                    double depthShade = Math.max(0.54, 1.0 - depth * 0.022);

                    int r = clamp((int) (lerp(fr, (waterRgb >> 16) & 0xFF, waterMix) * depthShade));
                    int g = clamp((int) (lerp(fg, (waterRgb >> 8) & 0xFF, waterMix) * depthShade));
                    int b = clamp((int) (lerp(fb, waterRgb & 0xFF, waterMix) * depthShade));
                    out.px[index] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    continue;
                }

                int rgb = blockColor(level, chunk, state, pos, wx, top, wz);
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
                double shade = 0.94 + Math.tanh(relief * 0.28) * 0.26;
                double alt = Math.max(-0.08, Math.min(0.10, (top - 64) * 0.0014));
                shade += alt;
                if (northTop - top >= 3 || westTop - top >= 3) {
                    shade *= 0.86;
                }

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

    private static int blockColor(ClientLevel level, LevelChunk chunk, BlockState state,
                                  BlockPos pos, int wx, int y, int wz) {
        Block b = state.getBlock();
        if (b == Blocks.GRASS_BLOCK || b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS
                || b == Blocks.FERN || b == Blocks.LARGE_FERN || b == Blocks.SUGAR_CANE) {
            return sampleBiomeColor(level, chunk, wx, y, wz, BIOME_GRASS);
        }
        if (state.is(BlockTags.LEAVES)) {
            if (b == Blocks.CHERRY_LEAVES) {
                return 0xE5A5BE;
            }
            if (b == Blocks.PALE_OAK_LEAVES) {
                return 0x767E73;
            }
            if (b == Blocks.SPRUCE_LEAVES) {
                return 0x436E43;
            }
            if (b == Blocks.BIRCH_LEAVES) {
                return 0x6E9444;
            }
            return scaleRgb(sampleBiomeColor(level, chunk, wx, y, wz, BIOME_FOLIAGE), 0.80);
        }
        if (b == Blocks.VINE) {
            return scaleRgb(sampleBiomeColor(level, chunk, wx, y, wz, BIOME_FOLIAGE), 0.80);
        }
        if (b == Blocks.PODZOL) return 0x6D4F2E;
        if (b == Blocks.DIRT) return 0x866043;
        if (b == Blocks.COARSE_DIRT) return 0x76543A;
        if (b == Blocks.ROOTED_DIRT) return 0x8A6548;
        if (b == Blocks.DIRT_PATH) return 0x9B8249;
        if (b == Blocks.FARMLAND) return 0x633E22;
        if (b == Blocks.MUD) return 0x3C383B;
        if (b == Blocks.CLAY) return 0x9EA5B2;
        if (b == Blocks.MYCELIUM) return 0x6E6265;
        if (b == Blocks.MOSS_BLOCK) return 0x5A6E2D;
        if (b == Blocks.STONE) return 0x7E7E7E;
        if (b == Blocks.COBBLESTONE) return 0x6F6F6F;
        if (b == Blocks.MOSSY_COBBLESTONE) return 0x64725A;
        if (b == Blocks.ANDESITE) return 0x888888;
        if (b == Blocks.GRANITE) return 0x956755;
        if (b == Blocks.DIORITE) return 0xC6C6C6;
        if (b == Blocks.GRAVEL) return 0x857F7F;
        if (b == Blocks.DEEPSLATE) return 0x505052;
        if (b == Blocks.COBBLED_DEEPSLATE) return 0x4C4C50;
        if (b == Blocks.TUFF) return 0x6C6D66;
        if (b == Blocks.CALCITE) return 0xE0E0DC;
        if (b == Blocks.DRIPSTONE_BLOCK) return 0x866B5C;
        if (b == Blocks.SAND) return 0xDCD3A1;
        if (b == Blocks.RED_SAND) return 0xBF6721;
        if (b == Blocks.SANDSTONE) return 0xD8CB9A;
        if (b == Blocks.RED_SANDSTONE) return 0xB66220;
        if (b == Blocks.SNOW || b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW) return 0xF2FBF9;
        if (b == Blocks.ICE) return 0x91B7FD;
        if (b == Blocks.PACKED_ICE) return 0x8DB4FA;
        if (b == Blocks.BLUE_ICE) return 0x74A7FD;
        if (b == Blocks.OAK_LOG) return 0x6D5533;
        if (b == Blocks.SPRUCE_LOG) return 0x3B2611;
        if (b == Blocks.BIRCH_LOG) return 0xD8D6D1;
        if (b == Blocks.JUNGLE_LOG) return 0x554319;
        if (b == Blocks.ACACIA_LOG) return 0x676056;
        if (b == Blocks.DARK_OAK_LOG) return 0x3C2E1B;
        if (b == Blocks.MANGROVE_LOG) return 0x544329;
        if (b == Blocks.CHERRY_LOG) return 0x37222B;
        if (b == Blocks.OAK_PLANKS) return 0xA2824E;
        if (b == Blocks.SPRUCE_PLANKS) return 0x725430;
        if (b == Blocks.BIRCH_PLANKS) return 0xC0AF79;
        if (b == Blocks.JUNGLE_PLANKS) return 0xA07350;
        if (b == Blocks.ACACIA_PLANKS) return 0xA85A32;
        if (b == Blocks.DARK_OAK_PLANKS) return 0x422B14;
        if (b == Blocks.MANGROVE_PLANKS) return 0x753630;
        if (b == Blocks.CHERRY_PLANKS) return 0xE2B2AC;
        if (b == Blocks.WHEAT) return 0xA8B038;
        if (b == Blocks.CARROTS) return 0x4A8C2A;
        if (b == Blocks.POTATOES) return 0x4E942E;
        if (b == Blocks.BEETROOTS) return 0x568C32;
        if (b == Blocks.HAY_BLOCK) return 0xB89828;
        if (b == Blocks.PUMPKIN) return 0xC87418;
        if (b == Blocks.MELON) return 0x769422;
        if (b == Blocks.CACTUS) return 0x56822A;
        if (b == Blocks.LILY_PAD) return 0x3D6E2A;
        if (b == Blocks.NETHERRACK) return 0x632727;
        if (b == Blocks.CRIMSON_NYLIUM) return 0x832020;
        if (b == Blocks.WARPED_NYLIUM) return 0x2B7265;
        if (b == Blocks.SOUL_SAND) return 0x513E32;
        if (b == Blocks.SOUL_SOIL) return 0x4B392E;
        if (b == Blocks.BASALT) return 0x49484D;
        if (b == Blocks.BLACKSTONE) return 0x2A242B;
        if (b == Blocks.MAGMA_BLOCK) return 0x8E3F1F;
        if (b == Blocks.GLOWSTONE) return 0xC99E57;
        if (b == Blocks.END_STONE) return 0xDBDE9E;
        if (b == Blocks.OBSIDIAN) return 0x14121D;
        if (b == Blocks.LAVA) return 0xD85E14;

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
