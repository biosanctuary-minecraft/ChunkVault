package com.chunkvault.save;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

public final class PlayerDataWriter {

    private PlayerDataWriter() {}

    public static CompoundTag buildPlayerTag(double x, double y, double z,
                                             float yaw, float pitch, String dimensionId) {
        CompoundTag player = new CompoundTag();

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        player.put("Pos", pos);

        ListTag motion = new ListTag();
        motion.add(DoubleTag.valueOf(0.0));
        motion.add(DoubleTag.valueOf(0.0));
        motion.add(DoubleTag.valueOf(0.0));
        player.put("Motion", motion);

        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(yaw));
        rotation.add(FloatTag.valueOf(pitch));
        player.put("Rotation", rotation);

        player.putString("Dimension", dimensionId);
        player.putFloat("FallDistance", 0f);
        player.putShort("Fire", (short) -1);
        player.putShort("Air", (short) 300);
        player.putBoolean("OnGround", true);
        player.putFloat("Health", 20f);
        player.putInt("foodLevel", 20);
        player.putFloat("foodSaturationLevel", 5f);
        player.putFloat("foodExhaustionLevel", 0f);
        player.putInt("Score", 0);
        player.putInt("SelectedItemSlot", 0);
        player.putInt("playerGameType", 0);
        player.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());

        return player;
    }

    public static CompoundTag buildPlayerTag(LocalPlayer localPlayer, ClientLevel level, boolean includeInventory) {
        CompoundTag tag = buildPlayerTag(
                localPlayer.getX(), localPlayer.getY(), localPlayer.getZ(),
                localPlayer.getYRot(), localPlayer.getXRot(),
                level.dimension().identifier().toString()
        );
        if (includeInventory) {
            try {
                TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                localPlayer.saveWithoutId(output);
                CompoundTag dumped = output.buildResult();
                Tag inv = dumped.get("Inventory");
                if (inv != null) {
                    tag.put("Inventory", inv.copy());
                }
                Tag equip = dumped.get("equipment");
                if (equip != null) {
                    tag.put("equipment", equip.copy());
                }
                Tag sel = dumped.get("SelectedItemSlot");
                if (sel != null) {
                    tag.put("SelectedItemSlot", sel.copy());
                }
            } catch (Throwable t) {
                System.err.println("[ChunkVault] Failed to serialize player inventory: " + t);
            }
        }
        return tag;
    }

    public static void writeToWorld(Path worldDir, UUID uuid, CompoundTag playerTag) {
        Path file = worldDir.resolve("players").resolve("data").resolve(uuid + ".dat");
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream fos = Files.newOutputStream(tmp);
                 GZIPOutputStream gz = new GZIPOutputStream(fos);
                 DataOutputStream dos = new DataOutputStream(gz)) {
                NbtIo.write(playerTag, dos);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to write player data to " + file + ": " + e);
        }
    }
}
