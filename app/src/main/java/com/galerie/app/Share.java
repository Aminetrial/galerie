package com.galerie.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Partage de photos / vidéos vers les autres applications. */
public final class Share {
    private Share() { }

    private static String mime(File f) {
        String ext = FileUtil.ext(f);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m != null) return m;
        return FileUtil.isVideo(f) ? "video/*" : "image/*";
    }

    public static void send(Activity a, List<String> paths) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            boolean allImages = true, allVideos = true;
            String single = null;
            for (String p : paths) {
                File f = new File(p);
                if (!f.exists()) continue;
                uris.add(FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", f));
                if (FileUtil.isVideo(f)) allImages = false; else allVideos = false;
                single = mime(f);
            }
            if (uris.isEmpty()) {
                Toast.makeText(a, "Rien à partager", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType(single);
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType(allImages ? "image/*" : allVideos ? "video/*" : "*/*");
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Partager via"));
        } catch (Throwable t) {
            Toast.makeText(a, "Partage impossible : " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
