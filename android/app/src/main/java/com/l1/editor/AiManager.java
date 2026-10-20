package com.l1.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * AI 模块：抠图（SILUETA）+ 移除（MI-GAN）。
 * 管线逐字节复刻网页版（预处理常数/通道序/回贴语义），网页踩坑全部吸收：
 *  - 坑1 MI-GAN mask：0=待修补(hole)、255=保留（alpha>40 → 0）
 *  - u2net 系输入固定 320×320（坑24），不可降分辨率
 *  - 回贴用 DST_IN 合成（= 网页 destination-in，坑20），绝不全尺寸像素循环
 *  - 抠图单模型 SILUETA（用户决策：u2netp 轻量兜底已移除，低内存失败直接报错）
 *  - arena off：阶段0真机实测同速度但峰值内存 +14MB vs +132MB
 *  - MI-GAN 输入/输出均 UINT8：createTensor 必须显式 OnnxJavaType.UINT8（Java byte 有符号）
 */
public final class AiManager {
    public interface Progress { void on(String msg); }

    private static OrtSession siluetaSess, miganSess;

    /** 从 assets 解压模型到 filesDir（首次），然后建会话缓存 */
    private static OrtSession session(Context ctx, String asset, boolean arena) throws Exception {
        File f = new File(ctx.getFilesDir(), asset);
        if (!f.exists() || f.length() < 1024) {
            try (InputStream in = ctx.getAssets().open(asset);
                 FileOutputStream fo = new FileOutputStream(f)) {
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setCPUArenaAllocator(arena);
        return env.createSession(f.getAbsolutePath(), so);
    }

    /** 抠图（SILUETA 高画质）。失败（含 OOM）由调用方提示 */
    public static void matting(Context ctx, Bitmap base, Progress prog) throws Exception {
        prog.on("加载推理模型…");
        if (siluetaSess == null) siluetaSess = session(ctx, "silueta.onnx", false);
        OrtSession sess = siluetaSess;
        prog.on("正在抠图…");
        final int S = 320; // u2net 系固定输入（坑24）
        Bitmap small = Bitmap.createScaledBitmap(base, S, S, true);
        int[] px = new int[S * S];
        small.getPixels(px, 0, S, 0, 0, S, S);
        if (small != base) small.recycle();
        float[] inp = new float[3 * S * S];
        float[] mean = {0.485f, 0.456f, 0.406f}, std = {0.229f, 0.224f, 0.225f};
        for (int i = 0; i < S * S; i++) {
            int c = px[i];
            inp[i] = (((c >> 16) & 0xFF) / 255f - mean[0]) / std[0];
            inp[S * S + i] = (((c >> 8) & 0xFF) / 255f - mean[1]) / std[1];
            inp[2 * S * S + i] = ((c & 0xFF) / 255f - mean[2]) / std[2];
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        String inName = sess.getInputNames().iterator().next();
        Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
        feeds.put(inName, OnnxTensor.createTensor(env, FloatBuffer.wrap(inp), new long[]{1, 3, S, S}));
        OrtSession.Result out;
        try {
            out = sess.run(feeds);
        } catch (Throwable t) { // 真实失败检测（ORT OOM 等）：交给调用方提示切换轻量模型
            feeds.get(inName).close();
            throw new RuntimeException("推理失败：" + t.getMessage(), t);
        }
        java.nio.FloatBuffer fb = ((OnnxTensor) out.get(0)).getFloatBuffer(); // CHW 平铺
        float[] pred = new float[S * S];
        fb.get(pred);
        out.close();
        feeds.get(inName).close();

        // min-max 归一化 → 320×320 alpha 图（网页同款）
        float ma = -1e9f, mi = 1e9f;
        for (float v : pred) { if (v > ma) ma = v; if (v < mi) mi = v; }
        float rng = (ma - mi) > 0 ? (ma - mi) : 1;
        Bitmap mSmall = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        int[] ap = new int[S * S];
        for (int i = 0; i < S * S; i++) {
            int a = Math.round((pred[i] - mi) / rng * 255);
            ap[i] = (a << 24) | 0x00FFFFFF;
        }
        mSmall.setPixels(ap, 0, S, 0, 0, S, S);
        // DST_IN 回贴：base 不透明时 alpha_out = mask_alpha（与网页 destination-in 数学等价，零像素循环）
        Bitmap alphaFull = Bitmap.createScaledBitmap(mSmall, base.getWidth(), base.getHeight(), true);
        mSmall.recycle();
        Canvas bc = new Canvas(base);
        Paint pm = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        pm.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        bc.drawBitmap(alphaFull, 0, 0, pm);
        pm.setXfermode(null);
        alphaFull.recycle();
    }

    /** 移除。mask 为 RGB_565（白=涂抹待移除），maskBB={x,y,w,h} 涂抹外接框 */
    public static void erase(Context ctx, Bitmap base, Bitmap mask, float[] bb, Progress prog) throws Exception {
        prog.on("加载移除模型…");
        if (miganSess == null) miganSess = session(ctx, "migan.onnx", false);
        OrtSession sess = miganSess;

        float pad = Math.max(bb[2], bb[3]) * 0.35f + 24;
        int x0 = Math.max(0, Math.round(bb[0] - pad)), y0 = Math.max(0, Math.round(bb[1] - pad));
        int x1 = Math.min(base.getWidth(), Math.round(bb[0] + bb[2] + pad));
        int y1 = Math.min(base.getHeight(), Math.round(bb[1] + bb[3] + pad));
        int w = x1 - x0, h = y1 - y0;
        float k = Math.min(1, 512f / Math.max(w, h)); // MI-GAN 512 训练
        int tw = Math.max(16, Math.round(w * k)), th = Math.max(16, Math.round(h * k));

        prog.on("正在移除…");
        // 图像 ROI → tw×th
        Bitmap roi = Bitmap.createBitmap(base, x0, y0, w, h);
        Bitmap roiS = Bitmap.createScaledBitmap(roi, tw, th, true);
        roi.recycle();
        int[] rp = new int[tw * th];
        roiS.getPixels(rp, 0, tw, 0, 0, tw, th);
        byte[] imgT = new byte[3 * tw * th];
        for (int i = 0; i < tw * th; i++) {
            int c = rp[i];
            imgT[i] = (byte) ((c >> 16) & 0xFF);
            imgT[tw * th + i] = (byte) ((c >> 8) & 0xFF);
            imgT[2 * tw * th + i] = (byte) (c & 0xFF);
        }
        // mask ROI → tw×th；坑1：绿通道>40 → 0(hole)，否则 255(keep)（mask 为 RGB_565，白=0xFFFF）
        Bitmap mRoi = Bitmap.createBitmap(mask, x0, y0, w, h);
        Bitmap mS = Bitmap.createScaledBitmap(mRoi, tw, th, true);
        mRoi.recycle();
        int[] mp = new int[tw * th];
        mS.getPixels(mp, 0, tw, 0, 0, tw, th);
        mS.recycle();
        byte[] maskT = new byte[tw * th];
        for (int i = 0; i < tw * th; i++) maskT[i] = (byte) ((((mp[i] >> 8) & 0xFF) > 40) ? 0 : 255);

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
        // 具名输入（模型 introspect 确认：image/mask 均 UINT8，输出 result 亦 UINT8）
        // ⚠️ Java byte[] 有符号，必须显式指定 OnnxJavaType.UINT8，否则按默认 INT8 喂入被 ORT 拒绝
        feeds.put("image", OnnxTensor.createTensor(env, java.nio.ByteBuffer.wrap(imgT), new long[]{1, 3, th, tw}, OnnxJavaType.UINT8));
        feeds.put("mask", OnnxTensor.createTensor(env, java.nio.ByteBuffer.wrap(maskT), new long[]{1, 1, th, tw}, OnnxJavaType.UINT8));
        OrtSession.Result out;
        try {
            out = sess.run(feeds);
        } catch (Throwable t) {
            throw new RuntimeException("推理失败：" + t.getMessage(), t);
        }
        // ⚠️ 输出是 UINT8：必须用 getByteBuffer（getFloatBuffer 会直接抛类型异常 = 网页 TypedArray 对应物）
        java.nio.ByteBuffer ob = ((OnnxTensor) out.get(0)).getByteBuffer();
        int[] op = new int[tw * th];
        for (int i = 0; i < tw * th; i++) {
            int r = ob.get(i) & 0xFF, g = ob.get(tw * th + i) & 0xFF, b = ob.get(2 * tw * th + i) & 0xFF;
            op[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        out.close();
        feeds.get("image").close();
        feeds.get("mask").close();
        Bitmap oBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        oBmp.setPixels(op, 0, tw, 0, 0, tw, th);
        Bitmap oFull = Bitmap.createScaledBitmap(oBmp, w, h, true);
        oBmp.recycle();
        new Canvas(base).drawBitmap(oFull, x0, y0, new Paint(Paint.FILTER_BITMAP_FLAG));
        oFull.recycle();
    }

    public static void shutdown() {
        try { if (siluetaSess != null) siluetaSess.close(); } catch (Exception ignored) {}
        try { if (miganSess != null) miganSess.close(); } catch (Exception ignored) {}
    }
}
