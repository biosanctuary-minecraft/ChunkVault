package com.chunkvault.gui;

import com.chunkvault.ChunkVaultClient;
import com.chunkvault.Lang;
import com.chunkvault.save.ContainerDatabase;
import com.chunkvault.state.AutoRecord;
import com.chunkvault.state.ChunkVaultSettings;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.Locale;

public class SettingsScreen extends Screen {

    private static final int ROW_H = 28;
    private static final int ROW_COUNT = 14;
    private static final int PANEL_MAX_W = 460;
    private static final int BADGE_W = 96;
    private static final int STEP_BTN_W = 18;
    private static final int FILTER_W = 72;
    private static final int SEED_W = 162;
    private static final int SCROLLBAR_W = 8;

    private final String serverKey = AutoRecord.serverKey();
    private final Screen parent;
    private final long openedAtMs = System.currentTimeMillis();

    private EditBox seedBox;
    private String seedText;

    private int listX0;
    private int listY0;
    private int listX1;
    private int listY1;
    private int scroll;
    private boolean draggingScrollbar;
    private double scrollDragOffset;

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
        int panelW = Math.min(PANEL_MAX_W, this.width - 32);
        listX0 = (this.width - panelW) / 2;
        listX1 = listX0 + panelW;
        listY0 = 44;
        listY1 = this.height - 44;
        clampScroll();

        int contentRight = maxScroll() > 0 ? listX1 - SCROLLBAR_W - 2 : listX1;
        seedBox = new EditBox(this.font, contentRight - 6 - SEED_W, listY0 + 8 * ROW_H + 4, SEED_W, 19, Lang.c("settings.seed_box"));
        seedBox.setMaxLength(20);
        seedBox.setValue(seedText == null ? "" : seedText);
        seedBox.setResponder(s -> seedText = s);
        this.addRenderableWidget(seedBox);

        this.addRenderableWidget(Button.builder(Lang.c("common.done"), b -> this.onClose())
                .bounds(listX0, this.height - 30, panelW, 20)
                .build());
    }

    private int maxScroll() {
        return Math.max(0, ROW_COUNT * ROW_H - (listY1 - listY0));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    private int thumbHeight() {
        int trackH = listY1 - listY0;
        return Math.max(22, (int) ((long) trackH * trackH / (ROW_COUNT * ROW_H)));
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

    @Override
    public boolean keyPressed(KeyEvent event) {
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
        g.centeredText(this.font, Lang.s("settings.title"), this.width / 2, 12, 0xFFFFFFFF);
        g.centeredText(this.font, Lang.s("settings.seed_note"), this.width / 2, 26, 0xFF9AA0B0);

        g.fill(listX0 - 1, listY0 - 1, listX1 + 1, listY1 + 1, 0xFF3A3A48);
        g.fill(listX0, listY0, listX1, listY1, 0xFF14161C);

        int maxS = maxScroll();
        int contentRight = maxS > 0 ? listX1 - SCROLLBAR_W - 2 : listX1;
        ChunkVaultSettings s = ChunkVaultSettings.get();

        if (seedBox != null) {
            int sy = listY0 + 8 * ROW_H - scroll + 4;
            boolean vis = sy >= listY0 && sy + 19 <= listY1;
            seedBox.setX(contentRight - 6 - SEED_W);
            seedBox.setY(sy);
            seedBox.setVisible(vis);
            seedBox.active = vis && s.useSeed();
        }

        g.enableScissor(listX0, listY0, listX1, listY1);

        boolean autoOn = AutoRecord.isAuto(serverKey);
        drawToggleRow(g, 0, contentRight, mouseX, mouseY, 0xFF3FB365,
                Lang.s("settings.row.auto"), Lang.s("settings.row.auto.desc"),
                autoOn ? Lang.s("common.on") : Lang.s("common.off"), autoOn);

        drawToggleRow(g, 1, contentRight, mouseX, mouseY, 0xFF38D8FF,
                Lang.s("settings.row.entities"), Lang.s("settings.row.entities.desc"),
                s.saveEntities() ? Lang.s("common.on") : Lang.s("common.off"), s.saveEntities());

        drawContainersRow(g, 2, contentRight, mouseX, mouseY, s);

        boolean showUnopened = ContainerDatabase.showUnopened();
        drawToggleRow(g, 3, contentRight, mouseX, mouseY, 0xFFFF9040,
                Lang.s("settings.row.unopened"), Lang.s("settings.row.unopened.desc"),
                showUnopened ? Lang.s("common.on") : Lang.s("common.off"), showUnopened);

        drawToggleRow(g, 4, contentRight, mouseX, mouseY, 0xFF55CCFF,
                Lang.s("settings.row.player_inventory"), Lang.s("settings.row.player_inventory.desc"),
                s.savePlayerInventory() ? Lang.s("common.on") : Lang.s("common.off"), s.savePlayerInventory());

        drawToggleRow(g, 5, contentRight, mouseX, mouseY, 0xFF28B8A8,
                Lang.s("settings.row.ender_chest"), Lang.s("settings.row.ender_chest.desc"),
                s.saveEnderChest() ? Lang.s("common.on") : Lang.s("common.off"), s.saveEnderChest());

        drawToggleRow(g, 6, contentRight, mouseX, mouseY, 0xFFFF6060,
                Lang.s("settings.row.mobs"), Lang.s("settings.row.mobs.desc"),
                s.preventMobSpawning() ? Lang.s("settings.mobs.blocked") : Lang.s("settings.mobs.allowed"),
                s.preventMobSpawning());

        drawToggleRow(g, 7, contentRight, mouseX, mouseY, 0xFFA870FF,
                Lang.s("settings.row.gen"), Lang.s("settings.row.gen.desc"),
                s.useSeed() ? Lang.s("settings.gen.seed_short") : Lang.s("settings.gen.void"),
                s.useSeed());

        drawSeedRow(g, 8, contentRight, mouseX, mouseY, s.useSeed());

        drawToggleRow(g, 9, contentRight, mouseX, mouseY, 0xFF38D8FF,
                Lang.s("settings.row.minimap"), Lang.s("settings.row.minimap.desc"),
                s.minimapEnabled() ? Lang.s("common.on") : Lang.s("common.off"), s.minimapEnabled());

        drawActionRow(g, 10, contentRight, mouseX, mouseY, s.minimapEnabled() ? 0xFF2AA8C4 : 0xFF3E4454,
                Lang.s("settings.row.minimap_pos"), Lang.s("settings.row.minimap_pos.desc"),
                Lang.s("settings.minimap.edit"), s.minimapEnabled());

        drawStepperRow(g, 11, contentRight, mouseX, mouseY, 0xFF2AA8C4,
                Lang.s("settings.row.minimap_size"), Lang.s("settings.row.minimap_size.desc"),
                Lang.s("settings.minimap.size_px", s.minimapSize()),
                s.minimapEnabled(),
                s.minimapSize() > ChunkVaultSettings.MINIMAP_MIN_SIZE,
                s.minimapSize() < ChunkVaultSettings.MINIMAP_MAX_SIZE);

        drawStepperRow(g, 12, contentRight, mouseX, mouseY, 0xFF2AA8C4,
                Lang.s("settings.row.minimap_zoom"), Lang.s("settings.row.minimap_zoom.desc"),
                formatZoom(s.minimapZoom()),
                s.minimapEnabled(),
                s.minimapZoom() > ChunkVaultSettings.MINIMAP_ZOOMS[0] + 0.001,
                s.minimapZoom() < ChunkVaultSettings.MINIMAP_ZOOMS[ChunkVaultSettings.MINIMAP_ZOOMS.length - 1] - 0.001);

        drawActionRow(g, 13, contentRight, mouseX, mouseY, 0xFF4A90E2,
                Lang.s("settings.row.hotkeys"), Lang.s("settings.row.hotkeys.desc"),
                Lang.s("settings.row.open_arrow"), true);

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

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawRowBase(GuiGraphicsExtractor g, int idx, int contentRight, int mouseX, int mouseY,
                             int accent, String title, String desc, int textLimitX) {
        int ry0 = listY0 + idx * ROW_H - scroll;
        int ry1 = ry0 + ROW_H;
        boolean hover = mouseX >= listX0 && mouseX < contentRight
                && mouseY >= Math.max(listY0, ry0) && mouseY < Math.min(listY1, ry1);
        int bg = hover ? 0xFF262A34 : ((idx & 1) == 0 ? 0xFF1C1F27 : 0xFF181B22);
        g.fill(listX0, ry0, contentRight, ry1 - 1, bg);
        g.fill(listX0, ry0, listX0 + 3, ry1 - 1, accent);

        g.text(this.font, title, listX0 + 11, ry0 + 5, 0xFFFFFFFF, true);
        int maxDescW = textLimitX - (listX0 + 16);
        if (maxDescW > 20) {
            if (this.font.width(desc) > maxDescW) {
                desc = this.font.plainSubstrByWidth(desc, Math.max(10, maxDescW - 8)) + "…";
            }
            g.text(this.font, desc, listX0 + 11, ry0 + 16, 0xFF8890A2, false);
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
                               int accent, String title, String desc, String value, boolean on) {
        int ry0 = listY0 + idx * ROW_H - scroll;
        int ry1 = ry0 + ROW_H;
        if (ry1 <= listY0 || ry0 >= listY1) {
            return;
        }
        int bx1 = contentRight - 6;
        int bx0 = bx1 - BADGE_W;
        drawRowBase(g, idx, contentRight, mouseX, mouseY, accent, title, desc, bx0);
        int by0 = ry0 + 4;
        int by1 = ry1 - 5;
        boolean hover = mouseX >= bx0 && mouseX < bx1 && mouseY >= Math.max(listY0, by0) && mouseY < Math.min(listY1, by1);
        drawPill(g, bx0, by0, bx1, by1, value, on, true, hover);
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
        drawRowBase(g, idx, contentRight, mouseX, mouseY, enabled ? accent : 0xFF3E4454, title, desc, bx0);
        int by0 = ry0 + 4;
        int by1 = ry1 - 5;
        int inY = mouseY >= Math.max(listY0, by0) && mouseY < Math.min(listY1, by1) ? 1 : 0;

        int mX0 = bx0;
        int mX1 = bx0 + STEP_BTN_W;
        int pX1 = bx1;
        int pX0 = bx1 - STEP_BTN_W;
        int vX0 = mX1 + 2;
        int vX1 = pX0 - 2;

        boolean mEnabled = enabled && canMinus;
        boolean pEnabled = enabled && canPlus;
        boolean mHover = mEnabled && inY == 1 && mouseX >= mX0 && mouseX < mX1;
        boolean pHover = pEnabled && inY == 1 && mouseX >= pX0 && mouseX < pX1;

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
                Lang.s("settings.row.containers"), Lang.s("settings.row.containers.desc"), fx0);

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
                Lang.s("settings.row.seed"), Lang.s("settings.row.seed.desc"), limitX);
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
        drawRowBase(g, idx, contentRight, mouseX, mouseY, accent, title, desc, bx0);
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
        double mx = event.x();
        double my = event.y();
        int maxS = maxScroll();
        int contentRight = maxS > 0 ? listX1 - SCROLLBAR_W - 2 : listX1;
        int btn = event.button();

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
            ChunkVaultSettings s = ChunkVaultSettings.get();
            if (idx == 11 || idx == 12) {
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
                } else if (mx >= bx1 - STEP_BTN_W && mx < bx1) {
                    dir = 1;
                } else {
                    dir = 1;
                }
                if (idx == 11) {
                    s.stepMinimapSize(dir);
                } else {
                    s.stepMinimapZoom(dir);
                }
                return true;
            }
            if (btn != InputConstants.MOUSE_BUTTON_LEFT) {
                return super.mouseClicked(event, isDoubleClick);
            }
            if (idx == 8) {
                return super.mouseClicked(event, isDoubleClick);
            }
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
                        applySeed();
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
                case 6 -> {
                    s.setPreventMobSpawning(!s.preventMobSpawning());
                    return true;
                }
                case 7 -> {
                    s.setUseSeed(!s.useSeed());
                    return true;
                }
                case 9 -> {
                    s.setMinimapEnabled(!s.minimapEnabled());
                    return true;
                }
                case 10 -> {
                    if (s.minimapEnabled()) {
                        applySeed();
                        Minecraft.getInstance().setScreenAndShow(new MinimapLayoutScreen(this));
                    }
                    return true;
                }
                case 13 -> {
                    applySeed();
                    Minecraft.getInstance().setScreenAndShow(new HotkeysScreen(this));
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
