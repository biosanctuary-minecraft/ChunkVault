package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.chunkvault.save.ContainerContentReader;
import com.chunkvault.save.ContainerDatabase;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ContainerDatabaseScreen extends Screen {

    private static final int MAX_W = 520;
    private static final int ROW_H = 28;
    private static final int LIST_TOP = 96;
    private static final int SCROLLBAR_W = 8;
    private static final int SCROLLBAR_PAD = 2;

    private static final DateTimeFormatter FMT_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FMT_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter FMT_FULL = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");

    private static final String[] CATEGORIES = {
            "all",
            ContainerDatabase.CAT_CHESTS,
            ContainerDatabase.CAT_SHULKERS,
            ContainerDatabase.CAT_ENDER,
            ContainerDatabase.CAT_FURNACES,
            ContainerDatabase.CAT_OTHER
    };

    private enum StatusFilter {
        ALL("db.status.all"),
        OPENED("db.status.opened"),
        UNOPENED("db.status.unopened");

        final String langKey;

        StatusFilter(String langKey) {
            this.langKey = langKey;
        }

        StatusFilter step(int dir) {
            StatusFilter[] v = values();
            return v[(ordinal() + dir + v.length) % v.length];
        }
    }

    private enum SortMode {
        NEWEST("db.sort.newest"),
        OLDEST("db.sort.oldest"),
        NEAREST("db.sort.nearest"),
        FARTHEST("db.sort.farthest"),
        MOST_FILLED("db.sort.filled_desc"),
        LEAST_FILLED("db.sort.filled_asc"),
        NAME("db.sort.name");

        final String langKey;

        SortMode(String langKey) {
            this.langKey = langKey;
        }

        SortMode step(int dir) {
            SortMode[] v = values();
            return v[(ordinal() + dir + v.length) % v.length];
        }
    }

    private record IndexedContainer(
            ContainerDatabase.ContainerEntry entry,
            ItemStack icon,
            boolean doubleChest,
            int totalSlots,
            int filledSlots,
            String titleLower,
            List<ItemStack> allItems,
            List<String> itemTexts
    ) {}

    private record ResultRow(
            IndexedContainer item,
            ItemStack matchedStack,
            int matchedCount,
            double distSq
    ) {}

    private final MapScreen parent;
    private final long openedAtMs = System.currentTimeMillis();
    private EditBox searchBox;

    private String query = "";
    private boolean allDimensions;
    private int catIndex;
    private StatusFilter statusFilter;
    private SortMode sortMode = SortMode.NEWEST;

    private int cachedRevision = -1;
    private final List<IndexedContainer> index = new ArrayList<>();
    private final List<ResultRow> rows = new ArrayList<>();

    private int selected = -1;
    private int scroll;
    private int listBottom;
    private boolean draggingScrollbar;
    private double dragOffsetY;

    private int fY0;
    private int fY1;
    private int dimX0;
    private int dimX1;
    private int catX0;
    private int catX1;
    private int statusX0;
    private int statusX1;
    private int sortX0;
    private int sortX1;

    private int bY0;
    private int bY1;
    private int openX0;
    private int openX1;
    private int mapX0;
    private int mapX1;
    private int backX0;
    private int backX1;

    public ContainerDatabaseScreen(MapScreen parent) {
        super(Lang.c("db.title_short"));
        this.parent = parent;
        this.statusFilter = ContainerDatabase.showUnopened() ? StatusFilter.ALL : StatusFilter.OPENED;
    }

    private int panelWidth() {
        return Math.min(MAX_W, this.width - 24);
    }

    private String currentDimension() {
        var level = Minecraft.getInstance().level;
        return level == null ? "minecraft:overworld" : level.dimension().identifier().toString();
    }

    private static ItemStack iconFor(String blockId) {
        Identifier id = Identifier.tryParse(blockId == null ? "" : blockId);
        if (id != null) {
            var opt = BuiltInRegistries.BLOCK.getOptional(id);
            if (opt.isPresent()) {
                ItemStack s = new ItemStack(opt.get().asItem());
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return new ItemStack(Items.CHEST);
    }

    private static String formatOpenedTime(long epochMs) {
        if (epochMs <= 0L) {
            return "";
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone);
        LocalDate today = LocalDate.now(zone);
        if (dt.toLocalDate().equals(today)) {
            return dt.format(FMT_TIME);
        }
        if (dt.getYear() == today.getYear()) {
            return dt.format(FMT_DATE_TIME);
        }
        return dt.format(FMT_FULL);
    }

    private void ensureIndex() {
        int rev = ContainerDatabase.revision();
        if (rev == cachedRevision) {
            return;
        }
        cachedRevision = rev;
        index.clear();
        var level = Minecraft.getInstance().level;
        var registries = level == null ? null : level.registryAccess();

        for (ContainerDatabase.ContainerEntry e : ContainerDatabase.all()) {
            if (ContainerDatabase.isSecondaryHalf(e)) {
                continue;
            }
            var partner = ContainerDatabase.partnerOf(e);
            boolean dbl = partner != null && e.slotCount() == 27 && partner.slotCount() == 27;
            int totalSlots = dbl ? 54 : e.slotCount();
            int filledSlots = dbl ? (e.filledSlots() + partner.filledSlots()) : e.filledSlots();
            List<ItemStack> items = (registries == null || !e.opened())
                    ? List.of()
                    : ContainerContentReader.collectAllItemsIncludingNested(e, registries);
            List<String> texts = new ArrayList<>(items.size());
            for (ItemStack s : items) {
                texts.add(ContainerContentReader.itemSearchText(s));
            }
            String title = (e.displayTitle() + " " + e.blockId()).toLowerCase(Locale.ROOT);
            index.add(new IndexedContainer(e, iconFor(e.blockId()), dbl, totalSlots, filledSlots, title, items, texts));
        }
        rebuildFilter();
    }

    private void rebuildFilter() {
        rows.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        String curDim = currentDimension();
        String cat = CATEGORIES[catIndex];
        var player = Minecraft.getInstance().player;
        double px = player == null ? 0.0 : player.getX();
        double py = player == null ? 0.0 : player.getY();
        double pz = player == null ? 0.0 : player.getZ();

        for (IndexedContainer ic : index) {
            ContainerDatabase.ContainerEntry e = ic.entry();
            if (statusFilter == StatusFilter.OPENED && !e.opened()) {
                continue;
            }
            if (statusFilter == StatusFilter.UNOPENED && e.opened()) {
                continue;
            }
            if (!allDimensions && !e.dimension().equals(curDim)) {
                continue;
            }
            if (!"all".equals(cat) && !e.category().equals(cat)) {
                continue;
            }
            double dx = (e.x() + 0.5) - px;
            double dy = (e.y() + 0.5) - py;
            double dz = (e.z() + 0.5) - pz;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (q.isEmpty()) {
                rows.add(new ResultRow(ic, null, 0, distSq));
                continue;
            }
            int matchCount = 0;
            ItemStack firstMatch = null;
            for (int i = 0; i < ic.allItems().size(); i++) {
                if (ic.itemTexts().get(i).contains(q)) {
                    ItemStack s = ic.allItems().get(i);
                    matchCount += s.getCount();
                    if (firstMatch == null) {
                        firstMatch = s;
                    }
                }
            }
            if (matchCount > 0 || ic.titleLower().contains(q)) {
                rows.add(new ResultRow(ic, firstMatch, matchCount, distSq));
            }
        }

        Comparator<ResultRow> primary = switch (sortMode) {
            case NEWEST -> Comparator
                    .comparing((ResultRow r) -> r.item().entry().opened(), Comparator.reverseOrder())
                    .thenComparing(Comparator.comparingLong((ResultRow r) -> r.item().entry().updatedAt()).reversed());
            case OLDEST -> Comparator
                    .comparing((ResultRow r) -> r.item().entry().opened(), Comparator.reverseOrder())
                    .thenComparingLong(r -> r.item().entry().updatedAt());
            case NEAREST -> Comparator.comparingDouble(ResultRow::distSq);
            case FARTHEST -> Comparator.comparingDouble(ResultRow::distSq).reversed();
            case MOST_FILLED -> Comparator
                    .comparing((ResultRow r) -> r.item().entry().opened(), Comparator.reverseOrder())
                    .thenComparing(Comparator.comparingInt((ResultRow r) -> r.item().filledSlots()).reversed())
                    .thenComparing(Comparator.comparingLong((ResultRow r) -> r.item().entry().updatedAt()).reversed());
            case LEAST_FILLED -> Comparator
                    .comparing((ResultRow r) -> r.item().entry().opened(), Comparator.reverseOrder())
                    .thenComparingInt(r -> r.item().filledSlots())
                    .thenComparing(Comparator.comparingLong((ResultRow r) -> r.item().entry().updatedAt()).reversed());
            case NAME -> Comparator
                    .comparing((ResultRow r) -> r.item().entry().displayTitle().toLowerCase(Locale.ROOT))
                    .thenComparing(Comparator.comparingLong((ResultRow r) -> r.item().entry().updatedAt()).reversed());
        };

        if (!q.isEmpty() && sortMode == SortMode.NEWEST) {
            rows.sort(Comparator.comparingInt(ResultRow::matchedCount).reversed().thenComparing(primary));
        } else {
            rows.sort(primary);
        }

        if (selected >= rows.size()) {
            selected = rows.isEmpty() ? -1 : 0;
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    @Override
    protected void init() {
        super.init();
        draggingScrollbar = false;
        ensureIndex();

        int w = panelWidth();
        int x = (this.width - w) / 2;
        listBottom = Math.max(LIST_TOP + ROW_H * 3, this.height - 46);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));

        searchBox = new EditBox(this.font, x, 32, w, 19, Lang.c("db.search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Lang.c("db.search_hint"));
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            query = text;
            rebuildFilter();
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        fY0 = 56;
        fY1 = 76;
        int gap = 4;
        int btnW = (w - gap * 3) / 4;
        dimX0 = x;
        dimX1 = dimX0 + btnW;
        catX0 = dimX1 + gap;
        catX1 = catX0 + btnW;
        statusX0 = catX1 + gap;
        statusX1 = statusX0 + btnW;
        sortX0 = statusX1 + gap;
        sortX1 = x + w;

        bY0 = listBottom + 10;
        bY1 = bY0 + 22;
        int bGap = 6;
        int third = (w - bGap * 2) / 3;
        openX0 = x;
        openX1 = openX0 + third;
        mapX0 = openX1 + bGap;
        mapX1 = mapX0 + third;
        backX0 = mapX1 + bGap;
        backX1 = x + w;
    }

    private void openSelected() {
        if (selected < 0 || selected >= rows.size()) {
            return;
        }
        ContainerDatabase.ContainerEntry e = rows.get(selected).item().entry();
        Minecraft.getInstance().setScreenAndShow(new ContainerViewerScreen(this, e.dimension(), e.x(), e.z(), e.key()));
    }

    private void showSelectedOnMap() {
        if (selected < 0 || selected >= rows.size()) {
            return;
        }
        ContainerDatabase.ContainerEntry e = rows.get(selected).item().entry();
        MapScreen target = parent != null ? parent : new MapScreen();
        target.focusOn(e.x(), e.z());
        Minecraft.getInstance().setScreenAndShow(target);
        if (!e.dimension().equals(currentDimension())) {
            target.showToast(Lang.s("db.other_dim_hint", shortDim(e.dimension()), e.x(), e.y(), e.z()));
        }
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - LIST_TOP) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - visibleRows());
    }

    private boolean hasScrollbar() {
        return rows.size() > visibleRows();
    }

    private int scrollbarThumbHeight() {
        int trackH = Math.max(1, (listBottom - 2) - (LIST_TOP + 2));
        int vis = visibleRows();
        if (rows.isEmpty() || rows.size() <= vis) {
            return trackH;
        }
        return Math.max(22, Math.min((int) Math.round((double) trackH * vis / rows.size()), trackH));
    }

    private int scrollbarThumbTop() {
        int trackTop = LIST_TOP + 2;
        int travel = Math.max(0, (listBottom - 2) - trackTop - scrollbarThumbHeight());
        int max = maxScroll();
        if (travel <= 0 || max <= 0) {
            return trackTop;
        }
        return trackTop + (int) Math.round((double) travel * scroll / max);
    }

    private void scrollFromThumbTop(double thumbTop) {
        int trackTop = LIST_TOP + 2;
        int travel = Math.max(0, (listBottom - 2) - trackTop - scrollbarThumbHeight());
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

    private static String shortDim(String dim) {
        int idx = dim.indexOf(':');
        return idx >= 0 ? dim.substring(idx + 1) : dim;
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
        ensureIndex();
        g.fill(0, 0, this.width, this.height, 0xF0101018);

        int w = panelWidth();
        int x = (this.width - w) / 2;

        g.centeredText(this.font, Lang.s("db.title", index.size()), this.width / 2, 12, 0xFFFFFFFF);

        String dimTxt = Lang.s("db.filter_dim", allDimensions ? Lang.s("db.dim.all") : Lang.s("db.dim.current"));
        drawStyledButton(g, dimX0, dimX1, fY0, fY1, dimTxt, mouseX, mouseY,
                allDimensions ? 0xFF3B6E80 : 0xFF4A5064,
                allDimensions ? 0xFF1A2E3A : 0xFF1E222C,
                allDimensions ? 0xFF284858 : 0xFF343A4A,
                allDimensions ? 0xFFE0F4FF : 0xFFDDDDDD, true);

        String catTxt = Lang.s("db.filter_type", Lang.s("db.cat." + CATEGORIES[catIndex]));
        boolean catActive = catIndex != 0;
        drawStyledButton(g, catX0, catX1, fY0, fY1, catTxt, mouseX, mouseY,
                catActive ? 0xFF7A6028 : 0xFF4A5064,
                catActive ? 0xFF4A3614 : 0xFF1E222C,
                catActive ? 0xFF7A5618 : 0xFF343A4A,
                catActive ? 0xFFFFE090 : 0xFFDDDDDD, true);

        String statusTxt = Lang.s(statusFilter.langKey);
        boolean statusActive = statusFilter != StatusFilter.ALL;
        drawStyledButton(g, statusX0, statusX1, fY0, fY1, statusTxt, mouseX, mouseY,
                statusActive ? 0xFF50C878 : 0xFF4A5064,
                statusActive ? 0xFF1F5236 : 0xFF1E222C,
                statusActive ? 0xFF2D6B48 : 0xFF343A4A,
                statusActive ? 0xFF90FFB0 : 0xFFDDDDDD, true);

        String sortTxt = Lang.s("db.sort_prefix", Lang.s(sortMode.langKey));
        boolean sortActive = sortMode != SortMode.NEWEST;
        drawStyledButton(g, sortX0, sortX1, fY0, fY1, sortTxt, mouseX, mouseY,
                sortActive ? 0xFF6B85C0 : 0xFF4A5064,
                sortActive ? 0xFF243858 : 0xFF1E222C,
                sortActive ? 0xFF365585 : 0xFF343A4A,
                sortActive ? 0xFFD8E8FF : 0xFFDDDDDD, true);

        int vis = visibleRows();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        boolean hasScroll = hasScrollbar();

        String foundText = Lang.s("db.found", rows.size());
        g.text(this.font, foundText, x, LIST_TOP - 11, 0xFF8890A2, true);
        if (hasScroll) {
            String range = Lang.s("explorer.range", scroll + 1, Math.min(rows.size(), scroll + vis), rows.size());
            g.text(this.font, range, x + w - this.font.width(range), LIST_TOP - 11, 0xFF8890A2, true);
        }

        g.fill(x - 1, LIST_TOP - 1, x + w + 1, listBottom + 1, 0xFF3A3A48);
        g.fill(x, LIST_TOP, x + w, listBottom, 0xFF14161C);

        if (rows.isEmpty()) {
            String emptyMsg = index.isEmpty() ? Lang.s("db.empty") : Lang.s("db.no_matches");
            g.centeredText(this.font, emptyMsg, this.width / 2, LIST_TOP + 20, 0xFF888899);
        }

        int contentRight = hasScroll ? x + w - SCROLLBAR_W - SCROLLBAR_PAD * 2 : x + w;
        var player = Minecraft.getInstance().player;
        String curDim = currentDimension();

        for (int i = 0; i < vis && scroll + i < rows.size(); i++) {
            int idx = scroll + i;
            int ry = LIST_TOP + i * ROW_H;
            boolean hover = !draggingScrollbar
                    && mouseX >= x && mouseX < contentRight
                    && mouseY >= ry && mouseY < ry + ROW_H;
            int bg = idx == selected ? 0xFF38301A : (hover ? 0xFF262A34 : ((idx & 1) == 0 ? 0xFF1C1F27 : 0xFF181B22));
            g.fill(x, ry, contentRight, ry + ROW_H - 1, bg);
            if (idx == selected) {
                g.fill(x, ry, contentRight, ry + 1, 0xFFB08924);
                g.fill(x, ry + ROW_H - 2, contentRight, ry + ROW_H - 1, 0xFFB08924);
            }

            ResultRow row = rows.get(idx);
            IndexedContainer ic = row.item();
            ContainerDatabase.ContainerEntry e = ic.entry();

            g.fill(x, ry, x + 3, ry + ROW_H - 1, ContainerDatabase.colorFor(e));

            int textLeft = x + 7;
            g.item(ic.icon(), textLeft, ry + 6);
            textLeft += 20;

            if (row.matchedStack() != null) {
                g.item(row.matchedStack(), textLeft, ry + 6);
                textLeft += 18;
                String countBadge = "×" + row.matchedCount();
                g.text(this.font, countBadge, textLeft, ry + 5, 0xFF70FF90, true);
                textLeft += this.font.width(countBadge) + 6;
            }

            String slotPart = e.opened()
                    ? (ic.filledSlots() + "/" + ic.totalSlots())
                    : Lang.s("db.unopened_tag");
            String timePart = e.opened() ? formatOpenedTime(e.updatedAt()) : "";
            int rightCursor = contentRight - 8;

            if (!timePart.isEmpty()) {
                int tw = this.font.width(timePart);
                g.text(this.font, timePart, rightCursor - tw, ry + 5, 0xFF7FA6C8, false);
                rightCursor -= tw + 8;
            }

            int slotW = this.font.width(slotPart);
            int slotCol = e.opened() ? 0xFFD0D8E8 : 0xFFFFD070;
            g.text(this.font, slotPart, rightCursor - slotW, ry + 5, slotCol, true);
            rightCursor -= slotW + 10;

            String title = e.displayTitle() + (ic.doubleChest() ? " (2x)" : "");
            int titleColor = e.opened() ? 0xFFFFFFFF : 0xFFB0B4C0;
            g.text(this.font, fit(title, rightCursor - textLeft), textLeft, ry + 5, titleColor, true);

            StringBuilder sub = new StringBuilder();
            if (allDimensions) {
                sub.append(shortDim(e.dimension())).append("  •  ");
            }
            sub.append("X:").append(e.x()).append(" Y:").append(e.y()).append(" Z:").append(e.z());
            if (player != null && e.dimension().equals(curDim)) {
                int dist = (int) Math.round(Math.sqrt(row.distSq()));
                sub.append("  •  ").append(dist).append("m");
            }
            g.text(this.font, fit(sub.toString(), contentRight - textLeft - 8), textLeft, ry + 16, 0xFF8890A2, false);
        }

        if (hasScroll) {
            int trackX0 = x + w - SCROLLBAR_W - SCROLLBAR_PAD;
            int trackX1 = x + w - SCROLLBAR_PAD;
            int thumbY0 = scrollbarThumbTop();
            int thumbY1 = thumbY0 + scrollbarThumbHeight();
            boolean thumbHover = mouseX >= trackX0 && mouseX < trackX1 && mouseY >= thumbY0 && mouseY < thumbY1;
            int thumbColor = draggingScrollbar ? 0xFFB0BCD4 : thumbHover ? 0xFF8A96B0 : 0xFF5A6074;
            g.fill(trackX0, LIST_TOP + 2, trackX1, listBottom - 2, 0xFF0E1015);
            g.fill(trackX0 + 1, thumbY0, trackX1 - 1, thumbY1, thumbColor);
        }

        boolean hasSel = selected >= 0 && selected < rows.size();
        drawStyledButton(g, openX0, openX1, bY0, bY1, Lang.s("db.open_slots"), mouseX, mouseY,
                0xFF50C878, 0xFF1F5236, 0xFF2D6B48, 0xFF90FFB0, hasSel);
        drawStyledButton(g, mapX0, mapX1, bY0, bY1, Lang.s("db.show_on_map"), mouseX, mouseY,
                0xFF3B6E80, 0xFF1A2E3A, 0xFF284858, 0xFFE0F4FF, hasSel);
        drawStyledButton(g, backX0, backX1, bY0, bY1, Lang.s("common.back"), mouseX, mouseY,
                0xFF4A5064, 0xFF1E222C, 0xFF343A4A, 0xFFFFFFFF, true);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (System.currentTimeMillis() - openedAtMs >= 180L
                && !(this.getFocused() instanceof EditBox eb && eb.canConsumeInput())
                && ChunkVaultClient.matchesOpenChests(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x();
        double my = event.y();
        int btn = event.button();

        if ((btn == InputConstants.MOUSE_BUTTON_LEFT || btn == InputConstants.MOUSE_BUTTON_RIGHT)
                && my >= fY0 && my < fY1) {
            int dir = btn == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1;
            if (mx >= dimX0 && mx < dimX1) {
                allDimensions = !allDimensions;
                rebuildFilter();
                return true;
            }
            if (mx >= catX0 && mx < catX1) {
                catIndex = (catIndex + dir + CATEGORIES.length) % CATEGORIES.length;
                rebuildFilter();
                return true;
            }
            if (mx >= statusX0 && mx < statusX1) {
                statusFilter = statusFilter.step(dir);
                ContainerDatabase.setShowUnopened(statusFilter != StatusFilter.OPENED);
                rebuildFilter();
                return true;
            }
            if (mx >= sortX0 && mx < sortX1) {
                sortMode = sortMode.step(dir);
                rebuildFilter();
                return true;
            }
        }

        if (btn == InputConstants.MOUSE_BUTTON_LEFT && my >= bY0 && my < bY1) {
            if (mx >= openX0 && mx < openX1) {
                openSelected();
                return true;
            }
            if (mx >= mapX0 && mx < mapX1) {
                showSelectedOnMap();
                return true;
            }
            if (mx >= backX0 && mx < backX1) {
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
            if (mx >= trackX0 - 2 && mx <= trackX1 + 2 && my >= LIST_TOP + 2 && my < listBottom - 2) {
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
            int row = (int) ((my - LIST_TOP) / ROW_H);
            int idx = scroll + row;
            if (row >= 0 && row < visibleRows() && idx >= 0 && idx < rows.size()) {
                boolean again = isDoubleClick && idx == selected;
                selected = idx;
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
        if (scrollY != 0 && !rows.isEmpty()) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent != null ? parent : new MapScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
