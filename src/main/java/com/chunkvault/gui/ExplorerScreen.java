package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.RecordingState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class ExplorerScreen extends Screen {

    private static final int W = 360;
    private static final int ROW_H = 20;
    private static final int LIST_TOP = 50;

    private record Entry(String name, String date) {}

    private final MapScreen parent;
    private final List<Entry> entries = new ArrayList<>();
    private int selected = -1;
    private int scroll;
    private int listBottom;
    private String info = "";

    private volatile boolean copying;
    private volatile String status = "";
    private volatile boolean statusOk = true;

    public ExplorerScreen(MapScreen parent) {
        super(Lang.c("explorer.title"));
        this.parent = parent;
    }

    private static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    private static Path root() {
        return gameDir().resolve("chunkvault");
    }

    private void scanFolders() {
        entries.clear();
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (Exception e) {
            System.err.println("[ChunkVault] Failed to list " + root + ": " + e);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        dirs.sort(Comparator.comparingLong(ExplorerScreen::modified).reversed());
        for (Path dir : dirs) {
            entries.add(new Entry(dir.getFileName().toString(), fmt.format(new Date(modified(dir)))));
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    protected void init() {
        super.init();
        scanFolders();
        int x = (this.width - W) / 2;
        listBottom = Math.max(LIST_TOP + ROW_H * 3, this.height - 140);
        int y0 = listBottom + 30;
        int half = (W - 6) / 2;

        this.addRenderableWidget(Button.builder(Lang.c("explorer.open_project"), b -> openSelected())
                .bounds(x, y0, W, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("explorer.copy"), b -> copySelected())
                .bounds(x, y0 + 26, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("explorer.open_folder"), b -> openFolder())
                .bounds(x + half + 6, y0 + 26, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("common.back"), b -> this.onClose())
                .bounds(x, y0 + 52, W, 20)
                .build());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    private void say(String text, boolean ok) {
        status = text;
        statusOk = ok;
    }

    private void openSelected() {
        if (selected < 0 || selected >= entries.size()) {
            say(Lang.s("explorer.select_project"), false);
            return;
        }
        if (RecordingState.get().isActive()) {
            say(Lang.s("toast.stop_first"), false);
            return;
        }
        String name = entries.get(selected).name();
        ProjectMap.open(name);
        Minecraft.getInstance().setScreenAndShow(parent);
        parent.showToast(Lang.s("toast.project_opened", name));
    }

    private void openFolder() {
        Path dir = selected >= 0 && selected < entries.size() ? root().resolve(entries.get(selected).name()) : root();
        try {
            Files.createDirectories(dir);
            String path = dir.toAbsolutePath().toString();
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("explorer.exe", path);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", path);
            } else {
                pb = new ProcessBuilder("xdg-open", path);
            }
            pb.start();
            say(Lang.s("explorer.folder_opened"), true);
        } catch (Exception e) {
            say(Lang.s("explorer.folder_failed", String.valueOf(e.getMessage())), false);
        }
    }

    private void copySelected() {
        if (copying) {
            return;
        }
        if (selected < 0 || selected >= entries.size()) {
            say(Lang.s("explorer.select_world"), false);
            return;
        }
        String name = entries.get(selected).name();
        String active = RecordingState.get().sessionName();
        if (active != null && RecordingState.sanitize(active).equalsIgnoreCase(name)) {
            say(Lang.s("explorer.world_recording"), false);
            return;
        }
        Path src = root().resolve(name);
        Path savesDir = gameDir().resolve("saves");
        String dest = name;
        int n = 2;
        while (Files.exists(savesDir.resolve(dest))) {
            dest = name + "_" + n++;
        }
        final String destName = dest;
        copying = true;
        say(Lang.s("explorer.copying"), true);
        Thread t = new Thread(() -> {
            try {
                copyTree(src, savesDir.resolve(destName));
                say(Lang.s("explorer.copied", destName), true);
            } catch (Exception e) {
                say(Lang.s("explorer.copy_error", String.valueOf(e.getMessage())), false);
            } finally {
                copying = false;
            }
        }, "chunkvault-copy");
        t.setDaemon(true);
        t.start();
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String describe(Path dir) {
        List<String> dims = new ArrayList<>();
        int regions = 0;
        long bytes = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) {
                    bytes += Files.size(p);
                    if (p.getFileName().toString().endsWith(".mca")) {
                        regions++;
                        Path regionDir = p.getParent();
                        Path dimDir = regionDir == null ? null : regionDir.getParent();
                        if (dimDir != null && dimDir.getFileName() != null) {
                            String d = dimDir.getFileName().toString();
                            if (!dims.contains(d)) {
                                dims.add(d);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            return Lang.s("explorer.read_failed", String.valueOf(e.getMessage()));
        }
        String size = bytes >= 1024L * 1024L
                ? String.format(Locale.ROOT, "%.1f ", bytes / 1048576.0) + Lang.s("unit.mb")
                : (bytes / 1024) + " " + Lang.s("unit.kb");
        return Lang.s("explorer.info", dims.isEmpty() ? "—" : String.join(", ", dims), regions, size);
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - LIST_TOP) / ROW_H);
    }

    private String fit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && this.font.width(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);
        g.centeredText(this.font, this.title.getString(), this.width / 2, 14, 0xFFFFFFFF);
        int x = (this.width - W) / 2;
        g.text(this.font, Lang.s("explorer.folder", fit(root().toString(), W - 60)), x, 32, 0xFF808090, true);

        g.fill(x - 1, LIST_TOP - 1, x + W + 1, listBottom + 1, 0xFF3A3A48);
        g.fill(x, LIST_TOP, x + W, listBottom, 0xFF14161C);
        if (entries.isEmpty()) {
            g.centeredText(this.font, Lang.s("explorer.empty"), this.width / 2, LIST_TOP + 16, 0xFF888899);
        }
        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - rows)));
        for (int i = 0; i < rows && scroll + i < entries.size(); i++) {
            int idx = scroll + i;
            int ry = LIST_TOP + i * ROW_H;
            boolean hover = mouseX >= x && mouseX < x + W && mouseY >= ry && mouseY < ry + ROW_H;
            int bg = idx == selected ? 0xFF8A6A1A : hover ? 0xFF262A34 : 0xFF1C1F27;
            g.fill(x, ry, x + W, ry + ROW_H - 1, bg);
            Entry e = entries.get(idx);
            int dateW = this.font.width(e.date());
            g.text(this.font, fit(e.name(), W - dateW - 24), x + 8, ry + 6, 0xFFFFFFFF, true);
            g.text(this.font, e.date(), x + W - dateW - 8, ry + 6, 0xFF9AA0B0, true);
        }
        if (entries.size() > rows) {
            g.text(this.font, Lang.s("explorer.range", scroll + 1, Math.min(entries.size(), scroll + rows), entries.size()),
                    x + W - 80, LIST_TOP - 11, 0xFF808090, true);
        }

        g.text(this.font, fit(info, W), x, listBottom + 6, 0xFFAAAAAA, true);
        String st = status;
        if (!st.isEmpty()) {
            g.text(this.font, fit(st, W), x, listBottom + 18, statusOk ? 0xFF70FF90 : 0xFFFF7070, true);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        int x = (this.width - W) / 2;
        if (event.x() >= x && event.x() < x + W && event.y() >= LIST_TOP && event.y() < listBottom) {
            int idx = scroll + (int) ((event.y() - LIST_TOP) / ROW_H);
            if (idx >= 0 && idx < entries.size()) {
                boolean again = isDoubleClick && idx == selected;
                selected = idx;
                info = describe(root().resolve(entries.get(idx).name()));
                status = "";
                if (again) {
                    openSelected();
                }
            }
            return true;
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && !entries.isEmpty()) {
            scroll += scrollY > 0 ? -1 : 1;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
