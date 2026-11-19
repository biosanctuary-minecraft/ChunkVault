package com.chunkvault;

import net.minecraft.network.chat.Component;

public final class Lang {
    private Lang() {}

    public static Component c(String key, Object... args) {
        return Component.translatable("chunkvault." + key, args);
    }

    public static String s(String key, Object... args) {
        return c(key, args).getString();
    }
}
