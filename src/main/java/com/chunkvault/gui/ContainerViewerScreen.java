package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.save.ContainerContentReader;
import com.chunkvault.save.ContainerDatabase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ContainerViewerScreen extends Screen {

    private static final int SLOT_SIZE = 18;
    private static final int SIDEBAR_W = 148;
    private static final int SIDEBAR_GAP = 8;
    private static final int ROW_H = 20;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_PAD = 2;

    private record SlotRect(int index, int x, int y, ItemStack stack) {}

    private final Screen parent;
    private final List<ContainerDatabase.ContainerEntry> stackEntries;
    private int selectedIndex;
    private ContainerContentReader.DecodedContainer decoded;

    private ItemStack nestedShulker;
    private ItemStack[] nestedSlots;

    private final List<SlotRect> slotRects = new ArrayList<>();
    private int scroll;
    private boolean draggingScrollbar;
    private double dragOffsetY;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listTop;
    private int listBottom;
    private int mainX;
    private int mainW;

    private String status = "";
    private long statusUntil;

    public ContainerViewerScreen(Screen parent, String dimension, int x, int z, String initialKey) {
        super(Lang.c("viewer.title"));
        this.parent = parent;
        List<ContainerDatabase.ContainerEntry> raw = ContainerDatabase.atColumn(dimension, x, z);
        List<ContainerDatabase.ContainerEntry> filtered = new ArrayList<>();
        for (ContainerDatabase.ContainerEntry e : raw) {
            if (!ContainerDatabase.isSecondaryHalf(e)) {
                filtered.add(e);
            }
        }
        if (filtered.isEmpty() && !raw.isEmpty()) {
            filtered.add(raw.get(0));
        }
        this.stackEntries = filtered;
        this.selectedIndex = 0;
        if (initialKey != null) {
            for (int i = 0; i < stackEntries.size(); i++) {
                ContainerDatabase.ContainerEntry e = stackEntries.get(i);
                if (initialKey.equals(e.key())) {
                    this.selectedIndex = i;
                    break;
                }
                ContainerDatabase.ContainerEntry partner = ContainerDatabase.partnerOf(e);
                if (partner != null && initialKey.equals(partner.key())) {
                    this.selectedIndex = i;
                    break;
                }
            }
        }
        reloadCurrent();
    }

    private ContainerDatabase.ContainerEntry currentEntry() {
        if (stackEntries.isEmpty()) {
            return null;
        }
        int idx = Math.max(0, Math.min(selectedIndex, stackEntries.size() - 1));
        return stackEntries.get(idx);
    }

    private void reloadCurrent() {
        nestedShulker = null;
        nestedSlots = null;
        ContainerDatabase.ContainerEntry entry = currentEntry();
        var level = Minecraft.getInstance().level;
        if (entry != null && level != null && entry.opened()) {
            decoded = ContainerContentReader.decode(entry, level.registryAccess());
        } else if (entry != null) {
            var partner = ContainerDatabase.partnerOf(entry);
            boolean dbl = partner != null && entry.slotCount() == 27 && partner.slotCount() == 27;
            int total = dbl ? 54 : entry.slotCount();
            decoded = new ContainerContentReader.DecodedContainer(new ItemStack[total], total, 0, dbl);
        } else {
            decoded = new ContainerContentReader.DecodedContainer(new ItemStack[0], 0, 0, false);
        }
        rebuildSlotLayout();
        updateNestedButton();
    }

    private boolean hasSidebar() {
        return stackEntries.size() > 1;
    }

    @Override
    protected void init() {
        super.init();
        draggingScrollbar = false;
        computeGeometry();
        rebuildSlotLayout();

        int btnY = panelY + panelH - 28;
        int innerW = mainW - 16;
        int btnX = mainX + 8;

        if (nestedShulker != null) {
            int third = (innerW - 12) / 3;
            this.addRenderableWidget(Button.builder(Lang.c("viewer.back_parent"), b -> exitNestedShulker())
                    .bounds(btnX, btnY, third, 20)
                    .build());
            this.addRenderableWidget(Button.builder(Lang.c("viewer.copy_coords"), b -> copyCoords())
                    .bounds(btnX + third + 6, btnY, third, 20)
                    .build());
            this.addRenderableWidget(Button.builder(Lang.c("common.back"), b -> this.onClose())
                    .bounds(btnX + (third + 6) * 2, btnY, innerW - (third + 6) * 2, 20)
                    .build());
        } else {
            int half = (innerW - 6) / 2;
            this.addRenderableWidget(Button.builder(Lang.c("viewer.copy_coords"), b -> copyCoords())
                    .bounds(btnX, btnY, half, 20)
                    .build());
            this.addRenderableWidget(Button.builder(Lang.c("common.back"), b -> this.onClose())
                    .bounds(btnX + half + 6, btnY, innerW - half - 6, 20)
                    .build());
        }
    }

    private void updateNestedButton() {
        if (this.width > 0 && this.height > 0) {
            this.rebuildWidgets();
        }
    }

    private void exitNestedShulker() {
        nestedShulker = null;
        nestedSlots = null;
        rebuildSlotLayout();
        this.rebuildWidgets();
    }

    private void copyCoords() {
        ContainerDatabase.ContainerEntry entry = currentEntry();
        if (entry == null) {
            return;
        }
        String coords = entry.x() + " " + entry.y() + " " + entry.z();
        Minecraft.getInstance().keyboardHandler.setClipboard(coords);
        status = Lang.s("viewer.copied", coords);
        statusUntil = System.currentTimeMillis() + 3000;
    }

    private void computeGeometry() {
        int gridH = activeGridPixelHeight();
        mainW = 240;
        int totalW = hasSidebar() ? SIDEBAR_W + SIDEBAR_GAP + mainW : mainW;
        panelW = Math.min(totalW, this.width - 20);
        if (hasSidebar()) {
            mainW = Math.max(200, panelW - SIDEBAR_W - SIDEBAR_GAP);
        } else {
            mainW = panelW;
        }
        panelH = Math.max(196, 62 + gridH + 50);
        panelH = Math.min(panelH, this.height - 20);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        if (hasSidebar()) {
            listX = panelX;
            listTop = panelY + 24;
            listBottom = panelY + panelH - 8;
            mainX = panelX + SIDEBAR_W + SIDEBAR_GAP;
        } else {
            listX = panelX;
            listTop = panelY;
            listBottom = panelY;
            mainX = panelX;
        }
    }

    private int activeGridPixelHeight() {
        if (nestedShulker != null) {
            return 3 * SLOT_SIZE + 16;
        }
        ContainerDatabase.ContainerEntry entry = currentEntry();
        if (entry == null || decoded == null) {
            return 3 * SLOT_SIZE + 16;
        }
        int total = decoded.totalSlots();
        String id = entry.blockId();
        if (total == 3 && (id.contains("furnace") || id.endsWith(":smoker"))) {
            return 3 * SLOT_SIZE + 16;
        }
        if (total == 5 && id.endsWith(":brewing_stand")) {
            return 3 * SLOT_SIZE + 20;
        }
        if (total == 9) {
            return 3 * SLOT_SIZE + 16;
        }
        if (total == 5) {
            return SLOT_SIZE + 24;
        }
        int rows = Math.max(1, (total + 8) / 9);
        return rows * SLOT_SIZE + 16;
    }

    private void rebuildSlotLayout() {
        slotRects.clear();
        ItemStack[] items = nestedShulker != null ? nestedSlots : (decoded == null ? null : decoded.slots());
        if (items == null || items.length == 0) {
            return;
        }
        int gridTop = panelY + 54;
        int gridCenterX = mainX + mainW / 2;

        if (nestedShulker != null) {
            addGrid(items, 9, gridCenterX, gridTop + 8);
            return;
        }

        ContainerDatabase.ContainerEntry entry = currentEntry();
        String id = entry == null ? "" : entry.blockId();
        int n = items.length;

        if (n == 3 && (id.contains("furnace") || id.endsWith(":smoker"))) {
            int leftX = gridCenterX - 38;
            int rightX = gridCenterX + 20;
            slotRects.add(new SlotRect(0, leftX, gridTop + 8, items[0]));
            slotRects.add(new SlotRect(1, leftX, gridTop + 44, items[1]));
            slotRects.add(new SlotRect(2, rightX, gridTop + 26, items[2]));
            return;
        }
        if (n == 5 && id.endsWith(":brewing_stand")) {
            slotRects.add(new SlotRect(4, gridCenterX - 54, gridTop + 8, items[4]));
            slotRects.add(new SlotRect(3, gridCenterX - 9, gridTop + 8, items[3]));
            slotRects.add(new SlotRect(0, gridCenterX - 34, gridTop + 40, items[0]));
            slotRects.add(new SlotRect(1, gridCenterX - 9, gridTop + 46, items[1]));
            slotRects.add(new SlotRect(2, gridCenterX + 16, gridTop + 40, items[2]));
            return;
        }
        if (n == 9) {
            addGrid(items, 3, gridCenterX, gridTop + 8);
            return;
        }
        if (n == 5) {
            addGrid(items, 5, gridCenterX, gridTop + 12);
            return;
        }
        addGrid(items, 9, gridCenterX, gridTop + 8);
    }

    private void addGrid(ItemStack[] items, int cols, int centerX, int startY) {
        int startX = centerX - (cols * SLOT_SIZE) / 2;
        for (int i = 0; i < items.length; i++) {
            int col = i % cols;
            int row = i / cols;
            slotRects.add(new SlotRect(i, startX + col * SLOT_SIZE, startY + row * SLOT_SIZE, items[i]));
        }
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, stackEntries.size() - visibleRows());
    }

    private boolean hasScrollbar() {
        return hasSidebar() && stackEntries.size() > visibleRows();
    }

    private int scrollbarThumbHeight() {
        int trackH = Math.max(1, (listBottom - 2) - (listTop + 2));
        int rows = visibleRows();
        if (stackEntries.size() <= rows) {
            return trackH;
        }
        return Math.max(20, Math.min((int) Math.round((double) trackH * rows / stackEntries.size()), trackH));
    }

    private int scrollbarThumbTop() {
        int trackTop = listTop + 2;
        int travel = Math.max(0, (listBottom - 2) - trackTop - scrollbarThumbHeight());
        int max = maxScroll();
        if (travel <= 0 || max <= 0) {
            return trackTop;
        }
        return trackTop + (int) Math.round((double) travel * scroll / max);
    }

    private void scrollFromThumbTop(double thumbTop) {
        int trackTop = listTop + 2;
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);

        if (hasSidebar()) {
            g.fill(listX - 1, panelY - 1, listX + SIDEBAR_W + 1, panelY + panelH + 1, 0xFF3A3A48);
            g.fill(listX, panelY, listX + SIDEBAR_W, panelY + panelH, 0xFF14161C);
            g.text(this.font, Lang.s("viewer.column_header", stackEntries.size()), listX + 8, panelY + 8, 0xFF808090, true);

            int rows = visibleRows();
            scroll = Math.max(0, Math.min(scroll, maxScroll()));
            boolean hasScroll = hasScrollbar();
            int contentRight = hasScroll ? listX + SIDEBAR_W - SCROLLBAR_W - SCROLLBAR_PAD * 2 : listX + SIDEBAR_W;

            for (int i = 0; i < rows && scroll + i < stackEntries.size(); i++) {
                int idx = scroll + i;
                int ry = listTop + i * ROW_H;
                boolean hover = !draggingScrollbar
                        && mouseX >= listX && mouseX < contentRight
                        && mouseY >= ry && mouseY < ry + ROW_H;
                int bg = idx == selectedIndex ? 0xFF8A6A1A : hover ? 0xFF262A34 : 0xFF1C1F27;
                g.fill(listX + 2, ry, contentRight - 2, ry + ROW_H - 1, bg);
                ContainerDatabase.ContainerEntry e = stackEntries.get(idx);
                g.fill(listX + 2, ry, listX + 5, ry + ROW_H - 1, ContainerDatabase.colorFor(e));
                String yTag = "Y:" + e.y();
                int yW = this.font.width(yTag);
                g.text(this.font, fit(e.displayTitle(), contentRight - listX - yW - 18), listX + 9, ry + 6, 0xFFFFFFFF, true);
                g.text(this.font, yTag, contentRight - yW - 6, ry + 6, 0xFF9AA0B0, true);
            }

            if (hasScroll) {
                int trackX0 = listX + SIDEBAR_W - SCROLLBAR_W - SCROLLBAR_PAD;
                int trackX1 = listX + SIDEBAR_W - SCROLLBAR_PAD;
                int thumbY0 = scrollbarThumbTop();
                int thumbY1 = thumbY0 + scrollbarThumbHeight();
                boolean thumbHover = mouseX >= trackX0 && mouseX < trackX1 && mouseY >= thumbY0 && mouseY < thumbY1;
                int thumbColor = draggingScrollbar ? 0xFF9AA4C0 : thumbHover ? 0xFF7A829C : 0xFF5A6074;
                g.fill(trackX0, listTop + 2, trackX1, listBottom - 2, 0xFF0E1015);
                g.fill(trackX0, thumbY0, trackX1, thumbY1, thumbColor);
            }
        }

        g.fill(mainX - 1, panelY - 1, mainX + mainW + 1, panelY + panelH + 1, 0xFF3A3A48);
        g.fill(mainX, panelY, mainX + mainW, panelY + panelH, 0xFF14161C);

        ContainerDatabase.ContainerEntry entry = currentEntry();
        if (entry != null) {
            String headerTitle;
            if (nestedShulker != null) {
                headerTitle = nestedShulker.getHoverName().getString();
            } else {
                headerTitle = entry.displayTitle();
                if (decoded != null && decoded.doubleChest()) {
                    headerTitle += " (" + Lang.s("viewer.double") + ")";
                }
            }
            g.text(this.font, fit(headerTitle, mainW - 16), mainX + 8, panelY + 8, 0xFFFFFFFF, true);

            String coords = "X:" + entry.x() + " Y:" + entry.y() + " Z:" + entry.z();
            String sub1;
            if (nestedShulker != null) {
                sub1 = coords + "  •  " + countNonEmpty(nestedSlots) + "/27";
            } else if (!entry.opened()) {
                sub1 = coords + "  •  " + Lang.s("db.unopened_tag");
            } else {
                int filled = decoded != null ? decoded.filledSlots() : 0;
                int total = decoded != null ? decoded.totalSlots() : 0;
                sub1 = coords + "  •  " + filled + "/" + total;
            }
            g.text(this.font, fit(sub1, mainW - 16), mainX + 8, panelY + 22, 0xFF9AA0B0, true);

            String dateStr = entry.updatedAt() > 0
                    ? new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(entry.updatedAt()))
                    : "—";
            String sub2 = shortDim(entry.dimension()) + "  •  " + dateStr;
            g.text(this.font, fit(sub2, mainW - 16), mainX + 8, panelY + 34, 0xFF808090, true);
        }

        int gridBoxX0 = mainX + 8;
        int gridBoxY0 = panelY + 50;
        int gridBoxX1 = mainX + mainW - 8;
        int gridBoxY1 = panelY + panelH - 44;
        g.fill(gridBoxX0 - 1, gridBoxY0 - 1, gridBoxX1 + 1, gridBoxY1 + 1, 0xFF2A2D38);
        g.fill(gridBoxX0, gridBoxY0, gridBoxX1, gridBoxY1, 0xFF0E1015);

        if (nestedShulker == null && entry != null && !entry.opened()) {
            g.centeredText(this.font, fit(Lang.s("viewer.unopened_notice"), mainW - 24),
                    mainX + mainW / 2, (gridBoxY0 + gridBoxY1) / 2 - 4, 0xFFFFD070);
        } else {
            if (nestedShulker == null && entry != null && decoded != null && decoded.totalSlots() == 3
                    && (entry.blockId().contains("furnace") || entry.blockId().endsWith(":smoker"))) {
                g.centeredText(this.font, "→", mainX + mainW / 2 - 4, panelY + 85, 0xFF808090);
            }

            ItemStack hoveredStack = null;
            for (SlotRect slot : slotRects) {
                int sx = slot.x();
                int sy = slot.y();
                boolean hover = mouseX >= sx && mouseX < sx + SLOT_SIZE && mouseY >= sy && mouseY < sy + SLOT_SIZE;
                g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0xFF1C1F27);
                g.fill(sx, sy, sx + SLOT_SIZE, sy + 1, 0xFF0D0F14);
                g.fill(sx, sy, sx + 1, sy + SLOT_SIZE, 0xFF0D0F14);
                g.fill(sx, sy + SLOT_SIZE - 1, sx + SLOT_SIZE, sy + SLOT_SIZE, 0xFF2E3342);
                g.fill(sx + SLOT_SIZE - 1, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0xFF2E3342);

                ItemStack stack = slot.stack();
                if (stack != null && !stack.isEmpty()) {
                    g.item(stack, sx + 1, sy + 1);
                    g.itemDecorations(this.font, stack, sx + 1, sy + 1);
                    if (nestedShulker == null && ContainerContentReader.isShulkerStack(stack)) {
                        g.fill(sx + SLOT_SIZE - 4, sy + 1, sx + SLOT_SIZE - 1, sy + 4, 0xFFC864FF);
                    }
                    if (hover) {
                        hoveredStack = stack;
                    }
                }
                if (hover) {
                    g.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, 0x40FFFFFF);
                }
            }

            if (hoveredStack != null) {
                g.setTooltipForNextFrame(this.font, hoveredStack, mouseX, mouseY);
            }
        }

        if (status != null && !status.isEmpty() && System.currentTimeMillis() < statusUntil) {
            g.text(this.font, fit(status, mainW - 16), mainX + 8, panelY + panelH - 40, 0xFF70FF90, true);
        } else if (nestedShulker == null && entry != null && entry.opened() && hasAnyShulker()) {
            g.text(this.font, fit(Lang.s("viewer.shulker_hint"), mainW - 16), mainX + 8, panelY + panelH - 40, 0xFFC864FF, true);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private boolean hasAnyShulker() {
        if (decoded == null) {
            return false;
        }
        for (ItemStack s : decoded.slots()) {
            if (ContainerContentReader.isShulkerStack(s)) {
                return true;
            }
        }
        return false;
    }

    private static int countNonEmpty(ItemStack[] arr) {
        if (arr == null) {
            return 0;
        }
        int c = 0;
        for (ItemStack s : arr) {
            if (s != null && !s.isEmpty()) {
                c++;
            }
        }
        return c;
    }

    private static String shortDim(String dim) {
        if (dim == null) {
            return "";
        }
        int idx = dim.indexOf(':');
        return idx >= 0 ? dim.substring(idx + 1) : dim;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (hasSidebar() && event.button() == 0) {
            boolean hasScroll = hasScrollbar();
            int contentRight = hasScroll ? listX + SIDEBAR_W - SCROLLBAR_W - SCROLLBAR_PAD * 2 : listX + SIDEBAR_W;
            if (hasScroll) {
                int trackX0 = listX + SIDEBAR_W - SCROLLBAR_W - SCROLLBAR_PAD;
                int trackX1 = listX + SIDEBAR_W - SCROLLBAR_PAD;
                if (event.x() >= trackX0 && event.x() < trackX1 && event.y() >= listTop + 2 && event.y() < listBottom - 2) {
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
            if (event.x() >= listX && event.x() < contentRight && event.y() >= listTop && event.y() < listBottom) {
                int row = (int) ((event.y() - listTop) / ROW_H);
                int idx = scroll + row;
                if (row >= 0 && row < visibleRows() && idx >= 0 && idx < stackEntries.size()) {
                    if (idx != selectedIndex) {
                        selectedIndex = idx;
                        reloadCurrent();
                        computeGeometry();
                        rebuildSlotLayout();
                    }
                }
                return true;
            }
        }

        ContainerDatabase.ContainerEntry cur = currentEntry();
        if (event.button() == 0 && nestedShulker == null && cur != null && cur.opened()) {
            for (SlotRect slot : slotRects) {
                if (event.x() >= slot.x() && event.x() < slot.x() + SLOT_SIZE
                        && event.y() >= slot.y() && event.y() < slot.y() + SLOT_SIZE) {
                    if (ContainerContentReader.isShulkerStack(slot.stack())) {
                        nestedShulker = slot.stack();
                        nestedSlots = ContainerContentReader.decodeNestedShulker(slot.stack());
                        computeGeometry();
                        rebuildSlotLayout();
                        this.rebuildWidgets();
                        return true;
                    }
                }
            }
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
        if (hasSidebar() && scrollY != 0 && mouseX >= listX && mouseX < listX + SIDEBAR_W) {
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
