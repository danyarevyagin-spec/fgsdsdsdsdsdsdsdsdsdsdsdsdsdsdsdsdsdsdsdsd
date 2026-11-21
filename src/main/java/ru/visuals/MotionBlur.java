package ru.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;

/**
 * Motion Blur через накопление кадров.
 * Рисует старый кадр поверх нового с прозрачностью — без GL14/GL11 констант.
 */
public final class MotionBlur {
    private MotionBlur() {}

    private static Framebuffer accum;
    private static boolean first = true;
    private static long lastNs = System.nanoTime();
    private static float lastYaw, lastPitch, motion;

    public static void run(DrawContext ctx, MinecraftClient mc) {
        if (!Features.MB.on() || mc.player == null || mc.currentScreen != null) {
            first = true;
            return;
        }

        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1_000_000_000f);
        lastNs = now;

        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        float ang = Math.abs(yaw - lastYaw) + Math.abs(pitch - lastPitch);
        lastYaw = yaw; lastPitch = pitch;
        float speed = dt > 0f ? ang / dt : 0f;
        float vel = (float) mc.player.getVelocity().length();
        float target = Math.min(1f, speed / 180f + vel * 1.2f);
        motion += (target - motion) * Math.min(1f, dt * 10f);

        float strength = Features.MB_STR.getf() / 100f;
        if (Features.MB_REACT.value) {
            float minS = Math.min(strength, Features.MB_MIN.getf() / 100f);
            strength = minS + (strength - minS) * motion;
        }
        if (Features.MB_ADAPT.value && dt > 0f)
            strength = (float) Math.pow(strength, dt * 60f);
        strength = Math.max(0f, Math.min(0.92f, strength));
        if (strength < 0.02f) { first = true; return; }

        Framebuffer main = mc.getFramebuffer();
        int w = main.textureWidth, h = main.textureHeight;
        if (w <= 0 || h <= 0) return;

        ctx.draw(); // сбрасываем draw-буфер

        if (accum == null || accum.textureWidth != w || accum.textureHeight != h) {
            if (accum != null) accum.delete();
            accum = new SimpleFramebuffer(w, h, false);
            first = true;
        }

        // Рисуем накопленный кадр поверх текущего с прозрачностью
        if (!first) {
            main.beginWrite(false);
            RenderSystem.enableBlend();
            // стандартный SRC_ALPHA / ONE_MINUS_SRC_ALPHA — работает везде
            RenderSystem.blendFunc(
                com.mojang.blaze3d.platform.GlStateManager.SrcFactor.CONSTANT_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE_MINUS_CONSTANT_ALPHA
            );
            com.mojang.blaze3d.platform.GlStateManager._blendColor(0f, 0f, 0f, strength);
            accum.draw(w, h);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }

        // Копируем текущий кадр в accum
        accum.beginWrite(true);
        RenderSystem.disableBlend();
        main.draw(w, h);
        main.beginWrite(false);
        first = false;
    }

    public static void release() {
        if (accum != null) { accum.delete(); accum = null; }
        first = true;
    }
}
