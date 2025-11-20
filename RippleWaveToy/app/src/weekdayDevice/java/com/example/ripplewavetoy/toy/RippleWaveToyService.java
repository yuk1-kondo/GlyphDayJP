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

    // Grid dimensions
    private static final int W = 25;
    private static final int H = 25;
    private static final float CX = (W - 1) * 0.5f;
    private static final float CY = (H - 1) * 0.5f;

    // Rendering constants
    private static final float RADIUS = 12.4f;
    private static final float TEXT_MARGIN = 1.4f;
    private static final float FEATHER_OUTER = 1.2f;
    private static final float FEATHER_INNER = 0.4f;
    private static final float TEXT_SIZE_REDUCTION_FACTOR = 0.92f;
    private static final int TEXT_SIZE_FIT_ITERATIONS = 8;
    private static final float TARGET_LEVEL_THRESHOLD = 0.06f;

    // Brightness values
    private static final int MAX_BRIGHTNESS = 2040;
    private static final int FALLING_DOT_BRIGHTNESS = 1600;
    private static final int TRAIL_BRIGHTNESS = 500;
    private static final int COLLAPSE_BRIGHTNESS = 1800;

    // Animation timing
    private static final long ANIM_FRAME_INTERVAL_MS = 40L;
    private static final long RESTART_DELAY_MS = 5000L;
    private static final long DEBUG_CYCLE_INTERVAL_MS = 3000L;

    // Particle physics
    private static final float FALL_SPEED_MIN = 0.5f;
    private static final float FALL_SPEED_RANGE = 0.7f;
    private static final double SPAWN_START_Y_MIN = 3.0;
    private static final double SPAWN_START_Y_RANGE = 10.0;
    private static final double COLLAPSE_VX_RANGE = 1.2;
    private static final double COLLAPSE_VX_OFFSET = 0.6;
    private static final double COLLAPSE_VY_RANGE = 0.6;
    private static final double COLLAPSE_VY_OFFSET = 0.2;
    private static final float COLLAPSE_GRAVITY = 0.08f;
    private static final float COLLAPSE_DAMPING = 0.985f;

    // Sensor constants
    private static final float SHAKE_THRESHOLD = 15.0f;
    private static final long SHAKE_COOLDOWN_MS = 800L;

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
            }, 0L, DEBUG_CYCLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
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
                if (v < 0) v = 0; else if (v > MAX_BRIGHTNESS) v = MAX_BRIGHTNESS;
                frameBuf[i] = MAX_BRIGHTNESS - v;
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
        }, 0L, ANIM_FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stepAnimation() {
        if (!animActive) return;
        animTicks++;

        if (animMode == ANIM_FALL) {
            stepFallAnimation();
        } else if (animMode == ANIM_COLLAPSE) {
            stepCollapseAnimation();
        }
    }

    /**
     * Updates the falling particle animation (ANIM_FALL mode).
     */
    private void stepFallAnimation() {
        // Spawn new particles gradually
        spawnParticles(SPAWN_PER_TICK);

        // Update particle positions
        for (int idx = 0; idx < particles.size(); idx++) {
            Particle p = particles.get(idx);
            if (p.settled) continue;

            p.y += p.vy;

            // Check if particle reached target position
            if (p.y >= p.tj) {
                p.y = p.tj;
                p.settled = true;
                int fi = p.tj * W + p.ti;
                reservedMask[fi] = false;

                if (!filledMask[fi]) {
                    filledMask[fi] = true;
                    remainingToFill--;
                }

                // Update next row for this column
                nextFillRow[p.ti] = findNextFillRow(p.ti);
            }
        }

        // Check if animation is complete
        if (remainingToFill <= 0) {
            stopAnimation();
        }
    }

    /**
     * Updates the collapse particle animation (ANIM_COLLAPSE mode).
     */
    private void stepCollapseAnimation() {
        boolean anyActive = false;

        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            if (p.settled) continue;

            anyActive = true;

            // Apply physics
            p.vy += p.ay;
            p.x += p.vx;
            p.y += p.vy;
            p.vx *= COLLAPSE_DAMPING;

            // Check if particle is out of bounds
            if (p.y >= H || p.x < -1 || p.x > W) {
                p.settled = true;
            }
        }

        // Stop animation if all particles have settled
        if (!anyActive) {
            stopAnimation();
        }
    }

    private void renderFallingFrame() {
        for (int idx = 0; idx < frameBuf.length; idx++) frameBuf[idx] = 0;
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                if (filledMask[j * W + i]) {
                    int idx = j * W + i;
                    int brightness = (int)(Math.max(0f, Math.min(1f, targetLevel[idx])) * MAX_BRIGHTNESS + 0.5f);
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
                if (FALLING_DOT_BRIGHTNESS > frameBuf[idx]) frameBuf[idx] = FALLING_DOT_BRIGHTNESS;
            }
            // subtler 1px trail every other frame
            if ((animTicks & 1) == 0) {
                int yj2 = yj - 1;
                if (yj2 >= 0) {
                    int idx2 = yj2 * W + xi;
                    if (!filledMask[idx2]) {
                        if (TRAIL_BRIGHTNESS > frameBuf[idx2]) frameBuf[idx2] = TRAIL_BRIGHTNESS;
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
            frameBuf[yj * W + xi] = COLLAPSE_BRIGHTNESS;
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

        // Clear target arrays
        for (int i = 0; i < targetMask.length; i++) {
            targetMask[i] = false;
            targetLevel[i] = 0f;
        }

        // Create kanji bitmap using helper method
        Bitmap bmp = createKanjiBitmap(kanji);

        // Apply circular mask and extract pixel values
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                float radialMask = computeRadialMask(i, j);
                if (radialMask <= 0f) continue;

                int argb = bmp.getPixel(i, j);
                float gray = getGrayscaleValue(argb);
                float valN = (gray / 255f) * radialMask;

                if (valN > TARGET_LEVEL_THRESHOLD) {
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
            float startY = - (float)(Math.random() * SPAWN_START_Y_RANGE + SPAWN_START_Y_MIN);
            float vy = (float)(Math.random() * FALL_SPEED_RANGE + FALL_SPEED_MIN);
            particles.add(new Particle(col, startY, vy, col, row));
        }
    }

    // Collapse on shake
    private void startCollapse() {
        particles.clear();
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                if (!filledMask[j * W + i]) continue;
                float vx = (float)(Math.random() * COLLAPSE_VX_RANGE - COLLAPSE_VX_OFFSET);
                float vy = (float)(Math.random() * -COLLAPSE_VY_RANGE - COLLAPSE_VY_OFFSET);
                particles.add(new Particle(i, j, vx, vy, COLLAPSE_GRAVITY));
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
            if (g > SHAKE_THRESHOLD && now - lastShakeMs > SHAKE_COOLDOWN_MS) {
                lastShakeMs = now;
                if (!isAodMode) {
                    stopAnimation();
                    startCollapse();
                    if (restartRunnable != null) { serviceHandler.removeCallbacks(restartRunnable); }
                    restartRunnable = () -> { if (!isAodMode && !animActive) startFallingToKanji(getWeekdayKanji()); };
                    serviceHandler.postDelayed(restartRunnable, RESTART_DELAY_MS);
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

        // Clear frame buffer
        for (int i = 0; i < frameBuf.length; i++) frameBuf[i] = 0;

        // Create kanji bitmap using helper method
        Bitmap bmp = createKanjiBitmap(kanji);

        // Apply circular mask and convert to brightness values
        int idx = 0;
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++, idx++) {
                float mask = computeRadialMask(i, j);
                if (mask <= 0f) {
                    frameBuf[idx] = 0;
                    continue;
                }

                int argb = bmp.getPixel(i, j);
                float gray = getGrayscaleValue(argb);
                float valN = (gray / 255f) * mask;
                int brightness = (int)(Math.max(0f, Math.min(1f, valN)) * MAX_BRIGHTNESS + 0.5f);
                frameBuf[idx] = brightness;
            }
        }

        bmp.recycle();

        // Cache result for reuse
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

    /**
     * Creates a bitmap with the specified kanji character rendered with circular feathering.
     * This is a helper method to eliminate code duplication between buildTargetMask and renderKanjiFrame.
     */
    private Bitmap createKanjiBitmap(char kanji) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);

        // Fit text size to available space
        float maxBox = (RADIUS - TEXT_MARGIN) * 2f;
        fitTextSize(p, String.valueOf(kanji), maxBox);

        // Draw text centered
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = CY - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(String.valueOf(kanji), CX, baseline, p);

        return bmp;
    }

    /**
     * Adjusts the text size of the paint to fit within the specified bounding box.
     */
    private void fitTextSize(Paint paint, String text, float maxBox) {
        float textSize = maxBox;
        paint.setTextSize(textSize);
        for (int iter = 0; iter < TEXT_SIZE_FIT_ITERATIONS; iter++) {
            float w = paint.measureText(text);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float h = fm.bottom - fm.top;
            if (w <= maxBox && h <= maxBox) break;
            textSize *= TEXT_SIZE_REDUCTION_FACTOR;
            paint.setTextSize(textSize);
        }
    }

    /**
     * Computes the radial distance and applies smoothstep feathering.
     */
    private float computeRadialMask(int i, int j) {
        float dx = i - CX, dy = j - CY;
        float rFromCenter = (float)Math.sqrt(dx * dx + dy * dy);
        if (rFromCenter > RADIUS + FEATHER_OUTER) return 0f;
        return smoothstep(RADIUS + FEATHER_OUTER, RADIUS - FEATHER_INNER, rFromCenter);
    }

    /**
     * Extracts grayscale value from an ARGB pixel.
     */
    private float getGrayscaleValue(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r + g + b) / 3f;
    }
}


