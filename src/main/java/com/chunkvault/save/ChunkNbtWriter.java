package com.chunkvault.save;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the CompoundTag for one chunk in the same shape the vanilla server
 * writes to region files (post-1.18 "new chunk format": section list with
 * block_states.palette / block_states.data, one compound per block entity).
 *
 * Known simplifications (fine for a "walk around and back it up" tool,
 * called out so nobody is surprised):
 *  - biomes are written as ONE uniform value per 16x16x16 section (sampled
 *    from a single point) instead of the real 4x4x4 resolution. Visual/
 *    spawn-table accuracy only, doesn't affect terrain or containers.
 *  - "isLightOn" is always written as 0 (false), so the client recomputes
 *    lighting the first time it loads the chunk instead of us having to
 *    reproduce the light engine's output.
 *  - Heightmaps are omitted; vanilla recalculates them lazily.
 */
public final class ChunkNbtWriter {

    // Fixed: WorldVersion and DataVersion are both records as of 1.21.6+,
    // so the chain is dataVersion() -> version(), not getDataVersion().getVersion().
    private static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    public static CompoundTag write(ClientLevel level, LevelChunk chunk) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", currentDataVersion());

        // Fixed: ChunkPos.x/z are private now; use the x()/z() accessors.
        root.putInt("xPos", chunk.getPos().x());
        root.putInt("zPos", chunk.getPos().z());
        // Fixed: getMinSection() was renamed to getMinSectionY().
        root.putInt("yPos", chunk.getMinSectionY());

        // CHECK: newer versions namespace the status id ("minecraft:full");
        // older ones used the bare string "full". Try the bare form first if
        // this doesn't load.
        root.putString("Status", "minecraft:full");
        root.putBoolean("isLightOn", false);

        ListTag sections = new ListTag();
        LevelChunkSection[] rawSections = chunk.getSections();
        int minSection = chunk.getMinSectionY();
        for (int i = 0; i < rawSections.length; i++) {
            LevelChunkSection section = rawSections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            sections.add(writeSection(level, section, minSection + i));
        }
        root.put("sections", sections);

        ListTag blockEntities = new ListTag();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockEntity be = entry.getValue();
            try {
                // CHECK: saveWithFullMetadata(HolderLookup.Provider) is the
                // modern (1.20.5+) signature; older versions used a no-arg
                // saveWithFullMetadata() or saveWithId().
                CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
                blockEntities.add(tag);
            } catch (Throwable t) {
                // Don't let one weird block entity abort the whole chunk save.
                System.err.println("[ChunkVault] failed to save block entity at " + entry.getKey() + ": " + t);
            }
        }
        root.put("block_entities", blockEntities);

        return root;
    }

    private static CompoundTag writeSection(ClientLevel level, LevelChunkSection section, int sectionY) {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Y", (byte) sectionY);
        tag.put("block_states", writeBlockStates(section));
        tag.put("biomes", writeBiomes(level, section));
        return tag;
    }

    private static CompoundTag writeBlockStates(LevelChunkSection section) {
        // Collect the 4096 block states of this section and build a palette.
        Map<BlockState, Integer> paletteIndex = new LinkedHashMap<>();
        List<BlockState> palette = new ArrayList<>();
        int[] indices = new int[4096];

        int cursor = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    Integer idx = paletteIndex.get(state);
                    if (idx == null) {
                        idx = palette.size();
                        paletteIndex.put(state, idx);
                        palette.add(state);
                    }
                    indices[cursor++] = idx;
                }
            }
        }

        CompoundTag out = new CompoundTag();
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            // NbtUtils.writeBlockState produces the standard {Name, Properties} entry.
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        out.put("palette", paletteTag);

        if (palette.size() > 1) {
            int bitsPerEntry = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            out.put("data", new LongArrayTag(packEntries(indices, bitsPerEntry)));
        }
        // if palette.size() <= 1 the "data" tag is omitted entirely (vanilla convention).

        return out;
    }

    private static CompoundTag writeBiomes(ClientLevel level, LevelChunkSection section) {
        // Simplification: one biome for the whole section, sampled at a quart-pos corner.
        Holder<Biome> biome = section.getNoiseBiome(0, 0, 0);
        CompoundTag out = new CompoundTag();
        ListTag paletteTag = new ListTag();
        // Fixed: registryOrThrow(...) was renamed to lookupOrThrow(...).
        String key = level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .getKey(biome.value())
                .toString();
        paletteTag.add(StringTag.valueOf(key));
        out.put("palette", paletteTag);
        // single-entry palette -> no "data" array needed.
        return out;
    }

    /**
     * Packs `values` (each < 2^bitsPerEntry) into longs, post-1.16 style:
     * entries never span two longs, unused high bits in each long are left
     * as zero padding.
     */
    private static long[] packEntries(int[] values, int bitsPerEntry) {
        int entriesPerLong = 64 / bitsPerEntry;
        int longCount = (values.length + entriesPerLong - 1) / entriesPerLong;
        long[] out = new long[longCount];

        long mask = (1L << bitsPerEntry) - 1L;
        for (int i = 0; i < values.length; i++) {
            int longIndex = i / entriesPerLong;
            int bitOffset = (i % entriesPerLong) * bitsPerEntry;
            out[longIndex] |= (values[i] & mask) << bitOffset;
        }
        return out;
    }

    private ChunkNbtWriter() {}
}
