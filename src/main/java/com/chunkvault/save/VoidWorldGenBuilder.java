package com.chunkvault.save;

import com.chunkvault.state.ChunkVaultSettings;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

public final class VoidWorldGenBuilder {

    private VoidWorldGenBuilder() {}

    public static CompoundTag build(ClientLevel level) {
        ChunkVaultSettings settings = ChunkVaultSettings.get();
        if (settings.useSeed()) {
            return buildSeeded(settings.seed());
        }
        return buildVoid(level, 0L);
    }

    private static CompoundTag buildSeeded(long seed) {
        CompoundTag dimensions = new CompoundTag();
        dimensions.put("minecraft:overworld", noiseDimension("minecraft:overworld", "minecraft:overworld", "minecraft:overworld"));
        dimensions.put("minecraft:the_nether", noiseDimension("minecraft:the_nether", "minecraft:nether", "minecraft:nether"));
        dimensions.put("minecraft:the_end", endDimension());

        CompoundTag inner = new CompoundTag();
        inner.put("dimensions", dimensions);
        inner.putLong("seed", seed);
        inner.putBoolean("generate_structures", true);
        inner.putBoolean("bonus_chest", false);

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("data", inner);
        return root;
    }

    private static CompoundTag noiseDimension(String type, String settings, String biomePreset) {
        CompoundTag biomeSource = new CompoundTag();
        biomeSource.putString("type", "minecraft:multi_noise");
        biomeSource.putString("preset", biomePreset);

        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:noise");
        generator.putString("settings", settings);
        generator.put("biome_source", biomeSource);

        CompoundTag dim = new CompoundTag();
        dim.putString("type", type);
        dim.put("generator", generator);
        return dim;
    }

    private static CompoundTag endDimension() {
        CompoundTag biomeSource = new CompoundTag();
        biomeSource.putString("type", "minecraft:the_end");

        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:noise");
        generator.putString("settings", "minecraft:end");
        generator.put("biome_source", biomeSource);

        CompoundTag dim = new CompoundTag();
        dim.putString("type", "minecraft:the_end");
        dim.put("generator", generator);
        return dim;
    }

    private static CompoundTag buildVoid(ClientLevel level, long seed) {
        HolderLookup.Provider registries = level.registryAccess();
        HolderLookup.RegistryLookup<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
        Holder<Biome> theVoid = biomes.getOrThrow(Biomes.THE_VOID);
        HolderLookup.RegistryLookup<DimensionType> dimensionTypes = registries.lookupOrThrow(Registries.DIMENSION_TYPE);

        FlatLevelGeneratorSettings flatSettings =
                new FlatLevelGeneratorSettings(Optional.empty(), theVoid, List.of());
        FlatLevelSource generator = new FlatLevelSource(flatSettings);

        Map<ResourceKey<LevelStem>, LevelStem> stems = new LinkedHashMap<>();
        stems.put(LevelStem.OVERWORLD, new LevelStem(dimensionTypes.getOrThrow(BuiltinDimensionTypes.OVERWORLD), generator));
        stems.put(LevelStem.NETHER, new LevelStem(dimensionTypes.getOrThrow(BuiltinDimensionTypes.NETHER), generator));
        stems.put(LevelStem.END, new LevelStem(dimensionTypes.getOrThrow(BuiltinDimensionTypes.END), generator));

        return encode(registries, new WorldDimensions(stems), seed, false);
    }

    private static CompoundTag encode(HolderLookup.Provider registries, WorldDimensions dimensions,
                                      long seed, boolean generateStructures) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        DataResult<Tag> encoded = WorldDimensions.CODEC.codec().encodeStart(ops, dimensions);
        Tag tag = encoded.result().orElseThrow(() ->
                new IllegalStateException("Failed to encode WorldDimensions: " + encoded.error()));

        CompoundTag inner;
        if (tag instanceof CompoundTag compound) {
            inner = compound;
        } else {
            inner = new CompoundTag();
            inner.put("dimensions", tag);
        }
        inner.putLong("seed", seed);
        inner.putBoolean("generate_structures", generateStructures);
        inner.putBoolean("bonus_chest", false);

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("data", inner);
        return root;
    }

    public static void writeToWorld(Path worldDir, CompoundTag tag) {
        Path out = worldDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
        try {
            Files.createDirectories(out.getParent());
            try (OutputStream fos = Files.newOutputStream(out);
                 GZIPOutputStream gz = new GZIPOutputStream(fos);
                 DataOutputStream dos = new DataOutputStream(gz)) {
                NbtIo.write(tag, dos);
            }
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to write world_gen_settings.dat: " + e);
        }
    }
}
