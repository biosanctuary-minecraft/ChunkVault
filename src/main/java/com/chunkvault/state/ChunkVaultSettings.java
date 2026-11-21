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

    public static final int MINIMAP_MIN_SIZE = 64;
    public static final int MINIMAP_MAX_SIZE = 260;
    public static final int MINIMAP_SIZE_STEP = 8;
    public static final double[] MINIMAP_ZOOMS = {0.25, 0.35, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0};

    private static final ChunkVaultSettings INSTANCE = new ChunkVaultSettings();

    private volatile boolean saveEntities = true;
    private volatile boolean saveContainers = true;
    private volatile boolean savePlayerInventory = true;
    private volatile boolean preventMobSpawning = true;
    private volatile boolean useSeed = false;
    private volatile long seed = 0L;
    private volatile boolean minimapEnabled = true;
    private volatile double minimapX = 0.0;
    private volatile double minimapY = 0.0;
    private volatile int minimapSize = 112;
    private volatile double minimapZoom = 0.5;
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

    public boolean minimapEnabled() {
        return minimapEnabled;
    }

    public void setMinimapEnabled(boolean value) {
        this.minimapEnabled = value;
        save();
    }

    public double minimapX() {
        return minimapX;
    }

    public double minimapY() {
        return minimapY;
    }

    public void setMinimapPosition(double rx, double ry) {
        this.minimapX = clamp01(rx);
        this.minimapY = clamp01(ry);
        save();
    }

    public int minimapSize() {
        return minimapSize;
    }

    public void setMinimapSize(int size) {
        this.minimapSize = clampSize(size);
        save();
    }

    public void stepMinimapSize(int dir) {
        setMinimapSize(minimapSize + dir * MINIMAP_SIZE_STEP);
    }

    public double minimapZoom() {
        return minimapZoom;
    }

    public void stepMinimapZoom(int dir) {
        int idx = 2;
        for (int i = 0; i < MINIMAP_ZOOMS.length; i++) {
            if (Math.abs(MINIMAP_ZOOMS[i] - minimapZoom) < 0.001) {
                idx = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(MINIMAP_ZOOMS.length - 1, idx + dir));
        minimapZoom = MINIMAP_ZOOMS[next];
        save();
    }

    public void resetMinimapLayout() {
        this.minimapX = 0.0;
        this.minimapY = 0.0;
        this.minimapSize = 112;
        this.minimapZoom = 0.5;
        save();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int clampSize(int v) {
        int clamped = Math.max(MINIMAP_MIN_SIZE, Math.min(MINIMAP_MAX_SIZE, v));
        int offset = clamped - MINIMAP_MIN_SIZE;
        return MINIMAP_MIN_SIZE + Math.round((float) offset / MINIMAP_SIZE_STEP) * MINIMAP_SIZE_STEP;
    }

    private static double snapZoom(double v) {
        double best = MINIMAP_ZOOMS[2];
        double bestDiff = Double.MAX_VALUE;
        for (double z : MINIMAP_ZOOMS) {
            double d = Math.abs(z - v);
            if (d < bestDiff) {
                bestDiff = d;
                best = z;
            }
        }
        return best;
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
            minimapEnabled = Boolean.parseBoolean(p.getProperty("minimapEnabled", "true"));
            if (p.containsKey("minimapX") && p.containsKey("minimapY")) {
                minimapX = clamp01(Double.parseDouble(p.getProperty("minimapX", "0.0")));
                minimapY = clamp01(Double.parseDouble(p.getProperty("minimapY", "0.0")));
            } else {
                String legacy = p.getProperty("minimapPosition", "top_left");
                if ("top_right".equals(legacy)) {
                    minimapX = 1.0;
                    minimapY = 0.0;
                } else if ("bottom_left".equals(legacy)) {
                    minimapX = 0.0;
                    minimapY = 1.0;
                } else if ("bottom_right".equals(legacy)) {
                    minimapX = 1.0;
                    minimapY = 1.0;
                } else {
                    minimapX = 0.0;
                    minimapY = 0.0;
                }
            }
            minimapSize = clampSize(Integer.parseInt(p.getProperty("minimapSize", "112")));
            minimapZoom = snapZoom(Double.parseDouble(p.getProperty("minimapZoom", "0.5")));
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
        p.setProperty("minimapEnabled", Boolean.toString(minimapEnabled));
        p.setProperty("minimapX", Double.toString(minimapX));
        p.setProperty("minimapY", Double.toString(minimapY));
        p.setProperty("minimapSize", Integer.toString(minimapSize));
        p.setProperty("minimapZoom", Double.toString(minimapZoom));
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
