package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.state.AreaPlan;
import com.chunkvault.state.RecordingState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Locale;

public final class AreaHudOverlay {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private AreaHudOverlay() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("chunkvault", "area_hud"),
                AreaHudOverlay::extract
        );
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) {
            return;
        }
        if (!ProjectMap.isOpen() || !AreaPlan.isActive() || !AreaPlan.isHudVisible()) {
            return;
        }

        Font font = mc.font;
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        AreaPlan.Progress prog = AreaPlan.computeProgress(pcx, pcz);
        boolean done = prog.total() > 0 && prog.remaining() == 0;
        boolean recording = RecordingState.get().isActive();

        String title = done ? Lang.s("hud.area_done") : Lang.s("hud.area_title");
        String recBadge = recording ? "● REC" : "";
        String pctText = String.format(Locale.ROOT, "%.1f%%", prog.percent());
        String statsLine = Lang.s("hud.area_stats", prog.recorded(), prog.total(), pctText, prog.remaining());

        String curDim = mc.level.dimension().identifier().toString();
        String navLine;
        int navColor;
        if (done) {
            navLine = Lang.s("hud.area_all_saved");
            navColor = 0xFF70FF90;
        } else if (!curDim.equals(AreaPlan.dimension())) {
            navLine = Lang.s("hud.area_wrong_dim", shortDim(AreaPlan.dimension()));
            navColor = 0xFFFFD070;
        } else if (prog.hasMissing()) {
            double targetX = prog.nearestCx() * 16.0 + 8.0;
            double targetZ = prog.nearestCz() * 16.0 + 8.0;
            double dx = targetX - mc.player.getX();
            double dz = targetZ - mc.player.getZ();
            int dist = (int) Math.round(Math.hypot(dx, dz));
            String arrow = relativeArrow(dx, dz, mc.player.getYRot());
            navLine = arrow + " " + Lang.s("hud.area_nearest", dist, (int) Math.floor(targetX), (int) Math.floor(targetZ));
            navColor = 0xFF38D8FF;
        } else {
            navLine = "";
            navColor = 0xFF9AA0B0;
        }

        int titleRowW = font.width(title) + (recBadge.isEmpty() ? 0 : font.width(recBadge) + 12);
        int cardW = Math.max(176, Math.max(titleRowW, Math.max(font.width(statsLine), font.width(navLine))) + 16);
        int cardH = navLine.isEmpty() ? 42 : 54;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int x0 = Math.max(8, screenW - cardW - 8);
        int y0 = 8;
        int x1 = x0 + cardW;
        int y1 = y0 + cardH;

        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF3A3A48);
        g.fill(x0, y0, x1, y1, 0xD814161C);

        g.text(font, title, x0 + 8, y0 + 6, done ? 0xFF70FF90 : 0xFFFFFFFF, true);
        if (!recBadge.isEmpty()) {
            g.text(font, recBadge, x1 - font.width(recBadge) - 8, y0 + 6, 0xFFFF6060, true);
        }

        int barX0 = x0 + 8;
        int barX1 = x1 - 8;
        int barY0 = y0 + 18;
        int barY1 = barY0 + 5;
        g.fill(barX0, barY0, barX1, barY1, 0xFF0E1015);
        int fillW = (int) Math.round((barX1 - barX0) * Math.max(0.0, Math.min(1.0, prog.percent() / 100.0)));
        if (fillW > 0) {
            g.fill(barX0, barY0, barX0 + fillW, barY1, done ? 0xFF70FF90 : 0xFF38D8FF);
        }

        g.text(font, statsLine, x0 + 8, y0 + 27, 0xFFCCCCCC, true);
        if (!navLine.isEmpty()) {
            g.text(font, navLine, x0 + 8, y0 + 40, navColor, true);
        }
    }

    private static String relativeArrow(double dx, double dz, float playerYawDeg) {
        double targetAngle = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = (targetAngle - playerYawDeg) % 360.0;
        if (rel < 0) {
            rel += 360.0;
        }
        int sector = (int) Math.round(rel / 45.0) & 7;
        return ARROWS[sector];
    }

    private static String shortDim(String dim) {
        int idx = dim.indexOf(':');
        return idx >= 0 ? dim.substring(idx + 1) : dim;
    }
}
