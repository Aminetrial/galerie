package com.galerie.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FileUtil {
    private FileUtil() {}

    private static final Set<String> IMAGES = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"));
    private static final Set<String> VIDEOS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v", "ts", "mpg", "mpeg", "wmv", "flv"));

    public static String ext(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImage(File f) { return IMAGES.contains(ext(f)); }
    public static boolean isVideo(File f) { return VIDEOS.contains(ext(f)); }
    public static boolean isMedia(File f) { String e = ext(f); return IMAGES.contains(e) || VIDEOS.contains(e); }

    /** Tri : dossiers d'abord, puis fichiers, ordre "naturel" (img2 avant img10). */
    public static void sort(List<File> list) {
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File f : list) (f.isDirectory() ? dirs : files).add(f);
        Comparator<File> c = (a, b) -> naturalCompare(a.getName(), b.getName());
        Collections.sort(dirs, c);
        Collections.sort(files, c);
        list.clear();
        list.addAll(dirs);
        list.addAll(files);
    }

    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        int la = a.length(), lb = b.length();
        while (i < la && j < lb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < la && Character.isDigit(a.charAt(i))) i++;
                while (j < lb && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return (la - i) - (lb - j);
    }

    public static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return f.delete();
    }

    /** Déplacement entre deux stockages différents (ex : mémoire interne -> carte SD). */
    public static boolean copyThenDelete(File src, File dst) {
        try {
            copyRecursive(src, dst);
            return deleteRecursive(src);
        } catch (IOException e) {
            deleteRecursive(dst);
            return false;
        }
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("mkdir " + dst);
            File[] children = src.listFiles();
            if (children != null) for (File c : children) copyRecursive(c, new File(dst, c.getName()));
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            dst.setLastModified(src.lastModified());
        }
    }

    public static boolean isValidName(String n) {
        return n != null && !n.isEmpty() && !n.equals(".") && !n.equals("..")
                && !n.contains("/") && !n.contains("\0");
    }

    /** Décode une image réduite à la taille demandée, avec rotation EXIF. */
    public static Bitmap decodeSampled(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        if (bm == null) return null;
        int deg = 0;
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            int or = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
        } catch (Exception ignored) { }
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            bm = r;
        }
        return bm;
    }

    public static String formatTime(int ms) {
        int s = Math.max(0, ms / 1000);
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }
}
