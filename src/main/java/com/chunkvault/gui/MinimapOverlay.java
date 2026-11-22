package com.chunkvault.gui;

import com.chunkvault.Lang;
import com.chunkvault.save.ContainerDatabase;
import com.chunkvault.state.AreaPlan;
import com.chunkvault.state.ChunkVaultSettings;
import com.chunkvault.state.ChunkVaultSettings.MinimapHudItem;
import com.chunkvault.state.MapPinFilter;
import com.chunkvault.state.RecordingState;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public final class MinimapOverlay {

    static final int LINE_H = 11;
    static final int BAR_PAD_Y = 3;
    static final int PAD = 8;

    private record HudSegment(String text, int color) {}

    private static long frame;
    private static int clipX0;
    private static int clipY0;
    private static int clipX1;
    private static int clipY1;

    private static int[] zoneX0 = new int[256];
    private static int[] zoneX1 = new int[256];
    private static int[] zoneZ = new int[256];
    private static int zoneCount;
    private static boolean[] zoneGrid = new boolean[0];
    private static int zoneMinX;
    private static int zoneMinZ;
    private static int zoneSide;

    private static final Map<Integer, Identifier> PIN_TEXTURES = new HashMap<>();
    private static boolean pinTexturesOk = true;

    private static volatile String cachedSizeProject = "";
    private static volatile long cachedSizeBytes = -1L;
    private static volatile long lastSizeScanMs;
    private static final AtomicBoolean SIZE_SCANNING = new AtomicBoolean();

    private static final class PinTexture extends DynamicTexture {
        PinTexture(String name, int size) {
            super(name, size, size, true);
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }

    private MinimapOverlay() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("chunkvault", "minimap"),
                MinimapOverlay::extract
        );
    }

    static int resolveX(int screenW, int mapSize, double rx) {
        int avail = Math.max(0, screenW - mapSize - PAD * 2);
        return PAD + (int) Math.round(Math.max(0.0, Math.min(1.0, rx)) * avail);
    }

    static int resolveY(int screenH, int mapSize, int barH, double ry) {
        int totalH = mapSize + barH;
        int avail = Math.max(0, screenH - totalH - PAD * 2);
        return PAD + (int) Math.round(Math.max(0.0, Math.min(1.0, ry)) * avail);
    }

    static int currentBarHeight(Font font, int mapSize) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return 0;
        }
        ChunkVaultSettings s = ChunkVaultSettings.get();
        if (!s.minimapHudEnabled()) {
            return 0;
        }
        List<HudSegment> segments = buildHudSegments(mc, mc.level, mc.player, s);
        if (segments.isEmpty()) {
            return 0;
        }
        List<List<HudSegment>> lines = wrapSegments(font, segments, Math.max(16, mapSize - 8));
        return lines.size() * LINE_H + BAR_PAD_Y * 2 - 2;
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) {
            return;
        }
        ChunkVaultSettings settings = ChunkVaultSettings.get();
        if (!settings.minimapEnabled()) {
            return;
        }
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int mapSize = settings.minimapSize();
        int barH = currentBarHeight(mc.font, mapSize);
        int x0 = resolveX(screenW, mapSize, settings.minimapX());
        int y0 = resolveY(screenH, mapSize, barH, settings.minimapY());
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        renderAt(g, partialTick, x0, y0, mapSize, settings.minimapZoom(), false);
    }

    static int renderAt(GuiGraphicsExtractor g, float partialTick, int x0, int y0,
                        int mapSize, double scale, boolean previewMode) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return 0;
        }

        frame++;
        ChunkVaultSettings settings = ChunkVaultSettings.get();
        Font font = mc.font;

        List<List<HudSegment>> hudLines = List.of();
        int barH = 0;
        if (settings.minimapHudEnabled()) {
            List<HudSegment> segments = buildHudSegments(mc, level, player, settings);
            if (!segments.isEmpty()) {
                hudLines = wrapSegments(font, segments, Math.max(16, mapSize - 8));
                barH = hudLines.size() * LINE_H + BAR_PAD_Y * 2 - 2;
            }
        }

        boolean hudAbove = settings.minimapHudAbove() && barH > 0;
        int totalH = mapSize + barH;
        int x1 = x0 + mapSize;
        int totalY1 = y0 + totalH;
        int mapY0 = hudAbove ? y0 + barH : y0;
        int mapY1 = mapY0 + mapSize;
        int barY0 = hudAbove ? y0 : mapY1;
        int barY1 = barY0 + barH;

        int borderCol = previewMode ? 0xFF38D8FF : 0xFF3A3A48;
        g.fill(x0 - 1, y0 - 1, x1 + 1, totalY1 + 1, borderCol);
        g.fill(x0, mapY0, x1, mapY1, 0xE80B0B0F);

        int screenW = mc.getWindow().getGuiScaledWidth();
        double dev = Math.max(1.0, (double) mc.getWindow().getWidth() / (double) screenW);
        clipX0 = (int) Math.round(x0 * dev);
        clipY0 = (int) Math.round(mapY0 * dev);
        clipX1 = (int) Math.round(x1 * dev);
        clipY1 = (int) Math.round(mapY1 * dev);

        String dimensionId = level.dimension().identifier().toString();
        var pos = player.getPosition(partialTick);
        double centerX = pos.x();
        double centerZ = pos.z();
        double mapCX = x0 + mapSize / 2.0;
        double mapCY = mapY0 + mapSize / 2.0;

        boolean hasProject = ProjectMap.isOpen();
        if (hasProject) {
            Map<Long, MapCache.ChunkColors> cache = ProjectMap.forDimension(dimensionId);

            var manager = RecordingState.get().saveManager();
            boolean recording = RecordingState.get().isActive() && manager != null;
            if (!recording) {
                zoneCount = 0;
                zoneSide = 0;
            } else if (frame % 5 == 0 || zoneSide == 0) {
                updateZone(mc, level, player);
            }

            MapTextures.beginFrame(frame, dimensionId, cache);
            boolean useTex = MapTextures.available();

            double half = (mapSize / 2.0) / scale;
            int minCx = (int) Math.floor((centerX - half) / 16.0);
            int maxCx = (int) Math.floor((centerX + half) / 16.0);
            int minCz = (int) Math.floor((centerZ - half) / 16.0);
            int maxCz = (int) Math.floor((centerZ + half) / 16.0);

            g.enableScissor(x0, mapY0, x1, mapY1);
            float inv = (float) (1.0 / dev);
            g.pose().pushMatrix();
            g.pose().scale(inv, inv);

            if (useTex) {
                drawTerrain(g, dimensionId, cache, mapCX, mapCY, centerX, centerZ, scale, dev, minCx, maxCx, minCz, maxCz);
            } else {
                double ppcDev = scale * 16.0 * dev;
                for (int cz = minCz; cz <= maxCz; cz++) {
                    for (int cx = minCx; cx <= maxCx; cx++) {
                        MapCache.ChunkColors c = cache.get(MapCache.key(cx, cz));
                        if (c != null && c.avg != 0) {
                            int sx0 = (int) Math.round((mapCX + (cx * 16.0 - centerX) * scale) * dev);
                            int sy0 = (int) Math.round((mapCY + (cz * 16.0 - centerZ) * scale) * dev);
                            int sx1 = (int) Math.round(sx0 + ppcDev);
                            int sy1 = (int) Math.round(sy0 + ppcDev);
                            fillI(g, sx0, sy0, Math.max(sx0 + 1, sx1), Math.max(sy0 + 1, sy1), c.avg);
                        }
                    }
                }
            }

            if (AreaPlan.isActive() && dimensionId.equals(AreaPlan.dimension())) {
                double bx0 = (mapCX + (AreaPlan.minCx() * 16.0 - centerX) * scale) * dev;
                double bx1 = (mapCX + ((AreaPlan.maxCx() + 1) * 16.0 - centerX) * scale) * dev;
                double by0 = (mapCY + (AreaPlan.minCz() * 16.0 - centerZ) * scale) * dev;
                double by1 = (mapCY + ((AreaPlan.maxCz() + 1) * 16.0 - centerZ) * scale) * dev;
                int t = Math.max(1, (int) Math.round(dev));
                int ix0 = (int) Math.round(bx0);
                int ix1 = (int) Math.round(bx1);
                int iy0 = (int) Math.round(by0);
                int iy1 = (int) Math.round(by1);
                fillI(g, ix0, iy0, ix1, iy0 + t, 0xEE38D8FF);
                fillI(g, ix0, iy1 - t, ix1, iy1, 0xEE38D8FF);
                fillI(g, ix0, iy0, ix0 + t, iy1, 0xEE38D8FF);
                fillI(g, ix1 - t, iy0, ix1, iy1, 0xEE38D8FF);
            }

            if (recording && zoneCount > 0) {
                for (int i = 0; i < zoneCount; i++) {
                    int zx0 = (int) Math.round((mapCX + (zoneX0[i] * 16.0 - centerX) * scale) * dev);
                    int zx1 = (int) Math.round((mapCX + (zoneX1[i] * 16.0 - centerX) * scale) * dev);
                    int zy0 = (int) Math.round((mapCY + (zoneZ[i] * 16.0 - centerZ) * scale) * dev);
                    int zy1 = (int) Math.round((mapCY + ((zoneZ[i] + 1) * 16.0 - centerZ) * scale) * dev);
                    fillI(g, zx0, zy0, zx1, zy1, 0x40FF1A1A);
                }
                if (zoneSide > 0) {
                    int t = Math.max(1, (int) Math.round(dev));
                    int borderColor = 0xEEFF4040;
                    for (int iz = 0; iz < zoneSide; iz++) {
                        int cz = zoneMinZ + iz;
                        int zy0 = (int) Math.round((mapCY + (cz * 16.0 - centerZ) * scale) * dev);
                        int zy1 = (int) Math.round((mapCY + ((cz + 1) * 16.0 - centerZ) * scale) * dev);
                        int rowOff = iz * zoneSide;
                        for (int ix = 0; ix < zoneSide; ix++) {
                            if (!zoneGrid[rowOff + ix]) {
                                continue;
                            }
                            int cx = zoneMinX + ix;
                            int zx0 = (int) Math.round((mapCX + (cx * 16.0 - centerX) * scale) * dev);
                            int zx1 = (int) Math.round((mapCX + ((cx + 1) * 16.0 - centerX) * scale) * dev);
                            if (iz == 0 || !zoneGrid[rowOff - zoneSide + ix]) {
                                fillI(g, zx0, zy0, zx1, zy0 + t, borderColor);
                            }
                            if (iz == zoneSide - 1 || !zoneGrid[rowOff + zoneSide + ix]) {
                                fillI(g, zx0, zy1 - t, zx1, zy1, borderColor);
                            }
                            if (ix == 0 || !zoneGrid[rowOff + ix - 1]) {
                                fillI(g, zx0, zy0, zx0 + t, zy1, borderColor);
                            }
                            if (ix == zoneSide - 1 || !zoneGrid[rowOff + ix + 1]) {
                                fillI(g, zx1 - t, zy0, zx1, zy1, borderColor);
                            }
                        }
                    }
                }
            }

            drawPins(g, dimensionId, mapCX, mapCY, centerX, centerZ, scale, dev, minCx, maxCx, minCz, maxCz);

            double pcx = mapCX * dev;
            double pcy = mapCY * dev;
            double yaw = Math.toRadians(player.getViewYRot(partialTick));
            drawArrow(g, pcx, pcy, -Math.sin(yaw), Math.cos(yaw), 2.2 * dev + 3.0);

            g.pose().popMatrix();
            g.disableScissor();
        } else {
            g.enableScissor(x0, mapY0, x1, mapY1);
            String prompt = Lang.s("minimap.open_project");
            int maxW = Math.max(16, mapSize - 10);
            if (font.width(prompt) > maxW) {
                prompt = font.plainSubstrByWidth(prompt, Math.max(8, maxW - 6)) + "…";
            }
            g.centeredText(font, prompt, x0 + mapSize / 2, mapY0 + mapSize / 2 - 4, 0xFF9AA4B8);
            g.disableScissor();
        }

        if (barH > 0) {
            g.fill(x0, barY0, x1, barY1, 0xEB14161C);
            int sepY = hudAbove ? barY1 - 1 : barY0;
            g.fill(x0, sepY, x1, sepY + 1, 0xFF2C303D);

            int maxLineW = Math.max(16, mapSize - 8);
            String sep = " · ";
            int sepW = font.width(sep);
            int ty = barY0 + BAR_PAD_Y;
            for (List<HudSegment> line : hudLines) {
                int tx = x0 + 4;
                for (int i = 0; i < line.size(); i++) {
                    if (i > 0) {
                        if (tx + sepW <= x1 - 4) {
                            g.text(font, sep, tx, ty, 0xFF5A6275, false);
                            tx += sepW;
                        }
                    }
                    HudSegment seg = line.get(i);
                    String txt = seg.text();
                    int rem = (x1 - 4) - tx;
                    if (rem <= 4) {
                        break;
                    }
                    if (font.width(txt) > rem) {
                        txt = font.plainSubstrByWidth(txt, Math.max(6, rem - 6)) + "…";
                    }
                    g.text(font, txt, tx, ty, seg.color(), true);
                    tx += font.width(txt);
                }
                ty += LINE_H;
                if (ty > barY1 - 4) {
                    break;
                }
            }
        }

        return barH;
    }

    private static List<HudSegment> buildHudSegments(Minecraft mc, ClientLevel level,
                                                     LocalPlayer player, ChunkVaultSettings s) {
        List<HudSegment> out = new ArrayList<>();
        boolean hasProject = ProjectMap.isOpen();
        var manager = RecordingState.get().saveManager();
        boolean recording = RecordingState.get().isActive() && manager != null;
        String dimId = level.dimension().identifier().toString();

        if (s.isMinimapHudItemEnabled(MinimapHudItem.STATUS)) {
            if (recording) {
                out.add(new HudSegment("● REC", 0xFFFF6060));
            } else {
                out.add(new HudSegment("○ OFF", 0xFF8C94A6));
            }
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.CHUNKS)) {
            int count = recording ? manager.chunksSaved()
                    : (hasProject ? ProjectMap.forDimension(dimId).size() : 0);
            out.add(new HudSegment(formatCompact(count) + " " + Lang.s("minimap.hud.short.chunks"), 0xFFE6ECF5));
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.COORDS)) {
            int px = (int) Math.floor(player.getX());
            int pz = (int) Math.floor(player.getZ());
            out.add(new HudSegment(px + ", " + pz, 0xFFCCCCCC));
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.PROJECT)) {
            String name = hasProject ? ProjectMap.name() : Lang.s("minimap.hud.none");
            out.add(new HudSegment(name, hasProject ? 0xFFFFD866 : 0xFF707888));
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.PROJECT_SIZE)) {
            String sizeStr = hasProject ? queryProjectSize(ProjectMap.name()) : "—";
            out.add(new HudSegment(sizeStr, 0xFF7CE8A8));
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.CONTAINERS)) {
            int c = hasProject ? ContainerDatabase.totalCount() : 0;
            out.add(new HudSegment(formatCompact(c) + " " + Lang.s("minimap.hud.short.containers"), 0xFFFFB830));
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.ENTITIES)) {
            int ent = recording ? manager.entitiesSeen() : 0;
            out.add(new HudSegment(formatCompact(ent) + " " + Lang.s("minimap.hud.short.entities"), 0xFF66D8FF));
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.AREA)) {
            if (hasProject && AreaPlan.isActive()) {
                int pcx = player.blockPosition().getX() >> 4;
                int pcz = player.blockPosition().getZ() >> 4;
                AreaPlan.Progress prog = AreaPlan.computeProgress(pcx, pcz);
                out.add(new HudSegment(String.format(Locale.ROOT, "%.1f%%", prog.percent()), 0xFF38D8FF));
            } else {
                out.add(new HudSegment(Lang.s("minimap.hud.short.area_none"), 0xFF707888));
            }
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.DIMENSION)) {
            out.add(new HudSegment(shortDim(dimId), 0xFFB894FF));
        }
        if (s.isMinimapHudItemEnabled(MinimapHudItem.BIOME)) {
            String biome = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(k -> prettifyId(k.identifier().getPath()))
                    .orElse("");
            if (!biome.isEmpty()) {
                out.add(new HudSegment(biome, 0xFF9EE09E));
            }
        }
        return out;
    }

    private static List<List<HudSegment>> wrapSegments(Font font, List<HudSegment> segments, int maxW) {
        List<List<HudSegment>> lines = new ArrayList<>();
        int sepW = font.width(" · ");
        List<HudSegment> cur = new ArrayList<>();
        int curW = 0;
        for (HudSegment seg : segments) {
            int w = font.width(seg.text());
            if (!cur.isEmpty() && curW + sepW + w > maxW) {
                lines.add(cur);
                cur = new ArrayList<>();
                curW = 0;
            }
            if (!cur.isEmpty()) {
                curW += sepW;
            }
            cur.add(seg);
            curW += w;
        }
        if (!cur.isEmpty()) {
            lines.add(cur);
        }
        return lines;
    }

    private static String queryProjectSize(String projectName) {
        if (projectName == null || projectName.isEmpty()) {
            return "—";
        }
        long now = System.currentTimeMillis();
        boolean projChanged = !projectName.equals(cachedSizeProject);
        if (projChanged) {
            cachedSizeProject = projectName;
            cachedSizeBytes = -1L;
            lastSizeScanMs = 0L;
        }
        if ((projChanged || now - lastSizeScanMs >= 10000L) && SIZE_SCANNING.compareAndSet(false, true)) {
            lastSizeScanMs = now;
            final String target = projectName;
            Thread t = new Thread(() -> {
                try {
                    Path dir = ProjectMap.root().resolve(target);
                    long total = 0L;
                    if (Files.isDirectory(dir)) {
                        try (Stream<Path> walk = Files.walk(dir)) {
                            for (Path p : (Iterable<Path>) walk::iterator) {
                                if (Files.isRegularFile(p)) {
                                    try {
                                        total += Files.size(p);
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    }
                    if (target.equals(cachedSizeProject)) {
                        cachedSizeBytes = total;
                    }
                } catch (Exception ignored) {
                } finally {
                    SIZE_SCANNING.set(false);
                }
            }, "chunkvault-size");
            t.setDaemon(true);
            t.start();
        }
        long bytes = cachedSizeBytes;
        return bytes < 0L ? "…" : formatBytes(bytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return kb < 100.0 ? String.format(Locale.ROOT, "%.1f KB", kb) : ((long) Math.round(kb)) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return mb < 100.0 ? String.format(Locale.ROOT, "%.1f MB", mb) : ((long) Math.round(mb)) + " MB";
        }
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.2f GB", gb);
    }

    private static String formatCompact(int n) {
        if (n >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        }
        if (n >= 10_000) {
            return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        }
        return Integer.toString(n);
    }

    private static String shortDim(String dimId) {
        if ("minecraft:overworld".equals(dimId)) {
            return "Overworld";
        }
        if ("minecraft:the_nether".equals(dimId)) {
            return "Nether";
        }
        if ("minecraft:the_end".equals(dimId)) {
            return "End";
        }
        int colon = dimId.indexOf(':');
        return prettifyId(colon >= 0 ? dimId.substring(colon + 1) : dimId);
    }

    private static String prettifyId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean cap = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '_' || c == '-') {
                sb.append(' ');
                cap = true;
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void drawTerrain(GuiGraphicsExtractor g, String dimensionId,
                                    Map<Long, MapCache.ChunkColors> cache,
                                    double mapCX, double mapCY, double centerX, double centerZ,
                                    double scale, double dev, int minCx, int maxCx, int minCz, int maxCz) {
        double pxPerBlock = scale * dev;
        int lodIdx = pxPerBlock >= 0.5 ? 0 : (pxPerBlock >= 0.25 ? 1 : 2);
        var regionKeys = ProjectMap.regionKeys(dimensionId);
        int rMinX = minCx >> 4;
        int rMaxX = maxCx >> 4;
        int rMinZ = minCz >> 4;
        int rMaxZ = maxCz >> 4;
        double originX = rMinX * 256.0;
        double originZ = rMinZ * 256.0;
        double offX = (mapCX + (originX - centerX) * scale) * dev;
        double offY = (mapCY + (originZ - centerZ) * scale) * dev;

        g.pose().pushMatrix();
        g.pose().translate((float) offX, (float) offY);
        float texScale = (float) pxPerBlock;
        g.pose().scale(texScale, texScale);
        for (int rz = rMinZ; rz <= rMaxZ; rz++) {
            int relZ = (rz - rMinZ) * 256;
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                if (!regionKeys.contains(MapCache.key(rx, rz))) {
                    continue;
                }
                MapTextures.Lod lod = MapTextures.acquire(dimensionId, rx, rz, lodIdx, cache, frame, true);
                if (lod == null) {
                    continue;
                }
                int relX = (rx - rMinX) * 256;
                int size = lod.size;
                g.blit(RenderPipelines.GUI_TEXTURED, lod.id, relX, relZ, 0, 0, 256, 256,
                        size, size, size, size);
            }
        }
        g.pose().popMatrix();
    }

    private static Identifier pinTextureFor(int color) {
        if (!pinTexturesOk) {
            return null;
        }
        int key = color | 0xFF000000;
        Identifier existing = PIN_TEXTURES.get(key);
        if (existing != null) {
            return existing;
        }
        try {
            PinTexture tex = new PinTexture("chunkvault_pin_" + Integer.toHexString(key), 16);
            NativeImage img = tex.getPixels();
            if (img == null) {
                tex.close();
                pinTexturesOk = false;
                return null;
            }
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int px;
                    if (x < 2 || x > 13 || y < 2 || y > 13) {
                        px = 0x00000000;
                    } else if (x < 4 || x > 11 || y < 4 || y > 11) {
                        px = 0xD8000000;
                    } else {
                        px = key;
                    }
                    img.setPixel(x, y, px);
                }
            }
            tex.upload();
            Identifier id = Identifier.fromNamespaceAndPath("chunkvault", "pin/" + Integer.toHexString(key));
            Minecraft.getInstance().getTextureManager().register(id, tex);
            PIN_TEXTURES.put(key, id);
            return id;
        } catch (Throwable t) {
            pinTexturesOk = false;
            return null;
        }
    }

    private static void drawPins(GuiGraphicsExtractor g, String dimensionId,
                                 double mapCX, double mapCY, double centerX, double centerZ,
                                 double scale, double dev, int minCx, int maxCx, int minCz, int maxCz) {
        List<ContainerDatabase.ContainerEntry> visible = ContainerDatabase.inChunkRange(dimensionId, minCx, maxCx, minCz, maxCz);
        if (visible.isEmpty()) {
            return;
        }
        float pinScale = (float) Math.max(0.35, 0.4 * dev);
        int pad = (int) Math.ceil(9.0 * pinScale) + 2;
        int r = Math.max(1, (int) Math.round(1.6 * dev));
        int o = Math.max(1, (int) Math.round(0.8 * dev));
        for (ContainerDatabase.ContainerEntry e : visible) {
            if (ContainerDatabase.isSecondaryHalf(e) || !MapPinFilter.matches(e)) {
                continue;
            }
            double sx = (mapCX + (e.x() + 0.5 - centerX) * scale) * dev;
            double sy = (mapCY + (e.z() + 0.5 - centerZ) * scale) * dev;
            if (sx < clipX0 - pad || sx > clipX1 + pad || sy < clipY0 - pad || sy > clipY1 + pad) {
                continue;
            }
            int col = ContainerDatabase.colorFor(e);
            Identifier pinId = pinTextureFor(col);
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            if (pinId != null) {
                g.pose().scale(pinScale, pinScale);
                g.blit(RenderPipelines.GUI_TEXTURED, pinId, -8, -8, 0, 0, 16, 16, 16, 16, 16, 16);
            } else {
                g.fill(-r - o, -r - o, r + o, r + o, 0xD0000000);
                g.fill(-r, -r, r, r, col);
            }
            g.pose().popMatrix();
        }
    }

    private static void updateZone(Minecraft mc, ClientLevel level, LocalPlayer player) {
        int effR = Math.max(2, mc.options.getEffectiveRenderDistance());
        int scanR = effR + 1;
        long effRSq = (long) effR * effR;
        int pcx = (int) Math.floor(player.getX() / 16.0);
        int pcz = (int) Math.floor(player.getZ() / 16.0);
        int side = scanR * 2 + 1;
        if (zoneGrid.length != side * side) {
            zoneGrid = new boolean[side * side];
        } else {
            Arrays.fill(zoneGrid, false);
        }
        zoneMinX = pcx - scanR;
        zoneMinZ = pcz - scanR;
        zoneSide = side;
        zoneCount = 0;

        for (int iz = 0; iz < side; iz++) {
            int dz = iz - scanR;
            int cz = zoneMinZ + iz;
            int rowOff = iz * side;
            int runStart = Integer.MIN_VALUE;
            for (int ix = 0; ix <= side; ix++) {
                boolean loaded = false;
                if (ix < side) {
                    int dx = ix - scanR;
                    long lx = Math.max(0, Math.abs(dx) - 2);
                    long lz = Math.max(0, Math.abs(dz) - 2);
                    if (lx * lx + lz * lz < effRSq && isLoaded(level, zoneMinX + ix, cz)) {
                        loaded = true;
                        zoneGrid[rowOff + ix] = true;
                    }
                }
                if (loaded && runStart == Integer.MIN_VALUE) {
                    runStart = zoneMinX + ix;
                } else if (!loaded && runStart != Integer.MIN_VALUE) {
                    if (zoneCount == zoneX0.length) {
                        int n = zoneCount * 2;
                        zoneX0 = Arrays.copyOf(zoneX0, n);
                        zoneX1 = Arrays.copyOf(zoneX1, n);
                        zoneZ = Arrays.copyOf(zoneZ, n);
                    }
                    zoneX0[zoneCount] = runStart;
                    zoneX1[zoneCount] = zoneMinX + ix;
                    zoneZ[zoneCount] = cz;
                    zoneCount++;
                    runStart = Integer.MIN_VALUE;
                }
            }
        }
    }

    private static boolean isLoaded(ClientLevel level, int cx, int cz) {
        var chunk = level.getChunk(cx, cz);
        return chunk instanceof LevelChunk && !(chunk instanceof EmptyLevelChunk);
    }

    private static void fillI(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int ax0 = Math.max(x0, clipX0);
        int ay0 = Math.max(y0, clipY0);
        int ax1 = Math.min(x1, clipX1);
        int ay1 = Math.min(y1, clipY1);
        if (ax1 > ax0 && ay1 > ay0) {
            g.fill(ax0, ay0, ax1, ay1, color);
        }
    }

    private static void drawArrow(GuiGraphicsExtractor g, double cx, double cy, double fx, double fy, double len) {
        double wid = len * 0.72;
        double tipU = len;
        double backU = -0.8 * len;
        double notchU = -0.3 * len;
        double reach = len + 3;
        int xMin = (int) Math.floor(cx - reach);
        int xMax = (int) Math.ceil(cx + reach);
        int yMin = (int) Math.floor(cy - reach);
        int yMax = (int) Math.ceil(cy + reach);
        for (int iy = yMin; iy <= yMax; iy++) {
            int runStart = xMin;
            int runClass = 0;
            for (int ix = xMin; ix <= xMax + 1; ix++) {
                int cls = 0;
                if (ix <= xMax) {
                    double px = ix + 0.5 - cx;
                    double py = iy + 0.5 - cy;
                    double u = px * fx + py * fy;
                    double v = -px * fy + py * fx;
                    if (u > backU - 2 && u < tipU + 2 && Math.abs(v) < wid + 2) {
                        if (inTri(u, v, tipU, 0, backU, wid, notchU, 0)
                                || inTri(u, v, tipU, 0, notchU, 0, backU, -wid)) {
                            cls = 2;
                        } else {
                            double d = Math.min(
                                    Math.min(segDist(u, v, tipU, 0, backU, wid), segDist(u, v, backU, wid, notchU, 0)),
                                    Math.min(segDist(u, v, notchU, 0, backU, -wid), segDist(u, v, backU, -wid, tipU, 0)));
                            if (d <= 1.1) {
                                cls = 1;
                            }
                        }
                    }
                }
                if (cls != runClass) {
                    if (runClass != 0) {
                        fillI(g, runStart, iy, ix, iy + 1, runClass == 2 ? 0xFFFFFFFF : 0xE0000000);
                    }
                    runStart = ix;
                    runClass = cls;
                }
            }
        }
    }

    private static boolean inTri(double px, double py, double ax, double ay, double bx, double by,
                                 double cx, double cy) {
        double d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        double d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        double d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        double qx = ax + t * dx - px;
        double qy = ay + t * dy - py;
        return Math.sqrt(qx * qx + qy * qy);
    }
}
