package com.chunkvault.state;

import com.chunkvault.Lang;
import net.minecraft.client.Minecraft;

public final class AreaEtaTracker {

    private static final long SEC_PER_DAY = 86_400L;
    private static final long SEC_PER_YEAR = 365L * SEC_PER_DAY;
    private static final long MAX_YEARS_SEC = 1000L * SEC_PER_YEAR;

    private static boolean hasPrev;
    private static double prevX;
    private static double prevZ;
    private static String prevDim;
    private static double smoothedSpeed;
    private static double lastValidSpeed;
    private static long lastMovingTimeMs;

    private AreaEtaTracker() {}

    public static void reset() {
        hasPrev = false;
        prevDim = null;
        smoothedSpeed = 0.0;
        lastValidSpeed = 0.0;
        lastMovingTimeMs = 0L;
    }

    public static void tickSpeed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        String dim = mc.level.dimension().identifier().toString();
        double x = mc.player.getX();
        double z = mc.player.getZ();
        if (!hasPrev || !dim.equals(prevDim)) {
            prevX = x;
            prevZ = z;
            prevDim = dim;
            hasPrev = true;
            smoothedSpeed = 0.0;
            lastValidSpeed = 0.0;
            lastMovingTimeMs = 0L;
            return;
        }
        double dist = Math.hypot(x - prevX, z - prevZ);
        prevX = x;
        prevZ = z;
        if (dist > 100.0) {
            smoothedSpeed = 0.0;
            lastValidSpeed = 0.0;
            lastMovingTimeMs = 0L;
            return;
        }
        double instantSpeed = dist * 20.0;
        smoothedSpeed = smoothedSpeed * 0.94 + instantSpeed * 0.06;
        if (smoothedSpeed >= 0.3) {
            lastMovingTimeMs = System.currentTimeMillis();
            lastValidSpeed = smoothedSpeed;
        }
    }

    public static double smoothedSpeed() {
        return smoothedSpeed;
    }

    public static double activeSpeed() {
        if (smoothedSpeed >= 0.3) {
            return smoothedSpeed;
        }
        if (lastValidSpeed >= 0.3 && System.currentTimeMillis() - lastMovingTimeMs <= 4000L) {
            return lastValidSpeed;
        }
        return 0.0;
    }

    public static long estimateEtaSeconds(AreaPlan.Progress prog) {
        if (prog == null || prog.remaining() <= 0L || !AreaPlan.isActive()) {
            return -1L;
        }
        double speed = activeSpeed();
        if (speed < 0.3) {
            return -1L;
        }
        Minecraft mc = Minecraft.getInstance();
        int effR = Math.max(2, mc.options.getEffectiveRenderDistance());
        long spanX = (long) AreaPlan.maxCx() - (long) AreaPlan.minCx() + 1L;
        long spanZ = (long) AreaPlan.maxCz() - (long) AreaPlan.minCz() + 1L;
        double swathChunks = Math.max(1.0, Math.min((double) Math.min(spanX, spanZ), effR * 1.6));
        double chunksPerSec = (speed / 16.0) * swathChunks;
        if (chunksPerSec <= 1e-6) {
            return -1L;
        }
        double secs = prog.remaining() / chunksPerSec;
        if (!Double.isFinite(secs) || secs >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, Math.round(secs));
    }

    public static String formatEta(long etaSeconds) {
        if (etaSeconds < 0L) {
            return "—";
        }
        if (etaSeconds < 60L) {
            return Lang.s("hud.eta_s", etaSeconds);
        }
        if (etaSeconds < 3600L) {
            long m = etaSeconds / 60L;
            long s = etaSeconds % 60L;
            return Lang.s("hud.eta_ms", m, s);
        }
        if (etaSeconds < SEC_PER_DAY) {
            long h = etaSeconds / 3600L;
            long m = (etaSeconds % 3600L) / 60L;
            return Lang.s("hud.eta_hm", h, m);
        }
        if (etaSeconds < SEC_PER_YEAR) {
            long d = etaSeconds / SEC_PER_DAY;
            long h = (etaSeconds % SEC_PER_DAY) / 3600L;
            return Lang.s("hud.eta_dh", d, h);
        }
        if (etaSeconds < MAX_YEARS_SEC) {
            long y = etaSeconds / SEC_PER_YEAR;
            long d = (etaSeconds % SEC_PER_YEAR) / SEC_PER_DAY;
            return Lang.s("hud.eta_yd", y, d);
        }
        return Lang.s("hud.eta_over_999y");
    }
}
