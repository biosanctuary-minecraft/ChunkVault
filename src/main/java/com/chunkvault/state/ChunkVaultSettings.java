package com.chunkvault.state;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Properties;

public final class ChunkVaultSettings {

    public enum ContainerCategory {
        CHESTS("chests"),
        SHULKERS("shulkers"),
        HOPPERS("hoppers"),
        DISPENSERS("dispensers"),
        FURNACES("furnaces"),
        BREWING("brewing"),
        CRAFTERS("crafters");

        private final String key;

        ContainerCategory(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static final ChunkVaultSettings INSTANCE = new ChunkVaultSettings();

    private volatile boolean saveEntities = true;
    private volatile boolean saveContainers = true;
    private volatile boolean savePlayerInventory = true;
    private volatile boolean preventMobSpawning = true;
    private volatile boolean useSeed = false;
    private volatile long seed = 0L;
    private final EnumSet<ContainerCategory> enabledContainers = EnumSet.allOf(ContainerCategory.class);

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

    public boolean saveContainers() {
        return saveContainers;
    }

    public void setSaveContainers(boolean value) {
        this.saveContainers = value;
        save();
    }

    public boolean savePlayerInventory() {
        return savePlayerInventory;
    }

    public void setSavePlayerInventory(boolean value) {
        this.savePlayerInventory = value;
        save();
    }

    public synchronized boolean isContainerEnabled(ContainerCategory category) {
        return enabledContainers.contains(category);
    }

    public synchronized void setContainerEnabled(ContainerCategory category, boolean enabled) {
        if (enabled) {
            enabledContainers.add(category);
        } else {
            enabledContainers.remove(category);
        }
        save();
    }

    public synchronized void setAllContainers(boolean enabled) {
        if (enabled) {
            enabledContainers.addAll(EnumSet.allOf(ContainerCategory.class));
        } else {
            enabledContainers.clear();
        }
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

    private synchronized void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
            saveEntities = Boolean.parseBoolean(p.getProperty("saveEntities", "true"));
            saveContainers = Boolean.parseBoolean(p.getProperty("saveContainers", "true"));
            savePlayerInventory = Boolean.parseBoolean(p.getProperty("savePlayerInventory", "true"));
            preventMobSpawning = Boolean.parseBoolean(p.getProperty("preventMobSpawning", "true"));
            useSeed = Boolean.parseBoolean(p.getProperty("useSeed", "false"));
            seed = Long.parseLong(p.getProperty("seed", "0"));
            enabledContainers.clear();
            for (ContainerCategory c : ContainerCategory.values()) {
                if (Boolean.parseBoolean(p.getProperty("container." + c.key(), "true"))) {
                    enabledContainers.add(c);
                }
            }
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to read settings, using defaults: " + e);
        }
    }

    private synchronized void save() {
        Properties p = new Properties();
        p.setProperty("saveEntities", Boolean.toString(saveEntities));
        p.setProperty("saveContainers", Boolean.toString(saveContainers));
        p.setProperty("savePlayerInventory", Boolean.toString(savePlayerInventory));
        p.setProperty("preventMobSpawning", Boolean.toString(preventMobSpawning));
        p.setProperty("useSeed", Boolean.toString(useSeed));
        p.setProperty("seed", Long.toString(seed));
        for (ContainerCategory c : ContainerCategory.values()) {
            p.setProperty("container." + c.key(), Boolean.toString(enabledContainers.contains(c)));
        }
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                p.store(out, "ChunkVault settings");
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[ChunkVault] Failed to save settings: " + e);
        }
    }
}
