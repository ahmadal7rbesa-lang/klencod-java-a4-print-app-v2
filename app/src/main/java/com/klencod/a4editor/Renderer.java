package com.klencod.a4editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

final class Renderer {
    static final float W = 595f;
    static final float H = 842f;

    private Renderer() {
    }

    /** يرسم عناصر الصفحة بإحداثيات A4 (595x842). */
    static void draw(Canvas c, DocPage p, boolean forceBw) {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        ColorMatrixColorFilter gray = new ColorMatrixColorFilter(cm);
        for (Item it : p.items) {
            c.save();
            c.translate(it.cx, it.cy);
            c.rotate(it.rot);
            c.scale(it.scale, it.scale);
            paint.setColorFilter((it.bw || forceBw) ? gray : null);
            c.drawBitmap(it.bmp, -it.bmp.getWidth() / 2f, -it.bmp.getHeight() / 2f, paint);
            c.restore();
        }
    }

    /** يرسم صفحة كاملة على لوحة بحجم pw x ph مع خلفية بيضاء، محافظًا على النسبة. */
    static void drawFitted(Canvas c, DocPage p, float pw, float ph, boolean forceBw) {
        c.drawColor(Color.WHITE);
        float s = Math.min(pw / W, ph / H);
        c.save();
        c.translate((pw - W * s) / 2f, (ph - H * s) / 2f);
        c.scale(s, s);
        draw(c, p, forceBw);
        c.restore();
    }
}
