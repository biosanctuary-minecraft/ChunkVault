package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Files;

public class NewProjectScreen extends Screen {

    private static final int CARD_W = 340;
    private static final int CARD_H = 138;

    private final MapScreen parent;
    private EditBox nameBox;
    private String typedKeep = "";
    private String error;

    public NewProjectScreen(MapScreen parent) {
        super(Lang.c("new.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int x0 = (this.width - CARD_W) / 2;
        int y0 = (this.height - CARD_H) / 2;
        int innerX = x0 + 16;
        int innerW = CARD_W - 32;

        nameBox = new EditBox(this.font, innerX, y0 + 46, innerW, 20, Lang.c("new.name_box"));
        nameBox.setMaxLength(48);
        nameBox.setValue(typedKeep);
        nameBox.setResponder(s -> error = null);
        this.addRenderableWidget(nameBox);
        this.setInitialFocus(nameBox);

        int half = (innerW - 8) / 2;
        this.addRenderableWidget(Button.builder(Lang.c("new.create"), b -> create())
                .bounds(innerX, y0 + CARD_H - 30, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("common.cancel"), b -> this.onClose())
                .bounds(innerX + half + 8, y0 + CARD_H - 30, half, 20)
                .build());
    }

    private void create() {
        String raw = nameBox.getValue().trim();
        if (raw.isEmpty()) {
            error = Lang.s("new.error.empty");
            return;
        }
        String folder = RecordingState.sanitize(raw);
        if (Files.exists(ProjectMap.root().resolve(folder))) {
            error = Lang.s("new.error.exists", folder);
            return;
        }
        if (!ProjectMap.createNew(folder)) {
            error = Lang.s("new.error.failed");
            return;
        }
        Minecraft.getInstance().setScreenAndShow(parent);
        parent.showToast(Lang.s("toast.project_created", folder));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);

        int x0 = (this.width - CARD_W) / 2;
        int y0 = (this.height - CARD_H) / 2;
        int x1 = x0 + CARD_W;
        int y1 = y0 + CARD_H;
        int innerX = x0 + 16;

        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF3A3A48);
        g.fill(x0, y0, x1, y1, 0xFF14161C);
        g.fill(x0, y0, x1, y0 + 2, 0xFF3FB365);

        g.centeredText(this.font, Lang.s("new.title"), this.width / 2, y0 + 12, 0xFFFFFFFF);
        g.text(this.font, Lang.s("new.name_label"), innerX, y0 + 34, 0xFF9AA0B0, true);

        if (nameBox != null) {
            typedKeep = nameBox.getValue();
        }
        if (error != null) {
            g.text(this.font, error, innerX, y0 + 74, 0xFFFF7070, true);
        } else if (typedKeep != null && !typedKeep.trim().isEmpty()) {
            g.text(this.font, Lang.s("new.folder", RecordingState.sanitize(typedKeep.trim())),
                    innerX, y0 + 74, 0xFF70FF90, true);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
