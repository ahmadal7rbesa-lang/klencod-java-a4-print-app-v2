package com.klencod.a4editor;

import android.content.Context;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.pdf.PrintedPdfDocument;

import java.io.FileOutputStream;
import java.util.List;

/** يرسل الصفحات المختارة إلى خدمة الطباعة في أندرويد (A4). */
class A4PrintAdapter extends PrintDocumentAdapter {

    private final Context ctx;
    private final List<DocPage> pages;
    private final boolean bw;
    private PrintedPdfDocument pdf;

    A4PrintAdapter(Context ctx, List<DocPage> pages, boolean bw) {
        this.ctx = ctx;
        this.pages = pages;
        this.bw = bw;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                         CancellationSignal cancellationSignal, LayoutResultCallback callback,
                         Bundle extras) {
        if (pdf != null) {
            pdf.close();
        }
        pdf = new PrintedPdfDocument(ctx, newAttributes);
        if (cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        PrintDocumentInfo info = new PrintDocumentInfo.Builder("a4-document.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages.size())
                .build();
        callback.onLayoutFinished(info, true);
    }

    @Override
    public void onWrite(PageRange[] range, ParcelFileDescriptor destination,
                        CancellationSignal cancellationSignal, WriteResultCallback callback) {
        try {
            for (int i = 0; i < pages.size(); i++) {
                if (!inRange(range, i)) continue;
                if (cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                    return;
                }
                PdfDocument.Page pg = pdf.startPage(i);
                Renderer.drawFitted(pg.getCanvas(), pages.get(i),
                        pg.getInfo().getPageWidth(), pg.getInfo().getPageHeight(), bw);
                pdf.finishPage(pg);
            }
            try (FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                pdf.writeTo(out);
            }
            callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
        } catch (Exception e) {
            callback.onWriteFailed(String.valueOf(e));
        }
    }

    @Override
    public void onFinish() {
        if (pdf != null) {
            pdf.close();
            pdf = null;
        }
    }

    private static boolean inRange(PageRange[] ranges, int index) {
        if (ranges == null) return true;
        for (PageRange r : ranges) {
            if (index >= r.getStart() && index <= r.getEnd()) return true;
        }
        return false;
    }
}
