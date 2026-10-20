package com.l1.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 画布视图：fitView 自适应缩放 + 平移缩放手势 + 各模式交互。
 * 踩坑映射：
 *  - 坑4 裁剪"原图"chip：ratio 存 float，0=自由，原图比例在设置 chip 时立即换算为数值（UI 与内部状态同步赋值）
 *  - 坑9 涂抹快速拖断线：沿线插值补圆（马赛克/消除同修）
 *  - 坑10 布局流：本 View 由 MainActivity 放在面板下方布局流内，面板展开/收起时调 requestFit() 重算
 */
public class EditorView extends View {
    public interface Listener {
        void onNeedsPushHistory();          // 破坏性操作前（马赛克笔起笔等）
        void onMaskChanged();               // mask 有新涂抹（更新 bbox）
        void onInfo(String msg);            // 调试/状态信息（一般不用）
        void onImportRequest();             // 空白处点击 = 请求导入
        void onTextSelectionChanged();      // 文字选中态变化（同步面板控件）
    }

    public static final int MODE_VIEW = 0, MODE_CROP = 1, MODE_MOSAIC = 2, MODE_MASK = 3, MODE_TEXT = 4;

    public Bitmap base;                  // 当前工作图（可变 ARGB_8888）
    private final Matrix viewM = new Matrix(), invM = new Matrix();
    private float viewScale = 1f;
    private boolean fitPending = true;

    // 手势
    private float lastX, lastY;
    private float pinchStartDist = 0, pinchStartScale = 1;
    private float pinchFocusX, pinchFocusY;
    private boolean pinching = false;

    // 裁剪（图坐标）
    public float[] crop;                // {x,y,w,h}，null=无
    public float cropRatio = 0;         // 0=自由，>0=w/h
    private int cropDragType = 0;       // 0=无 1=移动 2=调手柄 3=拖新框
    private String cropDragId;
    private float[] cropDragStart, cropDragP0;

    // 马赛克/消除
    public float brush = 40;            // 图像像素
    private boolean stroking = false;
    private float lastPX = Float.MIN_VALUE, lastPY;

    // 消除 mask（RGB_565，与 base 同尺寸）
    private Bitmap maskBmp;
    private Canvas maskCv;
    public float[] maskBB;              // {x,y,w,h} 增量缓存，null=无
    private final Path maskPath = new Path(); // 屏显蒙版轨迹（与 mask 同步累积，仅可视化）

    // 文字
    public static class TextItem {
        public String content; public float x, y, size; public int color; public boolean bold;
    }
    public final List<TextItem> texts = new ArrayList<>();
    public TextItem selectedText;       // 当前选中文字（虚框 + 面板实时编辑）
    private TextItem textDrag;
    private float textDragOffX, textDragOffY;

    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint pCrop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint();
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMaskView = new Paint(Paint.ANTI_ALIAS_FLAG); // 涂抹区可视化（半透明绿）
    private final Paint pSelDash = new Paint(Paint.ANTI_ALIAS_FLAG);  // 选中文字虚框
    private final Paint pMosaic = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mode = MODE_VIEW;

    private Listener listener;

    public EditorView(Context c) {
        super(c);
        pGrid.setColor(0x59FFFFFF);
        pGrid.setStrokeWidth(Util.dp(c, 1));
        pHandle.setColor(Color.WHITE);
        pMask.setColor(Color.WHITE);   // RGB_565 画布上画白 = 涂抹区（AI 读取绿通道判定）
        pMask.setStyle(Paint.Style.FILL);
        pMaskView.setColor(0x661FA75F); // 半透明主题绿
        pMaskView.setStyle(Paint.Style.FILL);
        pSelDash.setStyle(Paint.Style.STROKE);
        pSelDash.setColor(0xFF1FA75F);
        pSelDash.setStrokeWidth(Util.dp(c, 2));
        pSelDash.setPathEffect(new DashPathEffect(new float[]{Util.dp(c, 6), Util.dp(c, 4)}, 0));
        pText.setTextAlign(Paint.Align.CENTER);
        setFocusable(true);
    }

    public void setListener(Listener l) { listener = l; }
    public void setMode(int m) { mode = m; stroking = false; invalidate(); }
    public int getMode() { return mode; }

    /** 导入/替换 base（含裁剪后）。mask 同步重建清空（坑14：mask 失效点） */
    public void setBase(Bitmap b, boolean keepMask) {
        base = b;
        if (!keepMask) resetMask();
        fitPending = true;
        invalidate();
    }

    public void requestFit() { fitPending = true; invalidate(); }

    public void resetMask() {
        if (maskBmp != null) maskBmp.recycle();
        maskBmp = null; maskCv = null; maskBB = null;
        maskPath.rewind();
    }

    public Bitmap maskBitmap() {
        if (maskBmp == null && base != null) {
            // RGB_565：2字节/px（12MP=24MB），白色笔刷=0xFFFF，未涂=黑0；避开 ALPHA_8 缩放兼容坑
            maskBmp = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.RGB_565);
            maskCv = new Canvas(maskBmp);
        }
        return maskBmp;
    }

    // ================= 布局与变换 =================
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fitPending = true;
    }

    public void fitIfPending() {
        if (!fitPending || base == null || getWidth() == 0) return;
        fitPending = false;
        float s = Math.min(getWidth() / (float) base.getWidth(), getHeight() / (float) base.getHeight());
        viewScale = s;
        viewM.reset();
        viewM.postScale(s, s);
        viewM.postTranslate((getWidth() - base.getWidth() * s) / 2f, (getHeight() - base.getHeight() * s) / 2f);
        viewM.invert(invM);
    }

    // ================= 绘制 =================
    @Override protected void onDraw(Canvas c) {
        fitIfPending();
        c.drawColor(0xFFF2F5F1);
        if (base == null) { // 空状态：点击即导入
            float m = Util.dp(getContext(), 24);
            Paint pd = new Paint(Paint.ANTI_ALIAS_FLAG);
            pd.setStyle(Paint.Style.STROKE);
            pd.setColor(0x4D1FA75F);
            pd.setStrokeWidth(Util.dp(getContext(), 2));
            pd.setPathEffect(new DashPathEffect(new float[]{Util.dp(getContext(), 8), Util.dp(getContext(), 6)}, 0));
            c.drawRoundRect(m, m, getWidth() - m, getHeight() - m, Util.dp(getContext(), 16), Util.dp(getContext(), 16), pd);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF68786C); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(Util.dp(getContext(), 16));
            float cy = getHeight() / 2f;
            c.drawText("点击此处选择图片", getWidth() / 2f, cy - Util.dp(getContext(), 12), p);
            p.setTextSize(Util.dp(getContext(), 13));
            p.setColor(0xFF9AA89E);
            c.drawText("或点「导入」按钮", getWidth() / 2f, cy + Util.dp(getContext(), 14), p);
            return;
        }
        c.drawBitmap(base, viewM, pBmp);

        // 消除蒙版可视化：maskPath 存图像坐标，必须套 viewM 变换后绘制，否则与手指位置脱节
        if (mode == MODE_MASK && !maskPath.isEmpty()) {
            c.save();
            c.concat(viewM);
            c.drawPath(maskPath, pMaskView);
            c.restore();
        }

        if (mode == MODE_CROP && crop != null) {
            RectF r = mapRect(crop);
            pDim.setColor(0x8C000000);
            c.drawRect(0, 0, getWidth(), r.top, pDim);
            c.drawRect(0, r.bottom, getWidth(), getHeight(), pDim);
            c.drawRect(0, r.top, r.left, r.bottom, pDim);
            c.drawRect(r.right, r.top, getWidth(), r.bottom, pDim);
            pCrop.setStyle(Paint.Style.STROKE);
            pCrop.setColor(0xFF2FBF71);
            pCrop.setStrokeWidth(Util.dp(getContext(), 2));
            c.drawRect(r, pCrop);
            pGrid.setStyle(Paint.Style.STROKE);
            for (int i = 1; i <= 2; i++) {
                c.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, pGrid);
                c.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, pGrid);
            }
            float t = Util.dp(getContext(), 11);
            pHandle.setStyle(Paint.Style.FILL);
            for (float[] hd : cropHandlesScreen(r)) c.drawRect(hd[0] - t / 2, hd[1] - t / 2, hd[0] + t / 2, hd[1] + t / 2, pHandle);
        }
        if (mode == MODE_TEXT) {
            for (TextItem t : texts) drawText(c, t);
            if (selectedText != null) { // 选中态：虚线框
                RectF b = textScreenBounds(selectedText);
                c.drawRect(b, pSelDash);
            }
        }
    }

    /** 文字屏幕空间外接框（供虚框与命中测试共用） */
    private RectF textScreenBounds(TextItem t) {
        float[] pt = { t.x, t.y };
        viewM.mapPoints(pt);
        pText.setFakeBoldText(t.bold);
        pText.setTextSize(t.size * viewScale);
        float w = pText.measureText(t.content);
        Paint.FontMetrics fm = pText.getFontMetrics();
        float pad = Util.dp(getContext(), 6);
        return new RectF(pt[0] - w / 2 - pad, pt[1] + fm.ascent - pad, pt[0] + w / 2 + pad, pt[1] + fm.descent + pad);
    }

    private void drawText(Canvas c, TextItem t) {
        pText.setColor(t.color);
        pText.setFakeBoldText(t.bold);
        pText.setTextSize(t.size * viewScale); // 字号存图坐标，按视图缩放映射到屏幕
        // 文字存图坐标，直接用 viewM 映射位置绘制
        float[] pt = { t.x, t.y };
        viewM.mapPoints(pt);
        c.drawText(t.content, pt[0], pt[1], pText);
    }

    private RectF mapRect(float[] r) {
        float[] pts = { r[0], r[1], r[0] + r[2], r[1] + r[3] };
        viewM.mapPoints(pts);
        return new RectF(pts[0], pts[1], pts[2], pts[3]);
    }

    private float[][] cropHandlesScreen(RectF r) {
        return new float[][]{
            {r.left, r.top}, {r.centerX(), r.top}, {r.right, r.top},
            {r.right, r.centerY()}, {r.right, r.bottom}, {r.centerX(), r.bottom},
            {r.left, r.bottom}, {r.left, r.centerY()}
        };
    }

    private static final String[] HANDLE_IDS = {"nw", "n", "ne", "e", "se", "s", "sw", "w"};

    // ================= 触摸 =================
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (base == null) { // 空状态：点击任意处 = 导入
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && listener != null) listener.onImportRequest();
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) { // 双指进入：结束任何单指操作，进入捏合
                    stroking = false; cropDragType = 0; textDrag = null;
                    pinchStartDist = dist(e);
                    pinchStartScale = viewScale;
                    pinchFocusX = (e.getX(0) + e.getX(1)) / 2;
                    pinchFocusY = (e.getY(0) + e.getY(1)) / 2;
                    pinching = true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching && e.getPointerCount() >= 2) {
                    float d = dist(e);
                    float ns = Math.max(0.05f, Math.min(16f, pinchStartScale * d / pinchStartDist));
                    float fx = (e.getX(0) + e.getX(1)) / 2, fy = (e.getY(0) + e.getY(1)) / 2;
                    scaleAround(ns, fx, fy);
                    return true;
                }
                if (e.getPointerCount() > 1) return true;
                onMove(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_DOWN:
                pinching = false;
                lastX = e.getX(); lastY = e.getY();
                onDown(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                onUp();
                return true;
        }
        return true;
    }

    private float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void scaleAround(float newScale, float fx, float fy) {
        // 让 fx,fy 在缩放后仍在原位
        viewM.invert(invM);
        float[] imgPt = { fx, fy };
        invM.mapPoints(imgPt);
        viewScale = newScale;
        viewM.reset();
        viewM.postScale(newScale, newScale);
        float[] np = { imgPt[0] * newScale, imgPt[1] * newScale };
        viewM.postTranslate(fx - np[0], fy - np[1]);
        viewM.invert(invM);
        invalidate();
    }

    private void onDown(float sx, float sy) {
        viewM.invert(invM);
        float[] p = { sx, sy };
        invM.mapPoints(p);
        float[] ip = imageToImg(p[0], p[1]);

        if (mode == MODE_CROP) {
            if (crop == null) { // 图上任意拖 = 拖新框
                cropDragType = 3;
                cropDragP0 = new float[]{ ip[0], ip[1] };
                crop = new float[]{ ip[0], ip[1], 1, 1 };
                return;
            }
            RectF r = mapRect(crop);
            float t = Util.dp(getContext(), 22); // 触屏热区（宽于显示手柄）
            float[][] hds = cropHandlesScreen(r);
            for (int i = 0; i < hds.length; i++) {
                if (Math.abs(sx - hds[i][0]) < t && Math.abs(sy - hds[i][1]) < t) {
                    cropDragType = 2; cropDragId = HANDLE_IDS[i];
                    cropDragStart = crop.clone();
                    return;
                }
            }
            if (r.contains(sx, sy)) {
                cropDragType = 1; cropDragStart = crop.clone();
                cropDragP0 = new float[]{ ip[0], ip[1] }; // 移动锚点=按下位置
            } else { cropDragType = 3; cropDragP0 = new float[]{ ip[0], ip[1] }; }
        } else if (mode == MODE_MOSAIC) {
            if (listener != null) listener.onNeedsPushHistory();
            stroking = true;
            lastPX = Float.MIN_VALUE;
            mosaicStroke(ip[0], ip[1]);
        } else if (mode == MODE_MASK) {
            if (listener != null) listener.onNeedsPushHistory();
            stroking = true;
            lastPX = Float.MIN_VALUE;
            maskStroke(ip[0], ip[1]);
        } else if (mode == MODE_TEXT) {
            TextItem hit = hitText(ip[0], ip[1]);
            TextItem prev = selectedText;
            selectedText = hit;
            if (hit != null) { textDrag = hit; textDragOffX = ip[0] - hit.x; textDragOffY = ip[1] - hit.y; }
            if (prev != selectedText && listener != null) listener.onTextSelectionChanged();
            invalidate();
        } else { // view：单指平移
            cropDragType = 9; // 复用标记：pan
        }
    }

    private void onMove(float sx, float sy) {
        viewM.invert(invM);
        float[] p = { sx, sy };
        invM.mapPoints(p);
        float[] ip = imageToImg(p[0], p[1]);

        if (cropDragType == 9) { // pan
            viewM.postTranslate(sx - lastX, sy - lastY);
            viewM.invert(invM);
            lastX = sx; lastY = sy;
            invalidate();
            return;
        }
        if (mode == MODE_CROP && cropDragType != 0) {
            if (cropDragType == 1) {
                float dx = ip[0] - cropDragP0[0], dy = ip[1] - cropDragP0[1];
                crop[0] = cropDragStart[0] + dx; crop[1] = cropDragStart[1] + dy;
                clampRect();
                if (cropRatio > 0) {
                    crop[2] = cropDragStart[2]; crop[3] = cropDragStart[3];
                    crop[0] = Math.max(0, Math.min(crop[0], base.getWidth() - crop[2]));
                    crop[1] = Math.max(0, Math.min(crop[1], base.getHeight() - crop[3]));
                }
            } else if (cropDragType == 2) {
                resizeCrop(ip[0], ip[1]);
            } else if (cropDragType == 3) {
                float w0 = Math.abs(ip[0] - cropDragP0[0]), h0 = Math.abs(ip[1] - cropDragP0[1]);
                float w = w0, h = h0;
                if (cropRatio > 0) { w = Math.max(w0, h0 * cropRatio); h = w / cropRatio; }
                float x = ip[0] < cropDragP0[0] ? cropDragP0[0] - w : cropDragP0[0];
                float y = ip[1] < cropDragP0[1] ? cropDragP0[1] - h : cropDragP0[1];
                crop = new float[]{ x, y, w, h };
                clampRect();
            }
            invalidate();
        } else if (mode == MODE_MOSAIC && stroking) {
            mosaicStroke(ip[0], ip[1]);
        } else if (mode == MODE_MASK && stroking) {
            maskStroke(ip[0], ip[1]);
        } else if (mode == MODE_TEXT && textDrag != null) {
            textDrag.x = ip[0] - textDragOffX;
            textDrag.y = ip[1] - textDragOffY;
            invalidate();
        }
    }

    private void onUp() {
        stroking = false; cropDragType = 0; textDrag = null;
    }

    /** 屏幕像素 → 图像像素（含捏合缩放后的逆映射已完成，此处只做一致性兜底） */
    private float[] imageToImg(float x, float y) { return new float[]{ x, y }; }

    // ================= 裁剪（移植网页 129,360 测例算法） =================
    private void clampRect() {
        crop[0] = Math.max(0, Math.min(crop[0], base.getWidth() - 8));
        crop[1] = Math.max(0, Math.min(crop[1], base.getHeight() - 8));
        crop[2] = Math.min(crop[2], base.getWidth() - crop[0]);
        crop[3] = Math.min(crop[3], base.getHeight() - crop[1]);
        crop[2] = Math.max(crop[2], 8); crop[3] = Math.max(crop[3], 8);
    }

    /** 网页 fitRatioRect：按比例以中心适配出初始框 */
    public float[] fitRatioRect(float x, float y, float w, float h) {
        if (cropRatio <= 0) return new float[]{ x, y, w, h };
        float iw = base.getWidth(), ih = base.getHeight();
        float nw = w, nh = nw / cropRatio;
        if (nh > ih) { nh = ih; nw = nh * cropRatio; }
        float cx = x + w / 2, cy = y + h / 2;
        float nx = Math.max(0, Math.min(cx - nw / 2, iw - nw));
        float ny = Math.max(0, Math.min(cy - nh / 2, ih - nh));
        return new float[]{ nx, ny, nw, nh };
    }

    private void resizeCrop(float px, float py) {
        String id = cropDragId;
        String H = id.contains("w") ? "w" : (id.contains("e") ? "e" : "");
        String V = id.contains("n") ? "n" : (id.contains("s") ? "s" : "");
        float[] s = cropDragStart;
        float ax = H.equals("w") ? s[0] + s[2] : H.equals("e") ? s[0] : s[0] + s[2] / 2;
        float ay = V.equals("n") ? s[1] + s[3] : V.equals("s") ? s[1] : s[1] + s[3] / 2;
        float iw = base.getWidth(), ih = base.getHeight();
        float[] r;
        if (cropRatio <= 0) {
            float l = s[0], t = s[1], rr = s[0] + s[2], b = s[1] + s[3];
            if (H.equals("w")) l = Math.min(px, rr - 24);
            if (H.equals("e")) rr = Math.max(px, l + 24);
            if (V.equals("n")) t = Math.min(py, b - 24);
            if (V.equals("s")) b = Math.max(py, t + 24);
            l = Math.max(0, l); t = Math.max(0, t);
            rr = Math.min(iw, rr); b = Math.min(ih, b);
            if (rr - l < 24) l = rr - 24;
            if (b - t < 24) t = b - 24;
            r = new float[]{ l, t, rr - l, b - t };
        } else if (H.isEmpty()) { // 只拖上下边：宽以中心对称伸展
            float maxH = Math.min(V.equals("n") ? ay : ih - ay, iw / cropRatio);
            float h = clamp(Math.abs(py - ay), Math.min(24, maxH), maxH);
            float w = h * cropRatio;
            r = new float[]{ clamp(ax - w / 2, 0, iw - w), V.equals("n") ? ay - h : ay, w, h };
        } else { // 角/左右边：宽随指针高等比（锚点=对边/对角）
            float maxH = V.equals("n") ? ay : (V.equals("s") ? ih - ay : ih);
            float maxW = Math.min(H.equals("w") ? ax : iw - ax, maxH * cropRatio);
            float w = clamp(Math.abs(px - ax), Math.min(24, maxW), maxW);
            float h = w / cropRatio;
            float rx = H.equals("w") ? ax - w : ax;
            float ry = V.equals("n") ? ay - h : (V.equals("s") ? ay : ay - h / 2);
            if (V.isEmpty()) ry = clamp(ry, 0, ih - h); // 左右边垂直居中伸出
            r = new float[]{ rx, ry, w, h };
        }
        crop = r;
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(v, b)); }

    // ================= 马赛克（网页 pixelateCircle 移植） =================
    private void mosaicStroke(float px, float py) {
        // 沿线插值补采样：快速拖不断线（坑9）
        float step = Math.max(2, brush / 4);
        if (lastPX == Float.MIN_VALUE) {
            pixelateCircle(px, py);
        } else {
            float d = (float) Math.hypot(px - lastPX, py - lastPY);
            int n = (int) Math.ceil(d / step);
            for (int i = 1; i <= n; i++)
                pixelateCircle(lastPX + (px - lastPX) * i / n, lastPY + (py - lastPY) * i / n);
        }
        lastPX = px; lastPY = py;
        invalidate();
    }

    private void pixelateCircle(float cx, float cy) {
        float r = brush / 2;
        int x0 = Math.max(0, (int) Math.floor(cx - r)), y0 = Math.max(0, (int) Math.floor(cy - r));
        int x1 = Math.min(base.getWidth(), (int) Math.ceil(cx + r)), y1 = Math.min(base.getHeight(), (int) Math.ceil(cy + r));
        if (x1 <= x0 || y1 <= y0) return;
        int bw = x1 - x0, bh = y1 - y0;
        int block = Math.max(4, Math.round(brush / 3));
        int cols = Math.max(1, Math.round(bw / (float) block)), rows = Math.max(1, Math.round(bh / (float) block));
        Bitmap small = Bitmap.createScaledBitmap(Bitmap.createBitmap(base, x0, y0, bw, bh), cols, rows, true);
        Canvas bc = new Canvas(base);
        bc.save();
        bc.clipPath(circlePath(cx, cy, r));
        Paint nz = new Paint(pMosaic);
        nz.setFilterBitmap(false);
        bc.drawBitmap(Bitmap.createScaledBitmap(small, bw, bh, false), x0, y0, nz);
        bc.restore();
        if (small != base) small.recycle();
    }

    private android.graphics.Path circlePath(float cx, float cy, float r) {
        android.graphics.Path p = new android.graphics.Path();
        p.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
        return p;
    }

    // ================= 消除 mask 涂抹 =================
    private void maskStroke(float px, float py) {
        float r = brush / 2;
        float step = Math.max(2, brush / 4);
        if (lastPX == Float.MIN_VALUE) {
            stampMaskCircle(px, py, r);
        } else {
            float d = (float) Math.hypot(px - lastPX, py - lastPY);
            int n = (int) Math.ceil(d / step);
            for (int i = 1; i <= n; i++)
                stampMaskCircle(lastPX + (px - lastPX) * i / n, lastPY + (py - lastPY) * i / n, r);
        }
        lastPX = px; lastPY = py;
        invalidate();
    }

    private void stampMaskCircle(float cx, float cy, float r) {
        Bitmap m = maskBitmap();
        if (m == null) return;
        maskCv.drawCircle(cx, cy, r, pMask);
        maskPath.addCircle(cx, cy, r, Path.Direction.CW); // 屏显轨迹同步
        // maskBB 增量扩展（网页 v14 同款：圆外接框与旧 bbox 取并集）
        if (maskBB == null) {
            maskBB = new float[]{ Math.max(0, cx - r), Math.max(0, cy - r),
                Math.min(base.getWidth(), cx + r) - Math.max(0, cx - r),
                Math.min(base.getHeight(), cy + r) - Math.max(0, cy - r) };
        } else {
            float nx0 = Math.min(maskBB[0], cx - r), ny0 = Math.min(maskBB[1], cy - r);
            float nx1 = Math.max(maskBB[0] + maskBB[2], cx + r), ny1 = Math.max(maskBB[1] + maskBB[3], cy + r);
            nx0 = Math.max(0, nx0); ny0 = Math.max(0, ny0);
            nx1 = Math.min(base.getWidth(), nx1); ny1 = Math.min(base.getHeight(), ny1);
            maskBB = new float[]{ nx0, ny0, nx1 - nx0, ny1 - ny0 };
        }
        if (listener != null) listener.onMaskChanged();
    }

    // ================= 文字 =================
    private TextItem hitText(float x, float y) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            TextItem t = texts.get(i);
            pText.setFakeBoldText(t.bold);
            pText.setTextSize(t.size);
            float wHalf = pText.measureText(t.content) / 2 + 20;
            Paint.FontMetrics fm = pText.getFontMetrics();
            if (Math.abs(x - t.x) < wHalf && y > t.y + fm.ascent - 20 && y < t.y + fm.descent + 20) return t;
        }
        return null;
    }

    public void addText(TextItem t) { texts.add(t); selectedText = t; invalidate(); }
    public void clearTexts() { texts.clear(); selectedText = null; invalidate(); }

    /** 删除选中文字 */
    public void removeSelected() {
        if (selectedText != null) { texts.remove(selectedText); selectedText = null; invalidate(); }
    }
}
