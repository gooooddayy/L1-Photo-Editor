package com.l1.editor;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 导出：格式（PNG/JPG/WEBP/BMP/ICO）+ 尺寸预设 + 限 MB 降质循环 + 保存 */
public final class Exporter {
    /** 格式表（端侧版）：JPG 一项即可，去掉网页的 JPEG 重复项（同编码不同扩展名，端侧徒增困惑）；hasQ=有质量档 */
    public static final String[] FMT_KEYS = {"jpg", "png", "webp", "bmp", "ico"};
    public static final String[] FMT_LABELS = {"JPG", "PNG", "WEBP", "BMP（无损）", "ICO 图标"};
    public static final boolean[] FMT_HAS_Q = {true, false, true, false, false};

    /** 缩放到 W×H（contain-fit 居中，同网页 drawImage(img,x,y,w,h)）；非 PNG/ICO 填白底（网页坑：透明图导 JPG 不能黑底） */
    public static Bitmap render(Bitmap base, int W, int H, boolean keepAlpha) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (!keepAlpha) {
            Paint pw = new Paint();
            pw.setColor(0xFFFFFFFF);
            c.drawRect(0, 0, W, H, pw);
        }
        // ⚠️ 必须用 dstRect 重载：drawBitmap(bmp,left,top,paint) 是 1:1 原尺寸绘制（不缩放），
        //    曾导致非原始尺寸导出右下被裁（原始尺寸 k=1 掩盖了此 bug）
        float k = Math.min(W / (float) base.getWidth(), H / (float) base.getHeight());
        float dw = base.getWidth() * k, dh = base.getHeight() * k;
        android.graphics.RectF dst = new android.graphics.RectF((W - dw) / 2f, (H - dh) / 2f, (W + dw) / 2f, (H + dh) / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        c.drawBitmap(base, null, dst, p);
        return out;
    }

    /** 与导出完全一致的真实编码（仅用于最终导出；估算禁止调用——真机实测大图连续全尺寸渲染会 OOM） */
    public static byte[] encode(Bitmap base, String fmt, int W, int H, int quality) {
        boolean keepAlpha = fmt.equals("png") || fmt.equals("ico");
        Bitmap b = (W == base.getWidth() && H == base.getHeight() && (keepAlpha || !hasAlpha(base)))
            ? base : render(base, W, H, keepAlpha);
        try {
            if (fmt.equals("bmp")) return bmp24(b);
            if (fmt.equals("ico")) return ico(b);
            Bitmap.CompressFormat f = fmt.equals("png") ? Bitmap.CompressFormat.PNG
                : fmt.equals("webp") ? Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.JPEG;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!b.compress(f, quality, bos)) return null;
            return bos.toByteArray();
        } finally {
            if (b != base) b.recycle();
        }
    }

    /** 限 MB：最多 24 轮降质/降尺寸，网页 doExport 完全同语义（先降质量到 40%，再每轮缩 0.9）。outWH 回传最终输出宽高 */
    public static byte[] encodeLimited(Bitmap base, String fmt, int W, int H, int quality, double maxMB, int[] outWH) {
        double limit = maxMB > 0 ? maxMB * 1048576.0 : Double.POSITIVE_INFINITY;
        boolean hasQ = fmt.equals("jpg") || fmt.equals("jpeg") || fmt.equals("webp");
        int q = quality;
        double kk = fmt.equals("ico") && Math.max(W, H) > 256 ? 256.0 / Math.max(W, H) : 1;
        byte[] data = null;
        for (int round = 0; round < 24; round++) {
            int w = Math.max(16, (int) Math.round(W * kk)), h = Math.max(16, (int) Math.round(H * kk));
            data = encode(base, fmt, w, h, q);
            if (data == null) return null;
            if (outWH != null) { outWH[0] = w; outWH[1] = h; }
            if (data.length <= limit) return data;
            if (hasQ && q > 40) q = Math.max(40, q - 8); // 有质量档先降质量
            else kk *= 0.9;                              // 再缩尺寸
        }
        return data.length <= limit ? data : null; // 压不进限制 → 失败（网页同款提示交调用方）
    }

    /**
     * 估算：网页 estimateSize 完全同语义——目标尺寸全尺寸真实编码（PNG 压缩非线性，采样外推不准）。
     * 返回完整文件字节（同参数导出可直接复用）；OOM/失败返回 null（调用方降级 estimateProxy）。
     */
    public static byte[] estimateData(Bitmap base, String fmt, int W, int H, int quality) {
        try {
            return encode(base, fmt, W, H, quality); // ico() 已含 22 字节文件头，无需另加
        } catch (Throwable t) {
            return null;
        }
    }

    /** OOM 兜底：代理外推估算（仅显示用，结果不可导出复用）；BMP 精确公式 */
    public static long estimateProxy(Bitmap base, String fmt, int W, int H, int quality) {
        if (fmt.equals("bmp")) {
            int rowBytes = (W * 3 + 3) & ~3;
            return 54L + rowBytes * (long) H;
        }
        try {
            int cap = fmt.equals("ico") ? 256 : 480;
            double k = Math.min(1, cap / (float) Math.max(W, H));
            int pw = Math.max(16, (int) Math.round(W * k)), ph = Math.max(16, (int) Math.round(H * k));
            byte[] d = encode(base, fmt, pw, ph, quality);
            if (d == null) return -1;
            if (fmt.equals("ico") || k >= 1) return d.length;
            return Math.round(d.length / (k * k));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean hasAlpha(Bitmap b) {
        for (int y = 0; y < b.getHeight(); y += Math.max(1, b.getHeight() / 32))
            for (int x = 0; x < b.getWidth(); x += Math.max(1, b.getWidth() / 32))
                if ((b.getPixel(x, y) >>> 24) < 250) return true;
        return false;
    }

    /** 24-bit BMP 手写编码器（网页同款：BGR 底朝上 + 行 4 字节对齐填充） */
    public static byte[] bmp24(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int rowBytes = (w * 3 + 3) & ~3;
        int dataSize = rowBytes * h, fileSize = 54 + dataSize;
        ByteBuffer bb = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'B').put((byte) 'M');
        bb.putInt(fileSize).putShort((short) 0).putShort((short) 0).putInt(54);
        bb.putInt(40).putInt(w).putInt(h).putShort((short) 1).putShort((short) 24);
        bb.putInt(0).putInt(dataSize).putInt(2835).putInt(2835).putInt(0).putInt(0);
        int[] px = new int[w];
        for (int y = h - 1; y >= 0; y--) {
            b.getPixels(px, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = px[x];
                bb.put((byte) (c & 0xFF)).put((byte) ((c >> 8) & 0xFF)).put((byte) ((c >> 16) & 0xFF));
            }
            for (int p = w * 3; p < rowBytes; p++) bb.put((byte) 0);
        }
        return bb.array();
    }

    /** ICO = PNG 内嵌 ≤256 钳位（网页同款） */
    public static byte[] ico(Bitmap b) {
        int w = Math.min(b.getWidth(), 256), h = Math.min(b.getHeight(), 256);
        Bitmap s = (b.getWidth() <= 256 && b.getHeight() <= 256) ? b
            : Bitmap.createScaledBitmap(b, w, h, true);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.PNG, 100, png);
        if (s != b) s.recycle();
        int size = png.size();
        ByteBuffer bb = ByteBuffer.allocate(22 + size).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) 0).putShort((short) 1).putShort((short) 1); // ICONDIR: reserved/type/count
        bb.put((byte) (w >= 256 ? 0 : w)).put((byte) (h >= 256 ? 0 : h));
        bb.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
        bb.putInt(size).putInt(22);
        bb.put(png.toByteArray());
        return bb.array();
    }

    /** 保存到系统相册 Pictures/L1PhotoEditor，文件名网页同款 {原名}_edit_{W}x{H}.{ext} */
    public static Uri save(Context ctx, byte[] data, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, mime);
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/L1PhotoEditor");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) { os.write(data); }
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "L1PhotoEditor");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(data); }
            MediaStore.Images.Media.insertImage(ctx.getContentResolver(), f.getAbsolutePath(), name, null);
            return Uri.fromFile(f);
        }
    }
}
