package ru.visuals;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import javax.sound.sampled.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Плеер: играет wav/ogg/mp3/flac. Открывает системный диалог файлов через Swing. */
public final class MusicPlayer {
    private MusicPlayer() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kk-music-io"); t.setDaemon(true); return t;
    });

    private static final List<File> PLAYLIST = Collections.synchronizedList(new ArrayList<>());
    private static volatile int idx = 0;
    private static volatile Clip clip;
    private static volatile boolean playing = false;
    private static volatile String trackName = "";
    private static volatile long startMs = 0, lenMs = 0;
    private static volatile boolean chooserOpen = false;
    private static boolean prevCtrlP = false;

    // ─────────── файловый диалог ───────────

    public static void openFileChooser() {
        if (chooserOpen) return;
        chooserOpen = true;
        Thread t = new Thread(() -> {
            try {
                // инициализируем Swing в EDT
                javax.swing.SwingUtilities.invokeAndWait(() -> {});
                javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
                fc.setDialogTitle("КК Visuals — выберите музыку");
                fc.setMultiSelectionEnabled(true);
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "Аудио (wav, mp3, ogg, flac, aiff)", "wav", "mp3", "ogg", "flac", "aiff"));
                // пробуем запустить поверх MC окна
                java.awt.Frame frame = new java.awt.Frame();
                frame.setUndecorated(true);
                frame.setAlwaysOnTop(true);
                frame.setVisible(true);
                frame.toFront();
                int result = fc.showOpenDialog(frame);
                frame.dispose();
                if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
                    File[] files = fc.getSelectedFiles();
                    for (File f : files) PLAYLIST.add(f);
                    if (!playing && !PLAYLIST.isEmpty()) play(PLAYLIST.get(idx % PLAYLIST.size()));
                }
            } catch (Exception e) {
                System.err.println("[KKVisuals MusicPlayer] chooser error: " + e);
            } finally {
                chooserOpen = false;
            }
        }, "kk-chooser");
        t.setDaemon(true);
        t.start();
    }

    // ─────────── воспроизведение ───────────

    private static void play(File file) {
        IO.submit(() -> {
            try {
                stopClip();
                AudioInputStream raw = AudioSystem.getAudioInputStream(file);
                AudioFormat fmt = raw.getFormat();
                // конвертируем в PCM если нужно
                if (!fmt.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)) {
                    AudioFormat pcm = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            fmt.getSampleRate(), 16,
                            fmt.getChannels(), fmt.getChannels() * 2,
                            fmt.getSampleRate(), false);
                    raw = AudioSystem.getAudioInputStream(pcm, raw);
                }
                Clip c = AudioSystem.getClip();
                c.open(raw);
                // громкость
                if (c.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl vol = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
                    float db = 20f * (float) Math.log10(Math.max(0.001f, Features.MP_VOL.getf() / 100f));
                    vol.setValue(Math.max(vol.getMinimum(), Math.min(vol.getMaximum(), db)));
                }
                clip = c;
                trackName = file.getName().replaceFirst("\\.[^.]+$", "");
                lenMs = c.getMicrosecondLength() / 1000L;
                startMs = System.currentTimeMillis();
                playing = true;
                c.start();
                c.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP && playing) {
                        playing = false;
                        try { Thread.sleep(60); } catch (InterruptedException ignored) {}
                        advanceTrack();
                    }
                });
            } catch (Exception e) {
                System.err.println("[KKVisuals MusicPlayer] play error: " + e);
                playing = false;
                trackName = "Ошибка: " + file.getName();
            }
        });
    }

    private static void stopClip() {
        playing = false;
        Clip c = clip; clip = null;
        if (c != null) { try { c.stop(); c.close(); } catch (Exception ignored) {} }
    }

    private static void advanceTrack() {
        if (!Features.MPLAYER.on() || PLAYLIST.isEmpty()) return;
        if (Features.MP_SHUFFLE.value) idx = (int)(Math.random() * PLAYLIST.size());
        else idx = (idx + 1) % PLAYLIST.size();
        play(PLAYLIST.get(idx));
    }

    public static void next()  { if (PLAYLIST.isEmpty()) return; idx = (idx + 1) % PLAYLIST.size(); play(PLAYLIST.get(idx)); }
    public static void prev()  { if (PLAYLIST.isEmpty()) return; idx = Math.floorMod(idx - 1, PLAYLIST.size()); play(PLAYLIST.get(idx)); }

    public static void toggle() {
        Clip c = clip;
        if (c == null) { if (!PLAYLIST.isEmpty()) play(PLAYLIST.get(idx % PLAYLIST.size())); return; }
        if (playing) { c.stop(); playing = false; }
        else { c.start(); playing = true; startMs = System.currentTimeMillis() - c.getMicrosecondPosition() / 1000L; }
    }

    public static void clearPlaylist() { stopClip(); PLAYLIST.clear(); idx = 0; trackName = ""; }

    // ─────────── тик + клавиши ───────────

    public static void tickKey(MinecraftClient mc) {
        if (mc.player == null || mc.currentScreen != null) return;
        long h = mc.getWindow().getHandle();
        boolean ctrl = org.lwjgl.glfw.GLFW.glfwGetKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == 1
                    || org.lwjgl.glfw.GLFW.glfwGetKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == 1;
        boolean p    = org.lwjgl.glfw.GLFW.glfwGetKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_P) == 1;
        boolean down = ctrl && p;
        if (down && !prevCtrlP) openFileChooser();
        prevCtrlP = down;
        // обновляем громкость на лету
        if (playing && clip != null && clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl vol = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float db = 20f * (float) Math.log10(Math.max(0.001f, Features.MP_VOL.getf() / 100f));
            vol.setValue(Math.max(vol.getMinimum(), Math.min(vol.getMaximum(), db)));
        }
    }

    // ─────────── HUD ───────────

    public static void render(DrawContext ctx, MinecraftClient mc, int sw, int sh) {
        if (!Features.MPLAYER.on() || PLAYLIST.isEmpty()) return;
        boolean mini = Features.MP_MINIFY.value;
        int w = mini ? 110 : 210, h = mini ? 18 : 50;
        int bx = switch (Features.MP_POS.index) {
            case 1 -> sw - w - 4;
            case 2 -> 4;
            default -> 4;
        };
        int by = switch (Features.MP_POS.index) {
            case 2 -> sh - h - 24;
            default -> 24;
        };
        Theme.P th = Theme.cur();
        int acc = 0xFF000000 | Theme.accent();
        Gfx.glass(ctx, bx, by, bx + w, by + h, 8, Features.T_OPACITY.getf() / 100f, 1f, false);

        // иконка ♫
        ctx.drawText(mc.textRenderer, "♫", bx + 5, by + 5, acc, false);
        // название
        String name = trackName.isEmpty() ? "—" : trackName;
        name = fit(mc, (playing ? "" : "⏸ ") + name, w - 20);
        ctx.drawText(mc.textRenderer, name, bx + 15, by + 5, th.text(), false);

        if (!mini) {
            // прогресс-бар
            int t1 = bx + 7, t2 = bx + w - 7, ty = by + 18;
            Gfx.roundRect(ctx, t1, ty, t2, ty + 3, 1, Gfx.mul(th.line(), 1f));
            long pos = playing ? System.currentTimeMillis() - startMs : (clip != null ? clip.getMicrosecondPosition() / 1000L : 0L);
            if (lenMs > 0) {
                float frac = Math.max(0f, Math.min(1f, (float) pos / lenMs));
                int fx = t1 + Math.round(frac * (t2 - t1));
                if (fx > t1) Gfx.roundRect(ctx, t1, ty, fx, ty + 3, 1, acc);
            }
            // время
            String timeStr = fmt(Math.max(0L, pos)) + " / " + fmt(lenMs);
            ctx.drawText(mc.textRenderer, timeStr, bx + 7, by + 25, th.sub(), false);
            // кнопки ⏮ ⏯ ⏭
            int[] bxs = { bx + w / 2 - 24, bx + w / 2 - 8, bx + w / 2 + 8 };
            String[] btns = { "⏮", playing ? "⏸" : "▶", "⏭" };
            for (int i = 0; i < 3; i++) {
                Gfx.roundRect(ctx, bxs[i], by + 34, bxs[i] + 15, by + 46, 4, Gfx.mul(th.card(), 1f));
                ctx.drawText(mc.textRenderer, btns[i], bxs[i] + 3, by + 37, th.text(), false);
            }
        }
    }

    public static boolean handleClick(double mx, double my, int sw, int sh) {
        if (!Features.MPLAYER.on() || Features.MP_MINIFY.value || PLAYLIST.isEmpty()) return false;
        int w = 210, h = 50;
        int bx = switch (Features.MP_POS.index) { case 1 -> sw - w - 4; default -> 4; };
        int by = switch (Features.MP_POS.index) { case 2 -> sh - h - 24; default -> 24; };
        int[] bxs = { bx + w / 2 - 24, bx + w / 2 - 8, bx + w / 2 + 8 };
        if (my >= by + 34 && my <= by + 46) {
            if (mx >= bxs[0] && mx <= bxs[0] + 15) { prev(); return true; }
            if (mx >= bxs[1] && mx <= bxs[1] + 15) { toggle(); return true; }
            if (mx >= bxs[2] && mx <= bxs[2] + 15) { next(); return true; }
        }
        return false;
    }

    private static String fit(MinecraftClient mc, String s, int max) {
        while (mc.textRenderer.getWidth(s) > max && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
