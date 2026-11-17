package com.klencod.a4editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** واجهة قص: اسحب الزوايا أو حرّك المستطيل. */
public class CropView extends View {

    private static final float MIN = 30f;

    private final Bitmap bmp;
    private final RectF crop;
    private float vs = 1f, ox, oy;
    private int mode = 0;
    private float lx, ly;
    private final float density;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint();
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CropView(Context c, Bitmap b) {
        super(c);
        bmp = b;
        crop = new RectF(0, 0, b.getWidth(), b.getHeight());
        density = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0xFFFFFFFF);
        line.setStrokeWidth(2f * density);
        dim.setColor(0x99000000);
        handle.setColor(0xFF1E88E5);
    }

    RectF getCrop() {
        return new RectF(crop);
    }

    private RectF viewRect() {
        return new RectF(ox + crop.left * vs, oy + crop.top * vs,
                ox + crop.right * vs, oy + crop.bottom * vs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        vs = Math.min(getWidth() / (float) bw, getHeight() / (float) bh);
        ox = (getWidth() - bw * vs) / 2f;
        oy = (getHeight() - bh * vs) / 2f;
        RectF dst = new RectF(ox, oy, ox + bw * vs, oy + bh * vs);
        canvas.drawColor(0xFF2A2A2A);
        canvas.drawBitmap(bmp, null, dst, bitmapPaint);

        RectF r = viewRect();
        canvas.drawRect(dst.left, dst.top, dst.right, r.top, dim);
        canvas.drawRect(dst.left, r.bottom, dst.right, dst.bottom, dim);
        canvas.drawRect(dst.left, r.top, r.left, r.bottom, dim);
        canvas.drawRect(r.right, r.top, dst.right, r.bottom, dim);
        canvas.drawRect(r, line);
        float hr = 9f * density;
        canvas.drawCircle(r.left, r.top, hr, handle);
        canvas.drawCircle(r.right, r.top, hr, handle);
        canvas.drawCircle(r.left, r.bottom, hr, handle);
        canvas.drawCircle(r.right, r.bottom, hr, handle);
    }

    private boolean near(float x, float y, float px, float py) {
        float t = 30f * density;
        return Math.abs(x - px) < t && Math.abs(y - py) < t;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                RectF r = viewRect();
                float x = e.getX(), y = e.getY();
                if (near(x, y, r.left, r.top)) mode = 1;
                else if (near(x, y, r.right, r.top)) mode = 2;
                else if (near(x, y, r.left, r.bottom)) mode = 3;
                else if (near(x, y, r.right, r.bottom)) mode = 4;
                else if (r.contains(x, y)) mode = 5;
                else mode = 0;
                lx = x;
                ly = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == 0) return true;
                float dx = (e.getX() - lx) / vs;
                float dy = (e.getY() - ly) / vs;
                lx = e.getX();
                ly = e.getY();
                float bw = bmp.getWidth(), bh = bmp.getHeight();
                if (mode == 5) {
                    crop.offset(dx, dy);
                    if (crop.left < 0) crop.offset(-crop.left, 0);
                    if (crop.right > bw) crop.offset(bw - crop.right, 0);
                    if (crop.top < 0) crop.offset(0, -crop.top);
                    if (crop.bottom > bh) crop.offset(0, bh - crop.bottom);
                } else {
                    if (mode == 1 || mode == 3) crop.left += dx; else crop.right += dx;
                    if (mode == 1 || mode == 2) crop.top += dy; else crop.bottom += dy;
                    crop.left = Math.max(0f, crop.left);
                    crop.top = Math.max(0f, crop.top);
                    crop.right = Math.min(bw, crop.right);
                    crop.bottom = Math.min(bh, crop.bottom);
                    if (mode == 1 || mode == 3) crop.left = Math.min(crop.left, crop.right - MIN);
                    else crop.right = Math.max(crop.right, crop.left + MIN);
                    if (mode == 1 || mode == 2) crop.top = Math.min(crop.top, crop.bottom - MIN);
                    else crop.bottom = Math.max(crop.bottom, crop.top + MIN);
                }
                invalidate();
                return true;
            }
            default:
                mode = 0;
                return true;
        }
    }
}
