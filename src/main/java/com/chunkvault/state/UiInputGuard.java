package com.chunkvault.state;

import com.chunkvault.gui.MapScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;

public final class UiInputGuard {

    private static volatile Screen activeScreen;
    private static volatile long graceUntilMs;

    private UiInputGuard() {}

    public static void onScreenOpened(Screen screen) {
        if (screen != null && screen.getClass().getName().startsWith("com.chunkvault.gui.")) {
            activeScreen = screen;
            if (!(screen instanceof MapScreen)) {
                KeyMapping.releaseAll();
            }
        }
    }

    public static void onScreenClosed(Screen screen) {
        if (screen != null && screen == activeScreen) {
            boolean wasMap = screen instanceof MapScreen;
            activeScreen = null;
            graceUntilMs = System.currentTimeMillis() + 180L;
            if (!wasMap) {
                KeyMapping.releaseAll();
            }
        }
    }

    public static void clear() {
        activeScreen = null;
        graceUntilMs = 0L;
    }

    public static boolean isFullBlock() {
        Screen s = activeScreen;
        if (s != null) {
            if (s instanceof MapScreen map && !map.isTypingInSearch()) {
                return false;
            }
            return true;
        }
        return false;
    }

    public static boolean isShiftBlocked() {
        return activeScreen != null || System.currentTimeMillis() < graceUntilMs;
    }
}
