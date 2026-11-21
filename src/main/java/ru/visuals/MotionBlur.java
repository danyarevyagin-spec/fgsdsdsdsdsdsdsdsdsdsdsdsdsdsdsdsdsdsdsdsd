package ru.visuals;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Motion Blur — временно отключён (stub).
 * Будет реализован через шейдер в следующей версии.
 */
public final class MotionBlur {
    private MotionBlur() {}

    public static void run(DrawContext ctx, MinecraftClient mc) {
        // заглушка — не делает ничего
    }

    public static void release() {
        // заглушка
    }
}
