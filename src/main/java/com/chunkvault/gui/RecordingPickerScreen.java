package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.state.RecordingState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

/**
 * Opens when ЗАПИСЬ is pressed: a small "explorer" of the chunkvault folder.
 * Either type a new name (new world folder) or pick an existing folder and
 * record into it — chunks you record again replace the old copies, chunks
 * you don't visit stay as they were.
 */
public class RecordingPickerScreen extends Screen {

    private static final int W = 360;
    private static final int ROW_H = 20;
    private static final int LIST_TOP = 50;

    private record Entry(String name, String date) {}

    private final MapScreen parent;
    private final List<Entry> entries = new ArrayList<>();
    private int selected = -1;
    private int scroll;
    private EditBox nameBox;
    private String error;
    private int listBottom;

    public RecordingPickerScreen(MapScreen parent) {
        super(Component.literal("ChunkVault — куда записывать?"));
        this.parent = parent;
    }

    private static Path root() {
        return FabricLoader.getInstance().getGameDir().resolve("chunkvault");
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
            System.err.println("[ChunkVault] could not list " + root + ": " + e);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        dirs.sort(Comparator.comparingLong(RecordingPickerScreen::modified).reversed());
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
        listBottom = Math.max(LIST_TOP + ROW_H * 3, this.height - 118);
        int y0 = listBottom + 8;

        nameBox = new EditBox(this.font, x, y0 + 12, W, 20, Component.literal("Имя"));
        nameBox.setMaxLength(48);
        this.addRenderableWidget(nameBox);

        int half = (W - 6) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Создать и записать"), b -> startNew())
                .bounds(x, y0 + 38, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Записать в выбранный"), b -> startSelected())
                .bounds(x + half + 6, y0 + 38, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> this.onClose())
                .bounds(x, y0 + 64, W, 20)
                .build());
    }

    private void startNew() {
        String raw = nameBox.getValue().trim();
        if (raw.isEmpty()) {
            error = "Введите имя для новой записи";
            return;
        }
        start(RecordingState.sanitize(raw));
    }

    private void startSelected() {
        if (selected < 0 || selected >= entries.size()) {
            error = "Сначала выберите мир в списке";
            return;
        }
        start(entries.get(selected).name());
    }

    private void start(String folderName) {
        int captured = ChunkVaultClient.startRecording(folderName);
        if (captured < 0) {
            error = "Запись уже идёт";
            return;
        }
        Minecraft.getInstance().setScreenAndShow(parent);
        parent.showToast("Запись начата: " + folderName + " (сразу захвачено " + captured + " чанков)");
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
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
        g.text(this.font, "Папка: " + fit(root().toString(), W - 60), x, 32, 0xFF808090, true);

        // ---- list of existing worlds ----
        g.fill(x - 1, LIST_TOP - 1, x + W + 1, listBottom + 1, 0xFF3A3A48);
        g.fill(x, LIST_TOP, x + W, listBottom, 0xFF14161C);
        if (entries.isEmpty()) {
            g.centeredText(this.font, "Здесь пока пусто — создайте первую запись ниже", this.width / 2,
                    LIST_TOP + 16, 0xFF888899);
        }
        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - rows)));
        for (int i = 0; i < rows && scroll + i < entries.size(); i++) {
            int idx = scroll + i;
            int ry = LIST_TOP + i * ROW_H;
            boolean hover = mouseX >= x && mouseX < x + W && mouseY >= ry && mouseY < ry + ROW_H;
            int bg = idx == selected ? 0xFF3A5A8A : hover ? 0xFF262A34 : 0xFF1C1F27;
            g.fill(x, ry, x + W, ry + ROW_H - 1, bg);
            Entry e = entries.get(idx);
            int dateW = this.font.width(e.date());
            g.text(this.font, fit(e.name(), W - dateW - 24), x + 8, ry + 6, 0xFFFFFFFF, true);
            g.text(this.font, e.date(), x + W - dateW - 8, ry + 6, 0xFF9AA0B0, true);
        }
        if (entries.size() > rows) {
            g.text(this.font, (scroll + 1) + "–" + Math.min(entries.size(), scroll + rows) + " из " + entries.size(),
                    x + W - 80, LIST_TOP - 11, 0xFF808090, true);
        }

        // ---- new name ----
        int y0 = listBottom + 8;
        g.text(this.font, "Новая запись — имя:", x, y0 + 2, 0xFFAAAAAA, true);
        String typed = nameBox == null ? "" : nameBox.getValue().trim();
        if (!typed.isEmpty()) {
            String folder = RecordingState.sanitize(typed);
            boolean exists = entries.stream().anyMatch(e -> e.name().equals(folder));
            String hint = "→ " + folder + (exists ? "  (уже есть — запись пойдёт в неё)" : "");
            g.text(this.font, fit(hint, W - 130), x + 124, y0 + 2, exists ? 0xFFFFD070 : 0xFF70FF90, true);
        }

        String note = "В существующий мир: заново записанные чанки заменят старые, остальные останутся.";
        g.text(this.font, fit(note, W), x, y0 + 90, 0xFF808090, true);
        if (error != null) {
            g.text(this.font, error, x, y0 + 102, 0xFFFF7070, true);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        int x = (this.width - W) / 2;
        if (event.x() >= x && event.x() < x + W && event.y() >= LIST_TOP && event.y() < listBottom) {
            int idx = scroll + (int) ((event.y() - LIST_TOP) / ROW_H);
            if (idx >= 0 && idx < entries.size()) {
                selected = idx;
                error = null;
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
