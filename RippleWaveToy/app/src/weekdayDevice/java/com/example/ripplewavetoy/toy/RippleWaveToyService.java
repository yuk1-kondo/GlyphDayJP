package com.example.ripplewavetoy.toy;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.nothing.ketchum.Glyph;
import com.nothing.ketchum.GlyphMatrixManager;
import com.nothing.ketchum.GlyphToy;
import com.nothing.ketchum.GlyphException;

// Removed legacy Timer usage; using ScheduledExecutorService instead
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RippleWaveToyService extends Service {
    private static final String TAG = "WeekdayToy";
    // TEMP flag disabled: use real device date
    private static final boolean DEBUG_FORCE_FRIDAY = false;

    private static final int W = 25;
    private static final int H = 25;
    private static final float CX = (W - 1) * 0.5f;
    private static final float CY = (H - 1) * 0.5f;
    private static final float RADIUS = 12.4f;

    private GlyphMatrixManager mGM;
    private GlyphMatrixManager.Callback mCallback;
    private final int[] frameBuf = new int[W * H];

    private static final char[] WEEK_KANJI = new char[] {'\u65e5','\u6708','\u706b','\u6c34','\u6728','\u91d1','\u571f'}; // 日月火水木金土
    private static final char[] WEEK_KANJI_MON_FIRST = new char[] {'\u6708','\u706b','\u6c34','\u6728','\u91d1','\u571f','\u65e5'}; // 月火水木金土日
    
    // デバッグ用: 3秒おきに全曜日を順次表示
    private boolean debugCycleMode = false;
    private int debugCycleIndex = 0;

    private boolean isAodMode = false;
    // Scheduling
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> dailyFuture;
    private ScheduledFuture<?> animFuture;
    private boolean cycleActive = false;
    private int cycleIndex = 0;
    private boolean animActive = false;
    private char animKanji = '\u65e5';
    private final List<Particle> particles = new ArrayList<>();
    private final boolean[] targetMask = new boolean[W * H];
    private final float[] targetLevel = new float[W * H]; // 0..1 grayscale*mask for each target pixel
    private final boolean[] filledMask = new boolean[W * H];
    private final boolean[] reservedMask = new boolean[W * H];
    private final int[] nextFillRow = new int[W];
    private int animTicks = 0;
    private static final int ANIM_MAX_TICKS = 1000; // safety only; not used for switching
    private static final int SPAWN_PER_TICK = 6;
    private static final int ANIM_NONE = 0, ANIM_FALL = 1, ANIM_COLLAPSE = 2;
    private int animMode = ANIM_NONE;
    private Runnable restartRunnable = null;
    private boolean holdBlank = false; // after collapse until next falling starts
    private boolean invertEnabled = false; // long-press toggle

    // Sensors
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long lastShakeMs = 0L;
    private int remainingToFill = 0;

    // Caches to reduce allocations and GC pressure
    private final Map<Character, boolean[]> cachedMask = new HashMap<>();
    private final Map<Character, float[]> cachedLevel = new HashMap<>();
    private final Map<Character, int[]> cachedStaticFrame = new HashMap<>();

    private static class Particle {
        float x, y, vx, vy, ay;
        int ti, tj;
        boolean settled;
        Particle(float x, float y, float vy, int ti, int tj){ this.x=x; this.y=y; this.vx=0f; this.vy=vy; this.ay=0f; this.ti=ti; this.tj=tj; this.settled=false; }
        Particle(float x, float y, float vx, float vy, float ay){ this.x=x; this.y=y; this.vx=vx; this.vy=vy; this.ay=ay; this.ti=0; this.tj=0; this.settled=false; }
    }

    private final Handler serviceHandler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            if (msg.what == GlyphToy.MSG_GLYPH_TOY) {
                Bundle bundle = msg.getData();
                String event = bundle != null ? bundle.getString(GlyphToy.MSG_GLYPH_TOY_DATA) : null;
                if (GlyphToy.EVENT_CHANGE.equals(event)) {
                    onLongPress();
                } else if (GlyphToy.EVENT_AOD.equals(event)) {
                    isAodMode = true;
                    // AOD中は省電力の静止表示に切替（アニメ停止・空表示を解除して曜日を表示）
                    stopAnimation();
                    holdBlank = false;
                    renderAndPresent();
                } else {
                    isAodMode = false;
                }
            } else {
                super.handleMessage(msg);
            }
        }
    };
    private final Messenger serviceMessenger = new Messenger(serviceHandler);

    @Nullable @Override
    public IBinder onBind(Intent intent) {
        init();
        return serviceMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopDailyRefresh();
        stopCycleTimer();
        stopAnimation();
        teardownSensors();
        if (mGM != null) {
            try { mGM.turnOff(); } catch (Throwable ignored) {}
            mGM.unInit();
        }
        mGM = null;
        mCallback = null;
        return false;
    }

    private void init() {
        mGM = GlyphMatrixManager.getInstance(getApplicationContext());
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WeekdayToyScheduler");
                t.setDaemon(true);
                return t;
            });
        }
        mCallback = new GlyphMatrixManager.Callback() {
            @Override public void onServiceConnected(ComponentName name) {
                boolean registered = tryRegisterWithFallback();
                if (!registered) {
                    Log.w(TAG, "GlyphMatrix register failed for all candidates");
                    return;
                }
                // Start with debug cycle or today's Kanji
                if (debugCycleMode) {
                    debugCycleIndex = 0;
                    startFallingToKanji(WEEK_KANJI[0]); // 日から開始
                } else {
                    startFallingToKanji(getWeekdayKanji());
                }
                scheduleDailyRefresh();
                setupSensors();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        mGM.init(mCallback);
    }

    private boolean tryRegisterWithFallback() {
        String[] candidates = new String[] {
                Glyph.DEVICE_23112,
                Glyph.DEVICE_23113,
                Glyph.DEVICE_24111,
                Glyph.DEVICE_23111,
                Glyph.DEVICE_22111,
                Glyph.DEVICE_20111,
        };
        for (String code : candidates) {
            try {
                boolean ok = mGM.register(code);
                if (ok) {
                    try { mGM.setGlyphMatrixTimeout(false); } catch (GlyphException ignore) {}
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "register failed for code=" + code, t);
            }
        }
        return false;
    }

    private void onLongPress() {
        // Toggle inversion (white<->black) including animation drawing
        invertEnabled = !invertEnabled;
        renderAndPresent();
    }

    private void scheduleDailyRefresh() {
        stopDailyRefresh();
        if (scheduler == null) return;
        
        if (debugCycleMode) {
            // デバッグモード: 3秒おきに全曜日を順次表示
            dailyFuture = scheduler.scheduleAtFixedRate(() -> {
                if (!isAodMode) {
                    serviceHandler.post(() -> {
                        char kanji = WEEK_KANJI[debugCycleIndex];
                        startFallingToKanji(kanji);
                        debugCycleIndex = (debugCycleIndex + 1) % WEEK_KANJI.length;
                    });
                }
            }, 0L, 3000L, TimeUnit.MILLISECONDS);
        } else {
            // 通常モード: 日付に基づく表示
            long delay = millisUntilNextMidnight();
            dailyFuture = scheduler.scheduleAtFixedRate(() -> {
                if (!isAodMode) serviceHandler.post(() -> startFallingToKanji(getWeekdayKanji()));
            }, delay, 24L * 60L * 60L * 1000L, TimeUnit.MILLISECONDS);
        }
    }
    private void stopDailyRefresh() {
        if (dailyFuture != null) { try { dailyFuture.cancel(true); } catch (Throwable ignored) {} dailyFuture = null; }
    }
    private long millisUntilNextMidnight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        return Math.max(1000L, cal.getTimeInMillis() - now);
    }

    private void startCycleTimer(long periodMs) {
        stopCycleTimer();
        cycleActive = true;
        cycleIndex = 0; // 月から
        serviceHandler.post(() -> startFallingToKanji(WEEK_KANJI_MON_FIRST[cycleIndex]));
        // 以降の切替はアニメ完了時に行う（固定周期では行わない）
    }
    private void stopCycleTimer() {
        cycleActive = false;
        // No dedicated cycle timer anymore; switching is driven by animation completion
    }

    private void renderAndPresent() {
        if (mGM == null) return;
        if (animActive) {
            if (animMode == ANIM_COLLAPSE) {
                renderCollapseFrame();
            } else {
                renderFallingFrame();
            }
        } else if (holdBlank) {
            // render blank
            for (int idx = 0; idx < frameBuf.length; idx++) frameBuf[idx] = 0;
        } else {
            char ch = cycleActive ? WEEK_KANJI_MON_FIRST[cycleIndex] : getWeekdayKanji();
            renderKanjiFrame(ch);
        }
        // Global inversion if enabled
        if (invertEnabled) {
            for (int i = 0; i < frameBuf.length; i++) {
                int v = frameBuf[i];
                if (v < 0) v = 0; else if (v > 2040) v = 2040;
                frameBuf[i] = 2040 - v;
            }
        }

        try {
            mGM.setMatrixFrame(frameBuf);
        } catch (GlyphException e) {
            // If toy rendering fails (e.g., not selected), do not fall back to app-level drawing
            // to avoid overriding other toys or appearing as always selected.
        }
    }

    // ===== Falling-dot animation =====
    private void startFallingToKanji(char kanji) {
        buildTargetMask(kanji);
        // reset fill/reserve state
        for (int i = 0; i < filledMask.length; i++) { filledMask[i] = false; reservedMask[i] = false; }
        // compute bottom-up next rows per column
        for (int i = 0; i < W; i++) nextFillRow[i] = findNextFillRow(i);
        remainingToFill = countTarget();
        particles.clear();
        animKanji = kanji;
        animTicks = 0;
        animActive = true;
        animMode = ANIM_FALL;
        holdBlank = false;
        startAnimTimer();
    }

    private void stopAnimation() {
        animActive = false;
        particles.clear();
        if (animFuture != null) { try { animFuture.cancel(true); } catch (Throwable ignored) {} animFuture = null; }
    }

    private void startAnimTimer() {
        if (animFuture != null) { try { animFuture.cancel(true); } catch (Throwable ignored) {} animFuture = null; }
        if (scheduler == null) return;
        animFuture = scheduler.scheduleAtFixedRate(() -> {
            if (isAodMode) return;
            serviceHandler.post(() -> {
                stepAnimation();
                renderAndPresent();
            });
        }, 0L, 40L, TimeUnit.MILLISECONDS);
    }

    private void stepAnimation() {
        if (!animActive) return;
        animTicks++;
        // Modes
        if (animMode == ANIM_FALL) {
            // spawn new particles gradually
            spawnParticles(SPAWN_PER_TICK);
            for (int idx = 0; idx < particles.size(); idx++) {
                Particle p = particles.get(idx);
                if (p.settled) continue;
                p.y += p.vy;
                if (p.y >= p.tj) {
                    p.y = p.tj;
                    p.settled = true;
                    int fi = p.tj * W + p.ti;
                    reservedMask[fi] = false;
                    if (!filledMask[fi]) { filledMask[fi] = true; remainingToFill--; }
                    // update next row for this column
                    nextFillRow[p.ti] = findNextFillRow(p.ti);
                }
            }
            if (remainingToFill <= 0) {
                stopAnimation();
                // do NOT auto-cycle unless explicitly enabled (legacy)
            }
        } else if (animMode == ANIM_COLLAPSE) {
            boolean any = false;
            for (int i = 0; i < particles.size(); i++) {
                Particle p = particles.get(i);
                if (p.settled) continue;
                any = true;
                p.vy += p.ay;
                p.x += p.vx;
                p.y += p.vy;
                p.vx *= 0.985f;
                if (p.y >= H || p.x < -1 || p.x > W) { p.settled = true; }
            }
            if (!any) {
                stopAnimation();
            }
        }
    }

    private void renderFallingFrame() {
        for (int idx = 0; idx < frameBuf.length; idx++) frameBuf[idx] = 0;
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                if (filledMask[j * W + i]) {
                    int idx = j * W + i;
                    int brightness = (int)(Math.max(0f, Math.min(1f, targetLevel[idx])) * 2040f + 0.5f);
                    frameBuf[idx] = brightness;
                }
            }
        }
        for (Particle p : particles) {
            int xi = (int)(p.x + 0.5f);
            int yj = (int)(p.y + 0.5f);
            if (xi < 0 || xi >= W || yj < 0 || yj >= H) continue;
            int idx = yj * W + xi;
            if (!filledMask[idx]) {
                int pb = 1600; // bright falling dot
                if (pb > frameBuf[idx]) frameBuf[idx] = pb;
            }
            // subtler 1px trail every other frame
            if ((animTicks & 1) == 0) {
                int yj2 = yj - 1;
                if (yj2 >= 0) {
                    int idx2 = yj2 * W + xi;
                    if (!filledMask[idx2]) {
                        int tb = 500;
                        if (tb > frameBuf[idx2]) frameBuf[idx2] = tb;
                    }
                }
            }
        }
    }

    private void renderCollapseFrame() {
        for (int idx = 0; idx < frameBuf.length; idx++) frameBuf[idx] = 0;
        for (Particle p : particles) {
            if (p.settled) continue;
            int xi = (int)(p.x + 0.5f);
            int yj = (int)(p.y + 0.5f);
            if (xi < 0 || xi >= W || yj < 0 || yj >= H) continue;
            frameBuf[yj * W + xi] = 1800;
        }
    }

    private void buildTargetMask(char kanji) {
        // Use cached mask/level if available
        boolean[] mask = cachedMask.get(kanji);
        float[] level = cachedLevel.get(kanji);
        if (mask != null && level != null) {
            System.arraycopy(mask, 0, targetMask, 0, targetMask.length);
            System.arraycopy(level, 0, targetLevel, 0, targetLevel.length);
            return;
        }
        for (int i = 0; i < targetMask.length; i++) { targetMask[i] = false; targetLevel[i] = 0f; }
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float maxBox = (RADIUS - 1.4f) * 2f;
        float textSize = maxBox; p.setTextSize(textSize);
        String s = String.valueOf(kanji);
        for (int iter = 0; iter < 8; iter++) {
            float w = p.measureText(s);
            Paint.FontMetrics fm = p.getFontMetrics();
            float h = fm.bottom - fm.top;
            if (w <= maxBox && h <= maxBox) break;
            textSize *= 0.92f; p.setTextSize(textSize);
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = CY - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(s, CX, baseline, p);
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                float dx = i - CX, dy = j - CY;
                float rFromCenter = (float)Math.sqrt(dx*dx + dy*dy);
                if (rFromCenter > RADIUS + 1.2f) { continue; }
                int argb = bmp.getPixel(i, j);
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = (argb) & 0xFF;
                float gray = (r + g + b) / 3f;
                float radialMask = smoothstep(RADIUS + 1.2f, RADIUS - 0.4f, rFromCenter);
                float valN = (gray / 255f) * radialMask;
                if (valN > 0.06f) {
                    int idx = j * W + i;
                    targetMask[idx] = true;
                    targetLevel[idx] = valN;
                }
            }
        }
        bmp.recycle();
        // Cache results for reuse
        boolean[] maskCopy = new boolean[targetMask.length];
        float[] levelCopy = new float[targetLevel.length];
        System.arraycopy(targetMask, 0, maskCopy, 0, targetMask.length);
        System.arraycopy(targetLevel, 0, levelCopy, 0, targetLevel.length);
        cachedMask.put(kanji, maskCopy);
        cachedLevel.put(kanji, levelCopy);
    }

    private int countTarget() {
        int c = 0;
        for (int i = 0; i < targetMask.length; i++) if (targetMask[i]) c++;
        return c;
    }

    private int findNextFillRow(int col) {
        for (int j = H - 1; j >= 0; j--) {
            int idx = j * W + col;
            if (targetMask[idx] && !filledMask[idx] && !reservedMask[idx]) return j;
        }
        return -1;
    }

    private void spawnParticles(int count) {
        for (int k = 0; k < count; k++) {
            if (remainingToFill <= 0) break;
            // find a column with available next row
            int startCol = (int)(Math.random() * W);
            int col = -1;
            for (int d = 0; d < W; d++) {
                int c = (startCol + d) % W;
                if (nextFillRow[c] >= 0) { col = c; break; }
            }
            if (col < 0) break;
            int row = nextFillRow[col];
            if (row < 0) break;
            int idx = row * W + col;
            reservedMask[idx] = true;
            float startY = - (float)(Math.random() * 10.0 + 3.0);
            float vy = (float)(Math.random() * 0.7 + 0.5f); // 0.5..1.2 px/tick (slower)
            particles.add(new Particle(col, startY, vy, col, row));
        }
    }

    // Collapse on shake
    private void startCollapse() {
        particles.clear();
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                if (!filledMask[j * W + i]) continue;
                float vx = (float)(Math.random() * 1.2 - 0.6);
                float vy = (float)(Math.random() * -0.6 - 0.2);
                float ay = 0.08f;
                particles.add(new Particle(i, j, vx, vy, ay));
            }
        }
        for (int idx = 0; idx < filledMask.length; idx++) filledMask[idx] = false;
        animActive = true;
        animMode = ANIM_COLLAPSE;
        holdBlank = true;
        startAnimTimer();
    }

    private void setupSensors() {
        try {
            sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (accelerometer != null) {
                    sensorManager.registerListener(shakeListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
                }
            }
        } catch (Throwable ignored) {}
    }
    private void teardownSensors() {
        try { if (sensorManager != null) sensorManager.unregisterListener(shakeListener); } catch (Throwable ignored) {}
        accelerometer = null;
        sensorManager = null;
    }

    private final SensorEventListener shakeListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            float ax = event.values[0], ay = event.values[1], az = event.values[2];
            float g = (float)Math.sqrt(ax*ax + ay*ay + az*az);
            long now = System.currentTimeMillis();
            if (g > 15.0f && now - lastShakeMs > 800) {
                lastShakeMs = now;
                if (!isAodMode) {
                    stopAnimation();
                    startCollapse();
                    if (restartRunnable != null) { serviceHandler.removeCallbacks(restartRunnable); }
                    restartRunnable = () -> { if (!isAodMode && !animActive) startFallingToKanji(getWeekdayKanji()); };
                    serviceHandler.postDelayed(restartRunnable, 5_000L);
                }
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private void renderKanjiFrame(char kanji) {
        // Use cached static frame if available
        int[] cached = cachedStaticFrame.get(kanji);
        if (cached != null) {
            System.arraycopy(cached, 0, frameBuf, 0, frameBuf.length);
            return;
        }
        for (int i = 0; i < frameBuf.length; i++) frameBuf[i] = 0;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        float maxBox = (RADIUS - 1.4f) * 2f;
        float textSize = maxBox; p.setTextSize(textSize);
        String s = String.valueOf(kanji);
        for (int iter = 0; iter < 8; iter++) {
            float w = p.measureText(s);
            Paint.FontMetrics fm = p.getFontMetrics();
            float h = fm.bottom - fm.top;
            if (w <= maxBox && h <= maxBox) break;
            textSize *= 0.92f; p.setTextSize(textSize);
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = CY - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(s, CX, baseline, p);
        int idx = 0;
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++, idx++) {
                float dx = i - CX, dy = j - CY;
                float rFromCenter = (float)Math.sqrt(dx*dx + dy*dy);
                if (rFromCenter > RADIUS + 1.2f) { frameBuf[idx] = 0; continue; }
                float mask = smoothstep(RADIUS + 1.2f, RADIUS - 0.4f, rFromCenter);
                int argb = bmp.getPixel(i, j);
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = (argb) & 0xFF;
                float gray = (r + g + b) / 3f;
                float valN = (gray / 255f) * mask;
                int brightness = (int)(Math.max(0f, Math.min(1f, valN)) * 2040f + 0.5f);
                frameBuf[idx] = brightness;
            }
        }
        bmp.recycle();
        int[] frameCopy = new int[frameBuf.length];
        System.arraycopy(frameBuf, 0, frameCopy, 0, frameBuf.length);
        cachedStaticFrame.put(kanji, frameCopy);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { stopDailyRefresh(); } catch (Throwable ignored) {}
        try { if (animFuture != null) { animFuture.cancel(true); animFuture = null; } } catch (Throwable ignored) {}
        try { if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; } } catch (Throwable ignored) {}
        try { teardownSensors(); } catch (Throwable ignored) {}
        try { if (mGM != null) { mGM.turnOff(); mGM.unInit(); } } catch (Throwable ignored) {}
        mGM = null;
        mCallback = null;
    }

    private char getWeekdayKanji() {
        if (DEBUG_FORCE_FRIDAY) return '\u91d1'; // 金 (debug only)
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int idx = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1; // 0..6 (Sun..Sat)
        if (idx < 0 || idx >= WEEK_KANJI.length) idx = 0;
        return WEEK_KANJI[idx];
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }
    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}


