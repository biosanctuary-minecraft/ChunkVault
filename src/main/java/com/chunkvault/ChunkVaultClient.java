package com.chunkvault;

import com.chunkvault.state.RecordingState;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

// CHECK fixed: ClientCommandManager was renamed to ClientCommands in 26.2
// (per Fabric's own porting guide).
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class ChunkVaultClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openMapNextTick) {
                openMapNextTick = false;
                Minecraft.getInstance().setScreenAndShow(new com.chunkvault.gui.MapScreen());
            }

            // While recording, sample entities (mobs, dropped items, frames...) every 2 seconds.
            if (++tickCounter % 40 == 0 && RecordingState.get().isActive()) {
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
                        .then(literal("fixworld")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> fixWorld(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                )
        );
    }

    /**
     * Rewrites data/minecraft/world_gen_settings.dat inside an ALREADY
     * recorded world (in saves/<name> and/or chunkvault/<name>), so worlds
     * recorded with the earlier broken file format open without re-recording.
     * Needs to be run while connected to any server/world (a live level is
     * required to build the void dimension config from the game's own classes).
     */
    private int fixWorld(FabricClientCommandSource source, String name) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            source.sendFeedback(Component.literal(
                    "§c[ChunkVault] Нужно быть подключённым к серверу/миру, чтобы собрать настройки. Зайди на сервер и повтори."));
            return 0;
        }
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path inSaves = gameDir.resolve("saves").resolve(name);
        Path inVault = gameDir.resolve("chunkvault").resolve(name);

        int fixed = 0;
        try {
            var settings = com.chunkvault.save.VoidWorldGenBuilder.build(level);
            for (Path target : new Path[]{inSaves, inVault}) {
                if (java.nio.file.Files.isDirectory(target)) {
                    com.chunkvault.save.VoidWorldGenBuilder.writeToWorld(target, settings);
                    fixed++;
                    source.sendFeedback(Component.literal("§a[ChunkVault] Исправлено: " + target));
                }
            }
        } catch (Exception e) {
            source.sendFeedback(Component.literal("§c[ChunkVault] Не удалось собрать настройки: " + e));
            return 0;
        }
        if (fixed == 0) {
            source.sendFeedback(Component.literal(
                    "§c[ChunkVault] Не нашёл папку мира '" + name + "' ни в saves/, ни в chunkvault/."));
            return 0;
        }
        return 1;
    }

    // The command runs from inside ChatScreen's key handler, and ChatScreen
    // calls setScreen(null) on itself right AFTER the command returns — which
    // instantly closed any screen we opened from the command (the map
    // "flashed and vanished"). So the command only raises this flag, and the
    // screen is opened from the next client tick, once chat is already gone.
    private static volatile boolean openMapNextTick = false;
    private static int tickCounter = 0;

    private int map(FabricClientCommandSource source) {
        openMapNextTick = true;
        return 1;
    }

    private int start(FabricClientCommandSource source, String name) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        boolean started = RecordingState.get().start(gameDir, name);
        if (started) {
            var level = Minecraft.getInstance().level;
            if (level != null) {
                try {
                    var worldGen = com.chunkvault.save.VoidWorldGenBuilder.build(level);
                    com.chunkvault.save.VoidWorldGenBuilder.writeToWorld(
                            RecordingState.get().saveManager().rootDir(), worldGen);
                } catch (Exception e) {
                    System.err.println("[ChunkVault] could not build void world-gen settings, " +
                            "will fall back to copying from an existing world at stop time: " + e);
                }
            }
            int snapshotted = snapshotAlreadyLoadedChunks();
            source.sendFeedback(Component.literal(
                    "§a[ChunkVault] Запись начата: " + name +
                    " — ходите по миру и открывайте сундуки, всё будет сохраняться. " +
                    "(сразу захвачено " + snapshotted + " уже прогруженных чанков вокруг тебя)"));
        } else {
            source.sendFeedback(Component.literal(
                    "§c[ChunkVault] Запись уже идёт (" + RecordingState.get().sessionName() +
                    "). Сначала выполните /chunkvault stop."));
        }
        return 1;
    }

    /**
     * Fix for the "donut hole" bug: the chunk-packet mixin only ever sees
     * chunks that arrive AFTER recording starts. Any chunk the client
     * already had loaded before /chunkvault start (which, in practice, is
     * everywhere immediately around the player — they were already
     * standing there) never triggers a new packet and so was silently
     * never captured, leaving exactly the area the player cares about
     * empty. This walks every chunk position the client currently has
     * loaded within render distance and saves it immediately, on top of
     * whatever new packets arrive from here on.
     */
    private int snapshotAlreadyLoadedChunks() {
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
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                if (level.hasChunk(cx, cz)) {
                    var chunk = level.getChunk(cx, cz);
                    if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk) {
                        manager.saveChunk(level, levelChunk);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int stop(FabricClientCommandSource source) {
        if (!RecordingState.get().isActive()) {
            source.sendFeedback(Component.literal("§c[ChunkVault] Запись не активна."));
            return 0;
        }
        var manager = RecordingState.get().saveManager();
        var player = Minecraft.getInstance().player;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            manager.snapshotEntities(level); // last look at the entities before writing them out
        }
        int chunks = manager.chunksSaved();
        int chests = manager.chestsSeen();
        int entities = manager.entitiesSeen();

        java.util.UUID uuid = null;
        net.minecraft.nbt.CompoundTag playerTag = null;
        // level.dat's SpawnX/Y/Z only ever addresses the Overworld anyway,
        // so the real fix for "spawn where I stopped, in whatever dimension"
        // is writing actual player-position data (see PlayerDataWriter) —
        // this works for every dimension, not just the Overworld.
        boolean inOverworld = level != null && level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD);
        var spawnPos = (player != null && inOverworld) ? player.blockPosition() : null;

        if (player != null && level != null) {
            uuid = Minecraft.getInstance().getUser().getProfileId();
            playerTag = com.chunkvault.save.PlayerDataWriter.buildPlayerTag(
                    uuid, player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot(),
                    level.dimension().identifier().toString());
        }

        Path dir = RecordingState.get().stop(spawnPos, uuid, playerTag);
        source.sendFeedback(Component.literal(
                "§a[ChunkVault] Готово: " + chunks + " чанков, " + chests + " контейнеров, "
                + entities + " сущностей. Сохранено в: " + dir));
        source.sendFeedback(Component.literal(
                "§b[ChunkVault] При заходе в этот мир ты должен оказаться там же, где остановил запись."));
        return 1;
    }

    private int status(FabricClientCommandSource source) {
        if (!RecordingState.get().isActive()) {
            source.sendFeedback(Component.literal("§7[ChunkVault] Не активно."));
            return 0;
        }
        var manager = RecordingState.get().saveManager();
        source.sendFeedback(Component.literal(
                "§b[ChunkVault] Идёт запись \"" + RecordingState.get().sessionName() + "\": " +
                manager.chunksSaved() + " чанков, " + manager.chestsSeen() + " контейнеров, "
                + manager.entitiesSeen() + " сущностей."));
        return 1;
    }
}
