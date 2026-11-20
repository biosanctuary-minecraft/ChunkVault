package com.chunkvault;

import com.chunkvault.gui.AreaHudOverlay;
import com.chunkvault.gui.AreaInputScreen;
import com.chunkvault.gui.ContainerDatabaseScreen;
import com.chunkvault.gui.MapCache;
import com.chunkvault.gui.MapScreen;
import com.chunkvault.gui.ProjectMap;
import com.chunkvault.gui.SettingsScreen;
import com.chunkvault.save.PlayerDataWriter;
import com.chunkvault.save.VoidWorldGenBuilder;
import com.chunkvault.state.AreaPlan;
import com.chunkvault.state.AutoRecord;
import com.chunkvault.state.LastInteraction;
import com.chunkvault.state.RecordingState;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class ChunkVaultClient implements ClientModInitializer {

    public record StopResult(int chunks, int chests, int entities, Path dir) {}

    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("chunkvault", "main"));

    private static KeyMapping openMapKey;
    private static volatile boolean openMapNextTick;
    private static volatile boolean openSettingsNextTick;
    private static volatile boolean openChestsNextTick;
    private static volatile boolean openAreaInputNextTick;
    private static int tickCounter;

    @Override
    public void onInitializeClient() {
        openMapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chunkvault.open_map",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_V,
                KEY_CATEGORY
        ));

        AreaHudOverlay.register();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LastInteraction.clear();
            if (!RecordingState.get().isActive()) {
                return;
            }
            if (client.isSameThread()) {
                stopRecording();
            } else {
                client.execute(ChunkVaultClient::stopRecording);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AutoRecord.tick();
            ProjectMap.tick();
            while (openMapKey.consumeClick()) {
                if (client.player != null) {
                    if (!openMapKey.isDefault() || InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_C)) {
                        client.setScreenAndShow(new MapScreen());
                    }
                }
            }
            if (openMapNextTick) {
                openMapNextTick = false;
                Minecraft.getInstance().setScreenAndShow(new MapScreen());
            }
            if (openSettingsNextTick) {
                openSettingsNextTick = false;
                Minecraft.getInstance().setScreenAndShow(new SettingsScreen());
            }
            if (openChestsNextTick) {
                openChestsNextTick = false;
                MapScreen mapScreen = new MapScreen();
                if (ProjectMap.isOpen()) {
                    Minecraft.getInstance().setScreenAndShow(new ContainerDatabaseScreen(mapScreen));
                } else {
                    Minecraft.getInstance().setScreenAndShow(mapScreen);
                }
            }
            if (openAreaInputNextTick) {
                openAreaInputNextTick = false;
                if (ProjectMap.isOpen()) {
                    Minecraft.getInstance().setScreenAndShow(new AreaInputScreen(null));
                }
            }

            tickCounter++;
            if (tickCounter % 10 == 0 && RecordingState.get().isActive()) {
                AreaPlan.checkAutoStop();
            }
            if (tickCounter % 40 == 0 && RecordingState.get().isActive()) {
                var manager = RecordingState.get().saveManager();
                var level = Minecraft.getInstance().level;
                if (manager != null && level != null) {
                    manager.snapshotEntities(level);
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("chunkvault")
                        .then(literal("start")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                        .then(literal("stop").executes(ctx -> stop(ctx.getSource())))
                        .then(literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(literal("map").executes(ctx -> map(ctx.getSource())))
                        .then(literal("chests").executes(ctx -> chests(ctx.getSource())))
                        .then(literal("settings").executes(ctx -> settings(ctx.getSource())))
                        .then(literal("area")
                                .then(literal("set")
                                        .executes(ctx -> areaSetGui(ctx.getSource()))
                                        .then(argument("x1", IntegerArgumentType.integer())
                                                .then(argument("z1", IntegerArgumentType.integer())
                                                        .then(argument("x2", IntegerArgumentType.integer())
                                                                .then(argument("z2", IntegerArgumentType.integer())
                                                                        .executes(ctx -> areaSet(ctx.getSource(),
                                                                                IntegerArgumentType.getInteger(ctx, "x1"),
                                                                                IntegerArgumentType.getInteger(ctx, "z1"),
                                                                                IntegerArgumentType.getInteger(ctx, "x2"),
                                                                                IntegerArgumentType.getInteger(ctx, "z2"))))))))
                                .then(literal("radius")
                                        .then(argument("blocks", IntegerArgumentType.integer(16, AreaPlan.WORLD_BORDER))
                                                .executes(ctx -> areaRadius(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "blocks")))))
                                .then(literal("clear").executes(ctx -> areaClear(ctx.getSource())))
                                .then(literal("hud").executes(ctx -> areaHud(ctx.getSource())))
                                .then(literal("autostop").executes(ctx -> areaAutoStop(ctx.getSource())))
                                .then(literal("clip").executes(ctx -> areaClip(ctx.getSource())))
                                .then(literal("trim").executes(ctx -> areaTrim(ctx.getSource()))))
                        .then(literal("fixworld")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> fixWorld(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                )
        );
    }

    public static int startRecording(String name) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        if (!RecordingState.get().start(gameDir, name)) {
            return -1;
        }
        ProjectMap.ensureOpen(RecordingState.sanitize(name));
        AreaPlan.resetAutoStopTrigger();
        var level = Minecraft.getInstance().level;
        if (level != null) {
            try {
                var worldGen = VoidWorldGenBuilder.build(level);
                VoidWorldGenBuilder.writeToWorld(RecordingState.get().saveManager().rootDir(), worldGen);
            } catch (Exception e) {
                System.err.println("[ChunkVault] Failed to build world_gen_settings.dat: " + e);
            }
        }
        return snapshotAlreadyLoadedChunks();
    }

    public static StopResult stopRecording() {
        if (!RecordingState.get().isActive()) {
            return null;
        }
        var manager = RecordingState.get().saveManager();
        if (manager == null) {
            return null;
        }
        var player = Minecraft.getInstance().player;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            snapshotAlreadyLoadedChunks();
            manager.snapshotEntities(level);
        }
        int chunks = manager.chunksSaved();
        int chests = manager.chestsSeen();
        int entities = manager.entitiesSeen();

        UUID uuid = null;
        CompoundTag playerTag = null;
        boolean inOverworld = level != null && level.dimension().equals(Level.OVERWORLD);
        var spawnPos = (player != null && inOverworld) ? player.blockPosition() : null;

        if (player != null && level != null) {
            uuid = Minecraft.getInstance().getUser().getProfileId();
            playerTag = PlayerDataWriter.buildPlayerTag(
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot(),
                    level.dimension().identifier().toString());
        }

        LastInteraction.clear();
        Path dir = RecordingState.get().stop(spawnPos, uuid, playerTag);
        ProjectMap.save();
        return new StopResult(chunks, chests, entities, dir);
    }

    private static int snapshotAlreadyLoadedChunks() {
        var level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        var manager = RecordingState.get().saveManager();
        if (level == null || player == null || manager == null) {
            return 0;
        }
        int centerX = player.blockPosition().getX() >> 4;
        int centerZ = player.blockPosition().getZ() >> 4;
        int radius = Minecraft.getInstance().options.renderDistance().get() + 2;
        int count = 0;
        String dimensionId = level.dimension().identifier().toString();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                var chunk = level.getChunk(cx, cz);
                if (chunk instanceof LevelChunk levelChunk && !(chunk instanceof EmptyLevelChunk)) {
                    if (manager.saveChunk(level, levelChunk)) {
                        MapCache.update(level, levelChunk);
                        ProjectMap.onChunkRecorded(dimensionId, cx, cz);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int areaSetGui(FabricClientCommandSource source) {
        if (!ProjectMap.isOpen()) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("db.no_project")));
            return 0;
        }
        openAreaInputNextTick = true;
        return 1;
    }

    private int areaSet(FabricClientCommandSource source, int x1, int z1, int x2, int z2) {
        if (!ProjectMap.isOpen()) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("db.no_project")));
            return 0;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return 0;
        }
        AreaPlan.setFromBlocks(level.dimension().identifier().toString(), x1, z1, x2, z2);
        source.sendFeedback(Component.literal("§a[ChunkVault] " + Lang.s("area.set_toast", AreaPlan.totalChunks())));
        return 1;
    }

    private int areaRadius(FabricClientCommandSource source, int radiusBlocks) {
        if (!ProjectMap.isOpen()) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("db.no_project")));
            return 0;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return 0;
        }
        AreaPlan.setRadiusBlocks(mc.level.dimension().identifier().toString(),
                mc.player.blockPosition().getX(), mc.player.blockPosition().getZ(), radiusBlocks);
        source.sendFeedback(Component.literal("§a[ChunkVault] " + Lang.s("area.set_toast", AreaPlan.totalChunks())));
        return 1;
    }

    private int areaClear(FabricClientCommandSource source) {
        AreaPlan.clearPlan();
        source.sendFeedback(Component.literal("§e[ChunkVault] " + Lang.s("area.cleared")));
        return 1;
    }

    private int areaHud(FabricClientCommandSource source) {
        boolean on = AreaPlan.toggleHud();
        source.sendFeedback(Component.literal("§b[ChunkVault] "
                + Lang.s("area.btn_hud", on ? Lang.s("common.on") : Lang.s("common.off"))));
        return 1;
    }

    private int areaAutoStop(FabricClientCommandSource source) {
        boolean on = AreaPlan.toggleAutoStop();
        source.sendFeedback(Component.literal("§b[ChunkVault] "
                + Lang.s("area.btn_autostop", on ? Lang.s("common.on") : Lang.s("common.off"))));
        return 1;
    }

    private int areaClip(FabricClientCommandSource source) {
        boolean on = AreaPlan.toggleClipToArea();
        source.sendFeedback(Component.literal("§b[ChunkVault] "
                + Lang.s("area.btn_clip", on ? Lang.s("common.on") : Lang.s("common.off"))));
        return 1;
    }

    private int areaTrim(FabricClientCommandSource source) {
        if (RecordingState.get().isActive()) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("toast.stop_first")));
            return 0;
        }
        if (!AreaPlan.isActive()) {
            source.sendFeedback(Component.literal("§e[ChunkVault] " + Lang.s("area.trim_empty")));
            return 0;
        }
        var res = AreaPlan.trimProjectToArea();
        if (res.removedChunks() == 0 && res.removedContainers() == 0) {
            source.sendFeedback(Component.literal("§e[ChunkVault] " + Lang.s("area.trim_empty")));
        } else {
            source.sendFeedback(Component.literal("§a[ChunkVault] "
                    + Lang.s("area.trimmed", res.removedChunks(), res.removedContainers())));
        }
        return 1;
    }

    private int fixWorld(FabricClientCommandSource source, String name) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("cmd.need_world")));
            return 0;
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path[] targets = {
                gameDir.resolve("saves").resolve(name),
                gameDir.resolve("chunkvault").resolve(name)
        };

        int fixed = 0;
        try {
            var settings = VoidWorldGenBuilder.build(level);
            for (Path target : targets) {
                if (Files.isDirectory(target)) {
                    VoidWorldGenBuilder.writeToWorld(target, settings);
                    fixed++;
                    source.sendFeedback(Component.literal("§a[ChunkVault] " + Lang.s("cmd.fixed", target.toString())));
                }
            }
        } catch (Exception e) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("cmd.fix_failed", String.valueOf(e))));
            return 0;
        }
        if (fixed == 0) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("cmd.world_not_found", name)));
            return 0;
        }
        return 1;
    }

    private int map(FabricClientCommandSource source) {
        openMapNextTick = true;
        return 1;
    }

    private int chests(FabricClientCommandSource source) {
        if (!ProjectMap.isOpen()) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("db.no_project")));
            return 0;
        }
        openChestsNextTick = true;
        return 1;
    }

    private int settings(FabricClientCommandSource source) {
        openSettingsNextTick = true;
        return 1;
    }

    private int start(FabricClientCommandSource source, String name) {
        int snapshotted = startRecording(name);
        if (snapshotted >= 0) {
            source.sendFeedback(Component.literal(
                    "§a[ChunkVault] " + Lang.s("cmd.started", name, snapshotted)));
        } else {
            source.sendFeedback(Component.literal(
                    "§c[ChunkVault] " + Lang.s("cmd.already", RecordingState.get().sessionName())));
        }
        return 1;
    }

    private int stop(FabricClientCommandSource source) {
        StopResult result = stopRecording();
        if (result == null) {
            source.sendFeedback(Component.literal("§c[ChunkVault] " + Lang.s("cmd.not_active")));
            return 0;
        }
        source.sendFeedback(Component.literal(
                "§a[ChunkVault] " + Lang.s("cmd.done", result.chunks(), result.chests(), result.entities(), result.dir().toString())));
        source.sendFeedback(Component.literal("§b[ChunkVault] " + Lang.s("cmd.hint_spawn")));
        return 1;
    }

    private int status(FabricClientCommandSource source) {
        if (!RecordingState.get().isActive()) {
            source.sendFeedback(Component.literal("§7[ChunkVault] " + Lang.s("cmd.inactive")));
            return 0;
        }
        var manager = RecordingState.get().saveManager();
        source.sendFeedback(Component.literal(
                "§b[ChunkVault] " + Lang.s("cmd.status",
                        RecordingState.get().sessionName(),
                        manager.chunksSaved(),
                        manager.chestsSeen(),
                        manager.entitiesSeen())));
        return 1;
    }
}
