package com.chunkvault.state;

import com.chunkvault.save.WorldSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecordingState {

    private static final RecordingState INSTANCE = new RecordingState();

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile WorldSaveManager saveManager;
    private volatile String sessionName;

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

    public WorldSaveManager saveManager() {
        return saveManager;
    }

    public synchronized boolean start(Path gameDir, String name) {
        if (active.get()) {
            return false;
        }
        Path target = gameDir.resolve("chunkvault").resolve(sanitize(name));
        this.saveManager = new WorldSaveManager(target, gameDir, name);
        this.saveManager.init();
        this.sessionName = name;
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

    public static String sanitize(String name) {
        String cleaned = name.trim().replaceAll("[^\\p{L}\\p{N}_\\-]", "_");
        return cleaned.isBlank() ? "world" : cleaned;
    }
}
