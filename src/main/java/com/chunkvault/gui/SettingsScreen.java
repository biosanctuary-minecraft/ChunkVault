package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.chunkvault.save.ContainerDatabase;
import com.chunkvault.state.AutoRecord;
import com.chunkvault.state.ChunkVaultSettings;
import com.chunkvault.state.ChunkVaultSettings.MinimapHudItem;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;
import java.util.Locale;

public class SettingsScreen extends Screen {

    private static final int TAB_RECORDING = 0;
    private static final int TAB_WORLD = 1;
    private static final int TAB_MINIMAP = 2;
    private static final int TAB_HOTKEYS = 3;
    private static final int TAB_COUNT = 4;

    private static final int[] TAB_ACCENTS = {
            0xFF3FB365,
            0xFFA870FF,
            0xFF38D8FF,
            0xFF4A90E2
    };

    private static final String[] TAB_KEYS = {
            "settings.tab.recording",
            "settings.tab.world",
            "settings.tab.minimap",
            "settings.tab.hotkeys"
    };

    private static final MinimapHudItem[] HUD_ITEMS = MinimapHudItem.values();
    private static final int[] HUD_ACCENTS = {
            0xFFFF6060,
            0xFFE6ECF5,
            0xFFCCCCCC,
            0xFFFFD866,
            0xFF7CE8A8,
            0xFFFFB830,
            0xFF66D8FF,
            0xFF38D8FF,
            0xFFB894FF,
            0xFF9EE09E
    };

    private static final int ROW_H = 28;
    private static final int PANEL_MAX_W = 460;
    private static final int BADGE_W = 96;
    private static final int STEP_BTN_W = 18;
    private static final int FILTER_W = 72;
    private static final int SEED_W = 162;
    private static final int KEY_BTN_W = 132;
    private static final int RESET_BTN_W = 22;
    private static final int SCROLLBAR_W = 8;

    private static int lastTab = TAB_RECORDING;

    private final String serverKey = AutoRecord.serverKey();
    private final Screen parent;
    private final long openedAtMs = System.currentTimeMillis();

    private int activeTab = lastTab;
    private EditBox seedBox;
    private String seedText;
    private List<ChunkVaultClient.HotkeyEntry> hotkeyEntries = List.of();
    private KeyMapping listening;

    private int listX0;
    private int listX1;
    private int tabY0;
    private int tabY1;
    private int listY0;
    private int listY1;
    private int scroll;
    private boolean draggingScrollbar;
    private double scrollDragOffset;
    private int doneY0;
    private int doneY1;
    private int resetAllX0;
    private int resetAllX1;
    private int doneX0;
    private int doneX1;

    public SettingsScreen() {
        this(null);
    }

    public SettingsScreen(Screen parent) {
        super(Lang.c("settings.title"));
        this.parent = parent;
        this.seedText = Long.toString(ChunkVaultSettings.get().seed());
    }

    @Override
    protected void init() {
        super.init();
        hotkeyEntries = ChunkVaultClient.hotkeys();
        int panelW = Math.min(PANEL_MAX_W, this.width - 32);
        listX0 = (this.width - panelW) / 2;
        listX1 = listX0 + panelW;
        tabY0 = 38;
        tabY1 = 58;
        listY0 = tabY1 + 2;
        listY1 = this.height - 44;
        clampScroll();

        int contentRight = maxScroll() > 0 ? listX1 - SCROLLBAR_W - 2 : listX1;
        seedBox = new EditBox(this.font, contentRight - 6 - SEED_W, listY0 + 2 * ROW_H + 4, SEED_W, 19, Lang.c("settings.seed_box"));
        seedBox.setMaxLength(20);
        seedBox.setValue(seedText == null ? "" : seedText);
        seedBox.setResponder(s -> seedText = s);
        seedBox.setVisible(activeTab == TAB_WORLD);
        this.addRenderableWidget(seedBox);

        doneY0 = this.height - 32;
        doneY1 = doneY0 + 22;
        int half = (panelW - 8) / 2;
        resetAllX0 = listX0;
        resetAllX1 = listX0 + half;
        doneX0 = resetAllX1 + 8;
        doneX1 = listX1;
    }

    private int currentRowCount() {
        return switch (activeTab) {
            case TAB_RECORDING -> 6;
            case TAB_WORLD -> 3;
            case TAB_MINIMAP -> 6 + HUD_ITEMS.length;
            case TAB_HOTKEYS -> hotkeyEntries.size();
            default -> 0;
        };
    }

    private int maxScroll() {
        return Math.max(0, currentRowCount() * ROW_H - (listY1 - listY0));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    private int thumbHeight() {
        int trackH = listY1 - listY0;
        int totalH = Math.max(1, currentRowCount() * ROW_H);
        return Math.max(22, (int) ((long) trackH * trackH / totalH));
    }

    private int thumbY() {
        int maxS = maxScroll();
        if (maxS <= 0) {
            return listY0;
        }
        int trackH = listY1 - listY0;
        int thumbH = thumbHeight();
        return listY0 + (int) ((long) (trackH - thumbH) * scroll / maxS);
    }

    private void setScrollFromThumbY(double targetThumbY) {
        int maxS = maxScroll();
        if (maxS <= 0) {
            scroll = 0;
            return;
        }
        int travel = Math.max(1, (listY1 - listY0) - thumbHeight());
        double ratio = (targetThumbY - listY0) / travel;
        scroll = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * maxS);
        clampScroll();
    }

    private void selectTab(int tab) {
        if (tab == activeTab || tab < 0 || tab >= TAB_COUNT) {
            return;
        }
        if (activeTab == TAB_WORLD) {
            applySeed();
        }
        activeTab = tab;
        lastTab = tab;
        scroll = 0;
        listening = null;
        draggingScrollbar = false;
        if (seedBox != null) {
            seedBox.setFocused(false);
            seedBox.setVisible(activeTab == TAB_WORLD);
        }
    }

    private void applySeed() {
        if (seedBox != null) {
            seedText = seedBox.getValue();
        }
        try {
            String text = seedText == null ? "" : seedText.trim();
            long seed = text.isEmpty() ? 0L : Long.parseLong(text);
            ChunkVaultSettings.get().setSeed(seed);
        } catch (NumberFormatException ignored) {
        }
    }

    private static String formatZoom(double z) {
        if (Math.abs(z - Math.rint(z)) < 0.001) {
            return ((int) Math.rint(z)) + "x";
        }
        if (Math.abs(z * 10.0 - Math.rint(z * 10.0)) < 0.001) {
            return String.format(Locale.ROOT, "%.1fx", z);
        }
        return String.format(Locale.ROOT, "%.2fx", z);
    }

    private void resetAllHotkeys() {
        listening = null;
        for (ChunkVaultClient.HotkeyEntry entry : hotkeyEntries) {
            entry.mapping().setKey(entry.mapping().getDefaultKey());
        }
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    private boolean hasConflict(KeyMapping target) {
        if (target.isUnbound()) {
            return false;
        }
        String keyStr = target.saveString();
        int count = 0;
        for (ChunkVaultClient.HotkeyEntry e : hotkeyEntries) {
            if (!e.mapping().isUnbound() && keyStr.equals(e.mapping().saveString())) {
                count++;
                if (count > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private String keyBadgeText(ChunkVaultClient.HotkeyEntry entry) {
        KeyMapping m = entry.mapping();
        if (m == listening) {
            return Lang.s("hotkeys.press_key");
        }
        if (m.isUnbound()) {
            return Lang.s("hotkeys.unbound");
        }
        if (entry.comboDefault() && m.isDefault()) {
            return "C + " + m.getTranslatedKeyMessage().getString();
        }
        return m.getTranslatedKeyMessage().getString();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening != null) {
            if (event.isEscape()) {
                listening.setKey(InputConstants.UNKNOWN);
            } else {
                listening.setKey(InputConstants.getKey(event));
            }
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
            listening = null;
            return true;
        }
        if (System.currentTimeMillis() - openedAtMs >= 180L
                && !(this.getFocused() instanceof EditBox eb && eb.canConsumeInput())
                && ChunkVaultClient.matchesOpenSettings(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        listening = null;
        applySeed();
        if (parent != null) {
            Minecraft.getInstance().setScreenAndShow(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);
        g.centeredText(this.font, Lang.s("settings.title"), this.width / 2, 10, 0xFFFFFFFF);
        String subtitle = activeTab == TAB_HOTKEYS
                ? Lang.s("hotkeys.hint")
                : (activeTab == TAB_MINIMAP ? Lang.s("minimap.settings.subtitle") : Lang.s("settings.seed_note"));
        g.centeredText(this.font, subtitle, this.width / 2, 23, 0xFF9AA0B0);

        drawTabs(g, mouseX, mouseY);

        g.fill(listX0 - 1, listY0 - 1, listX1 + 1, listY1 + 1, 0xFF3A3A48);
        g.fill(listX0, listY0, listX1, listY1, 0xFF14161C);

        int maxS = maxScroll();
        int contentRight = maxS > 0 ? listX1 - SCROLLBAR_W - 2 : listX1;
        ChunkVaultSettings s = ChunkVaultSettings.get();

        if (seedBox != null) {
            if (activeTab == TAB_WORLD) {
                int sy = listY0 + 2 * ROW_H - scroll + 4;
                boolean vis = sy >= listY0 && sy + 19 <= listY1;
                seedBox.setX(contentRight - 6 - SEED_W);
                seedBox.setY(sy);
                seedBox.setVisible(vis);
                seedBox.active = vis && s.useSeed();
            } else {
                seedBox.setVisible(false);
                seedBox.active = false;
            }
        }

        g.enableScissor(listX0, listY0, listX1, listY1);
        switch (activeTab) {
            case TAB_RECORDING -> drawRecordingTab(g, contentRight, mouseX, mouseY, s);
            case TAB_WORLD -> drawWorldTab(g, contentRight, mouseX, mouseY, s);
            case TAB_MINIMAP -> drawMinimapTab(g, contentRight, mouseX, mouseY, s);
            case TAB_HOTKEYS -> drawHotkeysTab(g, contentRight, mouseX, mouseY);
            default -> {}
        }
        g.disableScissor();

        if (maxS > 0) {
            int trackX0 = listX1 - SCROLLBAR_W;
            g.fill(trackX0, listY0, listX1, listY1, 0xFF0E1015);
            int thumbH = thumbHeight();
            int thumbTop = thumbY();
            boolean overThumb = mouseX >= trackX0 && mouseX < listX1
                    && mouseY >= thumbTop && mouseY < thumbTop + thumbH;
            int thumbCol = draggingScrollbar ? 0xFFB0BCD4 : (overThumb ? 0xFF8A96B0 : 0xFF5A6074);
            g.fill(trackX0 + 1, thumbTop, listX1 - 1, thumbTop + thumbH, thumbCol);
        }

        if (activeTab == TAB_HOTKEYS) {
            boolean rHover = mouseX >= resetAllX0 && mouseX < resetAllX1 && mouseY >= doneY0 && mouseY < doneY1;
            g.fill(resetAllX0, doneY0, resetAllX1, doneY1, rHover ? 0xFFFFFFFF : 0xFF9A3838);
            g.fill(resetAllX0 + 1, doneY0 + 1, resetAllX1 - 1, doneY1 - 1, rHover ? 0xFF6B2C2C : 0xFF4A2020);
            g.centeredText(this.font, Lang.s("hotkeys.reset_all"), (resetAllX0 + resetAllX1) / 2, doneY0 + 7, 0xFFFFB0B0);

            boolean dHover = mouseX >= doneX0 && mouseX < doneX1 && mouseY >= doneY0 && mouseY < doneY1;
            g.fill(doneX0, doneY0, doneX1, doneY1, dHover ? 0xFFFFFFFF : 0xFF50C878);
            g.fill(doneX0 + 1, doneY0 + 1, doneX1 - 1, doneY1 - 1, dHover ? 0xFF2D6B48 : 0xFF1F5236);
            g.centeredText(this.font, Lang.s("common.done"), (doneX0 + doneX1) / 2, doneY0 + 7, 0xFF90FFB0);
        } else {
            boolean dHover = mouseX >= listX0 && mouseX < listX1 && mouseY >= doneY0 && mouseY < doneY1;
            g.fill(listX0, doneY0, listX1, doneY1, dHover ? 0xFFFFFFFF : 0xFF50C878);
            g.fill(listX0 + 1, doneY0 + 1, listX1 - 1, doneY1 - 1, dHover ? 0xFF2D6B48 : 0xFF1F5236);
            g.centeredText(this.font, Lang.s("common.done"), (listX0 + listX1) / 2, doneY0 + 7, 0xFF90FFB0);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int panelW = listX1 - listX0;
        int gap = 3;
        int tabW = (panelW - gap * (TAB_COUNT - 1)) / TAB_COUNT;
        for (int i = 0; i < TAB_COUNT; i++) {
            int tx0 = listX0 + i * (tabW + gap);
            int tx1 = (i == TAB_COUNT - 1) ? listX1 : tx0 + tabW;
            boolean active = i == activeTab;
            boolean hover = mouseX >= tx0 && mouseX < tx1 && mouseY >= tabY0 && mouseY < tabY1;
            int border = active ? TAB_ACCENTS[i] : (hover ? 0xFF7A8298 : 0xFF2E3240);
            int bg = active ? 0xFF1E232E : (hover ? 0xFF1B1F2A : 0xFF12141B);
            g.fill(tx0, tabY0, tx1, tabY1, border);
            g.fill(tx0 + 1, tabY0 + (active ? 2 : 1), tx1 - 1, tabY1 - 1, bg);
            int textCol = active ? 0xFFFFFFFF : (hover ? 0xFFD8DEE9 : 0xFF8C94A6);
            g.centeredText(this.font, Lang.s(TAB_KEYS[i]), (tx0 + tx1) / 2, tabY0 + 6, textCol);
        }
    }

    private void drawRecordingTab(GuiGraphicsExtractor g, int contentRight, int mouseX, int mouseY, ChunkVaultSettings s) {
        boolean autoOn = AutoRecord.isAuto(serverKey);
        drawToggleRow(g, 0, contentRight, mouseX, mouseY, 0xFF3FB365,
                Lang.s("settings.row.auto"), Lang.s("settings.row.auto.desc"),
                autoOn ? Lang.s("common.on") : Lang.s("common.off"), autoOn, true);

        drawToggleRow(g, 1, contentRight, mouseX, mouseY, 0xFF38D8FF,
                Lang.s("settings.row.entities"), Lang.s("settings.row.entities.desc"),
                s.saveEntities() ? Lang.s("common.on") : Lang.s("common.off"), s.saveEntities(), true);

        drawContainersRow(g, 2, contentRight, mouseX, mouseY, s);

        boolean showUnopened = ContainerDatabase.showUnopened();
        drawToggleRow(g, 3, contentRight, mouseX, mouseY, 0xFFFF9040,
                Lang.s("settings.row.unopened"), Lang.s("settings.row.unopened.desc"),
                showUnopened ? Lang.s("common.on") : Lang.s("common.off"), showUnopened, true);

        drawToggleRow(g, 4, contentRight, mouseX, mouseY, 0xFF55CCFF,
                Lang.s("settings.row.player_inventory"), Lang.s("settings.row.player_inventory.desc"),
                s.savePlayerInventory() ? Lang.s("common.on") : Lang.s("common.off"), s.savePlayerInventory(), true);

        drawToggleRow(g, 5, contentRight, mouseX, mouseY, 0xFF28B8A8,
                Lang.s("settings.row.ender_chest"), Lang.s("settings.row.ender_chest.desc"),
                s.saveEnderChest() ? Lang.s("common.on") : Lang.s("common.off"), s.saveEnderChest(), true);
    }

    private void drawWorldTab(GuiGraphicsExtractor g, int contentRight, int mouseX, int mouseY, ChunkVaultSettings s) {
        drawToggleRow(g, 0, contentRight, mouseX, mouseY, 0xFFFF6060,
                Lang.s("settings.row.mobs"), Lang.s("settings.row.mobs.desc"),
                s.preventMobSpawning() ? Lang.s("settings.mobs.blocked") : Lang.s("settings.mobs.allowed"),
                s.preventMobSpawning(), true);

        drawToggleRow(g, 1, contentRight, mouseX, mouseY, 0xFFA870FF,
                Lang.s("settings.row.gen"), Lang.s("settings.row.gen.desc"),
                s.useSeed() ? Lang.s("settings.gen.seed_short") : Lang.s("settings.gen.void"),
                s.useSeed(), true);

        drawSeedRow(g, 2, contentRight, mouseX, mouseY, s.useSeed());
    }

    private void drawMinimapTab(GuiGraphicsExtractor g, int contentRight, int mouseX, int mouseY, ChunkVaultSettings s) {
        boolean mapOn = s.minimapEnabled();
        boolean hudOn = mapOn && s.minimapHudEnabled();

        drawToggleRow(g, 0, contentRight, mouseX, mouseY, 0xFF38D8FF,
                Lang.s("settings.row.minimap"), Lang.s("settings.row.minimap.desc"),
                mapOn ? Lang.s("common.on") : Lang.s("common.off"), mapOn, true);

        drawActionRow(g, 1, contentRight, mouseX, mouseY, mapOn ? 0xFF2AA8C4 : 0xFF3E4454,
                Lang.s("settings.row.minimap_pos"), Lang.s("settings.row.minimap_pos.desc"),
                Lang.s("settings.minimap.edit"), mapOn);

        drawStepperRow(g, 2, contentRight, mouseX, mouseY, 0xFF2AA8C4,
                Lang.s("settings.row.minimap_size"), Lang.s("settings.row.minimap_size.desc"),
                Lang.s("settings.minimap.size_px", s.minimapSize()),
                mapOn,
                s.minimapSize() > ChunkVaultSettings.MINIMAP_MIN_SIZE,
                s.minimapSize() < ChunkVaultSettings.MINIMAP_MAX_SIZE);

        drawStepperRow(g, 3, contentRight, mouseX, mouseY, 0xFF2AA8C4,
                Lang.s("settings.row.minimap_zoom"), Lang.s("settings.row.minimap_zoom.desc"),
                formatZoom(s.minimapZoom()),
                mapOn,
                s.minimapZoom() > ChunkVaultSettings.MINIMAP_ZOOMS[0] + 0.001,
                s.minimapZoom() < ChunkVaultSettings.MINIMAP_ZOOMS[ChunkVaultSettings.MINIMAP_ZOOMS.length - 1] - 0.001);

        drawToggleRow(g, 4, contentRight, mouseX, mouseY, mapOn ? 0xFF70FF90 : 0xFF3E4454,
                Lang.s("minimap.settings.hud_bar"), Lang.s("minimap.settings.hud_bar.desc"),
                s.minimapHudEnabled() ? Lang.s("common.on") : Lang.s("common.off"),
                s.minimapHudEnabled(), mapOn);

        drawToggleRow(g, 5, contentRight, mouseX, mouseY, hudOn ? 0xFF70FF90 : 0xFF3E4454,
                Lang.s("minimap.settings.hud_pos"), Lang.s("minimap.settings.hud_pos.desc"),
                s.minimapHudAbove() ? Lang.s("minimap.settings.hud_pos.above") : Lang.s("minimap.settings.hud_pos.below"),
                s.minimapHudAbove(), hudOn);

        for (int i = 0; i < HUD_ITEMS.length; i++) {
            MinimapHudItem item = HUD_ITEMS[i];
            boolean on = s.isMinimapHudItemEnabled(item);
            int accent = hudOn ? HUD_ACCENTS[i % HUD_ACCENTS.length] : 0xFF3E4454;
            drawToggleRow(g, 6 + i, contentRight, mouseX, mouseY, accent,
                    Lang.s("minimap.hud.item." + item.key()),
                    Lang.s("minimap.hud.item." + item.key() + ".desc"),
                    on ? Lang.s("common.on") : Lang.s("common.off"),
                    on, hudOn);
        }
    }

    private void drawHotkeysTab(GuiGraphicsExtractor g, int contentRight, int mouseX, int mouseY) {
        for (int i = 0; i < hotkeyEntries.size(); i++) {
            int ry0 = listY0 + i * ROW_H - scroll;
            int ry1 = ry0 + ROW_H;
            if (ry1 <= listY0 || ry0 >= listY1) {
                continue;
            }
            ChunkVaultClient.HotkeyEntry entry = hotkeyEntries.get(i);
            KeyMapping m = entry.mapping();
            boolean isListening = m == listening;
            boolean rowHover = mouseX >= listX0 && mouseX < contentRight
                    && mouseY >= Math.max(listY0, ry0) && mouseY < Math.min(listY1, ry1);

            int rowBg = isListening ? 0xFF282418 : (rowHover ? 0xFF262A34 : ((i & 1) == 0 ? 0xFF1C1F27 : 0xFF181B22));
            g.fill(listX0, ry0, contentRight, ry1 - 1, rowBg);
            g.fill(listX0, ry0, listX0 + 3, ry1 - 1, entry.accentColor());

            int resetX1 = contentRight - 6;
            int resetX0 = resetX1 - RESET_BTN_W;
            int keyX1 = resetX0 - 4;
            int keyX0 = keyX1 - KEY_BTN_W;
            int by0 = ry0 + 4;
            int by1 = ry1 - 5;

            g.text(this.font, Lang.s(entry.labelKey()), listX0 + 11, ry0 + 5, 0xFFFFFFFF, true);
            String desc = Lang.s(entry.descKey());
            int maxDescW = keyX0 - (listX0 + 16);
            if (maxDescW > 20) {
                if (this.font.width(desc) > maxDescW) {
                    desc = this.font.plainSubstrByWidth(desc, Math.max(10, maxDescW - 8)) + "…";
                }
                g.text(this.font, desc, listX0 + 11, ry0 + 16, 0xFF8890A2, false);
            }

            boolean keyHover = rowHover && mouseX >= keyX0 && mouseX < keyX1 && mouseY >= by0 && mouseY < by1;
            boolean conflict = hasConflict(m);
            int keyBorder = isListening ? 0xFFFFD070 : (conflict ? 0xFFFF8830 : (keyHover ? 0xFFFFFFFF : 0xFF4A5064));
            int keyBg = isListening ? 0xFF5A4618 : (keyHover ? 0xFF343A4A : 0xFF1E222C);
            g.fill(keyX0, by0, keyX1, by1, keyBorder);
            g.fill(keyX0 + 1, by0 + 1, keyX1 - 1, by1 - 1, keyBg);

            String badge = keyBadgeText(entry);
            int badgeCol = isListening ? 0xFFFFE080 : (m.isUnbound() ? 0xFF7A8090 : (conflict ? 0xFFFFB060 : 0xFFFFFFFF));
            g.centeredText(this.font, badge, (keyX0 + keyX1) / 2, by0 + 5, badgeCol);

            boolean canReset = !m.isDefault();
            boolean resetHover = canReset && rowHover && mouseX >= resetX0 && mouseX < resetX1
                    && mouseY >= by0 && mouseY < by1;
            int resetBorder = canReset ? (resetHover ? 0xFFFFFFFF : 0xFF4A5064) : 0xFF282C36;
            int resetBg = canReset ? (resetHover ? 0xFF3A4050 : 0xFF222630) : 0xFF181A22;
            g.fill(resetX0, by0, resetX1, by1, resetBorder);
            g.fill(resetX0 + 1, by0 + 1, resetX1 - 1, by1 - 1, resetBg);
            g.centeredText(this.font, "↺", (resetX0 + resetX1) / 2, by0 + 5, canReset ? 0xFFFFFFFF : 0xFF4E5362);
        }
    }

    private void drawRowBase(GuiGraphicsExtractor g, int idx, int contentRight, int mouseX, int mouseY,
                             int accent, String title, String desc, int textLimitX, boolean enabled) {
        int ry0 = listY0 + idx * ROW_H - scroll;
        int ry1 = ry0 + ROW_H;
        boolean hover = enabled && mouseX >= listX0 && mouseX < contentRight
                && mouseY >= Math.max(listY0, ry0) && mouseY < Math.min(listY1, ry1);
        int bg = hover ? 0xFF262A34 : ((idx & 1) == 0 ? 0xFF1C1F27 : 0xFF181B22);
        g.fill(listX0, ry0, contentRight, ry1 - 1, bg);
        g.fill(listX0, ry0, listX0 + 3, ry1 - 1, accent);

        g.text(this.font, title, listX0 + 11, ry0 + 5, enabled ? 0xFFFFFFFF : 0xFF6B7280, true);
        int maxDescW = textLimitX - (listX0 + 16);
        if (maxDescW > 20) {
            if (this.font.width(desc) > maxDescW) {
                desc = this.font.plainSubstrByWidth(desc, Math.max(10, maxDescW - 8)) + "…";
            }
            g.text(this.font, desc, listX0 + 11, ry0 + 16, enabled ? 0xFF8890A2 : 0xFF555B6A, false);
        }
    }

    private void drawPill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                          String text, boolean activeState, boolean enabled, boolean hover) {
        int border = !enabled ? 0xFF282C36 : (hover ? 0xFFFFFFFF : (activeState ? 0xFF50C878 : 0xFF4A5064));
        int bg = !enabled ? 0xFF181A22 : (activeState
                ? (hover ? 0xFF2D6B48 : 0xFF1F5236)
                : (hover ? 0xFF343A4A : 0xFF1E222C));
        int col = !enabled ? 0xFF555B6A : (activeState ? 0xFF90FFB0 : 0xFFCCCCCC);
        g.fill(x0, y0, x1, y1, border);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, bg);
        g.centeredText(this.font, text, (x0 + x1) / 2, y0 + 5, col);
    }

    private void drawToggleRow(GuiGraphicsExtractor g, int idx, int contentRight, int mouseX, int mouseY,
                               int accent, String title, String desc, String value, boolean on, boolean enabled) {
        int ry0 = listY0 + idx * ROW_H - scroll;
        int ry1 = ry0 + ROW_H;
        if (ry1 <= listY0 || ry0 >= listY1) {
            return;
        }
        int bx1 = contentRight - 6;
        int bx0 = bx1 - BADGE_W;
        drawRowBase(g, idx, contentRight, mouseX, mouseY, accent, title, desc, bx0, enabled);
        int by0 = ry0 + 4;
        int by1 = ry1 - 5;
        boolean hover = enabled && mouseX >= bx0 && mouseX < bx1
                && mouseY >= Math.max(listY0, by0) && mouseY < Math.min(listY1, by1);
        drawPill(g, bx0, by0, bx1, by1, value, on, enabled, hover);
    }

    private void drawStepperRow(GuiGraphicsExtractor g, int idx, int contentRight, int mouseX, int mouseY,
                                int accent, String title, String desc, String value,
                                boolean enabled, boolean canMinus, boolean canPlus) {
        int ry0 = listY0 + idx * ROW_H - scroll;
        int ry1 = ry0 + ROW_H;
        if (ry1 <= listY0 || ry0 >= listY1) {
            return;
        }
        int bx1 = contentRight - 6;
        int bx0 = bx1 - BADGE_W;
        drawRowBase(g, idx, contentRight, mouseX, mouseY, enabled ? accent : 0xFF3E4454, title, desc, bx0, enabled);
        int by0 = ry0 + 4;
        int by1 = ry1 - 5;
        boolean inY = mouseY >= Math.max(listY0, by0) && mouseY < Math.min(listY1, by1);

        int mX0 = bx0;
        int mX1 = bx0 + STEP_BTN_W;
        int pX1 = bx1;
        int pX0 = bx1 - STEP_BTN_W;
        int vX0 = mX1 + 2;
        int vX1 = pX0 - 2;

        boolean mEnabled = enabled && canMinus;
        boolean pEnabled = enabled && canPlus;
        boolean mHover = mEnabled && inY && mouseX >= mX0 && mouseX < mX1;
        boolean pHover = pEnabled && inY && mouseX >= pX0 && mouseX < pX1;

        g.fill(mX0, by0, mX1, by1, !mEnabled ? 0xFF282C36 : (mHover ? 0xFFFFFFFF : 0xFF3B6E80));
        g.fill(mX0 + 1, by0 + 1, mX1 - 1, by1 - 1, !mEnabled ? 0xFF181A22 : (mHover ? 0xFF284858 : 0xFF1A2E3A));
        g.centeredText(this.font, "-", (mX0 + mX1) / 2, by0 + 5, mEnabled ? 0xFFFFFFFF : 0xFF555B6A);

        g.fill(vX0, by0, vX1, by1, !enabled ? 0xFF282C36 : 0xFF3A3E4C);
        g.fill(vX0 + 1, by0 + 1, vX1 - 1, by1 - 1, 0xFF141720);
        g.centeredText(this.font, value, (vX0 + vX1) / 2, by0 + 5, enabled ? 0xFFE0F4FF : 0xFF555B6A);

        g.fill(pX0, by0, pX1, by1, !pEnabled ? 0xFF282C36 : (pHover ? 0xFFFFFFFF : 0xFF3B6E80));
        g.fill(pX0 + 1, by0 + 1, pX1 - 1, by1 - 1, !pEnabled ? 0xFF181A22 : (pHover ? 0xFF284858 : 0xFF1A2E3A));
        g.centeredText(this.font, "+", (pX0 + pX1) / 2, by0 + 5, pEnabled ? 0xFFFFFFFF : 0xFF555B6A);
    }

    private void drawContainersRow(GuiGraphicsExtractor g, int idx, int contentRight, int mouseX, int mouseY, ChunkVaultSettings s) {
        int ry0 = listY0 + idx * ROW_H - scroll;
        int ry1 = ry0 + ROW_H;
        if (ry1 <= listY0 || ry0 >= listY1) {
            return;
        }
        int bx1 = contentRight - 6;
        int bx0 = bx1 - BADGE_W;
        int fx1 = bx0 - 4;
        int fx0 = fx1 - FILTER_W;
        drawRowBase(g, idx, contentRight, mouseX, mouseY, 0xFFFFB830,
                Lang.s("settings.row.containers"), Lang.s("settings.row.containers.desc"), fx0, true);

        int by0 = ry0 + 4;
        int by1 = ry1 - 5;
        boolean on = s.saveContainers();
        boolean fHover = on && mouseX >= fx0 && mouseX < fx1 && mouseY >= Math.max(listY0, by0) && mouseY < Math.min(listY1, by1);
        int fBorder = !on ? 0xFF282C36 : (fHover ? 0xFFFFFFFF : 0xFF7A6028);
        int fBg = !on ? 0xFF181A22 : (fHover ? 0xFF7A5618 : 0xFF4A3614);
        g.fill(fx0, by0, fx1, by1, fBorder);
        g.fill(fx0 + 1, by0 + 1, fx1 - 1, by1 - 1, fBg);
        g.centeredText(this.font, Lang.s("settings.containers.filter"), (fx0 + fx1) / 2, by0 + 5,
                on ? 0xFFFFE090 : 0xFF555B6A);

        boolean bHover = mouseX >= bx0 && mouseX < bx1 && mouseY >= Math.max(listY0, by0) && mouseY < Math.min(listY1, by1);
        drawPill(g, bx0, by0, bx1, by1, on ? Lang.s("common.on") : Lang.s("common.off"), on, true, bHover);
    }

    private void drawSeedRow(GuiGraphicsExtractor g, int idx, int contentRight, int mouseX, int mouseY, boolean enabled) {
        int ry0 = listY0 + idx * ROW_H - scroll;
        int ry1 = ry0 + ROW_H;
        if (ry1 <= listY0 || ry0 >= listY1) {
            return;
        }
        int limitX = contentRight - 6 - SEED_W;
        drawRowBase(g, idx, contentRight, mouseX, mouseY, enabled ? 0xFF6B85C0 : 0xFF3E4454,
                Lang.s("settings.row.seed"), Lang.s("settings.row.seed.desc"), limitX, enabled);
    }

    private void drawActionRow(GuiGraphicsExtractor g, int idx, int contentRight, int mouseX, int mouseY,
                               int accent, String title, String desc, String btnText, boolean enabled) {
        int ry0 = listY0 + idx * ROW_H - scroll;
        int ry1 = ry0 + ROW_H;
        if (ry1 <= listY0 || ry0 >= listY1) {
            return;
        }
        int bx1 = contentRight - 6;
        int bx0 = bx1 - BADGE_W;
        drawRowBase(g, idx, contentRight, mouseX, mouseY, accent, title, desc, bx0, enabled);
        int by0 = ry0 + 4;
        int by1 = ry1 - 5;
        boolean hover = enabled && mouseX >= bx0 && mouseX < bx1
                && mouseY >= Math.max(listY0, by0) && mouseY < Math.min(listY1, by1);
        g.fill(bx0, by0, bx1, by1, !enabled ? 0xFF282C36 : (hover ? 0xFFFFFFFF : 0xFF4A6FA5));
        g.fill(bx0 + 1, by0 + 1, bx1 - 1, by1 - 1, !enabled ? 0xFF181A22 : (hover ? 0xFF365585 : 0xFF243858));
        g.centeredText(this.font, btnText, (bx0 + bx1) / 2, by0 + 5, enabled ? 0xFFFFFFFF : 0xFF555B6A);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (listening != null) {
            listening.setKey(InputConstants.Type.MOUSE.getOrCreate(event.button()));
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
            listening = null;
            return true;
        }

        double mx = event.x();
        double my = event.y();
        int btn = event.button();

        if (btn == InputConstants.MOUSE_BUTTON_LEFT && my >= tabY0 && my < tabY1 && mx >= listX0 && mx < listX1) {
            int panelW = listX1 - listX0;
            int gap = 3;
            int tabW = (panelW - gap * (TAB_COUNT - 1)) / TAB_COUNT;
            for (int i = 0; i < TAB_COUNT; i++) {
                int tx0 = listX0 + i * (tabW + gap);
                int tx1 = (i == TAB_COUNT - 1) ? listX1 : tx0 + tabW;
                if (mx >= tx0 && mx < tx1) {
                    if (i != activeTab) {
                        UiSound.click();
                        selectTab(i);
                    }
                    return true;
                }
            }
        }

        if (btn == InputConstants.MOUSE_BUTTON_LEFT && my >= doneY0 && my < doneY1) {
            if (activeTab == TAB_HOTKEYS) {
                if (mx >= resetAllX0 && mx < resetAllX1) {
                    UiSound.click();
                    resetAllHotkeys();
                    return true;
                }
                if (mx >= doneX0 && mx < doneX1) {
                    UiSound.click();
                    this.onClose();
                    return true;
                }
            } else if (mx >= listX0 && mx < listX1) {
                UiSound.click();
                this.onClose();
                return true;
            }
        }

        int maxS = maxScroll();
        int contentRight = maxS > 0 ? listX1 - SCROLLBAR_W - 2 : listX1;

        if (btn == InputConstants.MOUSE_BUTTON_LEFT && maxS > 0
                && mx >= listX1 - SCROLLBAR_W - 2 && mx <= listX1 + 2 && my >= listY0 && my < listY1) {
            int tY = thumbY();
            int tH = thumbHeight();
            if (my >= tY && my < tY + tH) {
                scrollDragOffset = my - tY;
            } else {
                scrollDragOffset = tH / 2.0;
                setScrollFromThumbY(my - scrollDragOffset);
            }
            draggingScrollbar = true;
            return true;
        }

        if ((btn == InputConstants.MOUSE_BUTTON_LEFT || btn == InputConstants.MOUSE_BUTTON_RIGHT)
                && mx >= listX0 && mx < contentRight && my >= listY0 && my < listY1) {
            int idx = (int) ((my - listY0 + scroll) / ROW_H);
            if (idx < 0 || idx >= currentRowCount()) {
                return super.mouseClicked(event, isDoubleClick);
            }
            ChunkVaultSettings s = ChunkVaultSettings.get();
            switch (activeTab) {
                case TAB_RECORDING -> {
                    if (btn != InputConstants.MOUSE_BUTTON_LEFT) {
                        return super.mouseClicked(event, isDoubleClick);
                    }
                    UiSound.click();
                    switch (idx) {
                        case 0 -> {
                            boolean on = !AutoRecord.isAuto(serverKey);
                            AutoRecord.setAuto(serverKey, on);
                            if (on && ProjectMap.isOpen()) {
                                AutoRecord.setWorld(serverKey, ProjectMap.name());
                            }
                            return true;
                        }
                        case 1 -> {
                            s.setSaveEntities(!s.saveEntities());
                            return true;
                        }
                        case 2 -> {
                            int bx1 = contentRight - 6;
                            int bx0 = bx1 - BADGE_W;
                            int fx1 = bx0 - 4;
                            int fx0 = fx1 - FILTER_W;
                            if (mx >= fx0 && mx < fx1 && s.saveContainers()) {
                                Minecraft.getInstance().setScreenAndShow(new ContainerFilterScreen(this));
                                return true;
                            }
                            s.setSaveContainers(!s.saveContainers());
                            return true;
                        }
                        case 3 -> {
                            ContainerDatabase.toggleShowUnopened();
                            return true;
                        }
                        case 4 -> {
                            s.setSavePlayerInventory(!s.savePlayerInventory());
                            return true;
                        }
                        case 5 -> {
                            s.setSaveEnderChest(!s.saveEnderChest());
                            return true;
                        }
                        default -> {}
                    }
                }
                case TAB_WORLD -> {
                    if (btn != InputConstants.MOUSE_BUTTON_LEFT || idx == 2) {
                        return super.mouseClicked(event, isDoubleClick);
                    }
                    UiSound.click();
                    if (idx == 0) {
                        s.setPreventMobSpawning(!s.preventMobSpawning());
                        return true;
                    }
                    if (idx == 1) {
                        s.setUseSeed(!s.useSeed());
                        return true;
                    }
                }
                case TAB_MINIMAP -> {
                    if (idx == 2 || idx == 3) {
                        if (!s.minimapEnabled()) {
                            return true;
                        }
                        int bx1 = contentRight - 6;
                        int bx0 = bx1 - BADGE_W;
                        int dir;
                        if (btn == InputConstants.MOUSE_BUTTON_RIGHT) {
                            dir = -1;
                        } else if (mx >= bx0 && mx < bx0 + STEP_BTN_W) {
                            dir = -1;
                        } else {
                            dir = 1;
                        }
                        UiSound.click();
                        if (idx == 2) {
                            s.stepMinimapSize(dir);
                        } else {
                            s.stepMinimapZoom(dir);
                        }
                        return true;
                    }
                    if (btn != InputConstants.MOUSE_BUTTON_LEFT) {
                        return super.mouseClicked(event, isDoubleClick);
                    }
                    switch (idx) {
                        case 0 -> {
                            UiSound.click();
                            s.setMinimapEnabled(!s.minimapEnabled());
                            return true;
                        }
                        case 1 -> {
                            if (s.minimapEnabled()) {
                                UiSound.click();
                                Minecraft.getInstance().setScreenAndShow(new MinimapLayoutScreen(this));
                            }
                            return true;
                        }
                        case 4 -> {
                            if (s.minimapEnabled()) {
                                UiSound.click();
                                s.setMinimapHudEnabled(!s.minimapHudEnabled());
                            }
                            return true;
                        }
                        case 5 -> {
                            if (s.minimapEnabled() && s.minimapHudEnabled()) {
                                UiSound.click();
                                s.setMinimapHudAbove(!s.minimapHudAbove());
                            }
                            return true;
                        }
                        default -> {
                            int itemIdx = idx - 6;
                            if (itemIdx >= 0 && itemIdx < HUD_ITEMS.length) {
                                if (s.minimapEnabled() && s.minimapHudEnabled()) {
                                    UiSound.click();
                                    MinimapHudItem item = HUD_ITEMS[itemIdx];
                                    s.setMinimapHudItemEnabled(item, !s.isMinimapHudItemEnabled(item));
                                }
                                return true;
                            }
                        }
                    }
                }
                case TAB_HOTKEYS -> {
                    if (btn != InputConstants.MOUSE_BUTTON_LEFT) {
                        return super.mouseClicked(event, isDoubleClick);
                    }
                    ChunkVaultClient.HotkeyEntry entry = hotkeyEntries.get(idx);
                    KeyMapping m = entry.mapping();
                    int resetX1 = contentRight - 6;
                    int resetX0 = resetX1 - RESET_BTN_W;
                    if (mx >= resetX0 && mx < resetX1) {
                        if (!m.isDefault()) {
                            UiSound.click();
                            m.setKey(m.getDefaultKey());
                            KeyMapping.resetMapping();
                            Minecraft.getInstance().options.save();
                        }
                        return true;
                    }
                    UiSound.click();
                    listening = m;
                    return true;
                }
                default -> {}
            }
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && draggingScrollbar) {
            setScrollFromThumbY(event.y() - scrollDragOffset);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && scrollY != 0) {
            scroll -= (int) Math.round(scrollY * 22);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
