package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.AutoRecord;
import com.chunkvault.state.ChunkVaultSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SettingsScreen extends Screen {

    private static final int TOP_Y = 34;
    private static final int ROW_H = 24;
    private static final int WIDTH = 360;
    private static final int SEED_GAP = 12;
    private static final int FILTER_W = 110;

    private EditBox seedBox;
    private Button filterButton;
    private final String serverKey = AutoRecord.serverKey();
    private final Screen parent;

    public SettingsScreen() {
        this(null);
    }

    public SettingsScreen(Screen parent) {
        super(Lang.c("settings.title"));
        this.parent = parent;
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
    protected void init() {
        super.init();
        int x = (this.width - WIDTH) / 2;
        int y = TOP_Y;
        ChunkVaultSettings s = ChunkVaultSettings.get();

        this.addRenderableWidget(Button.builder(autoLabel(), b -> {
                    boolean on = !AutoRecord.isAuto(serverKey);
                    AutoRecord.setAuto(serverKey, on);
                    if (on && ProjectMap.isOpen()) {
                        AutoRecord.setWorld(serverKey, ProjectMap.name());
                    }
                    b.setMessage(autoLabel());
                })
                .bounds(x, y, WIDTH, 20)
                .build());
        y += ROW_H;

        this.addRenderableWidget(entitiesButton(x, y, s));
        y += ROW_H;

        int mainW = WIDTH - FILTER_W - 6;
        this.addRenderableWidget(Button.builder(containersLabel(s), b -> {
                    s.setSaveContainers(!s.saveContainers());
                    b.setMessage(containersLabel(s));
                    if (filterButton != null) {
                        filterButton.active = s.saveContainers();
                    }
                })
                .bounds(x, y, mainW, 20)
                .build());
        filterButton = Button.builder(Lang.c("settings.containers.filter"), b -> {
                    applySeed();
                    Minecraft.getInstance().setScreenAndShow(new ContainerFilterScreen(this));
                })
                .bounds(x + mainW + 6, y, FILTER_W, 20)
                .build();
        filterButton.active = s.saveContainers();
        this.addRenderableWidget(filterButton);
        y += ROW_H;

        this.addRenderableWidget(mobsButton(x, y, s));
        y += ROW_H;

        this.addRenderableWidget(genModeButton(x, y, s));
        y += ROW_H + SEED_GAP;

        seedBox = new EditBox(this.font, x + 140, y, WIDTH - 140, 20, Lang.c("settings.seed_box"));
        seedBox.setValue(Long.toString(s.seed()));
        seedBox.setMaxLength(20);
        this.addRenderableWidget(seedBox);
        y += ROW_H + 6;

        this.addRenderableWidget(Button.builder(Lang.c("common.done"), b -> this.onClose())
                .bounds(x, y, WIDTH, 20)
                .build());
    }

    private Component autoLabel() {
        return Lang.c("settings.auto", Lang.s(AutoRecord.isAuto(serverKey) ? "common.on" : "common.off"));
    }

    private Button entitiesButton(int x, int y, ChunkVaultSettings s) {
        return Button.builder(entitiesLabel(s), b -> {
                    s.setSaveEntities(!s.saveEntities());
                    b.setMessage(entitiesLabel(s));
                })
                .bounds(x, y, WIDTH, 20)
                .build();
    }

    private static Component entitiesLabel(ChunkVaultSettings s) {
        return Lang.c("settings.entities", Lang.s(s.saveEntities() ? "common.on" : "common.off"));
    }

    private static Component containersLabel(ChunkVaultSettings s) {
        return Lang.c("settings.containers", Lang.s(s.saveContainers() ? "common.on" : "common.off"));
    }

    private Button mobsButton(int x, int y, ChunkVaultSettings s) {
        return Button.builder(mobsLabel(s), b -> {
                    s.setPreventMobSpawning(!s.preventMobSpawning());
                    b.setMessage(mobsLabel(s));
                })
                .bounds(x, y, WIDTH, 20)
                .build();
    }

    private static Component mobsLabel(ChunkVaultSettings s) {
        return Lang.c("settings.mobs", Lang.s(s.preventMobSpawning() ? "settings.mobs.blocked" : "settings.mobs.allowed"));
    }

    private Button genModeButton(int x, int y, ChunkVaultSettings s) {
        return Button.builder(genModeLabel(s), b -> {
                    s.setUseSeed(!s.useSeed());
                    b.setMessage(genModeLabel(s));
                })
                .bounds(x, y, WIDTH, 20)
                .build();
    }

    private static Component genModeLabel(ChunkVaultSettings s) {
        return Lang.c("settings.gen", Lang.s(s.useSeed() ? "settings.gen.seed" : "settings.gen.void"));
    }

    private void applySeed() {
        if (seedBox == null) {
            return;
        }
        try {
            String text = seedBox.getValue().trim();
            long seed = text.isEmpty() ? 0L : Long.parseLong(text);
            ChunkVaultSettings.get().setSeed(seed);
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xE0101018);
        g.centeredText(this.font, this.title.getString(), this.width / 2, 14, 0xFFFFFFFF);

        int x = (this.width - WIDTH) / 2;
        g.text(this.font, Lang.s("settings.seed_label"), x, TOP_Y + ROW_H * 5 + SEED_GAP - 11, 0xFFAAAAAA, true);
        g.text(this.font, Lang.s("settings.seed_note"), x, this.height - 14, 0xFF808080, true);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
