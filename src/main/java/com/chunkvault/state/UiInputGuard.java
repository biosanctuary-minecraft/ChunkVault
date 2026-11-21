package com.chunkvault.state;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;

public final class UiInputGuard {

    private static volatile Screen activeScreen;
    private static volatile long graceUntilMs;

    private UiInputGuard() {}

    public static void onScreenOpened(Screen screen) {
        if (screen != null && screen.getClass().getName().startsWith("com.chunkvault.gui.")) {
            activeScreen = screen;
            KeyMapping.releaseAll();
        }
    }

    public static void onScreenClosed(Screen screen) {
        if (screen != null && screen == activeScreen) {
            activeScreen = null;
            graceUntilMs = System.currentTimeMillis() + 180L;
            KeyMapping.releaseAll();
        }
    }

    public static void clear() {
        activeScreen = null;
        graceUntilMs = 0L;
    }

    public static boolean isBlocking() {
        return activeScreen != null || System.currentTimeMillis() < graceUntilMs;
    }
}
