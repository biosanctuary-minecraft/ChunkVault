package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.AreaPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AreaInputScreen extends Screen {

    private static final int CARD_W = 320;
    private static final int CARD_H = 192;

    private final MapScreen parent;
    private boolean radiusMode;

    private String valX1;
    private String valZ1;
    private String valX2;
    private String valZ2;
    private String valCx;
    private String valCz;
    private String valRadius = "256";

    private EditBox boxX1;
    private EditBox boxZ1;
    private EditBox boxX2;
    private EditBox boxZ2;
    private EditBox boxCx;
    private EditBox boxCz;
    private EditBox boxRadius;

    private String error;

    public AreaInputScreen(MapScreen parent) {
        super(Lang.c("area.input.title"));
        this.parent = parent;
        prefillDefaults();
    }

    private void prefillDefaults() {
        Minecraft mc = Minecraft.getInstance();
        int px = mc.player != null ? mc.player.blockPosition().getX() : 0;
        int pz = mc.player != null ? mc.player.blockPosition().getZ() : 0;
        valCx = String.valueOf(px);
        valCz = String.valueOf(pz);

        if (AreaPlan.isActive()) {
            valX1 = String.valueOf(AreaPlan.minCx() * 16);
            valZ1 = String.valueOf(AreaPlan.minCz() * 16);
            valX2 = String.valueOf(AreaPlan.maxCx() * 16 + 15);
            valZ2 = String.valueOf(AreaPlan.maxCz() * 16 + 15);
        } else {
            valX1 = String.valueOf(px - 128);
            valZ1 = String.valueOf(pz - 128);
            valX2 = String.valueOf(px + 128);
            valZ2 = String.valueOf(pz + 128);
        }
    }

    private void syncValuesFromBoxes() {
        if (boxX1 != null) {
            valX1 = boxX1.getValue();
        }
        if (boxZ1 != null) {
            valZ1 = boxZ1.getValue();
        }
        if (boxX2 != null) {
            valX2 = boxX2.getValue();
        }
        if (boxZ2 != null) {
            valZ2 = boxZ2.getValue();
        }
        if (boxCx != null) {
            valCx = boxCx.getValue();
        }
        if (boxCz != null) {
            valCz = boxCz.getValue();
        }
        if (boxRadius != null) {
            valRadius = boxRadius.getValue();
        }
    }

    @Override
    protected void init() {
        super.init();
        int x0 = (this.width - CARD_W) / 2;
        int y0 = (this.height - CARD_H) / 2;
        int innerX = x0 + 14;
        int innerW = CARD_W - 28;
        int halfW = (innerW - 8) / 2;

        Button cornersBtn = Button.builder(Lang.c("area.input.mode_corners"), b -> switchMode(false))
                .bounds(innerX, y0 + 28, halfW, 18)
                .build();
        cornersBtn.active = radiusMode;
        this.addRenderableWidget(cornersBtn);

        Button radiusBtn = Button.builder(Lang.c("area.input.mode_radius"), b -> switchMode(true))
                .bounds(innerX + halfW + 8, y0 + 28, halfW, 18)
                .build();
        radiusBtn.active = !radiusMode;
        this.addRenderableWidget(radiusBtn);

        boxX1 = null;
        boxZ1 = null;
        boxX2 = null;
        boxZ2 = null;
        boxCx = null;
        boxCz = null;
        boxRadius = null;

        if (!radiusMode) {
            boxX1 = makeNumberBox(innerX, y0 + 64, halfW, valX1, "X1");
            boxZ1 = makeNumberBox(innerX + halfW + 8, y0 + 64, halfW, valZ1, "Z1");
            boxX2 = makeNumberBox(innerX, y0 + 104, halfW, valX2, "X2");
            boxZ2 = makeNumberBox(innerX + halfW + 8, y0 + 104, halfW, valZ2, "Z2");
            this.addRenderableWidget(boxX1);
            this.addRenderableWidget(boxZ1);
            this.addRenderableWidget(boxX2);
            this.addRenderableWidget(boxZ2);
            this.setInitialFocus(boxX1);
        } else {
            boxCx = makeNumberBox(innerX, y0 + 64, halfW, valCx, "Center X");
            boxCz = makeNumberBox(innerX + halfW + 8, y0 + 64, halfW, valCz, "Center Z");
            boxRadius = makeNumberBox(innerX, y0 + 104, halfW, valRadius, "Radius");
            this.addRenderableWidget(boxCx);
            this.addRenderableWidget(boxCz);
            this.addRenderableWidget(boxRadius);
            this.addRenderableWidget(Button.builder(Lang.c("area.input.my_pos"), b -> fillMyPos())
                    .bounds(innerX + halfW + 8, y0 + 104, halfW, 20)
                    .build());
            this.setInitialFocus(boxRadius);
        }

        this.addRenderableWidget(Button.builder(Lang.c("area.input.apply"), b -> apply())
                .bounds(innerX, y0 + CARD_H - 30, halfW, 20)
                .build());
        this.addRenderableWidget(Button.builder(Lang.c("common.cancel"), b -> this.onClose())
                .bounds(innerX + halfW + 8, y0 + CARD_H - 30, halfW, 20)
                .build());
    }

    private EditBox makeNumberBox(int x, int y, int w, String value, String label) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.literal(label));
        box.setMaxLength(12);
        box.setValue(value == null ? "" : value);
        box.setResponder(s -> error = null);
        return box;
    }

    private void switchMode(boolean toRadius) {
        if (this.radiusMode == toRadius) {
            return;
        }
        syncValuesFromBoxes();
        this.radiusMode = toRadius;
        this.error = null;
        this.clearWidgets();
        this.init();
    }

    private void fillMyPos() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        valCx = String.valueOf(player.blockPosition().getX());
        valCz = String.valueOf(player.blockPosition().getZ());
        if (boxCx != null) {
            boxCx.setValue(valCx);
        }
        if (boxCz != null) {
            boxCz.setValue(valCz);
        }
        error = null;
    }

    private void apply() {
        syncValuesFromBoxes();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ProjectMap.isOpen()) {
            error = Lang.s("db.no_project");
            return;
        }
        String dim = mc.level.dimension().identifier().toString();
        try {
            if (!radiusMode) {
                int x1 = Integer.parseInt(valX1.trim());
                int z1 = Integer.parseInt(valZ1.trim());
                int x2 = Integer.parseInt(valX2.trim());
                int z2 = Integer.parseInt(valZ2.trim());
                AreaPlan.setFromBlocks(dim, x1, z1, x2, z2);
            } else {
                int cx = Integer.parseInt(valCx.trim());
                int cz = Integer.parseInt(valCz.trim());
                int rad = Integer.parseInt(valRadius.trim());
                if (rad < 16 || rad > AreaPlan.WORLD_BORDER) {
                    error = Lang.s("area.input.err_radius");
                    return;
                }
                AreaPlan.setRadiusBlocks(dim, cx, cz, rad);
            }
        } catch (NumberFormatException ex) {
            error = Lang.s("area.input.err_number");
            return;
        }

        if (parent != null) {
            parent.onAreaInputApplied();
            mc.setScreenAndShow(parent);
        } else {
            mc.setScreenAndShow(null);
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§a[ChunkVault] "
                        + Lang.s("area.set_toast", AreaPlan.totalChunks())));
            }
        }
    }

    private static int clampBlock(long b) {
        return (int) Math.max(-AreaPlan.WORLD_BORDER, Math.min(AreaPlan.WORLD_BORDER, b));
    }

    private String previewText() {
        try {
            long cx1;
            long cz1;
            long cx2;
            long cz2;
            if (!radiusMode) {
                if (boxX1 == null || boxZ1 == null || boxX2 == null || boxZ2 == null) {
                    return null;
                }
                cx1 = clampBlock(Long.parseLong(boxX1.getValue().trim())) >> 4;
                cz1 = clampBlock(Long.parseLong(boxZ1.getValue().trim())) >> 4;
                cx2 = clampBlock(Long.parseLong(boxX2.getValue().trim())) >> 4;
                cz2 = clampBlock(Long.parseLong(boxZ2.getValue().trim())) >> 4;
            } else {
                if (boxCx == null || boxCz == null || boxRadius == null) {
                    return null;
                }
                long cx = Long.parseLong(boxCx.getValue().trim());
                long cz = Long.parseLong(boxCz.getValue().trim());
                long r = Math.max(16L, Math.min(AreaPlan.WORLD_BORDER, Long.parseLong(boxRadius.getValue().trim())));
                cx1 = clampBlock(cx - r) >> 4;
                cz1 = clampBlock(cz - r) >> 4;
                cx2 = clampBlock(cx + r) >> 4;
                cz2 = clampBlock(cz + r) >> 4;
            }
            long w = Math.abs(cx2 - cx1) + 1L;
            long h = Math.abs(cz2 - cz1) + 1L;
            return Lang.s("area.input.preview", w, h, w * h, w * 16L, h * 16L);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0101018);

        int x0 = (this.width - CARD_W) / 2;
        int y0 = (this.height - CARD_H) / 2;
        int x1 = x0 + CARD_W;
        int y1 = y0 + CARD_H;
        int innerX = x0 + 14;
        int innerW = CARD_W - 28;
        int halfW = (innerW - 8) / 2;

        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF3A3A48);
        g.fill(x0, y0, x1, y1, 0xFF14161C);

        g.centeredText(this.font, Lang.s("area.input.title"), this.width / 2, y0 + 10, 0xFFFFFFFF);

        if (!radiusMode) {
            g.text(this.font, Lang.s("area.input.x1"), innerX, y0 + 53, 0xFF9AA0B0, true);
            g.text(this.font, Lang.s("area.input.z1"), innerX + halfW + 8, y0 + 53, 0xFF9AA0B0, true);
            g.text(this.font, Lang.s("area.input.x2"), innerX, y0 + 93, 0xFF9AA0B0, true);
            g.text(this.font, Lang.s("area.input.z2"), innerX + halfW + 8, y0 + 93, 0xFF9AA0B0, true);
        } else {
            g.text(this.font, Lang.s("area.input.center_x"), innerX, y0 + 53, 0xFF9AA0B0, true);
            g.text(this.font, Lang.s("area.input.center_z"), innerX + halfW + 8, y0 + 53, 0xFF9AA0B0, true);
            g.text(this.font, Lang.s("area.input.radius"), innerX, y0 + 93, 0xFF9AA0B0, true);
        }

        int statusY = y0 + 136;
        if (error != null) {
            g.centeredText(this.font, error, this.width / 2, statusY, 0xFFFF7070);
        } else {
            String preview = previewText();
            if (preview != null) {
                g.centeredText(this.font, preview, this.width / 2, statusY, 0xFF70FF90);
            }
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
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
