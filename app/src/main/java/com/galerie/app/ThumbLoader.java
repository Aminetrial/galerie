package com.galerie.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Miniatures rapides (cache système MediaStore) avec cache mémoire ; les plus récentes demandées passent d'abord. */
public class ThumbLoader {
    private static final int SIZE = 320;
    private final LruCache<String, Bitmap> cache;
    private final ThreadPoolExecutor pool;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ContentResolver cr;

    @SuppressWarnings("serial")
    private static class LifoQueue extends LinkedBlockingDeque<Runnable> {
        @Override public boolean offer(Runnable r) { return offerFirst(r); }
    }

    public ThumbLoader(Context c) {
        cr = c.getContentResolver();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(maxKb / 5) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        pool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new LifoQueue());
    }

    public void load(final MediaItem m, final ImageView iv) {
        final String key = m.path;
        iv.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.animate().cancel();
            iv.setAlpha(1f);
            iv.setImageBitmap(cached);
            return;
        }
        iv.animate().cancel();
        iv.setAlpha(1f);
        iv.setImageDrawable(null);
        pool.execute(() -> {
            if (!key.equals(iv.getTag())) return; // la case a été réutilisée
            Bitmap bm = make(m);
            if (bm == null) return;
            cache.put(key, bm);
            ui.post(() -> {
                if (key.equals(iv.getTag())) {
                    iv.setImageBitmap(bm);
                    iv.setAlpha(0f);
                    iv.animate().alpha(1f).setDuration(160).start();
                }
            });
        });
    }

    @SuppressWarnings("deprecation")
    private Bitmap make(MediaItem m) {
        Uri u = m.uri();
        try {
            if (u != null) {
                if (Build.VERSION.SDK_INT >= 29) {
                    return cr.loadThumbnail(u, new Size(SIZE, SIZE), null);
                } else if (m.video) {
                    return MediaStore.Video.Thumbnails.getThumbnail(cr, m.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                } else {
                    return MediaStore.Images.Thumbnails.getThumbnail(cr, m.id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                }
            }
        } catch (Throwable ignored) { }
        File f = new File(m.path);
        try {
            if (m.video) {
                if (Build.VERSION.SDK_INT >= 29) return ThumbnailUtils.createVideoThumbnail(f, new Size(SIZE, SIZE), null);
                return ThumbnailUtils.createVideoThumbnail(m.path, MediaStore.Images.Thumbnails.MINI_KIND);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                try { return ThumbnailUtils.createImageThumbnail(f, new Size(SIZE, SIZE), null); } catch (Throwable ignored) { }
            }
            return FileUtil.decodeSampled(f, SIZE, SIZE);
        } catch (Throwable t) {
            return null;
        }
    }

    public void forget(String path) { cache.remove(path); }

    public void shutdown() { pool.shutdownNow(); }
}
