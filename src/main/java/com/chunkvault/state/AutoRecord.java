package com.chunkvault.state;

import com.chunkvault.ChunkVaultClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Per-server auto-record: for every server (or "singleplayer") remembers
 * whether recording should start by itself after joining, and into which
 * world folder. Stored in config/chunkvault-servers.properties as
 * "<server>.auto=true" and "<server>.world=<folder>".
 */
public final class AutoRecord {

    private static final Properties PROPS = new Properties();
    private static boolean loaded;

    private static Object lastConnection;
    private static int ticksSinceJoin;
    private static boolean handled = true;

    private AutoRecord() {}

    // ------------------------------------------------------------------
    // which server are we on
    // ------------------------------------------------------------------

    /** Address of the current server, lower-cased; "singleplayer" for a local world. */
    public static String serverKey() {
        Object data = Minecraft.getInstance().getCurrentServer();
        if (data == null) {
            return "singleplayer";
        }
        String ip = null;
        try {
            Field f = data.getClass().getField("ip");
            Object v = f.get(data);
            ip = v == null ? null : v.toString();
        } catch (Throwable first) {
            try {
                Method m = data.getClass().getMethod("ip");
                Object v = m.invoke(data);
                ip = v == null ? null : v.toString();
            } catch (Throwable ignored) {
                // fall through to the generic key
            }
        }
        return ip == null || ip.isBlank() ? "server" : ip.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------

    public static synchronized boolean isAuto(String key) {
        load();
        return Boolean.parseBoolean(PROPS.getProperty(key + ".auto", "false"));
    }

    public static synchronized void setAuto(String key, boolean on) {
        load();
        PROPS.setProperty(key + ".auto", Boolean.toString(on));
        save();
    }

    /** World folder used for auto-record on this server (defaults to a name made from the address). */
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
                PROPS.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.err.println("[ChunkVault] could not read " + f + ": " + e);
            }
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            try (OutputStream out = Files.newOutputStream(f)) {
                PROPS.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8),
                        "ChunkVault: per-server auto-record");
            }
        } catch (Exception e) {
            System.err.println("[ChunkVault] could not write server settings: " + e);
        }
    }

    // ------------------------------------------------------------------
    // the actual auto start, driven by the client tick
    // ------------------------------------------------------------------

    /** Called every client tick: a couple of seconds after joining a server with auto-record on, starts recording. */
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
                System.out.println("[ChunkVault] auto-record started for " + key + " -> " + world);
            }
        }
    }
}
