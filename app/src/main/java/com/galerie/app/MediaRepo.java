package com.galerie.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Toutes les photos/vidéos du téléphone (lues dans MediaStore, comme une vraie galerie)
 * et les albums (= dossiers qui contiennent des médias).
 * Toutes les modifications se font sur le thread principal.
 */
public class MediaRepo {

    public static class Album {
        public final String path;
        public final String name;
        public final List<MediaItem> items = new ArrayList<>();
        public int photos, videos;

        Album(String path) {
            this.path = path;
            this.name = new File(path).getName();
        }

        public MediaItem cover() { return items.isEmpty() ? null : items.get(0); }

        public String countLabel() {
            if (items.isEmpty()) return "Vide";
            StringBuilder sb = new StringBuilder();
            if (photos > 0) sb.append(photos).append(photos > 1 ? " photos" : " photo");
            if (videos > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(videos).append(videos > 1 ? " vidéos" : " vidéo");
            }
            return sb.toString();
        }
    }

    private static MediaRepo instance;

    public static synchronized MediaRepo get() {
        if (instance == null) instance = new MediaRepo();
        return instance;
    }

    public final List<MediaItem> all = new ArrayList<>();   // triés du plus récent au plus ancien
    public final List<Album> albums = new ArrayList<>();
    public boolean loaded = false;
    public int version = 0; // augmente à chaque modification de la liste
    public boolean dirty = false; // modifié depuis la visionneuse

    // ------------------------------------------------------------------ chargement

    /** À appeler en arrière-plan. */
    public static List<MediaItem> scan(Context c) {
        List<MediaItem> list = new ArrayList<>();
        query(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, list);
        query(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, list);
        if (list.isEmpty()) walkFileSystem(list); // secours si MediaStore ne renvoie rien
        Collections.sort(list, (a, b) -> Long.compare(b.date, a.date));
        return list;
    }

    private static void query(Context c, Uri uri, boolean video, List<MediaItem> out) {
        String[] proj = video
                ? new String[]{"_id", "_data", "datetaken", "date_modified", "duration", "_size"}
                : new String[]{"_id", "_data", "datetaken", "date_modified", "_size"};
        try (Cursor cur = c.getContentResolver().query(uri, proj, null, null, null)) {
            if (cur == null) return;
            int iId = cur.getColumnIndex("_id");
            int iData = cur.getColumnIndex("_data");
            int iTaken = cur.getColumnIndex("datetaken");
            int iMod = cur.getColumnIndex("date_modified");
            int iDur = video ? cur.getColumnIndex("duration") : -1;
            int iSize = cur.getColumnIndex("_size");
            while (cur.moveToNext()) {
                String path = cur.getString(iData);
                if (path == null || path.contains("/.")) continue;
                long taken = iTaken >= 0 ? cur.getLong(iTaken) : 0;
                long mod = iMod >= 0 ? cur.getLong(iMod) * 1000L : 0;
                long date = taken > 0 ? taken : mod;
                long dur = iDur >= 0 ? cur.getLong(iDur) : 0;
                MediaItem item = new MediaItem(cur.getLong(iId), path, date, video, dur);
                if (mod > 0) item.modified = mod;
                if (iSize >= 0) item.size = cur.getLong(iSize);
                out.add(item);
            }
        } catch (Exception ignored) { }
    }

    private static void walkFileSystem(List<MediaItem> out) {
        File root = Environment.getExternalStorageDirectory();
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && out.size() < 100000) {
            File[] l = stack.pop().listFiles();
            if (l == null) continue;
            for (File f : l) {
                String n = f.getName();
                if (n.startsWith(".")) continue;
                if (f.isDirectory()) {
                    if (!f.getAbsolutePath().equals(root.getAbsolutePath() + "/Android")) stack.push(f);
                } else if (FileUtil.isMedia(f)) {
                    MediaItem item = new MediaItem(-1, f.getAbsolutePath(), f.lastModified(), FileUtil.isVideo(f), 0);
                    item.size = f.length();
                    out.add(item);
                }
            }
        }
    }

    public void set(Context c, List<MediaItem> list) {
        version++;
        all.clear();
        all.addAll(list);
        loaded = true;
        rebuildAlbums(c);
    }

    // ------------------------------------------------------------------ albums

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("galerie", Context.MODE_PRIVATE);
    }

    /** Albums créés mais encore vides (pour qu'ils apparaissent quand même). */
    public static void addEmptyAlbum(Context c, String path) {
        Set<String> s = new HashSet<>(prefs(c).getStringSet("empty_albums", new HashSet<>()));
        s.add(path);
        prefs(c).edit().putStringSet("empty_albums", s).apply();
    }

    public void rebuildAlbums(Context c) {
        Map<String, Album> map = new LinkedHashMap<>();
        for (MediaItem m : all) {
            String folder = m.folder();
            Album a = map.get(folder);
            if (a == null) {
                a = new Album(folder);
                map.put(folder, a);
            }
            a.items.add(m);
            if (m.video) a.videos++; else a.photos++;
        }
        albums.clear();
        albums.addAll(map.values()); // déjà triés par activité récente
        // albums vides créés par l'utilisateur
        Set<String> keep = new HashSet<>();
        List<Album> fresh = new ArrayList<>();
        for (String p : prefs(c).getStringSet("empty_albums", new HashSet<>())) {
            if (map.containsKey(p)) continue;          // n'est plus vide
            if (!new File(p).isDirectory()) continue;  // supprimé
            keep.add(p);
            fresh.add(new Album(p));
        }
        albums.addAll(0, fresh); // les dossiers neufs d'abord : on les retrouve tout de suite
        prefs(c).edit().putStringSet("empty_albums", keep).apply();
    }

    public Album album(String path) {
        for (Album a : albums) if (a.path.equals(path)) return a;
        return null;
    }

    // ------------------------------------------------------------------ mises à jour après opérations

    public void removed(Context c, Collection<MediaItem> items) {
        version++;
        all.removeAll(items);
        rebuildAlbums(c);
    }

    public void removePath(Context c, String path) {
        version++;
        for (Iterator<MediaItem> it = all.iterator(); it.hasNext(); ) {
            if (it.next().path.equals(path)) it.remove();
        }
        rebuildAlbums(c);
    }

    /** Un fichier a changé de chemin (renommé ou déplacé). */
    public void moved(MediaItem m, String newPath) {
        version++;
        Favorites.rename(m.path, newPath);
        m.path = newPath;
        m.id = -1; // l'ancien identifiant MediaStore n'est plus valable
    }

    /** Un dossier entier a été renommé/déplacé (newDir == null : supprimé). */
    public List<String> folderChanged(Context c, String oldDir, String newDir) {
        version++;
        List<String> touched = new ArrayList<>();
        String prefix = oldDir + "/";
        for (Iterator<MediaItem> it = all.iterator(); it.hasNext(); ) {
            MediaItem m = it.next();
            if (!m.path.startsWith(prefix)) continue;
            touched.add(m.path);
            if (newDir == null) it.remove();
            else {
                moved(m, newDir + "/" + m.path.substring(prefix.length()));
                touched.add(m.path);
            }
        }
        rebuildAlbums(c);
        return touched;
    }

    /** Met à jour l'index Android des médias (les autres applis verront les changements). */
    public static void scan(Context c, Collection<String> paths) {
        if (paths.isEmpty()) return;
        MediaScannerConnection.scanFile(c.getApplicationContext(), paths.toArray(new String[0]), null, null);
    }

    public static void deleteFromMediaStore(Context c, MediaItem m) {
        Uri u = m.uri();
        if (u == null) return;
        try { c.getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
    }
}
