package com.galerie.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visionneuse photo + lecteur vidéo.
 *
 * Une seule barre de commandes : ↻ relancer · ⏮ précédent · ⏯ · ⏭ suivant · 🔀 aléatoire (on/off).
 * - Aléatoire activé : « suivant » (bouton, glissement, fin de vidéo, fin du délai d'une image)
 *   tire un élément au hasard, sans répétition ; « précédent » revient à l'élément vu avant.
 * - Aléatoire désactivé : suivant / précédent suivent l'ordre de la liste affichée
 *   (tout, un dossier, une recherche).
 * - Fin d'une vidéo, ou délai d'une image (réglage 5 s / 10 s) : passage automatique au suivant.
 */
public class ViewerActivity extends Activity {

    public static ArrayList<String> sPaths;

    private ArrayList<String> paths;
    private int index;
    private boolean randomMode;
    private int leftTap = 1;
    private int photoDelay = 0; // secondes ; 0 = pas de passage automatique des images
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final ArrayList<Integer> shuffleBag = new ArrayList<>();

    private FrameLayout root;
    private ImageView imageView;
    private VideoView videoView;
    private LinearLayout topBar, bottom, seekBlock;
    private TextView title, counter, timeCur, timeTotal;
    private SeekBar seek;
    private ImageButton restartBtn, playBtn, randomBtn;
    private View divider1;

    private boolean controlsVisible = true;
    private boolean userSeeking = false;
    private boolean isVideo = false;
    private boolean slidePaused = false;
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
        boolean video = isVideo && videoView.isPlaying() && !userSeeking;
        boolean slide = !isVideo && photoDelay > 0 && !slidePaused;
        if (video || slide) setControlsVisible(false);
    };

    private final Runnable slideNext = () -> {
        if (!isVideo && !slidePaused) autoAdvance();
    };

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.start(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        android.content.SharedPreferences prefs = getSharedPreferences("galerie", MODE_PRIVATE);
        photoDelay = prefs.getInt("photo_delay", 0);
        leftTap = prefs.getInt("left_tap", 1);
        if (state != null) {
            paths = sPaths;
            index = state.getInt("index", 0);
            resumePos = state.getInt("pos", -1);
            randomMode = state.getBoolean("random", false);
        } else {
            paths = sPaths;
            index = getIntent().getIntExtra("index", 0);
            randomMode = getIntent().getBooleanExtra("random", false) || prefs.getBoolean("random_on", false);
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
        h.removeCallbacks(slideNext);
        if (isVideo) {
            resumePos = videoView.getCurrentPosition();
            videoView.pause();
            updatePlayButton();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isVideo && paths != null) scheduleSlide();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("index", index);
        out.putInt("pos", isVideo ? videoView.getCurrentPosition() : -1);
        out.putBoolean("random", randomMode);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        loader.shutdownNow();
        if (videoView != null) videoView.stopPlayback();
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

    private ImageButton ibtn(int res, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(Color.WHITE);
        b.setBackground(null);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        b.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
            scheduleAutoHide();
        });
        return b;
    }

    private GradientDrawable gradient(boolean fromTop) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                fromTop ? new int[]{0xCC000000, 0x00000000} : new int[]{0x00000000, 0xCC000000});
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(0x33FFFFFF);
        return d;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView, new FrameLayout.LayoutParams(-1, -1));

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // --- barre du haut : ← nom position
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(gradient(true));
        topBar.setPadding(dp(4), dp(4), dp(12), dp(16));
        ImageButton back = ibtn(R.drawable.ic_back, v -> finish());
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        topBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        counter = new TextView(this);
        counter.setTextColor(0xFFBDBDBD);
        counter.setTextSize(13);
        counter.setPadding(dp(8), 0, 0, 0);
        topBar.addView(counter);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // --- bas : progression (vidéo) + barre de commandes
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(gradient(false));
        bottom.setPadding(dp(10), dp(24), dp(10), dp(12));

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
        pill.setPadding(dp(6), dp(4), dp(6), dp(4));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xE6171A21);
        pg.setCornerRadius(dp(30));
        pill.setBackground(pg);

        restartBtn = ibtn(R.drawable.ic_restart, v -> restart());
        ImageButton prevBtn = ibtn(R.drawable.ic_prev, v -> prev());
        playBtn = ibtn(R.drawable.ic_pause, v -> togglePlay());
        ImageButton nextBtn = ibtn(R.drawable.ic_next, v -> next());
        randomBtn = ibtn(R.drawable.ic_shuffle, v -> toggleRandom());

        pill.addView(restartBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        divider1 = divider();
        pill.addView(divider1, new LinearLayout.LayoutParams(dp(1), dp(32)));
        pill.addView(prevBtn, new LinearLayout.LayoutParams(0, dp(52), 1.2f));
        pill.addView(playBtn, new LinearLayout.LayoutParams(0, dp(52), 1.6f));
        pill.addView(nextBtn, new LinearLayout.LayoutParams(0, dp(52), 1.2f));
        pill.addView(divider(), new LinearLayout.LayoutParams(dp(1), dp(32)));
        pill.addView(randomBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        bottom.addView(pill, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        styleRandomBtn();

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
            seek.setMax(Math.max(0, videoView.getDuration()));
            if (resumePos > 0) {
                videoView.seekTo(resumePos);
                resumePos = -1;
            }
            videoView.start();
            updatePlayButton();
            scheduleAutoHide();
        });
        // fin de vidéo : suivant (ou aléatoire si activé)
        videoView.setOnCompletionListener(mp -> {
            updatePlayButton();
            autoAdvance();
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Impossible de lire cette vidéo", Toast.LENGTH_SHORT).show();
            updatePlayButton();
            return true;
        });
    }

    private void styleRandomBtn() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(26));
        g.setColor(randomMode ? 0xFF5B9CFF : 0x00000000);
        randomBtn.setBackground(g);
        randomBtn.setColorFilter(randomMode ? Color.BLACK : 0x99FFFFFF);
    }

    private void toggleRandom() {
        randomMode = !randomMode;
        shuffleBag.clear();
        getSharedPreferences("galerie", MODE_PRIVATE).edit().putBoolean("random_on", randomMode).apply();
        styleRandomBtn();
        Toast.makeText(this, randomMode ? "Aléatoire activé" : "Aléatoire désactivé", Toast.LENGTH_SHORT).show();
    }

    private void updateButtonsForType() {
        boolean slide = !isVideo && photoDelay > 0;
        int v = isVideo || slide ? View.VISIBLE : View.GONE;
        restartBtn.setVisibility(v);
        divider1.setVisibility(v);
        playBtn.setVisibility(v);
        seekBlock.setVisibility(isVideo ? View.VISIBLE : View.GONE);
    }

    private void setControlsVisible(boolean v) {
        controlsVisible = v;
        fade(topBar, v);
        fade(bottom, v);
        if (v) scheduleAutoHide();
    }

    private void fade(final View view, boolean show) {
        view.animate().cancel();
        if (show) {
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).setDuration(150).start();
        } else {
            view.animate().alpha(0f).setDuration(150).withEndAction(() -> view.setVisibility(View.GONE)).start();
        }
    }

    private void scheduleAutoHide() {
        h.removeCallbacks(autoHide);
        if (isVideo || photoDelay > 0) h.postDelayed(autoHide, 4000);
    }

    // ------------------------------------------------------------------ gestes

    private boolean inside(View v, MotionEvent e) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r) && r.contains((int) e.getRawX(), (int) e.getRawY());
    }

    private boolean onControls(MotionEvent e) {
        return inside(topBar, e) || inside(bottom, e);
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
                setControlsVisible(!controlsVisible);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (onControls(e)) return false;
                // côté gauche : réglage (média suivant / -10 s / rien) ; côté droit : +10 s (vidéo)
                int w = getResources().getDisplayMetrics().widthPixels;
                if (e.getRawX() < w / 2f) {
                    if (leftTap == 1) next();
                    else if (leftTap == 0) skip(-10000);
                } else skip(10000);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || onControls(e1)) return false;
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

    // ------------------------------------------------------------------ affichage d'un élément

    private void show(int i) {
        index = i;
        if (root != null) root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        final String p = paths.get(i);
        final File f = new File(p);
        title.setText(f.getName());
        counter.setText((i + 1) + " / " + paths.size());
        final int token = ++loadToken;
        h.removeCallbacks(slideNext);
        slidePaused = false;
        isVideo = FileUtil.isVideo(f);
        updateButtonsForType();

        if (isVideo) {
            imageView.setImageDrawable(null);
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            seek.setProgress(0);
            timeCur.setText("0:00");
            timeTotal.setText("0:00");
            videoView.setVideoPath(p);
        } else {
            videoView.stopPlayback();
            videoView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageDrawable(null);
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
                    if (res != null) {
                        imageView.setImageBitmap(res);
                        imageView.setAlpha(0f);
                        imageView.animate().alpha(1f).setDuration(180).start();
                    }
                    else Toast.makeText(this, "Impossible d'afficher cette image", Toast.LENGTH_SHORT).show();
                });
            });
            scheduleSlide();
        }
        updatePlayButton();
        setControlsVisible(true);
    }

    private void scheduleSlide() {
        h.removeCallbacks(slideNext);
        if (!isVideo && photoDelay > 0 && !slidePaused) h.postDelayed(slideNext, photoDelay * 1000L);
    }

    // ------------------------------------------------------------------ navigation

    private void goTo(int i) {
        if (i < 0 || i >= paths.size() || i == index) return;
        history.push(paths.get(index));
        if (history.size() > 500) history.removeLast();
        show(i);
    }

    /** Bouton ⏭ / glissement gauche. */
    private void next() {
        if (randomMode) { randomJump(); return; }
        if (index + 1 < paths.size()) goTo(index + 1);
        else Toast.makeText(this, "Dernier élément", Toast.LENGTH_SHORT).show();
    }

    /** Bouton ⏮ / glissement droite : aléatoire = revenir à l'élément vu avant ; sinon élément précédent. */
    private void prev() {
        if (randomMode) { goBackInHistory(); return; }
        if (index > 0) goTo(index - 1);
        else Toast.makeText(this, "Premier élément", Toast.LENGTH_SHORT).show();
    }

    /** Passage automatique (fin de vidéo, délai d'une image) : revient au début à la fin de la liste. */
    private void autoAdvance() {
        if (paths.size() < 2) {
            if (isVideo) setControlsVisible(true);
            return;
        }
        if (randomMode) randomJump();
        else goTo((index + 1) % paths.size());
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

    // ------------------------------------------------------------------ lecture

    /** Vidéo : lecture/pause. Image : pause/reprise du compte à rebours (si un délai est réglé). */
    private void togglePlay() {
        if (isVideo) {
            if (videoView.isPlaying()) videoView.pause(); else videoView.start();
        } else {
            slidePaused = !slidePaused;
            if (slidePaused) h.removeCallbacks(slideNext); else scheduleSlide();
        }
        updatePlayButton();
    }

    /** Vidéo : repart du début. Image : relance le compte à rebours. */
    private void restart() {
        if (isVideo) {
            videoView.seekTo(0);
            videoView.start();
        } else {
            slidePaused = false;
            scheduleSlide();
        }
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

    private void updatePlayButton() {
        h.postDelayed(() -> {
            boolean playing = isVideo ? videoView.isPlaying() : !slidePaused;
            playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }, 100);
    }
}
