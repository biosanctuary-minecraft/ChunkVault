package com.chunkvault.gui;

import com.chunkvault.state.AutoRecord;
import com.chunkvault.state.ChunkVaultSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * /chunkvault settings — every toggle is a plain Button whose label shows
 * the current state and flips it on click, rather than a Checkbox widget:
 * Button.builder(...).bounds(...).build() is confirmed working unchanged in
 * 26.2, Checkbox's constructor for this version was not confirmed anywhere
 * I could find, so this sidesteps that risk entirely.
 */
public class SettingsScreen extends Screen {

    private static final int ROW_H = 24;
    private static final int WIDTH = 360;

    private EditBox seedBox;
    private final String serverKey = AutoRecord.serverKey();

    private final Screen parent;

    public SettingsScreen() {
        this(null);
    }

    /** @param parent screen to go back to when closed (null = back to the game) */
    public SettingsScreen(Screen parent) {
        super(Component.literal("ChunkVault — настройки"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        if (parent != null) {
            net.minecraft.client.Minecraft.getInstance().setScreenAndShow(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    protected void init() {
        super.init();
        int x = (this.width - WIDTH) / 2;
        int y = 40;
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

        this.addRenderableWidget(mobsButton(x, y, s));
        y += ROW_H;

        this.addRenderableWidget(genModeButton(x, y, s));
        y += ROW_H;

        seedBox = new EditBox(this.font, x + 140, y, WIDTH - 140, 20, Component.literal("Сид"));
        seedBox.setValue(Long.toString(s.seed()));
        seedBox.setMaxLength(20);
        this.addRenderableWidget(seedBox);
        y += ROW_H + 10;

        this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> {
                    applySeed();
                    this.onClose();
                })
                .bounds(x, y, WIDTH, 20)
                .build());
    }

    private Component autoLabel() {
        return Component.literal("Автозапись на этом сервере: " + (AutoRecord.isAuto(serverKey) ? "ВКЛ" : "ВЫКЛ"));
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
        return Component.literal("Сохранять мобов/предметы: " + (s.saveEntities() ? "ВКЛ" : "ВЫКЛ"));
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
        return Component.literal("Спавн мобов в сохранённом мире: " + (s.preventMobSpawning() ? "ЗАПРЕЩЁН" : "разрешён"));
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
        return Component.literal("Вне записанного: " + (s.useSeed() ? "генерация по сиду ниже" : "пустота (Void)"));
    }

    private void applySeed() {
        try {
            String text = seedBox.getValue().trim();
            long seed = text.isEmpty() ? 0L : Long.parseLong(text);
            ChunkVaultSettings.get().setSeed(seed);
        } catch (NumberFormatException ignored) {
            // leave the previously saved seed untouched on bad input
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xE0101018);
        g.centeredText(this.font, this.title.getString(), this.width / 2, 18, 0xFFFFFFFF);

        int x = (this.width - WIDTH) / 2;
        g.text(this.font, "Сид (только для режима «по сиду»):", x, 40 + ROW_H * 4 - 11, 0xFFAAAAAA, true);
        g.text(this.font,
                "Применяется к новым записям (/chunkvault start), начатым после сохранения.",
                x, this.height - 20, 0xFF808080, true);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
