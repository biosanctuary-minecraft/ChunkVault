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
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_PAD = 2;

    private record Entry(String name, String date) {}

    private final MapScreen parent;
    private final List<Entry> entries = new ArrayList<>();
    private int selected = -1;
    private int scroll;
    private int listBottom;
    private boolean draggingScrollbar;
    private double dragOffsetY;
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
        draggingScrollbar = false;
        scanFolders();
        if (selected >= entries.size()) {
            selected = -1;
        }
        int x = (this.width - W) / 2;
        listBottom = Math.max(LIST_TOP + ROW_H * 3, this.height - 140);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
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
                if (!dir.equals(src) && "chunkvault_map".equals(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if ("chunkvault_containers.dat".equals(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
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

    private int maxScroll() {
        return Math.max(0, entries.size() - visibleRows());
    }

    private boolean hasScrollbar() {
        return entries.size() > visibleRows();
    }

    private int scrollbarTrackTop() {
        return LIST_TOP + 2;
    }

    private int scrollbarTrackBottom() {
        return listBottom - 2;
    }

    private int scrollbarThumbHeight() {
        int trackH = Math.max(1, scrollbarTrackBottom() - scrollbarTrackTop());
        int rows = visibleRows();
        if (entries.isEmpty() || entries.size() <= rows) {
            return trackH;
        }
        int h = (int) Math.round((double) trackH * rows / entries.size());
        return Math.max(20, Math.min(h, trackH));
    }

    private int scrollbarThumbTop() {
        int trackTop = scrollbarTrackTop();
        int trackH = Math.max(1, scrollbarTrackBottom() - trackTop);
        int thumbH = scrollbarThumbHeight();
        int travel = trackH - thumbH;
        int max = maxScroll();
        if (travel <= 0 || max <= 0) {
            return trackTop;
        }
        return trackTop + (int) Math.round((double) travel * scroll / max);
    }

    private void scrollFromThumbTop(double thumbTop) {
        int trackTop = scrollbarTrackTop();
        int trackH = Math.max(1, scrollbarTrackBottom() - trackTop);
        int thumbH = scrollbarThumbHeight();
        int travel = trackH - thumbH;
        int max = maxScroll();
        if (travel <= 0 || max <= 0) {
            scroll = 0;
            return;
        }
        double ratio = Math.max(0.0, Math.min(1.0, (thumbTop - trackTop) / travel));
        scroll = (int) Math.round(ratio * max);
    }

    private String fit(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
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

        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        boolean hasScroll = hasScrollbar();

        int headerY = LIST_TOP - 14;
        String rangeText = hasScroll
                ? Lang.s("explorer.range", scroll + 1, Math.min(entries.size(), scroll + rows), entries.size())
                : "";
        int rangeW = rangeText.isEmpty() ? 0 : this.font.width(rangeText);
        int folderMaxW = rangeW > 0 ? W - rangeW - 12 : W;
        g.text(this.font, fit(Lang.s("explorer.folder", root().toString()), folderMaxW), x, headerY, 0xFF808090, true);
        if (rangeW > 0) {
            g.text(this.font, rangeText, x + W - rangeW, headerY, 0xFF808090, true);
        }

        g.fill(x - 1, LIST_TOP - 1, x + W + 1, listBottom + 1, 0xFF3A3A48);
        g.fill(x, LIST_TOP, x + W, listBottom, 0xFF14161C);
        if (entries.isEmpty()) {
            g.centeredText(this.font, Lang.s("explorer.empty"), this.width / 2, LIST_TOP + 16, 0xFF888899);
        }

        int contentRight = hasScroll ? x + W - SCROLLBAR_W - SCROLLBAR_PAD * 2 : x + W;
        int contentW = contentRight - x;
        for (int i = 0; i < rows && scroll + i < entries.size(); i++) {
            int idx = scroll + i;
            int ry = LIST_TOP + i * ROW_H;
            boolean hover = !draggingScrollbar
                    && mouseX >= x && mouseX < contentRight
                    && mouseY >= ry && mouseY < ry + ROW_H;
            int bg = idx == selected ? 0xFF8A6A1A : hover ? 0xFF262A34 : 0xFF1C1F27;
            g.fill(x, ry, contentRight, ry + ROW_H - 1, bg);
            Entry e = entries.get(idx);
            int dateW = this.font.width(e.date());
            g.text(this.font, fit(e.name(), contentW - dateW - 24), x + 8, ry + 6, 0xFFFFFFFF, true);
            g.text(this.font, e.date(), contentRight - dateW - 8, ry + 6, 0xFF9AA0B0, true);
        }

        if (hasScroll) {
            int trackX0 = x + W - SCROLLBAR_W - SCROLLBAR_PAD;
            int trackX1 = x + W - SCROLLBAR_PAD;
            int trackY0 = scrollbarTrackTop();
            int trackY1 = scrollbarTrackBottom();
            int thumbY0 = scrollbarThumbTop();
            int thumbY1 = thumbY0 + scrollbarThumbHeight();
            boolean thumbHover = mouseX >= trackX0 && mouseX < trackX1 && mouseY >= thumbY0 && mouseY < thumbY1;
            int thumbColor = draggingScrollbar ? 0xFF9AA4C0 : thumbHover ? 0xFF7A829C : 0xFF5A6074;
            g.fill(trackX0, trackY0, trackX1, trackY1, 0xFF0E1015);
            g.fill(trackX0, thumbY0, trackX1, thumbY1, thumbColor);
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
        boolean hasScroll = hasScrollbar();
        int contentRight = hasScroll ? x + W - SCROLLBAR_W - SCROLLBAR_PAD * 2 : x + W;

        if (hasScroll && event.button() == 0) {
            int trackX0 = x + W - SCROLLBAR_W - SCROLLBAR_PAD;
            int trackX1 = x + W - SCROLLBAR_PAD;
            int trackY0 = scrollbarTrackTop();
            int trackY1 = scrollbarTrackBottom();
            if (event.x() >= trackX0 && event.x() < trackX1 && event.y() >= trackY0 && event.y() < trackY1) {
                int thumbY0 = scrollbarThumbTop();
                int thumbH = scrollbarThumbHeight();
                if (event.y() >= thumbY0 && event.y() < thumbY0 + thumbH) {
                    dragOffsetY = event.y() - thumbY0;
                } else {
                    dragOffsetY = thumbH / 2.0;
                    scrollFromThumbTop(event.y() - dragOffsetY);
                }
                draggingScrollbar = true;
                return true;
            }
        }

        if (event.x() >= x && event.x() < contentRight && event.y() >= LIST_TOP && event.y() < listBottom) {
            int row = (int) ((event.y() - LIST_TOP) / ROW_H);
            int idx = scroll + row;
            if (row >= 0 && row < visibleRows() && idx >= 0 && idx < entries.size()) {
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
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar && event.button() == 0) {
            scrollFromThumbTop(event.y() - dragOffsetY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar && event.button() == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && !entries.isEmpty()) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
