package com.klencod.a4editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** عرض ورقة A4 قابلة للتحرير: سحب بإصبع، وتكبير/تصغير بإصبعين، داخل حدود الورقة. */
public class PageView extends View {

    interface Listener {
        void onBeforeChange();
    }

    DocPage page;
    Item selected;
    Listener listener;

    private float s = 1f, offX, offY;
    private final Paint paper = new Paint();
    private final Paint shadow = new Paint();
    private final Paint sel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector sgd;
    private boolean dragging, pushed;
    private float lastX, lastY;

    public PageView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        paper.setColor(Color.WHITE);
        shadow.setColor(0x33000000);
        sel.setStyle(Paint.Style.STROKE);
        sel.setColor(0xFF1E88E5);
        hint.setColor(0xFF9BA39D);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(14f * c.getResources().getDisplayMetrics().density);

        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                if (selected == null && page != null && !page.items.isEmpty()) {
                    selected = page.items.get(page.items.size() - 1);
                }
                if (selected == null) return false;
                if (listener != null) listener.onBeforeChange();
                dragging = false;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (selected == null) return false;
                selected.scale *= d.getScaleFactor();
                selected.clamp(Renderer.W, Renderer.H);
                invalidate();
                return true;
            }
        });
    }

    void setPage(DocPage p) {
        page = p;
        selected = null;
        invalidate();
    }

    private float toPageX(float x) {
        return (x - offX) / s;
    }

    private float toPageY(float y) {
        return (y - offY) / s;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = 10f * getResources().getDisplayMetrics().density;
        float vw = getWidth(), vh = getHeight();
        s = Math.min((vw - 2 * pad) / Renderer.W, (vh - 2 * pad) / Renderer.H);
        if (s <= 0f) return;
        offX = (vw - Renderer.W * s) / 2f;
        offY = (vh - Renderer.H * s) / 2f;

        canvas.drawRect(offX + 4, offY + 4, offX + Renderer.W * s + 4, offY + Renderer.H * s + 4, shadow);
        canvas.drawRect(offX, offY, offX + Renderer.W * s, offY + Renderer.H * s, paper);
        if (page == null) return;

        canvas.save();
        canvas.translate(offX, offY);
        canvas.scale(s, s);
        canvas.clipRect(0, 0, Renderer.W, Renderer.H);
        Renderer.draw(canvas, page, false);

        if (selected != null && page.items.contains(selected)) {
            canvas.save();
            canvas.translate(selected.cx, selected.cy);
            canvas.rotate(selected.rot);
            sel.setStrokeWidth(2f / s);
            sel.setPathEffect(new DashPathEffect(new float[]{8f / s, 5f / s}, 0f));
            canvas.drawRect(-selected.w() / 2f, -selected.h() / 2f,
                    selected.w() / 2f, selected.h() / 2f, sel);
            canvas.restore();
        }
        canvas.restore();

        if (page.items.isEmpty()) {
            canvas.drawText("ورقة A4 فارغة — استورد صورة أو PDF أو أضف نصًا",
                    vw / 2f, vh / 2f, hint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (page == null) return false;
        sgd.onTouchEvent(e);
        if (sgd.isInProgress()) return true;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float px = toPageX(e.getX()), py = toPageY(e.getY());
                Item hit = null;
                for (int i = page.items.size() - 1; i >= 0; i--) {
                    if (page.items.get(i).hit(px, py, 6f)) {
                        hit = page.items.get(i);
                        break;
                    }
                }
                selected = hit;
                dragging = hit != null;
                pushed = false;
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging && selected != null && e.getPointerCount() == 1) {
                    if (!pushed) {
                        if (listener != null) listener.onBeforeChange();
                        pushed = true;
                    }
                    selected.cx += (e.getX() - lastX) / s;
                    selected.cy += (e.getY() - lastY) / s;
                    selected.clamp(Renderer.W, Renderer.H);
                    lastX = e.getX();
                    lastY = e.getY();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }
}
