package com.chunkvault.state;

import com.chunkvault.save.ContainerContentReader;
import com.chunkvault.save.ContainerDatabase;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MapPinFilter {

    public record ShulkerColor(String id, int rgb, String langKey) {}

    public static final List<ShulkerColor> SHULKER_COLORS = List.of(
            new ShulkerColor("default", 0xFF976797, "map.filter.color.default"),
            new ShulkerColor("white", 0xFFF9FFFE, "map.filter.color.white"),
            new ShulkerColor("light_gray", 0xFF9D9D97, "map.filter.color.light_gray"),
            new ShulkerColor("gray", 0xFF474F52, "map.filter.color.gray"),
            new ShulkerColor("black", 0xFF1D1D21, "map.filter.color.black"),
            new ShulkerColor("brown", 0xFF835432, "map.filter.color.brown"),
            new ShulkerColor("red", 0xFFB02E26, "map.filter.color.red"),
            new ShulkerColor("orange", 0xFFF9801D, "map.filter.color.orange"),
            new ShulkerColor("yellow", 0xFFFED83D, "map.filter.color.yellow"),
            new ShulkerColor("lime", 0xFF80C71F, "map.filter.color.lime"),
            new ShulkerColor("green", 0xFF5E7C16, "map.filter.color.green"),
            new ShulkerColor("cyan", 0xFF169C9C, "map.filter.color.cyan"),
            new ShulkerColor("light_blue", 0xFF3AB3DA, "map.filter.color.light_blue"),
            new ShulkerColor("blue", 0xFF3C44AA, "map.filter.color.blue"),
            new ShulkerColor("purple", 0xFF8932B8, "map.filter.color.purple"),
            new ShulkerColor("magenta", 0xFFC74EBD, "map.filter.color.magenta"),
            new ShulkerColor("pink", 0xFFF38BAA, "map.filter.color.pink")
    );

    private static final Map<String, Integer> COLOR_RGB_BY_ID = new LinkedHashMap<>();

    static {
        for (ShulkerColor c : SHULKER_COLORS) {
            COLOR_RGB_BY_ID.put(c.id(), c.rgb());
        }
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chunkvault-mapfilter-io");
        t.setDaemon(true);
        return t;
    });

    private static boolean showOpened = true;
    private static boolean chests = true;
    private static boolean barrels = true;
    private static boolean shulkers = true;
    private static boolean enderChests = true;
    private static boolean hoppers = true;
    private static boolean dispensers = true;
    private static boolean furnaces = true;
    private static boolean brewing = true;
    private static boolean crafters = true;
    private static final Set<String> enabledShulkerColors = new HashSet<>();
    private static String searchQuery = "";

    private static int cachedSearchRev = -1;
    private static String cachedSearchQuery = null;
    private static Set<String> cachedMatchedKeys = Set.of();

    static {
        for (ShulkerColor c : SHULKER_COLORS) {
            enabledShulkerColors.add(c.id());
        }
        loadPrefs();
    }

    private MapPinFilter() {}

    public static boolean showOpened() {
        return showOpened;
    }

    public static void toggleShowOpened() {
        synchronized (LOCK) {
            showOpened = !showOpened;
            saveAsyncLocked();
        }
    }

    public static boolean showUnopened() {
        return ContainerDatabase.showUnopened();
    }

    public static void toggleShowUnopened() {
        ContainerDatabase.toggleShowUnopened();
    }

    public static boolean chests() {
        return chests;
    }

    public static void toggleChests() {
        synchronized (LOCK) {
            chests = !chests;
            saveAsyncLocked();
        }
    }

    public static boolean barrels() {
        return barrels;
    }

    public static void toggleBarrels() {
        synchronized (LOCK) {
            barrels = !barrels;
            saveAsyncLocked();
        }
    }

    public static boolean shulkers() {
        return shulkers;
    }

    public static void toggleShulkers() {
        synchronized (LOCK) {
            shulkers = !shulkers;
            saveAsyncLocked();
        }
    }

    public static boolean enderChests() {
        return enderChests;
    }

    public static void toggleEnderChests() {
        synchronized (LOCK) {
            enderChests = !enderChests;
            saveAsyncLocked();
        }
    }

    public static boolean hoppers() {
        return hoppers;
    }

    public static void toggleHoppers() {
        synchronized (LOCK) {
            hoppers = !hoppers;
            saveAsyncLocked();
        }
    }

    public static boolean dispensers() {
        return dispensers;
    }

    public static void toggleDispensers() {
        synchronized (LOCK) {
            dispensers = !dispensers;
            saveAsyncLocked();
        }
    }

    public static boolean furnaces() {
        return furnaces;
    }

    public static void toggleFurnaces() {
        synchronized (LOCK) {
            furnaces = !furnaces;
            saveAsyncLocked();
        }
    }

    public static boolean brewing() {
        return brewing;
    }

    public static void toggleBrewing() {
        synchronized (LOCK) {
            brewing = !brewing;
            saveAsyncLocked();
        }
    }

    public static boolean crafters() {
        return crafters;
    }

    public static void toggleCrafters() {
        synchronized (LOCK) {
            crafters = !crafters;
            saveAsyncLocked();
        }
    }

    public static boolean isShulkerColorEnabled(String colorId) {
        synchronized (LOCK) {
            return colorId == null || enabledShulkerColors.contains(colorId);
        }
    }

    public static void toggleShulkerColor(String colorId) {
        if (colorId == null || !COLOR_RGB_BY_ID.containsKey(colorId)) {
            return;
        }
        synchronized (LOCK) {
            if (!enabledShulkerColors.remove(colorId)) {
                enabledShulkerColors.add(colorId);
            }
            saveAsyncLocked();
        }
    }

    public static void soloShulkerColor(String colorId) {
        if (colorId == null || !COLOR_RGB_BY_ID.containsKey(colorId)) {
            return;
        }
        synchronized (LOCK) {
            if (enabledShulkerColors.size() == 1 && enabledShulkerColors.contains(colorId)) {
                for (ShulkerColor c : SHULKER_COLORS) {
                    enabledShulkerColors.add(c.id());
                }
            } else {
                enabledShulkerColors.clear();
                enabledShulkerColors.add(colorId);
                shulkers = true;
            }
            saveAsyncLocked();
        }
    }

    public static void setAllShulkerColors(boolean enabled) {
        synchronized (LOCK) {
            enabledShulkerColors.clear();
            if (enabled) {
                for (ShulkerColor c : SHULKER_COLORS) {
                    enabledShulkerColors.add(c.id());
                }
            }
            saveAsyncLocked();
        }
    }

    public static String searchQuery() {
        return searchQuery;
    }

    public static void setSearchQuery(String query) {
        String next = query == null ? "" : query;
        synchronized (LOCK) {
            if (next.equals(searchQuery)) {
                return;
            }
            searchQuery = next;
            cachedSearchQuery = null;
            saveAsyncLocked();
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            showOpened = true;
            chests = true;
            barrels = true;
            shulkers = true;
            enderChests = true;
            hoppers = true;
            dispensers = true;
            furnaces = true;
            brewing = true;
            crafters = true;
            enabledShulkerColors.clear();
            for (ShulkerColor c : SHULKER_COLORS) {
                enabledShulkerColors.add(c.id());
            }
            searchQuery = "";
            cachedSearchQuery = null;
            cachedMatchedKeys = Set.of();
            saveAsyncLocked();
        }
        ContainerDatabase.setShowUnopened(true);
    }

    public static boolean isRestricting() {
        synchronized (LOCK) {
            if (!showOpened || !ContainerDatabase.showUnopened()) {
                return true;
            }
            if (!chests || !barrels || !shulkers || !enderChests || !hoppers || !dispensers || !furnaces || !brewing || !crafters) {
                return true;
            }
            if (enabledShulkerColors.size() < SHULKER_COLORS.size()) {
                return true;
            }
            return !searchQuery.trim().isEmpty();
        }
    }

    public static String shulkerColorId(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return null;
        }
        int colon = blockId.indexOf(':');
        String path = (colon >= 0 ? blockId.substring(colon + 1) : blockId).toLowerCase(Locale.ROOT);
        if (path.equals("shulker_box")) {
            return "default";
        }
        if (path.endsWith("_shulker_box")) {
            String color = path.substring(0, path.length() - "_shulker_box".length());
            return COLOR_RGB_BY_ID.containsKey(color) ? color : "default";
        }
        return null;
    }

    public static int shulkerColorRgb(String blockId) {
        String id = shulkerColorId(blockId);
        if (id == null) {
            return 0xFF976797;
        }
        return COLOR_RGB_BY_ID.getOrDefault(id, 0xFF976797);
    }

    public static boolean matches(ContainerDatabase.ContainerEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.opened()) {
            if (!showOpened) {
                return false;
            }
        } else {
            if (!ContainerDatabase.showUnopened()) {
                return false;
            }
        }

        String id = entry.blockId() == null ? "" : entry.blockId().toLowerCase(Locale.ROOT);
        if (id.endsWith("shulker_box")) {
            if (!shulkers) {
                return false;
            }
            String colorId = shulkerColorId(id);
            if (!isShulkerColorEnabled(colorId)) {
                return false;
            }
        } else if (id.endsWith(":ender_chest")) {
            if (!enderChests) {
                return false;
            }
        } else if (id.endsWith(":barrel")) {
            if (!barrels) {
                return false;
            }
        } else if (id.contains("chest")) {
            if (!chests) {
                return false;
            }
        } else if (id.endsWith(":hopper")) {
            if (!hoppers) {
                return false;
            }
        } else if (id.endsWith(":dispenser") || id.endsWith(":dropper")) {
            if (!dispensers) {
                return false;
            }
        } else if (id.contains("furnace") || id.endsWith(":smoker")) {
            if (!furnaces) {
                return false;
            }
        } else if (id.endsWith(":brewing_stand")) {
            if (!brewing) {
                return false;
            }
        } else if (id.endsWith(":crafter")) {
            if (!crafters) {
                return false;
            }
        }

        String q = searchQuery.trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) {
            Set<String> matched = matchedKeysFor(q);
            return matched.contains(entry.key());
        }
        return true;
    }

    private static Set<String> matchedKeysFor(String normalizedQuery) {
        int rev = ContainerDatabase.revision();
        synchronized (LOCK) {
            if (cachedSearchRev == rev && normalizedQuery.equals(cachedSearchQuery)) {
                return cachedMatchedKeys;
            }
        }
        var level = Minecraft.getInstance().level;
        var registries = level == null ? null : level.registryAccess();
        Set<String> out = new HashSet<>();
        for (ContainerDatabase.ContainerEntry e : ContainerDatabase.all()) {
            if (ContainerDatabase.isSecondaryHalf(e)) {
                continue;
            }
            boolean hit = false;
            String title = e.displayTitle().toLowerCase(Locale.ROOT);
            String blockId = e.blockId() == null ? "" : e.blockId().toLowerCase(Locale.ROOT);
            if (title.contains(normalizedQuery) || blockId.contains(normalizedQuery)) {
                hit = true;
            } else if (e.opened() && registries != null) {
                List<ItemStack> items = ContainerContentReader.collectAllItemsIncludingNested(e, registries);
                for (ItemStack stack : items) {
                    if (ContainerContentReader.itemSearchText(stack).contains(normalizedQuery)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                out.add(e.key());
                ContainerDatabase.ContainerEntry partner = ContainerDatabase.partnerOf(e);
                if (partner != null) {
                    out.add(partner.key());
                }
            }
        }
        synchronized (LOCK) {
            cachedSearchRev = rev;
            cachedSearchQuery = normalizedQuery;
            cachedMatchedKeys = out;
        }
        return out;
    }

    private static Path prefsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("chunkvault_mapfilter.properties");
    }

    private static void loadPrefs() {
        try {
            Path f = prefsFile();
            if (!Files.isRegularFile(f)) {
                return;
            }
            Properties p = new Properties();
            try (var in = Files.newInputStream(f)) {
                p.load(in);
            }
            showOpened = Boolean.parseBoolean(p.getProperty("showOpened", "true"));
            chests = Boolean.parseBoolean(p.getProperty("chests", "true"));
            barrels = Boolean.parseBoolean(p.getProperty("barrels", "true"));
            shulkers = Boolean.parseBoolean(p.getProperty("shulkers", "true"));
            enderChests = Boolean.parseBoolean(p.getProperty("enderChests", "true"));
            hoppers = Boolean.parseBoolean(p.getProperty("hoppers", "true"));
            dispensers = Boolean.parseBoolean(p.getProperty("dispensers", "true"));
            furnaces = Boolean.parseBoolean(p.getProperty("furnaces", "true"));
            brewing = Boolean.parseBoolean(p.getProperty("brewing", "true"));
            crafters = Boolean.parseBoolean(p.getProperty("crafters", "true"));
            searchQuery = p.getProperty("searchQuery", "");
            String disabled = p.getProperty("disabledShulkerColors", "");
            if (!disabled.isEmpty()) {
                for (String part : disabled.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        enabledShulkerColors.remove(trimmed);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveAsyncLocked() {
        Properties p = new Properties();
        p.setProperty("showOpened", Boolean.toString(showOpened));
        p.setProperty("chests", Boolean.toString(chests));
        p.setProperty("barrels", Boolean.toString(barrels));
        p.setProperty("shulkers", Boolean.toString(shulkers));
        p.setProperty("enderChests", Boolean.toString(enderChests));
        p.setProperty("hoppers", Boolean.toString(hoppers));
        p.setProperty("dispensers", Boolean.toString(dispensers));
        p.setProperty("furnaces", Boolean.toString(furnaces));
        p.setProperty("brewing", Boolean.toString(brewing));
        p.setProperty("crafters", Boolean.toString(crafters));
        p.setProperty("searchQuery", searchQuery);
        StringBuilder disabled = new StringBuilder();
        for (ShulkerColor c : SHULKER_COLORS) {
            if (!enabledShulkerColors.contains(c.id())) {
                if (!disabled.isEmpty()) {
                    disabled.append(',');
                }
                disabled.append(c.id());
            }
        }
        p.setProperty("disabledShulkerColors", disabled.toString());
        IO.execute(() -> writePrefs(p));
    }

    private static void writePrefs(Properties p) {
        try {
            Path f = prefsFile();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                p.store(out, "ChunkVault Map Pin Filter");
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
