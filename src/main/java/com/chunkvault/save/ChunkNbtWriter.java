package com.chunkvault.save;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public final class ChunkNbtWriter {

    private ChunkNbtWriter() {}

    public static CompoundTag write(ClientLevel level, LevelChunk chunk,
                                    BiFunction<BlockPos, BlockEntity, CompoundTag> overrides) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putInt("xPos", chunk.getPos().x());
        root.putInt("zPos", chunk.getPos().z());
        root.putInt("yPos", chunk.getMinSectionY());
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
                CompoundTag tag = overrides == null ? null : overrides.apply(entry.getKey(), be);
                if (tag == null) {
                    tag = be.saveWithFullMetadata(level.registryAccess());
                }
                blockEntities.add(tag);
            } catch (Throwable t) {
                System.err.println("[ChunkVault] Failed to serialize block entity at " + entry.getKey() + ": " + t);
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
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        out.put("palette", paletteTag);

        if (palette.size() > 1) {
            int bitsPerEntry = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            out.put("data", new LongArrayTag(packEntries(indices, bitsPerEntry)));
        }

        return out;
    }

    private static CompoundTag writeBiomes(ClientLevel level, LevelChunkSection section) {
        Holder<Biome> biome = section.getNoiseBiome(0, 0, 0);
        CompoundTag out = new CompoundTag();
        ListTag paletteTag = new ListTag();
        String key = level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .getKey(biome.value())
                .toString();
        paletteTag.add(StringTag.valueOf(key));
        out.put("palette", paletteTag);
        return out;
    }

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
}
