package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.ChunkVaultSettings;
import com.chunkvault.state.ChunkVaultSettings.ContainerCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;

public class ContainerFilterScreen extends Screen {

    private static final int WIDTH = 360;
    private static final int ROW_H = 24;

    private final Screen parent;
    private final Map<ContainerCategory, Button> buttons = new EnumMap<>(ContainerCategory.class);

    public ContainerFilterScreen(Screen parent) {
        super(Lang.c("containers.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        buttons.clear();
        int x = (this.width - WIDTH) / 2;
        int half = (WIDTH - 6) / 2;
        int y = 40;

        ContainerCategory[] categories = ContainerCategory.values();
        for (int i = 0; i < categories.length; i++) {
            ContainerCategory cat = categories[i];
            int col = i % 2;
            int row = i / 2;
            boolean lastOdd = (i == categories.length - 1) && (col == 0);
            int bx = col == 0 ? x : x + half + 6;
            int bw = lastOdd ? WIDTH : half;
            int by = y + row * ROW_H;

            Button btn = Button.builder(categoryLabel(cat), b -> {
                        ChunkVaultSettings s = ChunkVaultSettings.get();
                        s.setContainerEnabled(cat, !s.isContainerEnabled(cat));
                        b.setMessage(categoryLabel(cat));
                    })
                    .bounds(bx, by, bw, 20)
                    .build();
            buttons.put(cat, btn);
            this.addRenderableWidget(btn);
        }

        int rows = (categories.length + 1) / 2;
        int actionY = y + rows * ROW_H + 8;

        this.addRenderableWidget(Button.builder(Lang.c("containers.enable_all"), b -> {
                    ChunkVaultSettings.get().setAllContainers(true);
                    refreshLabels();
                })
                .bounds(x, actionY, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("containers.disable_all"), b -> {
                    ChunkVaultSettings.get().setAllContainers(false);
                    refreshLabels();
                })
                .bounds(x + half + 6, actionY, half, 20)
                .build());

        this.addRenderableWidget(Button.builder(Lang.c("common.done"), b -> this.onClose())
                .bounds(x, actionY + ROW_H + 4, WIDTH, 20)
                .build());
    }

    private void refreshLabels() {
        for (Map.Entry<ContainerCategory, Button> e : buttons.entrySet()) {
            e.getValue().setMessage(categoryLabel(e.getKey()));
        }
    }

    private static Component categoryLabel(ContainerCategory cat) {
        boolean on = ChunkVaultSettings.get().isContainerEnabled(cat);
        return Lang.c("containers." + cat.key(), Lang.s(on ? "common.on" : "common.off"));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xE0101018);
        g.centeredText(this.font, this.title.getString(), this.width / 2, 16, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
