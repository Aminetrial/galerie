package com.galerie.app;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
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

    private static File internal;

    /** Texte du journal (pour l'écran Réglages). */
    public static String read() {
        try {
            if (internal == null || !internal.exists()) return "(journal vide)";
            java.io.FileInputStream in = new java.io.FileInputStream(internal);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            String t = bo.toString("UTF-8");
            return t.length() > 30000 ? t.substring(t.length() - 30000) : t;
        } catch (Throwable e) { return "(lecture impossible : " + e + ")"; }
    }

    public static void clear() {
        try { if (internal != null) internal.delete(); } catch (Throwable ignored) { }
    }

    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        internal = new File(app.getFilesDir(), "journal.txt");
        write("Démarrage de l'application");
        if (Build.VERSION.SDK_INT >= 30) {
            new Thread(() -> {
                try {
                    ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                    for (ApplicationExitInfo i : am.getHistoricalProcessExitReasons(null, 0, 6)) {
                        StringBuilder sb = new StringBuilder("Fermeture précédente : raison=" + i.getReason()
                                + " (" + reasonName(i.getReason()) + ") description=" + i.getDescription()
                                + " date=" + new Date(i.getTimestamp()) + "\n");
                        if (i.getReason() == ApplicationExitInfo.REASON_ANR) {
                            try {
                                java.io.InputStream in = i.getTraceInputStream();
                                if (in != null) {
                                    byte[] buf = new byte[6000];
                                    int n = in.read(buf);
                                    if (n > 0) sb.append(new String(buf, 0, n, "UTF-8")).append('\n');
                                    in.close();
                                }
                            } catch (Throwable ignored) { }
                        }
                        write(sb.toString());
                    }
                } catch (Throwable ignored) { }
            }, "galerie-exit-info").start();
        }


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

    private static String reasonName(int r) {
        switch (r) {
            case 1: return "sortie volontaire";
            case 2: return "signal";
            case 3: return "mémoire insuffisante";
            case 4: return "plantage Java";
            case 5: return "plantage natif";
            case 6: return "ANR (ne répond plus)";
            case 7: return "erreur d'initialisation";
            case 8: return "permission";
            case 9: return "autre";
            case 10: return "tué par l'utilisateur";
            case 11: return "changement d'état";
            case 12: return "libéré pour mémoire";
            default: return "autre";
        }
    }

    private static void write(String text) {
        String stamp = "=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " ===\n" + text + "\n";
        try {
            if (internal != null) try (FileWriter w = new FileWriter(internal, true)) { w.write(stamp); }
        } catch (Throwable ignored) { }
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
