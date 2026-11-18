package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;

/** "Новый файл": asks for a name and creates an empty project (a folder in chunkvault/) that becomes the open one. */
public class NewProjectScreen extends Screen {

    private static final int W = 320;

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
        int x = (this.width - W) / 2;
        int y = this.height / 2 - 20;
        nameBox = new EditBox(this.font, x, y, W, 20, Lang.c("new.name_box"));
        nameBox.setMaxLength(48);
        nameBox.setValue(typedKeep);
        this.addRenderableWidget(nameBox);

        int half = (W - 6) / 2;
        this.addRenderableWidget(Button.builder(Lang.c("new.create"), b -> create())
                .bounds(x, y + 52, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("common.cancel"), b -> this.onClose())
                .bounds(x + half + 6, y + 52, half, 20)
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
        int x = (this.width - W) / 2;
        int y = this.height / 2 - 20;
        g.centeredText(this.font, Lang.s("new.title"), this.width / 2, y - 44, 0xFFFFFFFF);
        g.text(this.font, Lang.s("new.name_label"), x, y - 12, 0xFFAAAAAA, true);
        if (nameBox != null) {
            typedKeep = nameBox.getValue();
            String typed = typedKeep.trim();
            if (!typed.isEmpty()) {
                g.text(this.font, Lang.s("new.folder", RecordingState.sanitize(typed)), x, y + 26, 0xFF70FF90, true);
            }
        }
        if (error != null) {
            g.text(this.font, error, x, y + 80, 0xFFFF7070, true);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
