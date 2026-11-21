package ru.visuals;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.RotationAxis;

import java.util.ArrayList;
import java.util.List;

/**
 * Death Animation: при убийстве игрока падает 3D Тунг-тунг Сахур с неба
 * и появляются числа 6 и 7.
 */
public final class DeathEffects {
    private DeathEffects() {}

    // ── Тунг-тунг Сахур ──────────────────────────────────────────────
    private static class Tung {
        float x, y = -120f, vy = 0f;
        float rot, rotSpd;
        float size;
        float age = 0f, maxAge;
        boolean bounced = false;
        float bounceV = 0f;

        Tung(float screenW, float maxAge) {
            x = 60f + (float)(Math.random() * (screenW - 120f));
            size = 36f + (float)(Math.random() * 28f);
            rot = (float)(Math.random() * 360f);
            rotSpd = -80f + (float)(Math.random() * 160f);
            this.maxAge = maxAge;
        }

        boolean tick(float dt, float ground) {
            vy = Math.min(560f, vy + 420f * dt);
            y += vy * dt;
            rot += rotSpd * dt;
            age += dt;
            if (y >= ground && !bounced) {
                bounced = true; y = ground; vy = -vy * 0.38f;
                rotSpd *= 0.5f;
            }
            if (bounced && y >= ground) { y = ground; vy = -vy * 0.3f; }
            return age < maxAge;
        }
    }

    // ── 67 ───────────────────────────────────────────────────────────
    private static class SixSeven {
        float x, y, vy = -260f, vx;
        float age = 0f, maxAge = 2.8f;
        float scale = 0f;
        char ch;
        int col;

        SixSeven(float x, float y, char ch, int col) {
            this.x = x; this.y = y; this.ch = ch; this.col = col;
            this.vx = -40f + (float)(Math.random() * 80f);
        }

        boolean tick(float dt) {
            vy += 300f * dt; x += vx * dt; y += vy * dt;
            age += dt;
            scale = Math.min(1f, age * 7f) * Math.max(0f, 1f - Math.max(0f, (age - maxAge + 0.4f) / 0.4f));
            return age < maxAge;
        }
    }

    private static final List<Tung> TUNGS = new ArrayList<>();
    private static final List<SixSeven> SS = new ArrayList<>();
    private static long killNs = -1L;
    private static float shake = 0f;
    public static float shakeDx, shakeDy;

    public static void onKill() {
        if (!Features.DEATH.on()) return;
        killNs = System.nanoTime();
        shake = 1f;
    }

    public static void render(DrawContext ctx, MinecraftClient mc, int sw, int sh, float dt) {
        if (!Features.DEATH.on() || killNs < 0L) return;
        float elapsed = (System.nanoTime() - killNs) / 1_000_000_000f;
        float maxDur = Features.DA_DURATION.getf();
        if (elapsed > maxDur + 2f) {
            killNs = -1L; TUNGS.clear(); SS.clear(); shake = 0f;
            shakeDx = 0f; shakeDy = 0f;
            return;
        }

        // спавн
        if (elapsed < 0.05f && TUNGS.isEmpty()) {
            if (Features.DA_TUNG.value) {
                int n = 2 + (int)(Math.random() * 3);
                for (int i = 0; i < n; i++) TUNGS.add(new Tung(sw, maxDur));
            }
            if (Features.DA_67.value) {
                float cx = sw * 0.5f, cy = sh * 0.4f;
                float time = System.nanoTime() / 1_000_000_000f;
                SS.add(new SixSeven(cx - 28f, cy, '6', 0xFF000000 | Theme.rainbow(time, 0f)));
                SS.add(new SixSeven(cx + 6f,  cy, '7', 0xFF000000 | Theme.rainbow(time, 0.5f)));
            }
        }

        // тряска
        shake = Math.max(0f, shake - dt * 2.5f);
        shakeDx = shake > 0.01f ? (float)(Math.random() - 0.5) * shake * 5f : 0f;
        shakeDy = shake > 0.01f ? (float)(Math.random() - 0.5) * shake * 5f : 0f;

        ctx.getMatrices().push();
        ctx.getMatrices().translate(shakeDx, shakeDy, 0f);

        // ── 67 ──
        SS.removeIf(s -> !s.tick(dt));
        for (SixSeven ss : SS) {
            if (ss.scale < 0.02f) continue;
            ctx.getMatrices().push();
            ctx.getMatrices().translate(ss.x, ss.y, 0f);
            float sc = ss.scale * 5.5f;
            ctx.getMatrices().scale(sc, sc, 1f);
            int al = (int)(Math.min(1f, ss.scale) * 255f);
            int col = (al << 24) | (ss.col & 0xFFFFFF);
            ctx.drawText(mc.textRenderer, String.valueOf(ss.ch),
                    -mc.textRenderer.getWidth(String.valueOf(ss.ch)) / 2, -4, col, true);
            ctx.getMatrices().pop();
        }

        // ── 3D Тунг-тунг Сахур ──
        float ground = sh * 0.78f;
        TUNGS.removeIf(t -> !t.tick(dt, ground));
        for (Tung t : TUNGS) {
            float a = Math.min(1f, (t.maxAge - t.age) / 0.5f);
            if (a < 0.02f) continue;
            drawTung(ctx, t.x, t.y, t.size, t.rot, a);
        }

        ctx.getMatrices().pop();
    }

    // ── 3D Тунг-тунг Сахур (псевдо-3D через DrawContext) ─────────────
    private static void drawTung(DrawContext ctx, float cx, float cy, float sz, float rot, float alpha) {
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx, cy, 0f);
        ctx.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rot));

        int al = (int)(alpha * 255f);
        // Цвета
        int skin   = col(0xE89030, al);
        int skinD  = col(0xBB7020, al);  // тень
        int skinL  = col(0xFFB850, al);  // свет
        int eyeW   = col(0xFFFFFF, al);
        int eyeB   = col(0x222222, al);
        int mouth  = col(0x8B4513, al);
        int shadow = col(0x000000, (int)(al * 0.22f));

        float s = sz / 48f; // масштаб

        // ── тень на земле ──
        fillEllipse(ctx, 0, (int)(sz * 0.52f), (int)(sz * 0.55f), (int)(sz * 0.14f), shadow);

        // ── ТЕЛО ──
        // основа
        int bw = (int)(26 * s), bh = (int)(30 * s);
        int bx = -bw / 2, by2 = (int)(-18 * s);
        ctx.fill(bx, by2, bx + bw, by2 + bh, skin);
        // боковая тень (правая)
        for (int i = 0; i < 4; i++)
            ctx.fill(bx + bw - 4 + i, by2, bx + bw, by2 + bh, col(0x000000, (int)(al * 0.06f * (4 - i))));
        // боковой свет (левая)
        for (int i = 0; i < 3; i++)
            ctx.fill(bx, by2, bx + 3 - i, by2 + bh, col(0xFFFFFF, (int)(al * 0.06f * (3 - i))));

        // ── ГОЛОВА ──
        int hw = (int)(22 * s), hh = (int)(24 * s);
        int hx = -hw / 2, hy = (int)(-38 * s);
        ctx.fill(hx, hy, hx + hw, hy + hh, skin);
        // верхняя грань (свет)
        ctx.fill(hx + 1, hy, hx + hw - 1, hy + (int)(3 * s), skinL);
        // боковая грань (тень)
        for (int i = 0; i < 3; i++)
            ctx.fill(hx + hw - 3 + i, hy, hx + hw, hy + hh, col(0x000000, (int)(al * 0.07f * (3 - i))));

        // ── ГЛАЗА ──
        int ew = (int)(6 * s), eh = (int)(6 * s);
        int ey = hy + (int)(8 * s);
        // левый
        ctx.fill(hx + (int)(3 * s), ey, hx + (int)(3 * s) + ew, ey + eh, eyeW);
        ctx.fill(hx + (int)(4 * s) + ew / 4, ey + eh / 4, hx + (int)(4 * s) + ew * 3 / 4, ey + eh * 3 / 4, eyeB);
        // правый
        int rx = hx + hw - (int)(3 * s) - ew;
        ctx.fill(rx, ey, rx + ew, ey + eh, eyeW);
        ctx.fill(rx + ew / 4, ey + eh / 4, rx + ew * 3 / 4, ey + eh * 3 / 4, eyeB);

        // ── УЛЫБКА ──
        int my = hy + (int)(17 * s);
        for (int i = -3; i <= 3; i++) {
            int mx2 = hx + hw / 2 + (int)(i * 2.5f * s);
            int moff = (int)(Math.abs(i) * 1.3f * s);
            ctx.fill(mx2, my + moff, mx2 + (int)(2 * s), my + moff + (int)(2 * s), mouth);
        }

        // ── РУКИ ──
        int aw = (int)(6 * s), ah = (int)(20 * s);
        // левая
        ctx.fill(bx - aw - (int)(s), by2 + (int)(4 * s), bx, by2 + (int)(4 * s) + ah, skinD);
        // правая
        ctx.fill(bx + bw + (int)(s), by2 + (int)(4 * s), bx + bw + (int)(s) + aw, by2 + (int)(4 * s) + ah, skinD);

        // ── НОГИ ──
        int lw = (int)(10 * s), lh = (int)(18 * s);
        int ly = by2 + bh;
        ctx.fill(bx + (int)(2 * s), ly, bx + (int)(2 * s) + lw, ly + lh, skinD);
        ctx.fill(bx + bw - (int)(2 * s) - lw, ly, bx + bw - (int)(2 * s), ly + lh, skinD);

        ctx.getMatrices().pop();
    }

    private static int col(int rgb, int alpha) {
        return (Math.min(255, Math.max(0, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static void fillEllipse(DrawContext ctx, int cx, int cy, int rx2, int ry, int color) {
        for (int row = -ry; row <= ry; row++) {
            float t = 1f - (float)(row * row) / (ry * ry + 1);
            int halfW = (int)(rx2 * Math.sqrt(Math.max(0f, t)));
            if (halfW > 0) ctx.fill(cx - halfW, cy + row, cx + halfW, cy + row + 1, color);
        }
    }
}
