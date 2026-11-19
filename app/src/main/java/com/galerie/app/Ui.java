package com.galerie.app;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;

/** Petits outils d'interface partagés. */
public final class Ui {
    public static final int BG = 0xFF0F1115;
    public static final int BAR = 0xFF171A21;
    public static final int CARD = 0xFF1B1F27;
    public static final int ACCENT = 0xFF5B9CFF;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int MUTED = 0xFF8A8F98;

    private Ui() { }

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable rect(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static Drawable ripple(Context c) {
        TypedArray a = c.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackgroundBorderless});
        Drawable d = a.getDrawable(0);
        a.recycle();
        return d;
    }

    /** Bouton-icône 48 dp. */
    public static ImageButton iconButton(Context c, int res, View.OnClickListener l) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setColorFilter(TEXT);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        b.setBackground(ripple(c));
        b.setOnClickListener(l);
        return b;
    }
}
