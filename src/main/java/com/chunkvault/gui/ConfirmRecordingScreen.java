package com.chunkvault.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** "This world already exists — record into it anyway?" */
public class ConfirmRecordingScreen extends Screen {

    private static final int W = 320;

    private final Screen back;
    private final String folder;
    private final BooleanSupplier onYes;

    /**
     * @param back  screen to return to on "Назад"
     * @param onYes starts the recording; returns true when it did (that screen switch then already happened)
     */
    public ConfirmRecordingScreen(Screen back, String folder, BooleanSupplier onYes) {
        super(Component.literal("Подтверждение записи"));
        this.back = back;
        this.folder = folder;
        this.onYes = onYes;
    }

    @Override
    protected void init() {
        super.init();
        int x = (this.width - W) / 2;
        int y = this.height / 2 + 8;
        int half = (W - 6) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Да, записывать"), b -> {
                    if (!onYes.getAsBoolean()) {
                        Minecraft.getInstance().setScreenAndShow(back);
                    }
                })
                .bounds(x, y, half, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Назад"), b -> this.onClose())
                .bounds(x + half + 6, y, half, 20)
                .build());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(back);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);
        int cy = this.height / 2;
        g.centeredText(this.font, "Мир «" + folder + "» уже существует", this.width / 2, cy - 34, 0xFFFFD070);
        g.centeredText(this.font, "Записывать в него? Заново записанные чанки заменят", this.width / 2, cy - 18, 0xFFCCCCCC);
        g.centeredText(this.font, "старые копии, остальное останется как было.", this.width / 2, cy - 6, 0xFFCCCCCC);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
