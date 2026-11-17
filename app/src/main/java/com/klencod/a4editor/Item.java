package com.klencod.a4editor;

import android.graphics.Bitmap;

/** عنصر على الصفحة: صورة أو صفحة PDF أو نص (يتحول إلى صورة). الوحدات: نقاط A4 (595x842). */
class Item {
    Bitmap bmp;
    String text;      // غير null إذا كان العنصر نصًا
    float cx, cy;     // مركز العنصر على الصفحة
    float scale = 1f; // نقطة لكل بكسل
    float rot = 0f;   // بالدرجات
    boolean bw = false;

    Item(Bitmap b) {
        bmp = b;
    }

    Item copy() {
        Item i = new Item(bmp);
        i.text = text;
        i.cx = cx;
        i.cy = cy;
        i.scale = scale;
        i.rot = rot;
        i.bw = bw;
        return i;
    }

    float w() {
        return bmp.getWidth() * scale;
    }

    float h() {
        return bmp.getHeight() * scale;
    }

    private double[] extents1() {
        double r = Math.toRadians(rot);
        double c = Math.abs(Math.cos(r)), s = Math.abs(Math.sin(r));
        double bw0 = bmp.getWidth(), bh0 = bmp.getHeight();
        return new double[]{c * bw0 + s * bh0, s * bw0 + c * bh0};
    }

    /** يبقي العنصر داخل حدود الورقة (حجمًا وموضعًا). */
    void clamp(float pw, float ph) {
        double[] e = extents1();
        double maxScale = Math.min(pw / e[0], ph / e[1]);
        double minScale = Math.min(24.0 / Math.max(e[0], e[1]), maxScale);
        double sc = Math.max(minScale, Math.min(maxScale, scale));
        scale = (float) sc;
        float hw = (float) (e[0] * scale / 2.0);
        float hh = (float) (e[1] * scale / 2.0);
        cx = Math.max(hw, Math.min(pw - hw, cx));
        cy = Math.max(hh, Math.min(ph - hh, cy));
    }

    /** ملاءمة العنصر داخل الصفحة مع هامش وتوسيطه. */
    void fit(float pw, float ph, float margin) {
        double[] e = extents1();
        scale = (float) Math.min((pw - 2 * margin) / e[0], (ph - 2 * margin) / e[1]);
        cx = pw / 2f;
        cy = ph / 2f;
    }

    /** ملاءمة العنصر داخل خلية مستطيلة من الصفحة وتوسيطه فيها. */
    void fitIn(float left, float top, float w, float h) {
        double[] e = extents1();
        scale = (float) Math.min(w / e[0], h / e[1]);
        cx = left + w / 2f;
        cy = top + h / 2f;
    }

    boolean hit(float x, float y, float slop) {
        double r = Math.toRadians(-rot);
        float dx = x - cx, dy = y - cy;
        float lx = (float) (dx * Math.cos(r) - dy * Math.sin(r));
        float ly = (float) (dx * Math.sin(r) + dy * Math.cos(r));
        return Math.abs(lx) <= w() / 2f + slop && Math.abs(ly) <= h() / 2f + slop;
    }
}
