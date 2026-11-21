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

    public enum MinimapPosition {
        TOP_LEFT("top_left", "settings.minimap.pos.top_left"),
        TOP_RIGHT("top_right", "settings.minimap.pos.top_right"),
        BOTTOM_LEFT("bottom_left", "settings.minimap.pos.bottom_left"),
        BOTTOM_RIGHT("bottom_right", "settings.minimap.pos.bottom_right");

        private final String key;
        private final String langKey;

        MinimapPosition(String key, String langKey) {
            this.key = key;
            this.langKey = langKey;
        }

        public String key() {
            return key;
        }

        public String langKey() {
            return langKey;
        }

        public MinimapPosition step(int dir) {
            MinimapPosition[] vals = values();
            return vals[(ordinal() + dir + vals.length) % vals.length];
        }

        public static MinimapPosition fromKey(String key) {
            if (key != null) {
                for (MinimapPosition p : values()) {
                    if (p.key.equals(key)) {
                        return p;
                    }
                }
            }
            return TOP_LEFT;
        }
    }

    public static final int[] MINIMAP_SIZES = {84, 112, 144, 180};
    public static final double[] MINIMAP_ZOOMS = {0.25, 0.5, 1.0, 1.5, 2.0};

    private static final ChunkVaultSettings INSTANCE = new ChunkVaultSettings();

    private volatile boolean saveEntities = true;
    private volatile boolean saveContainers = true;
    private volatile boolean savePlayerInventory = true;
    private volatile boolean preventMobSpawning = true;
    private volatile boolean useSeed = false;
    private volatile long seed = 0L;
    private volatile boolean minimapEnabled = true;
    private volatile MinimapPosition minimapPosition = MinimapPosition.TOP_LEFT;
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

    public MinimapPosition minimapPosition() {
        return minimapPosition;
    }

    public void cycleMinimapPosition(int dir) {
        this.minimapPosition = this.minimapPosition.step(dir);
        save();
    }

    public int minimapSize() {
        return minimapSize;
    }

    public void cycleMinimapSize(int dir) {
        int idx = 1;
        for (int i = 0; i < MINIMAP_SIZES.length; i++) {
            if (MINIMAP_SIZES[i] == minimapSize) {
                idx = i;
                break;
            }
        }
        minimapSize = MINIMAP_SIZES[(idx + dir + MINIMAP_SIZES.length) % MINIMAP_SIZES.length];
        save();
    }

    public double minimapZoom() {
        return minimapZoom;
    }

    public void cycleMinimapZoom(int dir) {
        int idx = 1;
        for (int i = 0; i < MINIMAP_ZOOMS.length; i++) {
            if (Math.abs(MINIMAP_ZOOMS[i] - minimapZoom) < 0.001) {
                idx = i;
                break;
            }
        }
        minimapZoom = MINIMAP_ZOOMS[(idx + dir + MINIMAP_ZOOMS.length) % MINIMAP_ZOOMS.length];
        save();
    }

    private static int snapSize(int v) {
        int best = MINIMAP_SIZES[1];
        int bestDiff = Integer.MAX_VALUE;
        for (int s : MINIMAP_SIZES) {
            int d = Math.abs(s - v);
            if (d < bestDiff) {
                bestDiff = d;
                best = s;
            }
        }
        return best;
    }

    private static double snapZoom(double v) {
        double best = MINIMAP_ZOOMS[1];
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
            minimapPosition = MinimapPosition.fromKey(p.getProperty("minimapPosition", "top_left"));
            minimapSize = snapSize(Integer.parseInt(p.getProperty("minimapSize", "112")));
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
        p.setProperty("minimapPosition", minimapPosition.key());
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
