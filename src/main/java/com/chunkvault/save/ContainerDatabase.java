package com.chunkvault.save;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class ContainerDatabase {

    public static final String CAT_CHESTS = "chests";
    public static final String CAT_SHULKERS = "shulkers";
    public static final String CAT_FURNACES = "furnaces";
    public static final String CAT_OTHER = "other";

    public record ContainerEntry(
            String key,
            String dimension,
            int x,
            int y,
            int z,
            String blockId,
            String customName,
            int slotCount,
            int filledSlots,
            long updatedAt,
            String chestType,
            BlockPos connectedPos,
            boolean opened,
            CompoundTag blockEntityTag
    ) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public int chunkX() {
            return x >> 4;
        }

        public int chunkZ() {
            return z >> 4;
        }

        public boolean isDoubleChest() {
            return connectedPos != null && ("left".equals(chestType) || "right".equals(chestType));
        }

        public String category() {
            String id = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
            if (id.endsWith("shulker_box")) {
                return CAT_SHULKERS;
            }
            if (id.contains("chest") || id.endsWith(":barrel")) {
                return CAT_CHESTS;
            }
            if (id.contains("furnace") || id.endsWith(":smoker")) {
                return CAT_FURNACES;
            }
            return CAT_OTHER;
        }

        public String displayTitle() {
            if (customName != null && !customName.isBlank()) {
                return customName;
            }
            Identifier id = Identifier.tryParse(blockId);
            if (id != null) {
                var opt = BuiltInRegistries.BLOCK.getOptional(id);
                if (opt.isPresent()) {
                    Block block = opt.get();
                    return block.getName().getString();
                }
            }
            return blockId == null ? "" : blockId;
        }
    }

    private static final Map<String, ContainerEntry> ENTRIES = new ConcurrentHashMap<>();
    private static final Object INDEX_LOCK = new Object();
    private static volatile Map<String, Map<Long, List<ContainerEntry>>> byChunk = Map.of();
    private static volatile Map<String, Map<Long, List<ContainerEntry>>> byColumn = Map.of();
    private static volatile Path activeProjectDir;
    private static volatile boolean showUnopened = loadShowUnopenedPref();
    private static final AtomicInteger REVISION = new AtomicInteger();

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chunkvault-containers-io");
        t.setDaemon(true);
        return t;
    });

    private ContainerDatabase() {}

    public static int revision() {
        return REVISION.get();
    }

    public static int totalCount() {
        return ENTRIES.size();
    }

    public static boolean showUnopened() {
        return showUnopened;
    }

    public static boolean toggleShowUnopened() {
        showUnopened = !showUnopened;
        saveShowUnopenedPref(showUnopened);
        REVISION.incrementAndGet();
        return showUnopened;
    }

    private static Path prefsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("chunkvault_containers.properties");
    }

    private static boolean loadShowUnopenedPref() {
        try {
            Path f = prefsFile();
            if (!Files.isRegularFile(f)) {
                return true;
            }
            Properties p = new Properties();
            try (var in = Files.newInputStream(f)) {
                p.load(in);
            }
            return Boolean.parseBoolean(p.getProperty("showUnopened", "true"));
        } catch (Exception ignored) {
            return true;
        }
    }

    private static void saveShowUnopenedPref(boolean value) {
        IO.execute(() -> {
            try {
                Path f = prefsFile();
                Files.createDirectories(f.getParent());
                Properties p = new Properties();
                p.setProperty("showUnopened", Boolean.toString(value));
                try (var out = Files.newOutputStream(f)) {
                    p.store(out, "ChunkVault Container Preferences");
                }
            } catch (Exception ignored) {
            }
        });
    }

    public static String keyOf(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "|" + y + "|" + z;
    }

    private static long packCoords(int a, int b) {
        return (((long) a) << 32) | (b & 0xFFFFFFFFL);
    }

    private static String blockIdOf(BlockEntity be) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()));
    }

    public static void loadForProject(Path projectDir) {
        synchronized (INDEX_LOCK) {
            ENTRIES.clear();
            activeProjectDir = projectDir;
            readFile(projectDir.resolve("chunkvault_containers.dat"));
            rebuildIndexesLocked();
            REVISION.incrementAndGet();
        }
    }

    public static void ensureLoaded(Path projectDir) {
        if (projectDir != null && !projectDir.equals(activeProjectDir)) {
            loadForProject(projectDir);
        }
    }

    public static void clear() {
        synchronized (INDEX_LOCK) {
            ENTRIES.clear();
            activeProjectDir = null;
            byChunk = Map.of();
            byColumn = Map.of();
            REVISION.incrementAndGet();
        }
    }

    public static void put(ClientLevel level, BlockEntity be) {
        try {
            ContainerEntry entry = buildEntry(level, be, true);
            if (entry == null) {
                return;
            }
            Path targetDir;
            synchronized (INDEX_LOCK) {
                ENTRIES.put(entry.key(), entry);
                rebuildIndexesLocked();
                REVISION.incrementAndGet();
                targetDir = activeProjectDir;
            }
            if (targetDir != null) {
                saveAsync(targetDir);
            }
        } catch (Throwable t) {
            System.err.println("[ChunkVault] Failed to store container at " + be.getBlockPos() + ": " + t);
        }
    }

    public static void putUnopenedBatch(ClientLevel level, Collection<BlockEntity> blockEntities) {
        if (blockEntities == null || blockEntities.isEmpty()) {
            return;
        }
        List<ContainerEntry> added = null;
        String dimension = level.dimension().identifier().toString();
        for (BlockEntity be : blockEntities) {
            if (!(be instanceof Container container) || container.getContainerSize() < 3) {
                continue;
            }
            BlockPos pos = be.getBlockPos();
            String key = keyOf(dimension, pos.getX(), pos.getY(), pos.getZ());
            if (ENTRIES.containsKey(key)) {
                continue;
            }
            try {
                ContainerEntry entry = buildEntry(level, be, false);
                if (entry != null) {
                    if (added == null) {
                        added = new ArrayList<>();
                    }
                    added.add(entry);
                }
            } catch (Throwable ignored) {
            }
        }
        if (added == null || added.isEmpty()) {
            return;
        }
        Path targetDir;
        boolean changed = false;
        synchronized (INDEX_LOCK) {
            for (ContainerEntry e : added) {
                if (!ENTRIES.containsKey(e.key())) {
                    ENTRIES.put(e.key(), e);
                    changed = true;
                }
            }
            if (changed) {
                rebuildIndexesLocked();
                REVISION.incrementAndGet();
            }
            targetDir = activeProjectDir;
        }
        if (changed && targetDir != null) {
            saveAsync(targetDir);
        }
    }

    private static ContainerEntry buildEntry(ClientLevel level, BlockEntity be, boolean opened) {
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        String dimension = level.dimension().identifier().toString();
        BlockPos pos = be.getBlockPos();
        BlockState state = be.getBlockState();
        String blockId = blockIdOf(be);

        String customName = "";
        if (be instanceof Nameable nameable && nameable.hasCustomName() && nameable.getCustomName() != null) {
            customName = nameable.getCustomName().getString();
        }
        if (customName.isEmpty()) {
            customName = extractCustomName(tag);
        }

        int slotCount;
        int filledSlots = 0;
        if (be instanceof Container container) {
            slotCount = container.getContainerSize();
            if (opened) {
                for (int i = 0; i < slotCount; i++) {
                    if (!container.getItem(i).isEmpty()) {
                        filledSlots++;
                    }
                }
            }
        } else {
            slotCount = defaultSlotCount(blockId);
            filledSlots = opened ? countItemsInTag(tag) : 0;
        }

        String chestType = "";
        BlockPos connectedPos = null;
        if (state.getBlock() instanceof ChestBlock) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            chestType = type.getSerializedName();
            if (type != ChestType.SINGLE) {
                connectedPos = pos.relative(ChestBlock.getConnectedDirection(state));
            }
        }

        String key = keyOf(dimension, pos.getX(), pos.getY(), pos.getZ());
        return new ContainerEntry(
                key, dimension, pos.getX(), pos.getY(), pos.getZ(),
                blockId, customName, slotCount, filledSlots,
                System.currentTimeMillis(), chestType, connectedPos, opened, tag
        );
    }

    public static CompoundTag overrideFor(ClientLevel level, BlockPos pos, BlockEntity be) {
        String key = keyOf(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
        ContainerEntry entry = ENTRIES.get(key);
        if (entry == null || !entry.opened() || !entry.blockId().equals(blockIdOf(be))) {
            return null;
        }
        return entry.blockEntityTag().copy();
    }

    public static ContainerEntry get(String key) {
        return key == null ? null : ENTRIES.get(key);
    }

    public static ContainerEntry partnerOf(ContainerEntry entry) {
        if (entry == null || entry.connectedPos() == null) {
            return null;
        }
        BlockPos cp = entry.connectedPos();
        return ENTRIES.get(keyOf(entry.dimension(), cp.getX(), cp.getY(), cp.getZ()));
    }

    public static boolean isSecondaryHalf(ContainerEntry entry) {
        ContainerEntry partner = partnerOf(entry);
        if (partner == null) {
            return false;
        }
        if ("left".equals(entry.chestType()) && "right".equals(partner.chestType())) {
            return true;
        }
        if ("right".equals(entry.chestType()) && "left".equals(partner.chestType())) {
            return false;
        }
        return entry.key().compareTo(partner.key()) > 0;
    }

    public static List<ContainerEntry> all() {
        return new ArrayList<>(ENTRIES.values());
    }

    public static List<ContainerEntry> inDimension(String dimension) {
        List<ContainerEntry> result = new ArrayList<>();
        for (ContainerEntry e : ENTRIES.values()) {
            if (e.dimension().equals(dimension)) {
                result.add(e);
            }
        }
        return result;
    }

    public static List<ContainerEntry> inChunkRange(String dimension, int minCx, int maxCx, int minCz, int maxCz) {
        Map<Long, List<ContainerEntry>> dimMap = byChunk.get(dimension);
        if (dimMap == null || dimMap.isEmpty()) {
            return List.of();
        }
        List<ContainerEntry> out = new ArrayList<>();
        long area = (long) (maxCx - minCx + 1) * (long) (maxCz - minCz + 1);
        if (area <= dimMap.size() * 2L) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    List<ContainerEntry> list = dimMap.get(packCoords(cx, cz));
                    if (list != null) {
                        out.addAll(list);
                    }
                }
            }
        } else {
            for (List<ContainerEntry> list : dimMap.values()) {
                ContainerEntry first = list.get(0);
                int cx = first.chunkX();
                int cz = first.chunkZ();
                if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) {
                    out.addAll(list);
                }
            }
        }
        return out;
    }

    public static List<ContainerEntry> atColumn(String dimension, int x, int z) {
        Map<Long, List<ContainerEntry>> dimMap = byColumn.get(dimension);
        if (dimMap == null) {
            return List.of();
        }
        List<ContainerEntry> list = dimMap.get(packCoords(x, z));
        return list == null ? List.of() : list;
    }

    public static int colorFor(ContainerEntry entry) {
        int base = switch (entry.category()) {
            case CAT_SHULKERS -> 0xFFC864FF;
            case CAT_FURNACES -> 0xFFB8C0CC;
            case CAT_OTHER -> 0xFF55CCFF;
            default -> 0xFFFFB830;
        };
        if (!entry.opened()) {
            int r = ((base >> 16) & 0xFF) * 6 / 10;
            int g = ((base >> 8) & 0xFF) * 6 / 10;
            int b = (base & 0xFF) * 6 / 10;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return base;
    }

    private static void rebuildIndexesLocked() {
        Map<String, Map<Long, List<ContainerEntry>>> chunks = new HashMap<>();
        Map<String, Map<Long, List<ContainerEntry>>> columns = new HashMap<>();

        for (ContainerEntry e : ENTRIES.values()) {
            chunks.computeIfAbsent(e.dimension(), k -> new HashMap<>())
                    .computeIfAbsent(packCoords(e.chunkX(), e.chunkZ()), k -> new ArrayList<>())
                    .add(e);
            columns.computeIfAbsent(e.dimension(), k -> new HashMap<>())
                    .computeIfAbsent(packCoords(e.x(), e.z()), k -> new ArrayList<>())
                    .add(e);
        }

        Comparator<ContainerEntry> byYDesc = Comparator.comparingInt(ContainerEntry::y).reversed();
        for (Map<Long, List<ContainerEntry>> dimCols : columns.values()) {
            for (List<ContainerEntry> list : dimCols.values()) {
                list.sort(byYDesc);
            }
        }

        byChunk = chunks;
        byColumn = columns;
    }

    private static void saveAsync(Path dir) {
        List<ContainerEntry> snapshot = new ArrayList<>(ENTRIES.values());
        IO.execute(() -> writeFile(dir.resolve("chunkvault_containers.dat"), snapshot));
    }

    private static void writeFile(Path file, List<ContainerEntry> entries) {
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            for (ContainerEntry e : entries) {
                CompoundTag tag = new CompoundTag();
                tag.putString("type", e.blockId());
                tag.put("data", e.blockEntityTag());
                if (e.customName() != null && !e.customName().isEmpty()) {
                    tag.putString("customName", e.customName());
                }
                tag.putInt("slotCount", e.slotCount());
                tag.putInt("filledSlots", e.filledSlots());
                tag.putLong("updatedAt", e.updatedAt());
                tag.putBoolean("opened", e.opened());
                if (e.chestType() != null && !e.chestType().isEmpty()) {
                    tag.putString("chestType", e.chestType());
                }
                if (e.connectedPos() != null) {
                    tag.putInt("connX", e.connectedPos().getX());
                    tag.putInt("connY", e.connectedPos().getY());
                    tag.putInt("connZ", e.connectedPos().getZ());
                }
                root.put(e.key(), tag);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(root, new DataOutputStream(bytes));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes.toByteArray());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to write chunkvault_containers.dat: " + e);
        }
    }

    private static void readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        long fallbackTime = 0L;
        try {
            fallbackTime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception ignored) {
        }
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            CompoundTag root = NbtIo.read(dis);
            for (String key : root.keySet()) {
                CompoundTag entryTag = root.getCompound(key).orElse(null);
                if (entryTag == null) {
                    continue;
                }
                String type = entryTag.getString("type").orElse("");
                CompoundTag data = entryTag.getCompound("data").orElse(null);
                if (data == null || type.isEmpty()) {
                    continue;
                }
                String[] parts = key.split("\\|");
                if (parts.length != 4) {
                    continue;
                }
                int x;
                int y;
                int z;
                try {
                    x = Integer.parseInt(parts[1]);
                    y = Integer.parseInt(parts[2]);
                    z = Integer.parseInt(parts[3]);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                String customName = entryTag.getString("customName").orElse("");
                if (customName.isEmpty()) {
                    customName = extractCustomName(data);
                }
                int slotCount = entryTag.getInt("slotCount").orElse(defaultSlotCount(type));
                int filledSlots = entryTag.getInt("filledSlots").orElse(countItemsInTag(data));
                long updatedAt = entryTag.getLong("updatedAt").orElse(fallbackTime);
                boolean opened = entryTag.getBoolean("opened").orElse(true);
                String chestType = entryTag.getString("chestType").orElse("");
                BlockPos connectedPos = null;
                if (entryTag.contains("connX") && entryTag.contains("connY") && entryTag.contains("connZ")) {
                    connectedPos = new BlockPos(
                            entryTag.getInt("connX").orElse(0),
                            entryTag.getInt("connY").orElse(0),
                            entryTag.getInt("connZ").orElse(0)
                    );
                }
                ENTRIES.put(key, new ContainerEntry(
                        key, parts[0], x, y, z, type, customName,
                        slotCount, filledSlots, updatedAt, chestType, connectedPos, opened, data
                ));
            }
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to read chunkvault_containers.dat: " + e);
        }
    }

    private static int defaultSlotCount(String blockId) {
        String id = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
        if (id.contains("furnace") || id.endsWith(":smoker")) {
            return 3;
        }
        if (id.endsWith(":hopper") || id.endsWith(":brewing_stand")) {
            return 5;
        }
        if (id.endsWith(":dispenser") || id.endsWith(":dropper") || id.endsWith(":crafter")) {
            return 9;
        }
        return 27;
    }

    private static int countItemsInTag(CompoundTag tag) {
        ListTag list = tag.getList("Items").orElse(null);
        return list == null ? 0 : list.size();
    }

    private static String extractCustomName(CompoundTag tag) {
        String s = tag.getString("CustomName").orElse("");
        if (!s.isEmpty()) {
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }
        CompoundTag c = tag.getCompound("CustomName").orElse(null);
        if (c != null) {
            return c.getString("text").orElse("");
        }
        return "";
    }
}
