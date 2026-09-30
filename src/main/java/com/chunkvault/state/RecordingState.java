package com.chunkvault.state;

import com.chunkvault.save.WorldSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single global flag + handle to the currently active save session.
 * Deliberately simple (no DI framework) — this is a small client-only mod.
 */
public final class RecordingState {

    private static final RecordingState INSTANCE = new RecordingState();

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile WorldSaveManager saveManager;
    private volatile String sessionName;
    private volatile String lastSessionName;

    private RecordingState() {}

    public static RecordingState get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active.get();
    }

    public String sessionName() {
        return sessionName;
    }

    /** Name of the most recent recording, kept after /chunkvault stop so the map can still show it. */
    public String lastSessionName() {
        return lastSessionName;
    }

    public WorldSaveManager saveManager() {
        return saveManager;
    }

    /**
     * @param gameDir the client's run directory (from FabricLoader.getInstance().getGameDir())
     */
    public synchronized boolean start(Path gameDir, String name) {
        if (active.get()) {
            return false;
        }
        Path target = gameDir.resolve("chunkvault").resolve(sanitize(name));
        this.saveManager = new WorldSaveManager(target, gameDir, name);
        this.saveManager.init();
        this.sessionName = name;
        this.lastSessionName = name;
        active.set(true);
        return true;
    }

    public synchronized Path stop(BlockPos spawnPos, UUID uuid, CompoundTag playerTag) {
        if (!active.get()) {
            return null;
        }
        active.set(false);
        Path dir = saveManager.rootDir();
        saveManager.closeAndFinish(spawnPos, uuid, playerTag);
        saveManager = null;
        sessionName = null;
        return dir;
    }

    /**
     * Folder name for a recording. Letters and digits of any language are kept
     * (so "мой мир" and "лес" are different folders, not the same "___"),
     * everything else becomes "_".
     */
    public static String sanitize(String name) {
        String cleaned = name.trim().replaceAll("[^\\p{L}\\p{N}_\\-]", "_");
        return cleaned.isBlank() ? "world" : cleaned;
    }

    // Convenience for tests / callers that just want a path under the default game dir.
    public static Path defaultGameDir() {
        return Paths.get(".");
    }
}
