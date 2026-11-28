package com.galerie.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Favoris : l'ensemble des chemins de fichiers marqués ♥, gardé dans favorites.txt (stockage interne de l'appli).
 * Un déplacement ou un renommage fait suivre le favori (MediaRepo.moved appelle rename).
 * Un fichier mis à la corbeille garde son favori : s'il est restauré à son emplacement, il redevient favori.
 */
public final class Favorites {
    private static final Set<String> SET = new HashSet<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static Context app;
    private static boolean loaded = false;

    private Favorites() { }

    public static synchronized void init(Context c) {
        if (app == null) app = c.getApplicationContext();
        ensure();
    }

    private static File file() {
        return new File(app.getFilesDir(), "favorites.txt");
    }

    private static void ensure() {
        if (loaded || app == null) return;
        loaded = true;
        File f = file();
        if (!f.isFile()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.isEmpty()) SET.add(line);
        } catch (IOException ignored) { }
    }

    private static void save() {
        if (app == null) return;
        final List<String> snap = new ArrayList<>(SET);
        final File f = file();
        IO.execute(() -> {
            File tmp = new File(f.getParentFile(), "favorites.txt.tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                for (String p : snap) w.write(p + "\n");
            } catch (IOException e) {
                return;
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        });
    }

    public static synchronized boolean is(String path) {
        ensure();
        return SET.contains(path);
    }

    public static synchronized int count() {
        ensure();
        return SET.size();
    }

    /** Inverse le favori ; renvoie le nouvel état (true = favori). */
    public static synchronized boolean toggle(String path) {
        ensure();
        boolean now = SET.add(path);
        if (!now) SET.remove(path);
        save();
        return now;
    }

    public static synchronized void addAll(Collection<String> paths) {
        ensure();
        SET.addAll(paths);
        save();
    }

    public static synchronized void removeAll(Collection<String> paths) {
        ensure();
        SET.removeAll(paths);
        save();
    }

    /** Un fichier a changé de chemin : le favori le suit. */
    public static synchronized void rename(String oldPath, String newPath) {
        ensure();
        if (SET.remove(oldPath)) {
            SET.add(newPath);
            save();
        }
    }
}
