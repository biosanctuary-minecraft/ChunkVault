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
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("chunkvault")
                        .then(literal("start")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                        .then(literal("stop").executes(ctx -> stop(ctx.getSource())))
                        .then(literal("status").executes(ctx -> status(ctx.getSource())))
                )
        );
    }

    private int start(FabricClientCommandSource source, String name) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        boolean started = RecordingState.get().start(gameDir, name);
        if (started) {
            source.sendFeedback(Component.literal(
                    "§a[ChunkVault] Запись начата: " + name +
                    " — ходите по миру и открывайте сундуки, всё будет сохраняться."));
        } else {
            source.sendFeedback(Component.literal(
                    "§c[ChunkVault] Запись уже идёт (" + RecordingState.get().sessionName() +
                    "). Сначала выполните /chunkvault stop."));
        }
        return 1;
    }

    private int stop(FabricClientCommandSource source) {
        if (!RecordingState.get().isActive()) {
            source.sendFeedback(Component.literal("§c[ChunkVault] Запись не активна."));
            return 0;
        }
        var manager = RecordingState.get().saveManager();
        int chunks = manager.chunksSaved();
        int chests = manager.chestsSeen();

        var player = Minecraft.getInstance().player;
        var spawnPos = player != null ? player.blockPosition() : null;

        Path dir = RecordingState.get().stop(spawnPos);
        source.sendFeedback(Component.literal(
                "§a[ChunkVault] Готово: " + chunks + " чанков, " + chests + " контейнеров. Сохранено в: " + dir));
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
                manager.chunksSaved() + " чанков, " + manager.chestsSeen() + " контейнеров."));
        return 1;
    }
}
