package com.chunkvault.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import java.util.IdentityHashMap;
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
    private static final Map<Block, Integer> BLOCK_PALETTE = buildPalette();
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
        if (c instanceof LevelChunk lc && !(c instanceof EmptyLevelChunk)) {
            return lc;
        }
        return null;
    }

    private static ChunkColors build(ClientLevel level, LevelChunk chunk, boolean hasCeiling) {
        ChunkColors out = new ChunkColors();
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        int baseX = cx * 16;
        int baseZ = cz * 16;
        int minY = chunk.getMinSectionY() * 16;
        int maxY = (chunk.getMaxSectionY() + 1) * 16 - 1;

        LevelChunk northChunk = loadedChunk(level, cx, cz - 1);
        LevelChunk westChunk = loadedChunk(level, cx - 1, cz);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] tops = new int[256];
        int[] floorTops = new int[256];
        boolean[] isWaterArr = new boolean[256];

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int index = lz * 16 + lx;
                int top = findSurfaceY(level, chunk, lx, lz, baseX, baseZ, minY, maxY, hasCeiling, pos);
                tops[index] = top;
                floorTops[index] = top;
                if (top < minY) {
                    continue;
                }
                pos.set(baseX + lx, top, baseZ + lz);
                BlockState state = chunk.getBlockState(pos);
                if (state.getFluidState().is(FluidTags.WATER)) {
                    isWaterArr[index] = true;
                    floorTops[index] = findWaterFloorY(chunk, baseX + lx, baseZ + lz, top, minY, pos);
                }
            }
        }

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int index = lz * 16 + lx;
                int top = tops[index];
                if (top < minY) {
                    continue;
                }

                int wx = baseX + lx;
                int wz = baseZ + lz;
                pos.set(wx, top, wz);
                BlockState state = chunk.getBlockState(pos);

                if (isWaterArr[index]) {
                    int floorY = floorTops[index];
                    int depth = Math.max(1, top - floorY);
                    int waterRgb = blendedBiomeColor(level, chunk, wx, top, wz, 0);

                    int floorRgb;
                    if (floorY >= minY) {
                        pos.set(wx, floorY, wz);
                        BlockState floorState = chunk.getBlockState(pos);
                        floorRgb = blockColor(level, chunk, floorState, pos);
                        int northFloor = lz > 0 ? floorTops[(lz - 1) * 16 + lx] : floorY;
                        int westFloor = lx > 0 ? floorTops[index - 1] : floorY;
                        int floorRelief = (floorY - northFloor) * 2 + (floorY - westFloor);
                        double floorShade = 0.94 + Math.tanh(floorRelief * 0.20) * 0.16;
                        floorRgb = scaleRgb(floorRgb, floorShade);
                    } else {
                        floorRgb = scaleRgb(waterRgb, 0.45);
                    }

                    double clarity = Math.exp(-depth * 0.14);
                    double depthDarken = 0.62 + 0.38 * Math.exp(-depth * 0.065);
                    int deepWater = scaleRgb(waterRgb, depthDarken);
                    int blended = lerpRgb(deepWater, floorRgb, clarity * 0.82);

                    int northTop = neighborSurfaceY(level, chunk, northChunk, lx, lz - 1, baseX, baseZ, minY, maxY, hasCeiling, tops, top, pos);
                    int westTop = neighborSurfaceY(level, chunk, westChunk, lx - 1, lz, baseX, baseZ, minY, maxY, hasCeiling, tops, top, pos);
                    int waterRelief = (top - northTop) + (top - westTop);
                    double waterShade = 1.0 + Math.tanh(waterRelief * 0.15) * 0.08;
                    int finalRgb = scaleRgb(blended, waterShade);
                    out.px[index] = 0xFF000000 | finalRgb;
                    continue;
                }

                int rgb = blockColor(level, chunk, state, pos);
                if (rgb == 0) {
                    continue;
                }

                int northTop = neighborSurfaceY(level, chunk, northChunk, lx, lz - 1, baseX, baseZ, minY, maxY, hasCeiling, tops, top, pos);
                int westTop = neighborSurfaceY(level, chunk, westChunk, lx - 1, lz, baseX, baseZ, minY, maxY, hasCeiling, tops, top, pos);
                int relief = (top - northTop) * 2 + (top - westTop);

                double shade = 0.93 + Math.tanh(relief * 0.24) * 0.24;
                if (relief <= -3) {
                    shade *= 0.92;
                }
                double altBoost = Math.max(-0.07, Math.min(0.08, (top - 64) * 0.0012));
                shade += altBoost;

                int shaded = scaleRgb(rgb, shade);
                out.px[index] = 0xFF000000 | shaded;
            }
        }

        finishDerived(out);
        return out;
    }

    private static int neighborSurfaceY(ClientLevel level, LevelChunk chunk, LevelChunk neighborChunk,
                                        int nlx, int nlz, int baseX, int baseZ, int minY, int maxY,
                                        boolean hasCeiling, int[] tops, int fallback,
                                        BlockPos.MutableBlockPos pos) {
        if (nlx >= 0 && nlz >= 0) {
            int y = tops[nlz * 16 + nlx];
            return y >= minY ? y : fallback;
        }
        if (neighborChunk == null) {
            return fallback;
        }
        int lx = Math.floorMod(nlx, 16);
        int lz = Math.floorMod(nlz, 16);
        int nBaseX = neighborChunk.getPos().x() * 16;
        int nBaseZ = neighborChunk.getPos().z() * 16;
        int y = findSurfaceY(level, neighborChunk, lx, lz, nBaseX, nBaseZ, minY, maxY, hasCeiling, pos);
        return y >= minY ? y : fallback;
    }

    private static int findSurfaceY(ClientLevel level, LevelChunk chunk, int lx, int lz,
                                    int baseX, int baseZ, int minY, int maxY,
                                    boolean hasCeiling, BlockPos.MutableBlockPos pos) {
        int y = hasCeiling
                ? netherFloor(chunk, baseX + lx, baseZ + lz, minY, maxY, pos)
                : Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz));
        int wx = baseX + lx;
        int wz = baseZ + lz;
        while (y >= minY) {
            pos.set(wx, y, wz);
            BlockState state = chunk.getBlockState(pos);
            if (!shouldSkipSurfaceBlock(level, state, pos)) {
                return y;
            }
            y--;
        }
        return minY - 1;
    }

    private static boolean shouldSkipSurfaceBlock(ClientLevel level, BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        Block block = state.getBlock();
        if (block == Blocks.BARRIER || block == Blocks.LIGHT || block == Blocks.STRUCTURE_VOID) {
            return true;
        }
        if (block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS
                || block == Blocks.FERN || block == Blocks.LARGE_FERN
                || block == Blocks.DEAD_BUSH || block == Blocks.VINE
                || block == Blocks.GLOW_LICHEN || block == Blocks.COBWEB
                || block == Blocks.TORCH || block == Blocks.WALL_TORCH
                || block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH
                || block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH
                || block == Blocks.REDSTONE_WIRE || block == Blocks.TRIPWIRE
                || block == Blocks.TRIPWIRE_HOOK || block == Blocks.STRING
                || block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM
                || block == Blocks.CRIMSON_FUNGUS || block == Blocks.WARPED_FUNGUS
                || block == Blocks.CRIMSON_ROOTS || block == Blocks.WARPED_ROOTS
                || block == Blocks.NETHER_SPROUTS || block == Blocks.SEAGRASS
                || block == Blocks.TALL_SEAGRASS || block == Blocks.KELP
                || block == Blocks.KELP_PLANT || block == Blocks.SUGAR_CANE
                || block == Blocks.BAMBOO_SAPLING || block == Blocks.PINK_PETALS
                || block == Blocks.LEAF_LITTER || block == Blocks.WILDFLOWERS) {
            return true;
        }
        if (state.is(BlockTags.FLOWERS) || state.is(BlockTags.RAILS) || state.is(BlockTags.SAPLINGS)) {
            return true;
        }
        return state.getMapColor(level, pos) == MapColor.NONE;
    }

    private static int findWaterFloorY(LevelChunk chunk, int wx, int wz, int surfaceY, int minY,
                                       BlockPos.MutableBlockPos pos) {
        int y = surfaceY - 1;
        while (y >= minY) {
            pos.set(wx, y, wz);
            BlockState s = chunk.getBlockState(pos);
            if (!s.isAir() && !s.getFluidState().is(FluidTags.WATER)) {
                Block b = s.getBlock();
                if (b != Blocks.SEAGRASS && b != Blocks.TALL_SEAGRASS
                        && b != Blocks.KELP && b != Blocks.KELP_PLANT
                        && b != Blocks.BUBBLE_COLUMN) {
                    return y;
                }
            }
            y--;
        }
        return minY - 1;
    }

    private static int blockColor(ClientLevel level, LevelChunk chunk, BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (block == Blocks.GRASS_BLOCK || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS
                || block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.SUGAR_CANE) {
            return blendedBiomeColor(level, chunk, pos.getX(), pos.getY(), pos.getZ(), 1);
        }
        if (block == Blocks.SPRUCE_LEAVES) {
            return 0x4E7A4E;
        }
        if (block == Blocks.BIRCH_LEAVES) {
            return 0x6A8E46;
        }
        if (block == Blocks.CHERRY_LEAVES) {
            return 0xE5A3BE;
        }
        if (block == Blocks.AZALEA_LEAVES) {
            return 0x5B7B34;
        }
        if (block == Blocks.FLOWERING_AZALEA_LEAVES) {
            return 0x7A7352;
        }
        if (block == Blocks.PALE_OAK_LEAVES) {
            return 0x76827B;
        }
        if (state.is(BlockTags.LEAVES) || block == Blocks.VINE) {
            int foliage = blendedBiomeColor(level, chunk, pos.getX(), pos.getY(), pos.getZ(), 2);
            return scaleRgb(foliage, 0.84);
        }
        Integer curated = BLOCK_PALETTE.get(block);
        if (curated != null) {
            return curated;
        }
        return state.getMapColor(level, pos).col;
    }

    private static int blendedBiomeColor(ClientLevel level, LevelChunk originChunk, int wx, int wy, int wz, int mode) {
        int rSum = 0;
        int gSum = 0;
        int bSum = 0;
        int count = 0;
        int originCx = originChunk.getPos().x();
        int originCz = originChunk.getPos().z();

        for (int dz = -2; dz <= 2; dz += 2) {
            int sz = wz + dz;
            int scz = sz >> 4;
            for (int dx = -2; dx <= 2; dx += 2) {
                int sx = wx + dx;
                int scx = sx >> 4;
                LevelChunk target = (scx == originCx && scz == originCz)
                        ? originChunk
                        : loadedChunk(level, scx, scz);
                if (target == null) {
                    continue;
                }
                Biome biome = sampleBiome(target, sx & 15, wy, sz & 15);
                if (biome == null) {
                    continue;
                }
                int col = switch (mode) {
                    case 0 -> biome.getWaterColor();
                    case 1 -> biome.getGrassColor(sx, sz);
                    default -> biome.getFoliageColor();
                };
                rSum += (col >> 16) & 0xFF;
                gSum += (col >> 8) & 0xFF;
                bSum += col & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return mode == 0 ? 0x3F76E4 : 0x79C05A;
        }
        return ((rSum / count) << 16) | ((gSum / count) << 8) | (bSum / count);
    }

    private static Biome sampleBiome(LevelChunk chunk, int lx, int wy, int lz) {
        int idx = chunk.getSectionIndex(wy);
        if (idx < 0 || idx >= chunk.getSectionsCount()) {
            idx = Math.max(0, Math.min(chunk.getSectionsCount() - 1, idx));
        }
        LevelChunkSection section = chunk.getSection(idx);
        Holder<Biome> holder = section.getNoiseBiome((lx >> 2) & 3, (wy >> 2) & 3, (lz >> 2) & 3);
        return holder == null ? null : holder.value();
    }

    private static int scaleRgb(int rgb, double factor) {
        int r = clamp((int) Math.round(((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int) Math.round(((rgb >> 8) & 0xFF) * factor));
        int b = clamp((int) Math.round((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int lerpRgb(int a, int b, double t) {
        double ct = Math.max(0.0, Math.min(1.0, t));
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = clamp((int) Math.round(ar + (br - ar) * ct));
        int g = clamp((int) Math.round(ag + (bg - ag) * ct));
        int bl = clamp((int) Math.round(ab + (bb - ab) * ct));
        return (r << 16) | (g << 8) | bl;
    }

    private static Map<Block, Integer> buildPalette() {
        Map<Block, Integer> m = new IdentityHashMap<>();
        m.put(Blocks.DIRT, 0x866043);
        m.put(Blocks.COARSE_DIRT, 0x77553B);
        m.put(Blocks.ROOTED_DIRT, 0x90674C);
        m.put(Blocks.DIRT_PATH, 0x947941);
        m.put(Blocks.FARMLAND, 0x5B3920);
        m.put(Blocks.PODZOL, 0x5A3E1B);
        m.put(Blocks.MYCELIUM, 0x6F6265);
        m.put(Blocks.MUD, 0x3C393C);
        m.put(Blocks.SAND, 0xDBD3A0);
        m.put(Blocks.RED_SAND, 0xBE6621);
        m.put(Blocks.SANDSTONE, 0xD8CB9B);
        m.put(Blocks.RED_SANDSTONE, 0xBA631D);
        m.put(Blocks.GRAVEL, 0x837F7E);
        m.put(Blocks.CLAY, 0xA0A6B3);
        m.put(Blocks.STONE, 0x7D7D7D);
        m.put(Blocks.COBBLESTONE, 0x7A7A7A);
        m.put(Blocks.MOSSY_COBBLESTONE, 0x6E7A5E);
        m.put(Blocks.STONE_BRICKS, 0x7A7A7A);
        m.put(Blocks.ANDESITE, 0x888888);
        m.put(Blocks.DIORITE, 0xBCBCBC);
        m.put(Blocks.GRANITE, 0x956755);
        m.put(Blocks.DEEPSLATE, 0x505052);
        m.put(Blocks.TUFF, 0x6C6D66);
        m.put(Blocks.CALCITE, 0xDFE0DC);
        m.put(Blocks.TERRACOTTA, 0x985E43);
        m.put(Blocks.SNOW, 0xF0F6F6);
        m.put(Blocks.SNOW_BLOCK, 0xF0F6F6);
        m.put(Blocks.POWDER_SNOW, 0xECF4F5);
        m.put(Blocks.ICE, 0x91B8FE);
        m.put(Blocks.PACKED_ICE, 0x8DB4FA);
        m.put(Blocks.BLUE_ICE, 0x74A7FD);
        m.put(Blocks.OAK_PLANKS, 0xA2824E);
        m.put(Blocks.SPRUCE_PLANKS, 0x725430);
        m.put(Blocks.BIRCH_PLANKS, 0xC0AF79);
        m.put(Blocks.JUNGLE_PLANKS, 0xA07350);
        m.put(Blocks.ACACIA_PLANKS, 0xA85A32);
        m.put(Blocks.DARK_OAK_PLANKS, 0x422B14);
        m.put(Blocks.MANGROVE_PLANKS, 0x753630);
        m.put(Blocks.CHERRY_PLANKS, 0xE2B2AC);
        m.put(Blocks.BAMBOO_PLANKS, 0xC1AD50);
        m.put(Blocks.OAK_LOG, 0x6D5533);
        m.put(Blocks.SPRUCE_LOG, 0x3A2510);
        m.put(Blocks.BIRCH_LOG, 0xD7D3CB);
        m.put(Blocks.JUNGLE_LOG, 0x554319);
        m.put(Blocks.ACACIA_LOG, 0x676056);
        m.put(Blocks.DARK_OAK_LOG, 0x3C2E1A);
        m.put(Blocks.LAVA, 0xD96514);
        m.put(Blocks.OBSIDIAN, 0x15121E);
        m.put(Blocks.NETHERRACK, 0x612626);
        m.put(Blocks.SOUL_SAND, 0x513E32);
        m.put(Blocks.SOUL_SOIL, 0x4B392E);
        m.put(Blocks.BASALT, 0x4A4A4F);
        m.put(Blocks.BLACKSTONE, 0x2A2429);
        m.put(Blocks.CRIMSON_NYLIUM, 0x821F1F);
        m.put(Blocks.WARPED_NYLIUM, 0x2B7265);
        m.put(Blocks.END_STONE, 0xDBDE9E);
        m.put(Blocks.MOSS_BLOCK, 0x596D2D);
        m.put(Blocks.WHEAT, 0xC2A33E);
        m.put(Blocks.CARROTS, 0x568A29);
        m.put(Blocks.POTATOES, 0x5A912C);
        m.put(Blocks.BEETROOTS, 0x4E8328);
        return m;
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
