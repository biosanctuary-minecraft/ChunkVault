package com.chunkvault;

import net.minecraft.network.chat.Component;

/**
 * Tiny helper for translated text. All strings live in
 * assets/chunkvault/lang/<locale>.json (en_us.json, ru_ru.json, ...) and
 * Minecraft picks the file that matches the language chosen in the game
 * settings; anything missing falls back to en_us.
 */
public final class Lang {
    private Lang() {
    }

    /** Translated Component for key "chunkvault.<key>". */
    public static Component c(String key, Object... args) {
        return Component.translatable("chunkvault." + key, args);
    }

    /** Same as {@link #c} but as a plain String (for drawing text directly). */
    public static String s(String key, Object... args) {
        return c(key, args).getString();
    }
}
