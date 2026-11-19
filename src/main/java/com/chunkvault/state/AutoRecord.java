package com.chunkvault.state;

import com.chunkvault.ChunkVaultClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class AutoRecord {

    private static final Properties PROPS = new Properties();
    private static boolean loaded;

    private static Object lastConnection;
    private static int ticksSinceJoin;
    private static boolean handled = true;

    private AutoRecord() {}

    public static String serverKey() {
        ServerData data = Minecraft.getInstance().getCurrentServer();
        if (data == null) {
            return "singleplayer";
        }
        String ip = data.ip;
        return ip == null || ip.isBlank() ? "server" : ip.trim().toLowerCase(Locale.ROOT);
    }

    public static synchronized boolean isAuto(String key) {
        load();
        return Boolean.parseBoolean(PROPS.getProperty(key + ".auto", "false"));
    }

    public static synchronized void setAuto(String key, boolean on) {
        load();
        PROPS.setProperty(key + ".auto", Boolean.toString(on));
        save();
    }

    public static synchronized String worldFor(String key) {
        load();
        String w = PROPS.getProperty(key + ".world");
        return w == null || w.isBlank() ? RecordingState.sanitize(key) : w;
    }

    public static synchronized void setWorld(String key, String folder) {
        load();
        PROPS.setProperty(key + ".world", folder);
        save();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("chunkvault-servers.properties");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                PROPS.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.err.println("[ChunkVault] Failed to read " + f + ": " + e);
            }
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                PROPS.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "ChunkVault per-server auto-record");
            }
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to write server settings: " + e);
        }
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Object connection = mc.getConnection();
        if (connection == null || mc.level == null || mc.player == null) {
            lastConnection = null;
            handled = true;
            return;
        }
        if (connection != lastConnection) {
            lastConnection = connection;
            ticksSinceJoin = 0;
            handled = false;
        }
        if (handled || ++ticksSinceJoin < 40) {
            return;
        }
        handled = true;
        if (RecordingState.get().isActive()) {
            return;
        }
        String key = serverKey();
        if (isAuto(key)) {
            String world = worldFor(key);
            int captured = ChunkVaultClient.startRecording(world);
            if (captured >= 0) {
                System.out.println("[ChunkVault] Auto-record started for " + key + " -> " + world);
            }
        }
    }
}
