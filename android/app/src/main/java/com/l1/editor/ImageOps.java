package com.l1.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

/** 几何操作：逆时针 90° 旋转 / 水平镜像 / 裁剪应用。全部返回新 Bitmap，调用方负责 recycle 旧图 */
public final class ImageOps {

    /** 逆时针 90°（网页坑5：非方形图方向必须实测；createBitmap 按变换自动定界，无 translate 公式坑） */
    public static Bitmap rotateCCW(Bitmap src) {
        Matrix m = new Matrix();
        m.postRotate(-90); // 正角=顺时针，-90=逆时针；(0,0)→(0,w) 即左上到左下 ✓
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public static Bitmap mirror(Bitmap src) {
        Matrix m = new Matrix();
        m.setScale(-1, 1);
        m.postTranslate(src.getWidth(), 0);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public static Bitmap crop(Bitmap src, int x, int y, int w, int h) {
        return Bitmap.createBitmap(src, x, y, w, h);
    }

    /** 文字合并到图（网页语义：size × base.width/800 随图缩放） */
    public static void mergeTexts(Bitmap base, EditorView.TextItem t) {
        Canvas c = new Canvas(base);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(t.color);
        p.setFakeBoldText(t.bold);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(t.size);
        c.drawText(t.content, t.x, t.y, p);
    }

    /** 真实字号换算：用户输入 size 按 800px 基准随图缩放 */
    public static float textImageSize(float size, Bitmap base) {
        return Math.max(12, Math.round(size * base.getWidth() / 800f));
    }
}
