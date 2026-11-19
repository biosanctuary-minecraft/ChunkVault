package com.chunkvault.state;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ChunkVaultSettings {

    private static final ChunkVaultSettings INSTANCE = new ChunkVaultSettings();

    private volatile boolean saveEntities = true;
    private volatile boolean preventMobSpawning = true;
    private volatile boolean useSeed = false;
    private volatile long seed = 0L;

    private ChunkVaultSettings() {
        load();
    }

    public static ChunkVaultSettings get() {
        return INSTANCE;
    }

    public boolean saveEntities() {
        return saveEntities;
    }

    public void setSaveEntities(boolean value) {
        this.saveEntities = value;
        save();
    }

    public boolean preventMobSpawning() {
        return preventMobSpawning;
    }

    public void setPreventMobSpawning(boolean value) {
        this.preventMobSpawning = value;
        save();
    }

    public boolean useSeed() {
        return useSeed;
    }

    public void setUseSeed(boolean value) {
        this.useSeed = value;
        save();
    }

    public long seed() {
        return seed;
    }

    public void setSeed(long value) {
        this.seed = value;
        save();
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("chunkvault.properties");
    }

    private void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
            saveEntities = Boolean.parseBoolean(p.getProperty("saveEntities", "true"));
            preventMobSpawning = Boolean.parseBoolean(p.getProperty("preventMobSpawning", "true"));
            useSeed = Boolean.parseBoolean(p.getProperty("useSeed", "false"));
            seed = Long.parseLong(p.getProperty("seed", "0"));
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to read settings, using defaults: " + e);
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("saveEntities", Boolean.toString(saveEntities));
        p.setProperty("preventMobSpawning", Boolean.toString(preventMobSpawning));
        p.setProperty("useSeed", Boolean.toString(useSeed));
        p.setProperty("seed", Long.toString(seed));
        try {
            Files.createDirectories(file().getParent());
            try (var out = Files.newOutputStream(file())) {
                p.store(out, "ChunkVault settings");
            }
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to save settings: " + e);
        }
    }
}
