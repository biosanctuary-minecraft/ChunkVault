package com.chunkvault.save;

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
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.FlatLevelSource;
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

/**
 * Builds a real Void-preset world's dimension config entirely in code, using
 * the game's own FlatLevelGeneratorSettings/LevelStem/WorldDimensions classes
 * and their Codecs, instead of hand-typing the NBT and instead of asking the
 * player to create a template world once. The actual byte layout comes from
 * real game logic that already knows the correct schema for this version.
 *
 * All three dimensions get the same flat generator: no layers, biome
 * "the_void" — meaning nothing generates anywhere we haven't explicitly
 * recorded a chunk, matching a real superflat "The Void" preset exactly.
 *
 * CHECK: this is the least-tested corner of the whole mod. The shapes below
 * (FlatLevelGeneratorSettings/LevelStem/WorldDimensions constructors,
 * BuiltinDimensionTypes' field names) reflect how flat-world generation has
 * worked since roughly 1.19-1.21 and this general area hasn't churned as
 * much as e.g. ChunkPos or WorldVersion did — but a follow-up compile fix
 * here would not be surprising, same as everywhere else in this project.
 */
public final class VoidWorldGenBuilder {

    public static CompoundTag build(ClientLevel level) {
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

        WorldDimensions dimensions = new WorldDimensions(stems);

        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        // Using result()/orElseThrow rather than DataResult.getOrThrow(...):
        // the latter's signature has changed across DFU versions, result()
        // returning Optional has not.
        // Fixed: WorldDimensions.CODEC is a MapCodec<WorldDimensions>, not a
        // full Codec — .codec() converts it before encodeStart() is usable.
        DataResult<Tag> encoded = WorldDimensions.CODEC.codec().encodeStart(ops, dimensions);
        Tag tag = encoded.result().orElseThrow(() ->
                new IllegalStateException("failed to encode void WorldDimensions: " + encoded.error()));

        // The game's own error for our previous output was:
        //   "No key dimensions in MapLike[{}]; No key seed in MapLike[{}]"
        // i.e. the reader looked for BOTH `dimensions` and `seed` inside a
        // (then empty) map. Two things were wrong with what we wrote:
        //  1) WorldDimensions.CODEC alone only produces `dimensions` —
        //     the file is really the full WorldGenSettings shape, which also
        //     needs `seed` (plus generate_features / bonus_chest).
        //  2) world_gen_settings.dat is a saved-data file, and those always
        //     wrap their payload as { DataVersion: int, data: { ... } };
        //     with the payload at the root, the reader saw `data` as an
        //     empty map — hence the empty MapLike[{}] in the message.
        CompoundTag inner;
        if (tag instanceof CompoundTag compound) {
            inner = compound;
        } else {
            inner = new CompoundTag();
            inner.put("dimensions", tag);
        }
        inner.putLong("seed", 0L);
        inner.putBoolean("generate_features", false);
        inner.putBoolean("bonus_chest", false);

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("data", inner);
        return root;
    }

    /** Writes the built tag to data/minecraft/world_gen_settings.dat, gzip-compressed like level.dat. */
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
            System.err.println("[ChunkVault] failed to write world_gen_settings.dat: " + e);
        }
    }

    private VoidWorldGenBuilder() {}
}
