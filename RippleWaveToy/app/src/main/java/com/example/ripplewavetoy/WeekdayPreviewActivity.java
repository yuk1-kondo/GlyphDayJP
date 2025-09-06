package com.example.ripplewavetoy;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

public class WeekdayPreviewActivity extends Activity {

    private static final int W = 25;
    private static final int H = 25;
    private static final float CX = (W - 1) * 0.5f;
    private static final float CY = (H - 1) * 0.5f;
    private static final float RADIUS = 12.4f;
    private static final char[] WEEK_KANJI = new char[] {'\u65e5','\u6708','\u706b','\u6c34','\u6728','\u91d1','\u571f'};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weekday_preview);

        String[] titles = new String[] {"日", "月", "火", "水", "木", "金", "土"};
        int[] titleIds = new int[] { R.id.title0, R.id.title1, R.id.title2, R.id.title3, R.id.title4, R.id.title5, R.id.title6 };
        int[] imageIds = new int[] { R.id.img0, R.id.img1, R.id.img2, R.id.img3, R.id.img4, R.id.img5, R.id.img6 };

        for (int i = 0; i < 7; i++) {
            ((TextView)findViewById(titleIds[i])).setText(titles[i]);
            Bitmap bmp = renderKanjiBitmap(WEEK_KANJI[i]);
            ((ImageView)findViewById(imageIds[i])).setImageBitmap(scaleBitmap(bmp, 20));
        }
    }

    private Bitmap renderKanjiBitmap(char kanji) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);

        float maxBox = (RADIUS - 1.4f) * 2f;
        float textSize = maxBox;
        p.setTextSize(textSize);
        String s = String.valueOf(kanji);
        for (int iter = 0; iter < 8; iter++) {
            float w = p.measureText(s);
            Paint.FontMetrics fm = p.getFontMetrics();
            float h = fm.bottom - fm.top;
            if (w <= maxBox && h <= maxBox) break;
            textSize *= 0.92f;
            p.setTextSize(textSize);
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        float cx = W * 0.5f;
        float cy = H * 0.5f;
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(s, cx, baseline, p);

        // Apply circular mask feather similar to service
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                float dx = i - CX;
                float dy = j - CY;
                float rFromCenter = (float)Math.sqrt(dx*dx + dy*dy);
                if (rFromCenter > RADIUS + 1.2f) {
                    bmp.setPixel(i, j, Color.BLACK);
                } else {
                    float mask = smoothstep(RADIUS + 1.2f, RADIUS - 0.4f, rFromCenter);
                    int argb = bmp.getPixel(i, j);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = (argb) & 0xFF;
                    int gray = (int)Math.max(0, Math.min(255, ((r + g + b) / 3f) * mask));
                    int out = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                    bmp.setPixel(i, j, out);
                }
            }
        }
        return bmp;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static Bitmap scaleBitmap(Bitmap src, int scale) {
        int w = src.getWidth();
        int h = src.getHeight();
        Bitmap dst = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int color = src.getPixel(i, j);
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        dst.setPixel(i * scale + dx, j * scale + dy, color);
                    }
                }
            }
        }
        return dst;
    }
}



