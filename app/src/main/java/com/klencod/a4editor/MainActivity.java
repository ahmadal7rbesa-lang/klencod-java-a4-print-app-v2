package com.klencod.a4editor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    private static final float W = Renderer.W;
    private static final float H = Renderer.H;
    private static final int PRIMARY = 0xFF0E4F46;

    private static class Snap {
        List<DocPage> pages;
        int cur;
    }

    private final List<DocPage> pages = new ArrayList<>();
    private int cur = 0;
    private final ArrayDeque<Snap> history = new ArrayDeque<>();
    private final ArrayDeque<Snap> future = new ArrayDeque<>();

    private PageView pageView;
    private TextView pageLabel;
    private Button undoBtn, redoBtn;
    private AlertDialog busy;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> imagePicker;
    private ActivityResultLauncher<String[]> pdfPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pages.add(new DocPage());

        imagePicker = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris != null && !uris.isEmpty()) askLayout(uris);
        });
        pdfPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) openPdf(uri);
        });

        buildUi();
        showCurrent();
    }

    // ================= الواجهة =================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF1EEE6);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // استيراد
        LinearLayout rowImport = row();
        rowImport.addView(btn("🖼 صورة", true, v -> imagePicker.launch(new String[]{"image/*"})), w1());
        rowImport.addView(btn("📄 PDF", true, v -> pdfPicker.launch(new String[]{"application/pdf"})), w1());
        rowImport.addView(btn("T نص", true, v -> addText()), w1());
        root.addView(rowImport);

        // التنقل بين الصفحات
        LinearLayout rowNav = row();
        rowNav.addView(btn("السابقة", false, v -> goTo(cur - 1)), w1());
        pageLabel = new TextView(this);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setTextColor(0xFF182521);
        pageLabel.setTextSize(14);
        rowNav.addView(pageLabel, new LinearLayout.LayoutParams(0, dp(44), 1.2f));
        rowNav.addView(btn("التالية", false, v -> goTo(cur + 1)), w1());
        root.addView(rowNav);

        // عمليات الصفحات
        LinearLayout rowOps = row();
        rowOps.addView(btn("＋ صفحة", false, v -> addBlankPage()), w1());
        rowOps.addView(btn("حذف الصفحة", false, v -> deletePage()), w1());
        undoBtn = btn("↶", false, v -> undo());
        redoBtn = btn("↷", false, v -> redo());
        rowOps.addView(undoBtn, w1());
        rowOps.addView(redoBtn, w1());
        root.addView(rowOps);

        // ورقة A4
        pageView = new PageView(this);
        pageView.listener = this::pushHistory;
        root.addView(pageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // أدوات العنصر المحدد
        HorizontalScrollView tools = new HorizontalScrollView(this);
        tools.setHorizontalScrollBarEnabled(false);
        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(toolsRow);
        addTool(toolsRow, "－ تصغير", v -> zoom(1f / 1.1f));
        addTool(toolsRow, "＋ تكبير", v -> zoom(1.1f));
        addTool(toolsRow, "⟳ تدوير", v -> rotateSel());
        addTool(toolsRow, "✂ قص", v -> cropSel());
        addTool(toolsRow, "ملاءمة", v -> fitSel());
        addTool(toolsRow, "أبيض/أسود", v -> bwSel());
        addTool(toolsRow, "تعديل النص", v -> editTextSel());
        addTool(toolsRow, "حذف العنصر", v -> deleteSel());
        root.addView(tools);

        // طباعة ومشاركة
        LinearLayout rowPrint = row();
        rowPrint.addView(btn("🖨 طباعة", true, v -> showPagesDialog(false)), w1());
        rowPrint.addView(btn("مشاركة PDF", false, v -> showPagesDialog(true)), w1());
        root.addView(rowPrint);

        setContentView(root);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams w1() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p.setMargins(dp(3), dp(2), dp(3), dp(2));
        return p;
    }

    private void addTool(LinearLayout parent, String text, View.OnClickListener l) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        p.setMargins(dp(3), dp(2), dp(3), dp(2));
        Button b = btn(text, false, l);
        b.setPadding(dp(14), 0, dp(14), 0);
        parent.addView(b, p);
    }

    private Button btn(String text, boolean filled, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        if (filled) {
            d.setColor(PRIMARY);
            b.setTextColor(Color.WHITE);
        } else {
            d.setColor(0xFFFFFFFF);
            d.setStroke(dp(1), PRIMARY);
            b.setTextColor(PRIMARY);
        }
        b.setBackground(d);
        b.setOnClickListener(l);
        return b;
    }

    private void updateUi() {
        pageLabel.setText("صفحة " + (cur + 1) + " / " + pages.size());
        undoBtn.setAlpha(history.isEmpty() ? 0.4f : 1f);
        redoBtn.setAlpha(future.isEmpty() ? 0.4f : 1f);
    }

    private void showCurrent() {
        if (cur >= pages.size()) cur = pages.size() - 1;
        if (cur < 0) cur = 0;
        pageView.setPage(pages.get(cur));
        updateUi();
    }

    private void goTo(int index) {
        if (index < 0 || index >= pages.size()) return;
        cur = index;
        showCurrent();
    }

    // ================= السجل (تراجع / إعادة) =================

    private Snap snap() {
        Snap s = new Snap();
        s.pages = new ArrayList<>();
        for (DocPage p : pages) s.pages.add(p.copy());
        s.cur = cur;
        return s;
    }

    private void pushHistory() {
        history.addFirst(snap());
        while (history.size() > 30) history.removeLast();
        future.clear();
        updateUi();
    }

    private void restore(Snap s) {
        pages.clear();
        pages.addAll(s.pages);
        if (pages.isEmpty()) pages.add(new DocPage());
        cur = s.cur;
        showCurrent();
    }

    private void undo() {
        if (history.isEmpty()) return;
        future.addFirst(snap());
        restore(history.removeFirst());
    }

    private void redo() {
        if (future.isEmpty()) return;
        history.addFirst(snap());
        restore(future.removeFirst());
    }

    // ================= الصفحات =================

    private void addBlankPage() {
        pushHistory();
        pages.add(cur + 1, new DocPage());
        cur++;
        showCurrent();
    }

    private void deletePage() {
        pushHistory();
        if (pages.size() == 1) {
            pages.set(0, new DocPage());
        } else {
            pages.remove(cur);
            if (cur >= pages.size()) cur = pages.size() - 1;
        }
        showCurrent();
    }

    /** يضع العنصر على الصفحة الحالية إن كانت فارغة، وإلا في صفحة جديدة بعدها. */
    private void insertItem(Item it) {
        DocPage cp = pages.get(cur);
        if (cp.items.isEmpty()) {
            cp.items.add(it);
        } else {
            DocPage np = new DocPage();
            np.items.add(it);
            pages.add(cur + 1, np);
            cur++;
        }
    }

    // ================= أدوات العنصر المحدد =================

    private Item selectedOrToast() {
        Item s = pageView.selected;
        if (s == null || !pages.get(cur).items.contains(s)) {
            toast("المس عنصرًا على الورقة لتحديده أولًا");
            return null;
        }
        return s;
    }

    private void zoom(float f) {
        Item s = selectedOrToast();
        if (s == null) return;
        pushHistory();
        s.scale *= f;
        s.clamp(W, H);
        pageView.invalidate();
    }

    private void rotateSel() {
        Item s = selectedOrToast();
        if (s == null) return;
        pushHistory();
        s.rot = (s.rot + 90f) % 360f;
        s.clamp(W, H);
        pageView.invalidate();
    }

    private void fitSel() {
        Item s = selectedOrToast();
        if (s == null) return;
        pushHistory();
        s.fit(W, H, 0f);
        pageView.invalidate();
    }

    private void bwSel() {
        Item s = selectedOrToast();
        if (s == null) return;
        pushHistory();
        s.bw = !s.bw;
        pageView.invalidate();
    }

    private void cropSel() {
        final Item s = selectedOrToast();
        if (s == null) return;
        final CropView cv = new CropView(this, s.bmp);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(8), dp(8), dp(8), 0);
        box.addView(cv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        new AlertDialog.Builder(this)
                .setTitle("اسحب الزوايا لتحديد منطقة القص")
                .setView(box)
                .setPositiveButton("قص", (d, w) -> applyCrop(s, cv.getCrop()))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void applyCrop(Item s, android.graphics.RectF r) {
        int bw = s.bmp.getWidth(), bh = s.bmp.getHeight();
        int x = Math.max(0, Math.min(bw - 1, Math.round(r.left)));
        int y = Math.max(0, Math.min(bh - 1, Math.round(r.top)));
        int w = Math.max(1, Math.min(bw - x, Math.round(r.width())));
        int h = Math.max(1, Math.min(bh - y, Math.round(r.height())));
        if (w == bw && h == bh) return;
        pushHistory();
        Bitmap nb = Bitmap.createBitmap(s.bmp, x, y, w, h);
        float dx = (x + w / 2f - bw / 2f) * s.scale;
        float dy = (y + h / 2f - bh / 2f) * s.scale;
        double a = Math.toRadians(s.rot);
        s.cx += (float) (dx * Math.cos(a) - dy * Math.sin(a));
        s.cy += (float) (dx * Math.sin(a) + dy * Math.cos(a));
        s.bmp = nb;
        s.text = null;
        s.clamp(W, H);
        pageView.invalidate();
    }

    private void deleteSel() {
        Item s = selectedOrToast();
        if (s == null) return;
        pushHistory();
        pages.get(cur).items.remove(s);
        pageView.selected = null;
        pageView.invalidate();
    }

    private void addText() {
        textDialog("", text -> {
            if (text.trim().isEmpty()) return;
            pushHistory();
            Item it = new Item(makeTextBitmap(text));
            it.text = text;
            it.scale = 0.5f;
            it.cx = W / 2f;
            it.cy = H / 3f;
            it.clamp(W, H);
            pages.get(cur).items.add(it);
            pageView.selected = it;
            pageView.invalidate();
        });
    }

    private void editTextSel() {
        final Item s = selectedOrToast();
        if (s == null) return;
        if (s.text == null) {
            toast("هذا العنصر صورة وليس نصًا");
            return;
        }
        textDialog(s.text, text -> {
            if (text.trim().isEmpty()) return;
            pushHistory();
            s.text = text;
            s.bmp = makeTextBitmap(text);
            s.clamp(W, H);
            pageView.invalidate();
        });
    }

    private void textDialog(String initial, Consumer<String> onOk) {
        final EditText et = new EditText(this);
        et.setText(initial);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        FrameLayoutHelper.pad(et, dp(20));
        new AlertDialog.Builder(this)
                .setTitle("النص")
                .setView(et)
                .setPositiveButton("موافق", (d, w) -> onOk.accept(et.getText().toString()))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private Bitmap makeTextBitmap(String text) {
        TextPaint tp = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.BLACK);
        tp.setTextSize(48f);
        int width = 900;
        StaticLayout sl = StaticLayout.Builder.obtain(text, 0, text.length(), tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.2f)
                .build();
        Bitmap b = Bitmap.createBitmap(width, Math.max(1, sl.getHeight()), Bitmap.Config.ARGB_8888);
        sl.draw(new Canvas(b));
        return b;
    }

    // ================= استيراد الصور =================

    private void askLayout(List<Uri> uris) {
        if (uris.size() == 1) {
            importImages(uris, 1);
            return;
        }
        final int[] per = {1, 2, 4, 6};
        new AlertDialog.Builder(this)
                .setTitle("ترتيب الصور على الورقة")
                .setItems(new String[]{"صورة في كل صفحة", "صورتان في الصفحة",
                        "4 صور في الصفحة", "6 صور في الصفحة"},
                        (d, which) -> importImages(uris, per[which]))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void importImages(List<Uri> uris, int per) {
        showBusy("جارٍ تحميل الصور…");
        bg.execute(() -> {
            List<Bitmap> list = new ArrayList<>();
            for (Uri u : uris) {
                try {
                    Bitmap b = decodeImage(u);
                    if (b != null) list.add(b);
                } catch (Throwable ignored) {
                }
            }
            runOnUiThread(() -> {
                hideBusy();
                if (list.isEmpty()) {
                    toast("تعذر قراءة الصور");
                    return;
                }
                pushHistory();
                final int cols = per >= 4 ? 2 : 1;
                final int rows = (per + cols - 1) / cols;
                final float m = 20f;
                final float cw = (W - m * (cols + 1)) / cols;
                final float ch = (H - m * (rows + 1)) / rows;
                for (int i = 0; i < list.size(); i += per) {
                    DocPage target;
                    DocPage cp = pages.get(cur);
                    if (cp.items.isEmpty()) {
                        target = cp;
                    } else {
                        target = new DocPage();
                        pages.add(cur + 1, target);
                        cur++;
                    }
                    int end = Math.min(list.size(), i + per);
                    for (int k = i; k < end; k++) {
                        int idx = k - i;
                        int col = idx % cols;
                        int row = idx / cols;
                        Item it = new Item(list.get(k));
                        it.fitIn(m + col * (cw + m), m + row * (ch + m), cw, ch);
                        target.items.add(it);
                    }
                }
                showCurrent();
            });
        });
    }

    private Bitmap decodeImage(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2000) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bmp == null) return null;

        int ori = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                ExifInterface ex = new ExifInterface(in);
                ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
        }
        Matrix m = new Matrix();
        switch (ori) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            default:
                return bmp;
        }
        Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (r != bmp) bmp.recycle();
        return r;
    }

    // ================= استيراد PDF =================

    private void openPdf(Uri uri) {
        showBusy("جارٍ فتح الملف…");
        bg.execute(() -> {
            try {
                File f = new File(getCacheDir(), "import.pdf");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer r = new PdfRenderer(pfd);
                final int count = r.getPageCount();
                r.close();
                pfd.close();
                runOnUiThread(() -> {
                    hideBusy();
                    choosePdfPages(f, count);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideBusy();
                    toast("تعذر فتح ملف PDF (قد يكون محميًا بكلمة مرور)");
                });
            }
        });
    }

    private void choosePdfPages(File f, int count) {
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) labels[i] = "صفحة " + (i + 1);
        ListView lv = new ListView(this);
        lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, labels));
        for (int i = 0; i < count; i++) lv.setItemChecked(i, true);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("اختر الصفحات (" + count + ")")
                .setView(lv)
                .setPositiveButton("إضافة", (d, w) -> {
                    List<Integer> sel = new ArrayList<>();
                    for (int i = 0; i < count; i++) if (lv.isItemChecked(i)) sel.add(i);
                    if (sel.isEmpty()) {
                        toast("لم تختر أي صفحة");
                    } else {
                        importPdfPages(f, sel);
                    }
                })
                .setNeutralButton("الكل / لا شيء", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> toggleAll(lv, count));
    }

    private void toggleAll(ListView lv, int count) {
        boolean all = true;
        for (int i = 0; i < count; i++) if (!lv.isItemChecked(i)) all = false;
        for (int i = 0; i < count; i++) lv.setItemChecked(i, !all);
    }

    private void importPdfPages(File f, List<Integer> sel) {
        showBusy("جارٍ تحويل الصفحات…");
        bg.execute(() -> {
            List<Bitmap> bmps = new ArrayList<>();
            String error = null;
            float factor = sel.size() <= 10 ? 2.0f : (sel.size() <= 25 ? 1.5f : 1.2f);
            ParcelFileDescriptor pfd = null;
            PdfRenderer r = null;
            try {
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                r = new PdfRenderer(pfd);
                for (int idx : sel) {
                    PdfRenderer.Page pg = r.openPage(idx);
                    int w = Math.max(1, Math.round(pg.getWidth() * factor));
                    int h = Math.max(1, Math.round(pg.getHeight() * factor));
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    pg.close();
                    bmps.add(b);
                }
            } catch (Throwable t) {
                error = "تعذر تحويل بعض الصفحات (قد تكون الذاكرة غير كافية، جرّب عددًا أقل)";
            } finally {
                try {
                    if (r != null) r.close();
                    if (pfd != null) pfd.close();
                } catch (Exception ignored) {
                }
            }
            final String err = error;
            runOnUiThread(() -> {
                hideBusy();
                if (!bmps.isEmpty()) {
                    pushHistory();
                    for (Bitmap b : bmps) {
                        Item it = new Item(b);
                        it.fit(W, H, 0f);
                        insertItem(it);
                    }
                    showCurrent();
                }
                if (err != null) toast(err);
            });
        });
    }

    // ================= الطباعة والمشاركة =================

    private void showPagesDialog(boolean forShare) {
        final int n = pages.size();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            labels[i] = "صفحة " + (i + 1) + (pages.get(i).items.isEmpty() ? " (فارغة)" : "");
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final CheckBox bwBox = new CheckBox(this);
        bwBox.setText(forShare ? "أبيض وأسود" : "طباعة بالأبيض والأسود");
        final ListView lv = new ListView(this);
        lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, labels));
        for (int i = 0; i < n; i++) lv.setItemChecked(i, true);
        box.addView(bwBox);
        box.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(Math.min(280, 52 * n))));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(forShare ? "اختر الصفحات للـ PDF" : "اختر الصفحات للطباعة")
                .setView(box)
                .setPositiveButton(forShare ? "إنشاء PDF" : "متابعة للطباعة", (d, w) -> {
                    List<Integer> sel = new ArrayList<>();
                    for (int i = 0; i < n; i++) if (lv.isItemChecked(i)) sel.add(i);
                    if (sel.isEmpty()) {
                        toast("لم تختر أي صفحة");
                        return;
                    }
                    if (forShare) sharePdf(sel, bwBox.isChecked());
                    else printPages(sel, bwBox.isChecked());
                })
                .setNeutralButton("الكل / لا شيء", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> toggleAll(lv, n));
    }

    private List<DocPage> copyOf(List<Integer> idx) {
        List<DocPage> out = new ArrayList<>();
        for (int i : idx) out.add(pages.get(i).copy());
        return out;
    }

    private void printPages(List<Integer> idx, boolean bw) {
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (pm == null) {
            toast("خدمة الطباعة غير متاحة على هذا الجهاز");
            return;
        }
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();
        pm.print("مستند A4", new A4PrintAdapter(this, copyOf(idx), bw), attrs);
    }

    private void sharePdf(List<Integer> idx, boolean bw) {
        final List<DocPage> list = copyOf(idx);
        showBusy("جارٍ إنشاء PDF…");
        bg.execute(() -> {
            try {
                PdfDocument doc = new PdfDocument();
                File out;
                try {
                    for (int i = 0; i < list.size(); i++) {
                        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, 842, i + 1).create();
                        PdfDocument.Page pg = doc.startPage(info);
                        Renderer.drawFitted(pg.getCanvas(), list.get(i), 595f, 842f, bw);
                        doc.finishPage(pg);
                    }
                    File dir = new File(getCacheDir(), "pdfs");
                    if (!dir.exists()) dir.mkdirs();
                    out = new File(dir, "document.pdf");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        doc.writeTo(fos);
                    }
                } finally {
                    doc.close();
                }
                final File file = out;
                runOnUiThread(() -> {
                    hideBusy();
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/pdf");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "مشاركة ملف PDF"));
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    hideBusy();
                    toast("تعذر إنشاء PDF");
                });
            }
        });
    }

    // ================= أدوات =================

    private void showBusy(String msg) {
        hideBusy();
        busy = new AlertDialog.Builder(this).setMessage(msg).setCancelable(false).show();
    }

    private void hideBusy() {
        if (busy != null) {
            busy.dismiss();
            busy = null;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** مساعد صغير لإضافة حشوة حول الحقل داخل الحوار. */
    private static final class FrameLayoutHelper {
        static void pad(View v, int px) {
            v.setPadding(px, px, px, px);
        }
    }
}