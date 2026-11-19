package com.galerie.app;

import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Charge les miniatures photo/vidéo en arrière-plan avec un cache mémoire. */
public class ThumbLoader {
    private static final int SIZE = 256;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    public ThumbLoader() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(maxKb / 6) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    public void load(final File f, final ImageView iv) {
        final String key = f.getAbsolutePath();
        iv.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        pool.execute(() -> {
            if (!key.equals(iv.getTag())) return; // cellule déjà réutilisée
            Bitmap bm = make(f);
            if (bm == null) return;
            cache.put(key, bm);
            ui.post(() -> {
                if (key.equals(iv.getTag())) iv.setImageBitmap(bm);
            });
        });
    }

    public void forget(File f) {
        cache.remove(f.getAbsolutePath());
    }

    @SuppressWarnings("deprecation")
    private Bitmap make(File f) {
        try {
            if (FileUtil.isVideo(f)) {
                if (Build.VERSION.SDK_INT >= 29) {
                    return ThumbnailUtils.createVideoThumbnail(f, new Size(SIZE, SIZE), null);
                }
                return ThumbnailUtils.createVideoThumbnail(f.getAbsolutePath(),
                        MediaStore.Images.Thumbnails.MINI_KIND);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    return ThumbnailUtils.createImageThumbnail(f, new Size(SIZE, SIZE), null);
                } catch (Throwable ignored) { }
            }
            return FileUtil.decodeSampled(f, SIZE, SIZE);
        } catch (Throwable t) {
            return null;
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
