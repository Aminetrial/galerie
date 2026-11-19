package com.galerie.app;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Journal de diagnostic : écrit dans Téléchargements/galerie-erreur.txt
 * - les plantages (exception + pile)
 * - les blocages de l'interface de plus de 2,5 s (pile du fil principal au moment du blocage)
 */
public final class CrashLog {
    private static boolean started = false;
    private static volatile long lastTick = 0;

    private CrashLog() { }

    public static synchronized void start() {
        if (started) return;
        started = true;

        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            write("PLANTAGE (fil " + t.getName() + ")\n" + sw);
            if (old != null) old.uncaughtException(t, e);
        });

        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                lastTick = System.currentTimeMillis();
            }
        };
        lastTick = System.currentTimeMillis();
        Thread watchdog = new Thread(() -> {
            boolean reported = false;
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                if (now - lastTick > 2500) {
                    if (!reported) {
                        reported = true;
                        StringBuilder sb = new StringBuilder("INTERFACE BLOQUEE depuis " + (now - lastTick) + " ms\n");
                        for (StackTraceElement el : Looper.getMainLooper().getThread().getStackTrace())
                            sb.append("    at ").append(el).append('\n');
                        write(sb.toString());
                    }
                } else reported = false;
                main.post(tick);
            }
        }, "galerie-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void write(String text) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dir, "galerie-erreur.txt");
            try (FileWriter w = new FileWriter(f, true)) {
                w.write("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " ===\n");
                w.write(text + "\n");
            }
        } catch (Throwable ignored) { }
    }
}
