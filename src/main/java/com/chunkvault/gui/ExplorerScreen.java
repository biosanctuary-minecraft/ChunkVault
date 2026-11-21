package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.chunkvault.state.RecordingState;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
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

    private static final int MAX_W = 460;
    private static final int ROW_H = 24;
    private static final int LIST_TOP = 50;
    private static final int SCROLLBAR_W = 8;
    private static final int SCROLLBAR_PAD = 2;

    private record Entry(String name, String date) {}

    private final MapScreen parent;
    private final long openedAtMs = System.currentTimeMillis();
    private final List<Entry> entries = new ArrayList<>();
    private int selected = -1;
    private int scroll;
    private int listBottom;
    private boolean draggingScrollbar;
    private double dragOffsetY;
    private String info = "";

    private volatile boolean copying;
    private volatile boolean deleting;
    private volatile String status = "";
    private volatile boolean statusOk = true;
    private long deleteConfirmUntil;
    private String deleteConfirmName = "";

    private int r1Y0;
    private int r1Y1;
    private int openX0;
    private int openX1;
    private int delX0;
    private int delX1;

    private int r2Y0;
    private int r2Y1;
    private int copyX0;
    private int copyX1;
    private int folderX0;
    private int folderX1;

    private int r3Y0;
    private int r3Y1;
    private int backX0;
    private int backX1;

    public ExplorerScreen(MapScreen parent) {
        super(Lang.c("explorer.title"));
        this.parent = parent;
    }

    private int panelWidth() {
        return Math.min(MAX_W, this.width - 32);
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

    private void updateButtonBounds() {
        int w = panelWidth();
        int x = (this.width - w) / 2;
        int y0 = listBottom + 32;
        int gap = 6;

        r1Y0 = y0;
        r1Y1 = y0 + 22;
        int delW = Math.max(96, w / 3 - 4);
        openX0 = x;
        openX1 = x + w - delW - gap;
        delX0 = openX1 + gap;
        delX1 = x + w;

        r2Y0 = r1Y1 + 5;
        r2Y1 = r2Y0 + 22;
        int half = (w - gap) / 2;
        copyX0 = x;
        copyX1 = x + half;
        folderX0 = copyX1 + gap;
        folderX1 = x + w;

        r3Y0 = r2Y1 + 5;
        r3Y1 = r3Y0 + 22;
        backX0 = x;
        backX1 = x + w;
    }

    @Override
    protected void init() {
        super.init();
        draggingScrollbar = false;
        deleteConfirmUntil = 0L;
        deleteConfirmName = "";
        scanFolders();
        if (selected >= entries.size()) {
            selected = -1;
        }
        listBottom = Math.max(LIST_TOP + ROW_H * 3, this.height - 124);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        updateButtonBounds();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (System.currentTimeMillis() - openedAtMs >= 180L
                && !(this.getFocused() instanceof EditBox eb && eb.canConsumeInput())
                && ChunkVaultClient.matchesOpenProjects(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent != null ? parent : new MapScreen());
    }

    private void say(String text, boolean ok) {
        status = text;
        statusOk = ok;
    }

    private void openSelected() {
        if (copying || deleting) {
            return;
        }
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
        MapScreen target = parent != null ? parent : new MapScreen();
        Minecraft.getInstance().setScreenAndShow(target);
        target.showToast(Lang.s("toast.project_opened", name));
    }

    private void deleteSelected() {
        if (copying || deleting) {
            return;
        }
        if (selected < 0 || selected >= entries.size()) {
            say(Lang.s("explorer.select_world"), false);
            return;
        }
        String name = entries.get(selected).name();
        String active = RecordingState.get().sessionName();
        if (RecordingState.get().isActive() && active != null
                && RecordingState.sanitize(active).equalsIgnoreCase(name)) {
            deleteConfirmUntil = 0L;
            say(Lang.s("explorer.world_recording"), false);
            return;
        }
        long now = System.currentTimeMillis();
        if (deleteConfirmUntil > now && name.equals(deleteConfirmName)) {
            deleteConfirmUntil = 0L;
            deleteConfirmName = "";
            deleting = true;
            say(Lang.s("explorer.deleting"), true);
            Path target = root().resolve(name);
            Thread t = new Thread(() -> {
                try {
                    RecordingState.get().awaitFinished();
                    if (ProjectMap.isOpen() && name.equalsIgnoreCase(ProjectMap.name())) {
                        ProjectMap.close();
                    }
                    deleteTree(target);
                    Minecraft.getInstance().execute(() -> {
                        scanFolders();
                        selected = -1;
                        info = "";
                        scroll = Math.max(0, Math.min(scroll, maxScroll()));
                        say(Lang.s("explorer.deleted", name), true);
                    });
                } catch (Exception e) {
                    say(Lang.s("explorer.delete_error", String.valueOf(e.getMessage())), false);
                } finally {
                    deleting = false;
                }
            }, "chunkvault-delete");
            t.setDaemon(true);
            t.start();
        } else {
            deleteConfirmName = name;
            deleteConfirmUntil = now + 4000L;
            say(Lang.s("explorer.delete_confirm_hint", name), false);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
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
        if (copying || deleting) {
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
                RecordingState.get().awaitFinished();
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

    private static boolean isInternalFile(String fileName) {
        return fileName.equals("chunkvault_containers.dat")
                || fileName.equals("chunkvault_area.properties")
                || fileName.startsWith("chunkvault_")
                || fileName.endsWith(".tmp");
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(src)) {
                    String name = dir.getFileName().toString();
                    if (name.equals("chunkvault_map") || name.startsWith("chunkvault_")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isInternalFile(file.getFileName().toString())) {
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
        return Math.max(22, Math.min(h, trackH));
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

    private void drawStyledButton(GuiGraphicsExtractor g, int x0, int x1, int y0, int y1,
                                  String label, int mouseX, int mouseY,
                                  int border, int bg, int bgHover, int textColor, boolean enabled) {
        boolean hover = enabled && mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        int bCol = !enabled ? 0xFF282C36 : (hover ? 0xFFFFFFFF : border);
        int fillCol = !enabled ? 0xFF181A22 : (hover ? bgHover : bg);
        int tCol = !enabled ? 0xFF555B6A : textColor;
        g.fill(x0, y0, x1, y1, bCol);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fillCol);
        String text = fit(label, (x1 - x0) - 8);
        int ty = y0 + ((y1 - y0) - 8) / 2;
        g.centeredText(this.font, text, (x0 + x1) / 2, ty, tCol);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);
        g.centeredText(this.font, this.title.getString(), this.width / 2, 14, 0xFFFFFFFF);
        int w = panelWidth();
        int x = (this.width - w) / 2;
        updateButtonBounds();

        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        boolean hasScroll = hasScrollbar();

        int headerY = LIST_TOP - 13;
        String rangeText = hasScroll
                ? Lang.s("explorer.range", scroll + 1, Math.min(entries.size(), scroll + rows), entries.size())
                : "";
        int rangeW = rangeText.isEmpty() ? 0 : this.font.width(rangeText);
        int folderMaxW = rangeW > 0 ? w - rangeW - 12 : w;
        g.text(this.font, fit(Lang.s("explorer.folder", root().toString()), folderMaxW), x, headerY, 0xFF8890A2, true);
        if (rangeW > 0) {
            g.text(this.font, rangeText, x + w - rangeW, headerY, 0xFF8890A2, true);
        }

        g.fill(x - 1, LIST_TOP - 1, x + w + 1, listBottom + 1, 0xFF3A3A48);
        g.fill(x, LIST_TOP, x + w, listBottom, 0xFF14161C);
        if (entries.isEmpty()) {
            g.centeredText(this.font, Lang.s("explorer.empty"), this.width / 2, LIST_TOP + 20, 0xFF888899);
        }

        String openProject = ProjectMap.isOpen() ? ProjectMap.name() : null;
        int contentRight = hasScroll ? x + w - SCROLLBAR_W - SCROLLBAR_PAD * 2 : x + w;
        int contentW = contentRight - x;
        for (int i = 0; i < rows && scroll + i < entries.size(); i++) {
            int idx = scroll + i;
            int ry = LIST_TOP + i * ROW_H;
            boolean hover = !draggingScrollbar
                    && mouseX >= x && mouseX < contentRight
                    && mouseY >= ry && mouseY < ry + ROW_H;
            Entry e = entries.get(idx);
            boolean isCurrent = openProject != null && openProject.equalsIgnoreCase(e.name());
            int bg = idx == selected ? 0xFF38301A : (hover ? 0xFF262A34 : ((idx & 1) == 0 ? 0xFF1C1F27 : 0xFF181B22));
            g.fill(x, ry, contentRight, ry + ROW_H - 1, bg);
            if (idx == selected) {
                g.fill(x, ry, contentRight, ry + 1, 0xFFB08924);
                g.fill(x, ry + ROW_H - 2, contentRight, ry + ROW_H - 1, 0xFFB08924);
            }
            g.fill(x, ry, x + 3, ry + ROW_H - 1, isCurrent ? 0xFFFFD070 : 0xFF3B6E80);

            int dateW = this.font.width(e.date());
            String activeTag = isCurrent ? Lang.s("explorer.active_tag") : "";
            int tagW = activeTag.isEmpty() ? 0 : this.font.width(activeTag) + 10;
            int nameMaxW = contentW - dateW - tagW - 24;
            g.text(this.font, fit(e.name(), nameMaxW), x + 10, ry + 8, isCurrent ? 0xFFFFE090 : 0xFFFFFFFF, true);
            if (!activeTag.isEmpty()) {
                g.text(this.font, activeTag, contentRight - dateW - tagW - 4, ry + 8, 0xFF70FF90, false);
            }
            g.text(this.font, e.date(), contentRight - dateW - 8, ry + 8, 0xFF8890A2, true);
        }

        if (hasScroll) {
            int trackX0 = x + w - SCROLLBAR_W - SCROLLBAR_PAD;
            int trackX1 = x + w - SCROLLBAR_PAD;
            int trackY0 = scrollbarTrackTop();
            int trackY1 = scrollbarTrackBottom();
            int thumbY0 = scrollbarThumbTop();
            int thumbY1 = thumbY0 + scrollbarThumbHeight();
            boolean thumbHover = mouseX >= trackX0 && mouseX < trackX1 && mouseY >= thumbY0 && mouseY < thumbY1;
            int thumbColor = draggingScrollbar ? 0xFFB0BCD4 : thumbHover ? 0xFF8A96B0 : 0xFF5A6074;
            g.fill(trackX0, trackY0, trackX1, trackY1, 0xFF0E1015);
            g.fill(trackX0 + 1, thumbY0, trackX1 - 1, thumbY1, thumbColor);
        }

        g.text(this.font, fit(info, w), x, listBottom + 6, 0xFF9AA0B0, true);
        String st = status;
        if (!st.isEmpty()) {
            g.text(this.font, fit(st, w), x, listBottom + 18, statusOk ? 0xFF70FF90 : 0xFFFF7070, true);
        }

        boolean busy = copying || deleting;
        boolean hasSel = selected >= 0 && selected < entries.size();
        long now = System.currentTimeMillis();
        boolean confirmingDel = hasSel && deleteConfirmUntil > now
                && entries.get(selected).name().equals(deleteConfirmName);

        drawStyledButton(g, openX0, openX1, r1Y0, r1Y1, Lang.s("explorer.open_project"), mouseX, mouseY,
                0xFF50C878, 0xFF1F5236, 0xFF2D6B48, 0xFF90FFB0, hasSel && !busy);
        drawStyledButton(g, delX0, delX1, r1Y0, r1Y1,
                confirmingDel ? Lang.s("explorer.delete_confirm") : Lang.s("explorer.delete"),
                mouseX, mouseY,
                confirmingDel ? 0xFFFF6060 : 0xFF9A3838,
                confirmingDel ? 0xFF9E2C2C : 0xFF4A2020,
                confirmingDel ? 0xFFC03838 : 0xFF6B2C2C,
                confirmingDel ? 0xFFFFFFFF : 0xFFFFB0B0, hasSel && !busy);

        drawStyledButton(g, copyX0, copyX1, r2Y0, r2Y1, Lang.s("explorer.copy"), mouseX, mouseY,
                0xFF3B6E80, 0xFF1A2E3A, 0xFF284858, 0xFFE0F4FF, hasSel && !busy);
        drawStyledButton(g, folderX0, folderX1, r2Y0, r2Y1, Lang.s("explorer.open_folder"), mouseX, mouseY,
                0xFF4A5064, 0xFF1E222C, 0xFF343A4A, 0xFFDDDDDD, true);

        drawStyledButton(g, backX0, backX1, r3Y0, r3Y1, Lang.s("common.back"), mouseX, mouseY,
                0xFF4A5064, 0xFF1E222C, 0xFF343A4A, 0xFFFFFFFF, true);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x();
        double my = event.y();
        int btn = event.button();

        if (btn == InputConstants.MOUSE_BUTTON_LEFT) {
            if (my >= r1Y0 && my < r1Y1) {
                if (mx >= openX0 && mx < openX1) {
                    deleteConfirmUntil = 0L;
                    openSelected();
                    return true;
                }
                if (mx >= delX0 && mx < delX1) {
                    deleteSelected();
                    return true;
                }
            }
            if (my >= r2Y0 && my < r2Y1) {
                deleteConfirmUntil = 0L;
                if (mx >= copyX0 && mx < copyX1) {
                    copySelected();
                    return true;
                }
                if (mx >= folderX0 && mx < folderX1) {
                    openFolder();
                    return true;
                }
            }
            if (my >= r3Y0 && my < r3Y1 && mx >= backX0 && mx < backX1) {
                deleteConfirmUntil = 0L;
                this.onClose();
                return true;
            }
        }

        int w = panelWidth();
        int x = (this.width - w) / 2;
        boolean hasScroll = hasScrollbar();
        int contentRight = hasScroll ? x + w - SCROLLBAR_W - SCROLLBAR_PAD * 2 : x + w;

        if (hasScroll && btn == InputConstants.MOUSE_BUTTON_LEFT) {
            int trackX0 = x + w - SCROLLBAR_W - SCROLLBAR_PAD;
            int trackX1 = x + w - SCROLLBAR_PAD;
            int trackY0 = scrollbarTrackTop();
            int trackY1 = scrollbarTrackBottom();
            if (mx >= trackX0 - 2 && mx <= trackX1 + 2 && my >= trackY0 && my < trackY1) {
                int thumbY0 = scrollbarThumbTop();
                int thumbH = scrollbarThumbHeight();
                if (my >= thumbY0 && my < thumbY0 + thumbH) {
                    dragOffsetY = my - thumbY0;
                } else {
                    dragOffsetY = thumbH / 2.0;
                    scrollFromThumbTop(my - dragOffsetY);
                }
                draggingScrollbar = true;
                return true;
            }
        }

        if (btn == InputConstants.MOUSE_BUTTON_LEFT && mx >= x && mx < contentRight && my >= LIST_TOP && my < listBottom) {
            deleteConfirmUntil = 0L;
            int row = (int) ((my - LIST_TOP) / ROW_H);
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
        if (draggingScrollbar && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            scrollFromThumbTop(event.y() - dragOffsetY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
