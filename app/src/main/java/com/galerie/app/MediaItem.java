package com.galerie.app;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;

/** Une photo ou une vidéo. */
public class MediaItem {
    public long id;          // identifiant MediaStore (-1 si inconnu)
    public String path;
    public final long date;  // ms
    public final boolean video;
    public final long duration; // ms (vidéo)

    public MediaItem(long id, String path, long date, boolean video, long duration) {
        this.id = id;
        this.path = path;
        this.date = date;
        this.video = video;
        this.duration = duration;
    }

    public String name() { return new File(path).getName(); }

    public String folder() {
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : "/";
    }

    public Uri uri() {
        if (id < 0) return null;
        return ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }
}
