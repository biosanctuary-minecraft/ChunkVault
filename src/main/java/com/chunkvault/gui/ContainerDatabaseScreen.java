package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.save.ContainerContentReader;
import com.chunkvault.save.ContainerDatabase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ContainerDatabaseScreen extends Screen {

    private static final int MAX_W = 480;
    private static final int ROW_H = 24;
    private static final int LIST_TOP = 94;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_PAD = 2;

    private static final String[] CATEGORIES = {
            "all",
            ContainerDatabase.CAT_CHESTS,
            ContainerDatabase.CAT_SHULKERS,
            ContainerDatabase.CAT_FURNACES,
            ContainerDatabase.CAT_OTHER
    };

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
            int matchedCount
    ) {}

    private final MapScreen parent;
    private EditBox searchBox;

    private String query = "";
    private boolean allDimensions;
    private int catIndex;

    private int cachedRevision = -1;
    private final List<IndexedContainer> index = new ArrayList<>();
    private final List<ResultRow> rows = new ArrayList<>();

    private int selected = -1;
    private int scroll;
    private int listBottom;
    private boolean draggingScrollbar;
    private double dragOffsetY;

    public ContainerDatabaseScreen(MapScreen parent) {
        super(Lang.c("db.title_short"));
        this.parent = parent;
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
        boolean showUnopened = ContainerDatabase.showUnopened();

        for (IndexedContainer ic : index) {
            ContainerDatabase.ContainerEntry e = ic.entry();
            if (!showUnopened && !e.opened()) {
                continue;
            }
            if (!allDimensions && !e.dimension().equals(curDim)) {
                continue;
            }
            if (!"all".equals(cat) && !e.category().equals(cat)) {
                continue;
            }
            if (q.isEmpty()) {
                rows.add(new ResultRow(ic, null, 0));
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
                rows.add(new ResultRow(ic, firstMatch, matchCount));
            }
        }

        Comparator<ResultRow> byOpenedAndNewest = Comparator
                .comparing((ResultRow r) -> r.item().entry().opened(), Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong((ResultRow r) -> r.item().entry().updatedAt()).reversed());

        if (!q.isEmpty()) {
            rows.sort(Comparator.comparingInt(ResultRow::matchedCount).reversed()
                    .thenComparing(byOpenedAndNewest));
        } else {
            rows.sort(byOpenedAndNewest);
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
        listBottom = Math.max(LIST_TOP + ROW_H * 3, this.height - 54);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));

        searchBox = new EditBox(this.font, x, 34, w, 20, Lang.c("db.search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Lang.c("db.search_hint"));
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            query = text;
            rebuildFilter();
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        int third = (w - 12) / 3;
        this.addRenderableWidget(Button.builder(dimLabel(), b -> {
            allDimensions = !allDimensions;
            b.setMessage(dimLabel());
            rebuildFilter();
        }).bounds(x, 60, third, 20).build());

        this.addRenderableWidget(Button.builder(catLabel(), b -> {
            catIndex = (catIndex + 1) % CATEGORIES.length;
            b.setMessage(catLabel());
            rebuildFilter();
        }).bounds(x + third + 6, 60, third, 20).build());

        this.addRenderableWidget(Button.builder(unopenedLabel(), b -> {
            ContainerDatabase.toggleShowUnopened();
            b.setMessage(unopenedLabel());
            rebuildFilter();
        }).bounds(x + (third + 6) * 2, 60, w - (third + 6) * 2, 20).build());

        int btnY = listBottom + 12;
        this.addRenderableWidget(Button.builder(Lang.c("db.open_slots"), b -> openSelected())
                .bounds(x, btnY, third, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("db.show_on_map"), b -> showSelectedOnMap())
                .bounds(x + third + 6, btnY, third, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("common.back"), b -> this.onClose())
                .bounds(x + (third + 6) * 2, btnY, w - (third + 6) * 2, 20)
                .build());
    }

    private Component dimLabel() {
        return Lang.c("db.filter_dim", allDimensions ? Lang.s("db.dim.all") : Lang.s("db.dim.current"));
    }

    private Component catLabel() {
        return Lang.c("db.filter_type", Lang.s("db.cat." + CATEGORIES[catIndex]));
    }

    private Component unopenedLabel() {
        return Lang.c("db.filter_unopened", ContainerDatabase.showUnopened() ? Lang.s("common.on") : Lang.s("common.off"));
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
        parent.focusOn(e.x(), e.z());
        Minecraft.getInstance().setScreenAndShow(parent);
        if (!e.dimension().equals(currentDimension())) {
            parent.showToast(Lang.s("db.other_dim_hint", shortDim(e.dimension()), e.x(), e.y(), e.z()));
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
        return Math.max(20, Math.min((int) Math.round((double) trackH * vis / rows.size()), trackH));
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ensureIndex();
        g.fill(0, 0, this.width, this.height, 0xF0101018);

        int w = panelWidth();
        int x = (this.width - w) / 2;

        g.centeredText(this.font, Lang.s("db.title", index.size()), this.width / 2, 12, 0xFFFFFFFF);

        int vis = visibleRows();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        boolean hasScroll = hasScrollbar();

        String foundText = Lang.s("db.found", rows.size());
        g.text(this.font, foundText, x, LIST_TOP - 11, 0xFF808090, true);
        if (hasScroll) {
            String range = Lang.s("explorer.range", scroll + 1, Math.min(rows.size(), scroll + vis), rows.size());
            g.text(this.font, range, x + w - this.font.width(range), LIST_TOP - 11, 0xFF808090, true);
        }

        g.fill(x - 1, LIST_TOP - 1, x + w + 1, listBottom + 1, 0xFF3A3A48);
        g.fill(x, LIST_TOP, x + w, listBottom, 0xFF14161C);

        if (rows.isEmpty()) {
            String emptyMsg = index.isEmpty() ? Lang.s("db.empty") : Lang.s("db.no_matches");
            g.centeredText(this.font, emptyMsg, this.width / 2, LIST_TOP + 18, 0xFF888899);
        }

        int contentRight = hasScroll ? x + w - SCROLLBAR_W - SCROLLBAR_PAD * 2 : x + w;
        for (int i = 0; i < vis && scroll + i < rows.size(); i++) {
            int idx = scroll + i;
            int ry = LIST_TOP + i * ROW_H;
            boolean hover = !draggingScrollbar
                    && mouseX >= x && mouseX < contentRight
                    && mouseY >= ry && mouseY < ry + ROW_H;
            int bg = idx == selected ? 0xFF8A6A1A : hover ? 0xFF262A34 : 0xFF1C1F27;
            g.fill(x, ry, contentRight, ry + ROW_H - 1, bg);

            ResultRow row = rows.get(idx);
            IndexedContainer ic = row.item();
            ContainerDatabase.ContainerEntry e = ic.entry();

            g.fill(x, ry, x + 3, ry + ROW_H - 1, ContainerDatabase.colorFor(e));

            int textLeft = x + 7;
            g.item(ic.icon(), textLeft, ry + 4);
            textLeft += 20;

            if (row.matchedStack() != null) {
                g.item(row.matchedStack(), textLeft, ry + 4);
                textLeft += 18;
                String countBadge = "×" + row.matchedCount();
                g.text(this.font, countBadge, textLeft, ry + 8, 0xFF70FF90, true);
                textLeft += this.font.width(countBadge) + 8;
            }

            String slotPart = e.opened()
                    ? (ic.filledSlots() + "/" + ic.totalSlots())
                    : Lang.s("db.unopened_tag");
            String rightInfo = "X:" + e.x() + " Y:" + e.y() + " Z:" + e.z() + "  •  " + slotPart;
            if (allDimensions) {
                rightInfo = shortDim(e.dimension()) + "  •  " + rightInfo;
            }
            int rightW = this.font.width(rightInfo);

            String title = e.displayTitle() + (ic.doubleChest() ? " (2x)" : "");
            int titleColor = e.opened() ? 0xFFFFFFFF : 0xFFB0B4C0;
            g.text(this.font, fit(title, contentRight - textLeft - rightW - 14), textLeft, ry + 8, titleColor, true);
            g.text(this.font, rightInfo, contentRight - rightW - 8, ry + 8, 0xFF9AA0B0, true);
        }

        if (hasScroll) {
            int trackX0 = x + w - SCROLLBAR_W - SCROLLBAR_PAD;
            int trackX1 = x + w - SCROLLBAR_PAD;
            int thumbY0 = scrollbarThumbTop();
            int thumbY1 = thumbY0 + scrollbarThumbHeight();
            boolean thumbHover = mouseX >= trackX0 && mouseX < trackX1 && mouseY >= thumbY0 && mouseY < thumbY1;
            int thumbColor = draggingScrollbar ? 0xFF9AA4C0 : thumbHover ? 0xFF7A829C : 0xFF5A6074;
            g.fill(trackX0, LIST_TOP + 2, trackX1, listBottom - 2, 0xFF0E1015);
            g.fill(trackX0, thumbY0, trackX1, thumbY1, thumbColor);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        int w = panelWidth();
        int x = (this.width - w) / 2;
        boolean hasScroll = hasScrollbar();
        int contentRight = hasScroll ? x + w - SCROLLBAR_W - SCROLLBAR_PAD * 2 : x + w;

        if (hasScroll && event.button() == 0) {
            int trackX0 = x + w - SCROLLBAR_W - SCROLLBAR_PAD;
            int trackX1 = x + w - SCROLLBAR_PAD;
            if (event.x() >= trackX0 && event.x() < trackX1 && event.y() >= LIST_TOP + 2 && event.y() < listBottom - 2) {
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
        if (scrollY != 0 && !rows.isEmpty()) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
