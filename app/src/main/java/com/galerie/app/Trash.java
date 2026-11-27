package com.galerie.app;

import android.os.Environment;

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
import java.util.List;

/**
 * Corbeille de la galerie.
 *
 * Les fichiers supprimés sont déplacés dans un dossier caché du stockage interne
 * (.Galerie-corbeille, avec .nomedia pour qu'ils ne reviennent pas dans la galerie).
 * Un petit fichier index.tsv garde pour chacun : nom dans la corbeille, chemin d'origine,
 * date de suppression, taille. Après 30 jours, ils sont supprimés définitivement.
 */
public final class Trash {
    public static final long KEEP_MS = 30L * 24 * 3600 * 1000;
    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final String INDEX = "index.tsv";
    private static int counter = 0;

    private Trash() { }

    public static final class Entry {
        public String name;      // nom du fichier dans la corbeille
        public String orig;      // chemin d'origine
        public long deleted;     // ms
        public long size;        // octets
        public File file;        // fichier dans la corbeille

        public boolean video() { return FileUtil.isVideo(new File(orig)); }

        public String displayName() {
            int i = orig.lastIndexOf('/');
            return i >= 0 ? orig.substring(i + 1) : orig;
        }

        /** Jours restants avant la suppression définitive (arrondi au-dessus, minimum 0). */
        public int daysLeft() {
            long left = deleted + KEEP_MS - System.currentTimeMillis();
            return (int) Math.max(0, (left + DAY_MS - 1) / DAY_MS);
        }
    }

    private static File dir() {
        File d = new File(Environment.getExternalStorageDirectory(), ".Galerie-corbeille");
        if (!d.isDirectory()) d.mkdirs();
        File nm = new File(d, ".nomedia");
        if (!nm.exists()) {
            try { nm.createNewFile(); } catch (IOException ignored) { }
        }
        return d;
    }

    // ------------------------------------------------------------------ index

    private static List<Entry> readIndex(File d) {
        List<Entry> out = new ArrayList<>();
        File idx = new File(d, INDEX);
        if (!idx.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(idx), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                Entry e = new Entry();
                e.name = p[0];
                e.orig = p[1];
                try {
                    e.deleted = Long.parseLong(p[2]);
                    e.size = Long.parseLong(p[3]);
                } catch (NumberFormatException ex) {
                    continue;
                }
                e.file = new File(d, e.name);
                out.add(e);
            }
        } catch (IOException ignored) { }
        return out;
    }

    private static void writeIndex(File d, List<Entry> list) {
        File tmp = new File(d, INDEX + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Entry e : list) {
                w.write(e.name + "\t" + e.orig + "\t" + e.deleted + "\t" + e.size + "\n");
            }
        } catch (IOException ex) {
            return;
        }
        File idx = new File(d, INDEX);
        if (!tmp.renameTo(idx)) {
            idx.delete();
            tmp.renameTo(idx);
        }
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    // ------------------------------------------------------------------ API

    /** Éléments de la corbeille, le plus récemment supprimé d'abord (les fichiers disparus sont oubliés). */
    public static synchronized List<Entry> list() {
        File d = dir();
        List<Entry> all = readIndex(d);
        List<Entry> ok = new ArrayList<>();
        for (Entry e : all) if (e.file.isFile()) ok.add(e);
        if (ok.size() != all.size()) writeIndex(d, ok);
        java.util.Collections.sort(ok, (a, b) -> Long.compare(b.deleted, a.deleted));
        return ok;
    }

    public static int count() {
        return list().size();
    }

    public static long totalBytes(List<Entry> l) {
        long t = 0;
        for (Entry e : l) t += e.size;
        return t;
    }

    /** Déplace un fichier dans la corbeille. Renvoie false si ça a échoué. */
    public static synchronized boolean moveToTrash(File src) {
        if (!src.exists()) return true;
        File d = dir();
        String base = clean(src.getName());
        String prefix = Long.toString(System.currentTimeMillis(), 36) + "-" + (counter++) + "_";
        // un nom de fichier ne doit pas dépasser 255 octets
        while ((prefix + base).getBytes(StandardCharsets.UTF_8).length > 200 && base.length() > 12) {
            int dot = base.lastIndexOf('.');
            String ext = dot >= 0 && base.length() - dot <= 10 ? base.substring(dot) : "";
            String stem = dot >= 0 && !ext.isEmpty() ? base.substring(0, dot) : base;
            base = stem.substring(0, Math.max(1, stem.length() - 10)) + ext;
        }
        File dst = new File(d, prefix + base);
        long size = src.length();
        boolean ok = src.renameTo(dst) || FileUtil.copyThenDelete(src, dst);
        if (!ok) return false;
        List<Entry> list = readIndex(d);
        Entry e = new Entry();
        e.name = dst.getName();
        e.orig = clean(src.getAbsolutePath());
        e.deleted = System.currentTimeMillis();
        e.size = size;
        e.file = dst;
        list.add(e);
        writeIndex(d, list);
        return true;
    }

    /** Remet un élément à son emplacement d'origine (nom modifié s'il existe déjà). Renvoie le fichier restauré, ou null. */
    public static synchronized File restore(Entry e) {
        File d = dir();
        List<Entry> list = readIndex(d);
        File target = new File(e.orig);
        File parent = target.getParentFile();
        if (parent == null) return null;
        if (!parent.isDirectory() && !parent.mkdirs()) return null;
        File dst = FileUtil.uniqueFile(parent, target.getName());
        File src = new File(d, e.name);
        if (!src.isFile()) return null;
        boolean ok = src.renameTo(dst) || FileUtil.copyThenDelete(src, dst);
        if (!ok) return null;
        removeFromIndex(d, list, e.name);
        return dst;
    }

    /** Supprime définitivement un élément de la corbeille. */
    public static synchronized boolean deleteForever(Entry e) {
        File d = dir();
        List<Entry> list = readIndex(d);
        File f = new File(d, e.name);
        boolean gone = !f.exists() || f.delete();
        if (gone) removeFromIndex(d, list, e.name);
        return gone;
    }

    private static void removeFromIndex(File d, List<Entry> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                list.remove(i);
                break;
            }
        }
        writeIndex(d, list);
    }

    /** Supprime ce qui a plus de 30 jours. Renvoie le nombre d'éléments effacés. */
    public static synchronized int purgeExpired() {
        File d = dir();
        List<Entry> list = readIndex(d);
        List<Entry> keep = new ArrayList<>();
        int n = 0;
        long now = System.currentTimeMillis();
        for (Entry e : list) {
            if (now - e.deleted > KEEP_MS) {
                File f = new File(d, e.name);
                if (!f.exists() || f.delete()) { n++; continue; }
            }
            keep.add(e);
        }
        if (n > 0) writeIndex(d, keep);
        return n;
    }
}
