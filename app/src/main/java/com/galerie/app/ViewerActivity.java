package com.galerie.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visionneuse photo + lecteur vidéo plein écran (commandes par-dessus l'image).
 *
 * Vidéo : barre de progression, -10 s / +10 s, lecture/pause, relancer, vitesse, muet,
 * capture d'image, image dans l'image, rotation, verrouillage, ajuster/remplir.
 * Navigation : précédent / suivant (ordre de la liste), aléatoire sans répétition,
 * « Retour » = revenir à l'élément vu juste avant.
 */
public class ViewerActivity extends Activity {

    public static ArrayList<String> sPaths;

    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f};

    private ArrayList<String> paths;
    private int index;
    private boolean randomMode;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final ArrayList<Integer> shuffleBag = new ArrayList<>();

    private FrameLayout root;
    private ImageView imageView;
    private VideoView videoView;
    private LinearLayout topBar, bottom, toolRow, seekBlock;
    private TextView title, counter, timeCur, timeTotal;
    private SeekBar seek;
    private Button playBtn, muteBtn, speedBtn, lockOverlay, randomFab;
    private View[] videoOnly;

    private MediaPlayer player;
    private int videoW = 0, videoH = 0;
    private boolean aspectFill = false;
    private boolean muted = false;
    private int speedIdx = 0;
    private boolean locked = false;
    private boolean pipRequested = false;

    private boolean controlsVisible = true;
    private boolean userSeeking = false;
    private boolean isVideo = false;
    private int resumePos = -1;
    private int loadToken = 0;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private GestureDetector gestures;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isVideo && !userSeeking) {
                int cur = videoView.getCurrentPosition();
                int dur = videoView.getDuration();
                if (dur > 0) {
                    seek.setMax(dur);
                    seek.setProgress(cur);
                    timeCur.setText(FileUtil.formatTime(cur));
                    timeTotal.setText(FileUtil.formatTime(dur));
                }
            }
            h.postDelayed(this, 400);
        }
    };

    private final Runnable autoHide = () -> {
        if (isVideo && videoView.isPlaying() && !userSeeking) setControlsVisible(false);
    };

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (state != null) {
            paths = state.getStringArrayList("paths");
            index = state.getInt("index", 0);
            resumePos = state.getInt("pos", -1);
            randomMode = state.getBoolean("random", false);
        } else {
            paths = sPaths;
            index = getIntent().getIntExtra("index", 0);
            randomMode = getIntent().getBooleanExtra("random", false)
                    || getSharedPreferences("galerie", MODE_PRIVATE).getBoolean("random_on", false);
        }
        if (paths == null || paths.isEmpty()) {
            finish();
            return;
        }
        if (index < 0 || index >= paths.size()) index = 0;

        buildUi();
        setupGestures();
        show(index);
        h.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isVideo && !inPip()) {
            resumePos = videoView.getCurrentPosition();
            videoView.pause();
            updatePlayButton();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList("paths", paths);
        out.putInt("index", index);
        out.putInt("pos", isVideo ? videoView.getCurrentPosition() : -1);
        out.putBoolean("random", randomMode);
        out.putBoolean("fill", aspectFill);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        loader.shutdownNow();
        if (videoView != null) videoView.stopPlayback();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        root.post(this::applyAspect);
    }

    private boolean inPip() {
        return pipRequested || (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean in, Configuration c) {
        super.onPictureInPictureModeChanged(in, c);
        pipRequested = in;
        setControlsVisible(!in);
    }

    // ------------------------------------------------------------------ interface

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button btn(String text, int sp, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(sp);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> {
            l.onClick(v);
            scheduleAutoHide();
        });
        return b;
    }

    private void addWeighted(LinearLayout row, View v, int heightDp) {
        row.addView(v, new LinearLayout.LayoutParams(0, dp(heightDp), 1));
    }

    private GradientDrawable gradient(boolean fromTop) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                fromTop ? new int[]{0xCC000000, 0x00000000} : new int[]{0x00000000, 0xCC000000});
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // --- barre du haut : ← titre compteur 🔄 ⋮
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(gradient(true));
        topBar.setPadding(dp(4), dp(4), dp(8), dp(16));
        topBar.addView(btn("←", 24, v -> finish()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        counter = new TextView(this);
        counter.setTextColor(0xFFBDBDBD);
        counter.setTextSize(13);
        counter.setPadding(dp(8), 0, dp(4), 0);
        topBar.addView(counter);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // --- bas : outils, progression, barre principale
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(gradient(false));
        bottom.setPadding(dp(10), dp(24), dp(10), dp(10));

        toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        Button backBtn = btn("↶", 22, v -> goBackInHistory());
        Button lockBtn = btn("🔓", 18, v -> toggleLock());
        muteBtn = btn("🔊", 18, v -> toggleMute());
        speedBtn = btn("1x", 14, v -> cycleSpeed());
        speedBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        Button pipBtn = btn("PiP", 12, v -> enterPip());
        pipBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        Button capBtn = btn("📷", 18, v -> capture());
        addWeighted(toolRow, backBtn, 44);
        addWeighted(toolRow, lockBtn, 44);
        addWeighted(toolRow, muteBtn, 44);
        addWeighted(toolRow, speedBtn, 44);
        addWeighted(toolRow, pipBtn, 44);
        addWeighted(toolRow, capBtn, 44);
        bottom.addView(toolRow);

        seekBlock = new LinearLayout(this);
        seekBlock.setOrientation(LinearLayout.VERTICAL);
        seek = new SeekBar(this);
        seekBlock.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setPadding(dp(14), 0, dp(14), dp(6));
        timeCur = new TextView(this);
        timeCur.setTextColor(0xFFE0E0E0);
        timeCur.setTextSize(12);
        timeCur.setText("0:00");
        timeTotal = new TextView(this);
        timeTotal.setTextColor(0xFFE0E0E0);
        timeTotal.setTextSize(12);
        timeTotal.setGravity(Gravity.END);
        timeTotal.setText("0:00");
        times.addView(timeCur, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(timeTotal, new LinearLayout.LayoutParams(0, -2, 1));
        seekBlock.addView(times);
        bottom.addView(seekBlock);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xDD1E1E1E);
        pg.setCornerRadius(dp(30));
        pill.setBackground(pg);
        Button restartBtn = btn("↻", 22, v -> restart());
        Button prevBtn = btn("⏮", 20, v -> prev());
        Button backSkip = btn("-10 s", 13, v -> skip(-10000));
        playBtn = btn("⏸", 24, v -> togglePlay());
        Button fwdSkip = btn("+10 s", 13, v -> skip(10000));
        Button nextBtn = btn("⏭", 20, v -> next());
        addWeighted(pill, restartBtn, 48);
        addWeighted(pill, prevBtn, 48);
        addWeighted(pill, backSkip, 48);
        addWeighted(pill, playBtn, 48);
        addWeighted(pill, fwdSkip, 48);
        addWeighted(pill, nextBtn, 48);
        addWeighted(pill, btn("⚙", 22, v -> showSettings()), 48);
        bottom.addView(pill, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        videoOnly = new View[]{restartBtn, muteBtn, speedBtn, pipBtn, capBtn, backSkip, playBtn, fwdSkip};

        // --- bouton de déverrouillage (visible seulement quand l'écran est verrouillé)
        lockOverlay = new Button(this, null, android.R.attr.borderlessButtonStyle);
        lockOverlay.setText("🔒");
        lockOverlay.setTextSize(18);
        lockOverlay.setMinWidth(0);
        lockOverlay.setMinimumWidth(0);
        lockOverlay.setPadding(0, 0, 0, 0);
        GradientDrawable lg = new GradientDrawable();
        lg.setShape(GradientDrawable.OVAL);
        lg.setColor(0x99000000);
        lockOverlay.setBackground(lg);
        lockOverlay.setOnClickListener(v -> toggleLock());
        lockOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.START);
        ll.setMargins(dp(16), 0, 0, dp(28));
        root.addView(lockOverlay, ll);

        // --- bouton « mode aléatoire » toujours visible, en bas à droite
        randomFab = new Button(this, null, android.R.attr.borderlessButtonStyle);
        randomFab.setText("🔀");
        randomFab.setTextSize(22);
        randomFab.setMinWidth(0);
        randomFab.setMinimumWidth(0);
        randomFab.setPadding(0, 0, 0, 0);
        randomFab.setOnClickListener(v -> toggleRandom());
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM | Gravity.END);
        rl.setMargins(0, 0, dp(16), dp(16));
        root.addView(randomFab, rl);
        styleRandomFab();
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionFab());

        setContentView(root);

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) applyAspect();
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) timeCur.setText(FileUtil.formatTime(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                userSeeking = true;
                h.removeCallbacks(autoHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                videoView.seekTo(s.getProgress());
                scheduleAutoHide();
            }
        });

        videoView.setOnPreparedListener(mp -> {
            player = mp;
            videoW = mp.getVideoWidth();
            videoH = mp.getVideoHeight();
            mp.setOnVideoSizeChangedListener((m, w, hh) -> {
                videoW = w;
                videoH = hh;
                applyAspect();
            });
            applyAspect();
            seek.setMax(Math.max(0, videoView.getDuration()));
            if (muted) mp.setVolume(0f, 0f);
            if (resumePos > 0) {
                videoView.seekTo(resumePos);
                resumePos = -1;
            }
            videoView.start();
            applySpeed();
            updatePlayButton();
            scheduleAutoHide();
        });
        videoView.setOnCompletionListener(mp -> {
            updatePlayButton();
            // fin de vidéo : enchaîne sur le suivant (aléatoire si le mode 🔀 est activé)
            if (randomMode && paths.size() > 1) randomJump();
            else if (index + 1 < paths.size()) goTo(index + 1);
            else setControlsVisible(true);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Impossible de lire cette vidéo", Toast.LENGTH_SHORT).show();
            updatePlayButton();
            return true;
        });
    }

    private void setControlsVisible(boolean v) {
        controlsVisible = v;
        randomFab.setVisibility(locked || inPip() ? View.GONE : View.VISIBLE);
        if (locked) {
            topBar.setVisibility(View.GONE);
            bottom.setVisibility(View.GONE);
            lockOverlay.setVisibility(View.VISIBLE);
            return;
        }
        lockOverlay.setVisibility(View.GONE);
        topBar.setVisibility(v ? View.VISIBLE : View.GONE);
        bottom.setVisibility(v ? View.VISIBLE : View.GONE);
        if (v) scheduleAutoHide();
        positionFab();
        root.post(this::positionFab);
    }

    /** Le bouton aléatoire reste en bas à droite, au-dessus des commandes quand elles sont affichées. */
    private void positionFab() {
        if (randomFab == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) randomFab.getLayoutParams();
        int want = (bottom.getVisibility() == View.VISIBLE ? bottom.getHeight() : 0) + dp(16);
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want;
            randomFab.setLayoutParams(lp);
        }
    }

    private void styleRandomFab() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(randomMode ? 0xFF42A5F5 : 0xAA222222);
        g.setStroke(dp(2), randomMode ? 0xFFFFFFFF : 0x88FFFFFF);
        randomFab.setBackground(g);
        randomFab.setAlpha(randomMode ? 1f : 0.85f);
    }

    private void toggleRandom() {
        randomMode = !randomMode;
        getSharedPreferences("galerie", MODE_PRIVATE).edit().putBoolean("random_on", randomMode).apply();
        styleRandomFab();
        Toast.makeText(this, randomMode
                ? "Mode aléatoire activé : faites défiler (↶ pour revenir en arrière)"
                : "Mode aléatoire désactivé", Toast.LENGTH_SHORT).show();
    }

    private void scheduleAutoHide() {
        h.removeCallbacks(autoHide);
        if (isVideo) h.postDelayed(autoHide, 4000);
    }

    private void toggleLock() {
        locked = !locked;
        Toast.makeText(this, locked ? "Écran verrouillé" : "Écran déverrouillé", Toast.LENGTH_SHORT).show();
        setControlsVisible(true);
    }

    // ------------------------------------------------------------------ affichage : ajuster / remplir / rotation

    private void applyAspect() {
        if (root == null) return;
        imageView.setScaleType(aspectFill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        int nw = -1, nh = -1;
        if (aspectFill && videoW > 0 && videoH > 0) {
            float s = Math.max((float) W / videoW, (float) H / videoH);
            nw = Math.round(videoW * s);
            nh = Math.round(videoH * s);
        }
        if (lp.width != nw || lp.height != nh) {
            lp.width = nw;
            lp.height = nh;
            lp.gravity = Gravity.CENTER;
            videoView.setLayoutParams(lp);
        }
    }

    private void toggleAspect() {
        aspectFill = !aspectFill;
        applyAspect();
        Toast.makeText(this, aspectFill ? "Plein écran (rogné)" : "Image entière", Toast.LENGTH_SHORT).show();
    }

    private void rotate() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    // ------------------------------------------------------------------ gestes

    private boolean inside(View v, MotionEvent e) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r) && r.contains((int) e.getRawX(), (int) e.getRawY());
    }

    private boolean onControls(MotionEvent e) {
        return inside(topBar, e) || inside(bottom, e) || inside(lockOverlay, e);
    }

    private void showSettings() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"ℹ️  Propriétés", "📤  Partager", "✏️  Renommer", "🗑️  Supprimer", "🔄  Tourner l'écran"},
                        (d, w) -> {
                            switch (w) {
                                case 0: showProperties(); break;
                                case 1: shareCurrent(); break;
                                case 2: renameCurrent(); break;
                                case 3: deleteCurrent(); break;
                                case 4: rotate(); break;
                            }
                        })
                .show();
    }

    private static String humanSize(long b) {
        if (b < 1024) return b + " o";
        double v = b / 1024.0;
        String[] u = {"Ko", "Mo", "Go", "To"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.FRANCE, "%.1f %s", v, u[i]);
    }

    private void showProperties() {
        final String p = paths.get(index);
        final boolean vid = isVideo;
        loader.execute(() -> {
            File f = new File(p);
            StringBuilder sb = new StringBuilder();
            sb.append("Nom : ").append(f.getName());
            sb.append("\nDossier : ").append(f.getParent());
            sb.append("\nTaille : ").append(humanSize(f.length()));
            sb.append("\nModifié : ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date(f.lastModified())));
            try {
                if (vid) {
                    MediaMetadataRetriever mr = new MediaMetadataRetriever();
                    try {
                        mr.setDataSource(p);
                        String w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                        String hh = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                        String rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                        String dur = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        String br = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                        if (w != null && hh != null) {
                            boolean swap = "90".equals(rot) || "270".equals(rot);
                            sb.append("\nRésolution : ").append(swap ? hh : w).append(" × ").append(swap ? w : hh);
                        }
                        if (dur != null) sb.append("\nDurée : ").append(FileUtil.formatTime(Integer.parseInt(dur)));
                        if (br != null) sb.append("\nDébit : ").append(Integer.parseInt(br) / 1000).append(" kb/s");
                    } finally {
                        try { mr.release(); } catch (Throwable ignored) { }
                    }
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(p, o);
                    if (o.outWidth > 0) sb.append("\nRésolution : ").append(o.outWidth).append(" × ").append(o.outHeight);
                }
            } catch (Throwable ignored) { }
            final String msg = sb.toString();
            h.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Propriétés")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show());
        });
    }

    private void shareCurrent() {
        File f = new File(paths.get(index));
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(isVideo ? "video/*" : "image/*");
            send.putExtra(Intent.EXTRA_STREAM, u);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Partager"));
        } catch (Exception e) {
            Toast.makeText(this, "Partage impossible", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onControls(e)) return false;
                if (locked) {
                    lockOverlay.setVisibility(View.VISIBLE);
                    return true;
                }
                setControlsVisible(!controlsVisible);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (locked || !isVideo || onControls(e)) return false;
                // double-tap côté gauche = -10 s, côté droit = +10 s
                int w = getResources().getDisplayMetrics().widthPixels;
                skip(e.getRawX() < w / 2f ? -10000 : 10000);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (locked || e1 == null || onControls(e1)) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > dp(80)) {
                    if (dx < 0) next(); else prev();
                    return true;
                }
                if (dy > dp(150) && Math.abs(dy) > Math.abs(dx)) { // glisser vers le bas = fermer
                    finish();
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestures != null) gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (locked) {
            Toast.makeText(this, "Écran verrouillé : touchez 🔒 pour déverrouiller", Toast.LENGTH_SHORT).show();
            lockOverlay.setVisibility(View.VISIBLE);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ affichage d'un élément

    private void show(int i) {
        index = i;
        final String p = paths.get(i);
        final File f = new File(p);
        title.setText(f.getName());
        counter.setText((i + 1) + " / " + paths.size());
        final int token = ++loadToken;
        boolean vid = FileUtil.isVideo(f);
        for (View v : videoOnly) v.setVisibility(vid ? View.VISIBLE : View.GONE);
        seekBlock.setVisibility(vid ? View.VISIBLE : View.GONE);

        if (vid) {
            isVideo = true;
            player = null;
            videoW = 0;
            videoH = 0;
            speedIdx = 0;
            speedBtn.setText("1x");
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            seek.setProgress(0);
            timeCur.setText("0:00");
            timeTotal.setText("0:00");
            applyAspect();
            videoView.setVideoPath(p);
            updatePlayButton();
        } else {
            isVideo = false;
            player = null;
            videoView.stopPlayback();
            videoView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageDrawable(null);
            applyAspect();
            DisplayMetrics dm = getResources().getDisplayMetrics();
            final int req = Math.max(dm.widthPixels, dm.heightPixels);
            loader.execute(() -> {
                Bitmap bm = null;
                try {
                    bm = FileUtil.decodeSampled(f, req, req);
                } catch (OutOfMemoryError oom) {
                    try { bm = FileUtil.decodeSampled(f, req / 2, req / 2); } catch (Throwable ignored) { }
                } catch (Throwable ignored) { }
                final Bitmap res = bm;
                h.post(() -> {
                    if (token != loadToken) return;
                    if (res != null) imageView.setImageBitmap(res);
                    else Toast.makeText(this, "Impossible d'afficher cette image", Toast.LENGTH_SHORT).show();
                });
            });
        }
        setControlsVisible(true);
    }

    // ------------------------------------------------------------------ navigation

    private void goTo(int i) {
        if (i < 0 || i >= paths.size() || i == index) return;
        history.push(paths.get(index));
        if (history.size() > 500) history.removeLast();
        show(i);
    }

    private void next() {
        if (randomMode) { randomJump(); return; }
        if (index + 1 < paths.size()) goTo(index + 1);
        else Toast.makeText(this, "Dernier élément", Toast.LENGTH_SHORT).show();
    }

    private void prev() {
        if (randomMode) { goBackInHistory(); return; }
        if (index > 0) goTo(index - 1);
        else Toast.makeText(this, "Premier élément", Toast.LENGTH_SHORT).show();
    }

    /** Aléatoire « sans répétition » : chaque élément passe une fois avant de recommencer. */
    private void randomJump() {
        if (paths.size() < 2) return;
        if (shuffleBag.isEmpty()) {
            for (int i = 0; i < paths.size(); i++) if (i != index) shuffleBag.add(i);
            Collections.shuffle(shuffleBag);
        }
        int r = shuffleBag.remove(shuffleBag.size() - 1);
        if (r == index && !shuffleBag.isEmpty()) r = shuffleBag.remove(shuffleBag.size() - 1);
        goTo(r);
    }

    private void goBackInHistory() {
        while (!history.isEmpty()) {
            int i = paths.indexOf(history.pop());
            if (i >= 0) { show(i); return; }
        }
        Toast.makeText(this, "Aucun élément précédent dans l'historique", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ renommer / supprimer

    private void renameCurrent() {
        final String old = paths.get(index);
        final File src = new File(old);
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(src.getName());
        int dot = src.getName().lastIndexOf('.');
        if (dot > 0) et.setSelection(0, dot); else et.selectAll();
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("Renommer")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!FileUtil.isValidName(n) || n.equals(src.getName())) return;
                    File dest = new File(src.getParentFile(), n);
                    if (dest.exists()) { Toast.makeText(this, "Un fichier porte déjà ce nom", Toast.LENGTH_SHORT).show(); return; }
                    if (!src.renameTo(dest)) { Toast.makeText(this, "Échec du renommage", Toast.LENGTH_SHORT).show(); return; }
                    String np = dest.getAbsolutePath();
                    paths.set(index, np);
                    MediaRepo repo = MediaRepo.get();
                    for (MediaItem m : repo.all) if (m.path.equals(old)) { repo.moved(m, np); break; }
                    repo.dirty = true;
                    List<String> scan = new ArrayList<>();
                    scan.add(old);
                    scan.add(np);
                    MediaRepo.scan(this, scan);
                    title.setText(n);
                    Toast.makeText(this, "Renommé", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void deleteCurrent() {
        final String p = paths.get(index);
        new AlertDialog.Builder(this)
                .setTitle("Supprimer ?")
                .setMessage("« " + new File(p).getName() + " » sera supprimé définitivement.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    File f = new File(p);
                    if (f.exists() && !f.delete()) {
                        Toast.makeText(this, "Suppression impossible", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    MediaRepo repo = MediaRepo.get();
                    for (MediaItem m : repo.all) if (m.path.equals(p)) { MediaRepo.deleteFromMediaStore(this, m); break; }
                    repo.removePath(this, p);
                    repo.dirty = true;
                    Toast.makeText(this, "Supprimé", Toast.LENGTH_SHORT).show();
                    paths.remove(index);
                    shuffleBag.clear();
                    if (paths.isEmpty()) { finish(); return; }
                    if (isVideo) videoView.stopPlayback();
                    show(Math.min(index, paths.size() - 1));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ------------------------------------------------------------------ vidéo

    private void togglePlay() {
        if (!isVideo) return;
        if (videoView.isPlaying()) videoView.pause();
        else videoView.start();
        updatePlayButton();
    }

    private void skip(int ms) {
        if (!isVideo) return;
        int dur = videoView.getDuration();
        int pos = videoView.getCurrentPosition() + ms;
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur - 500) pos = Math.max(0, dur - 500);
        videoView.seekTo(pos);
        Toast.makeText(this, ms > 0 ? "+10 s" : "-10 s", Toast.LENGTH_SHORT).show();
    }

    private void restart() {
        if (!isVideo) return;
        videoView.seekTo(0);
        videoView.start();
        updatePlayButton();
    }

    private void updatePlayButton() {
        h.postDelayed(() -> playBtn.setText(videoView.isPlaying() ? "⏸" : "▶"), 100);
    }

    private void toggleMute() {
        muted = !muted;
        if (player != null) {
            try { player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Exception ignored) { }
        }
        muteBtn.setText(muted ? "🔇" : "🔊");
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % SPEEDS.length;
        applySpeed();
    }

    private void applySpeed() {
        float sp = SPEEDS[speedIdx];
        String label = (sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "x";
        speedBtn.setText(label);
        if (player == null) return;
        boolean wasPlaying = videoView.isPlaying();
        try {
            PlaybackParams pp = new PlaybackParams();
            pp.setSpeed(sp);
            player.setPlaybackParams(pp);
            if (!wasPlaying) videoView.pause(); // changer la vitesse peut relancer la lecture
        } catch (Exception e) {
            Toast.makeText(this, "Vitesse non disponible pour cette vidéo", Toast.LENGTH_SHORT).show();
        }
        updatePlayButton();
    }

    private void enterPip() {
        if (!isVideo) return;
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "Image dans l'image : Android 8 minimum", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            float a = videoW > 0 && videoH > 0 ? (float) videoW / videoH : 16f / 9f;
            a = Math.max(0.42f, Math.min(2.38f, a));
            pipRequested = true;
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(Math.round(a * 1000), 1000)).build());
        } catch (Exception e) {
            pipRequested = false;
            Toast.makeText(this, "Image dans l'image indisponible", Toast.LENGTH_SHORT).show();
        }
    }

    /** Enregistre l'image affichée dans Pictures/Captures. */
    @SuppressWarnings("deprecation")
    private void capture() {
        if (!isVideo) return;
        final String p = paths.get(index);
        final long posMs = videoView.getCurrentPosition();
        loader.execute(() -> {
            String res = null;
            MediaMetadataRetriever mr = new MediaMetadataRetriever();
            try {
                mr.setDataSource(p);
                Bitmap bm = mr.getFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                if (bm != null) {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Captures");
                    dir.mkdirs();
                    String base = new File(p).getName();
                    int dot = base.lastIndexOf('.');
                    if (dot > 0) base = base.substring(0, dot);
                    File out = FileUtil.uniqueFile(dir, base + "_" + FileUtil.formatTime((int) posMs).replace(':', '-') + ".jpg");
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        bm.compress(Bitmap.CompressFormat.JPEG, 95, fo);
                    }
                    res = out.getAbsolutePath();
                }
            } catch (Throwable ignored) {
            } finally {
                try { mr.release(); } catch (Throwable ignored) { }
            }
            final String path = res;
            h.post(() -> {
                if (path == null) {
                    Toast.makeText(this, "Capture impossible", Toast.LENGTH_SHORT).show();
                    return;
                }
                MediaRepo repo = MediaRepo.get();
                repo.all.add(0, new MediaItem(-1, path, System.currentTimeMillis(), false, 0));
                repo.rebuildAlbums(this);
                repo.dirty = true;
                List<String> one = new ArrayList<>();
                one.add(path);
                MediaRepo.scan(this, one);
                Toast.makeText(this, "Image enregistrée dans Pictures/Captures", Toast.LENGTH_SHORT).show();
            });
        });
    }
}
