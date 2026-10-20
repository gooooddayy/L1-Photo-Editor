package com.l1.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements EditorView.Listener {
    private static final int REQ_PICK = 1;

    // ===== 主题（网页版同款绿 + 半透明质感） =====
    private static final int C_BG = 0xFFF2F5F1;
    private static final int C_PANEL = 0xC7FFFFFF;   // rgba(255,255,255,.78)
    private static final int C_LINE = 0x1F1C3A26;    // rgba(28,58,38,.12)
    private static final int C_TXT = 0xFF1D2620;
    private static final int C_ACC = 0xFF1FA75F;
    private static final int C_ACC2 = 0xFF35C26E;
    private static final int C_ON = 0xFF1FA75F;      // mode-on 选中态

    private EditorView view;
    private final UndoManager undo = new UndoManager();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private Uri lastUri; // 供「还原原图」重新解码（不占内存）
    private String importedName = "image"; // 导入文件名（去扩展名），供导出命名

    private LinearLayout panelBox;
    private TextView progressText;
    private LinearLayout progressBox;
    private ProgressBar progressBar;

    // 行1（全局操作）：导入图片 / 撤销 / 还原原图 / 导出
    // 行2（编辑模式）：裁剪 / 旋转 / 镜像 / 马赛克 / 文字 / 抠图 / 移除
    private TextView btnImport, btnUndo, btnReset, btnExport;
    private TextView bCrop, bRotate, bMirror, bMosaic, bText, bMatting, bErase;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getPreferences(MODE_PRIVATE);
        buildUi();
    }

    /**
     * UI 构建（onCreate 与配置变化重建共用）：屏幕形态/密度/字号变化时重跑，
     * dp/sp 按新参数重解析；EditorView 实例复用（图/文字/撤销/模式状态全保留）。
     * 适配：窄屏(<360dp)缩字号；横屏/折叠态(<480dp 高)压缩按钮高度；平板(>480dp 宽)限宽居中。
     */
    private void buildUi() {
        if (view != null) exitModes(); // 重建后粘性选中态与内部模式同步重置（坑3）
        // 配置变化时主动关面板：Dialog 窗口按旧屏幕尺寸定位，旋转/折叠后必然错位，关闭是可预期行为
        if (exportDialog != null && exportDialog.isShowing()) exportDialog.dismiss();
        if (pickerDialog != null && pickerDialog.isShowing()) pickerDialog.dismiss();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int widthDp = Math.round(dm.widthPixels / dm.density);
        boolean narrow = widthDp < 360;                            // 小屏手机
        boolean compact = dm.heightPixels / dm.density < 480;      // 横屏 / 折叠展开态
        int sidePad = widthDp > 480 ? Math.round((dm.widthPixels - 480 * dm.density) / 2f) : d(8); // 平板限宽居中

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        int pad = d(8);

        // ===== 行1：全局操作 =====
        LinearLayout row1 = new LinearLayout(this);
        row1.setPadding(sidePad, pad, sidePad, d(2));
        btnImport = toolBtn("导入", R.drawable.ic_import, true, v -> pickImage());
        btnUndo = toolBtn("撤销", R.drawable.ic_undo, false, v -> doUndo());
        btnReset = toolBtn("还原", R.drawable.ic_history, false, v -> doReset());
        btnExport = toolBtn("导出", R.drawable.ic_export, false, v -> openExport());
        TextView[] row1b = {btnImport, btnUndo, btnReset, btnExport};
        for (TextView t : row1b) {
            t.setPadding(d(10), compact ? d(4) : d(9), d(10), compact ? d(4) : d(9));
            row1.addView(t, row(1));
        }
        root.addView(row1);

        // ===== 行2：编辑模式 =====
        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(sidePad, d(2), sidePad, pad);
        bCrop = toolBtn("裁剪", R.drawable.ic_crop, false, v -> toggleSticky("crop", () -> enterCrop()));
        bRotate = toolBtn("旋转", R.drawable.ic_rotate, false, v -> applyGeo(false));
        bMirror = toolBtn("镜像", R.drawable.ic_mirror, false, v -> applyGeo(true));
        bMosaic = toolBtn("马赛克", R.drawable.ic_mosaic, false, v -> toggleSticky("mosaic", () -> setPanelMode(EditorView.MODE_MOSAIC)));
        bText = toolBtn("文字", R.drawable.ic_text, false, v -> toggleSticky("text", () -> setPanelMode(EditorView.MODE_TEXT)));
        bMatting = toolBtn("抠图", R.drawable.ic_matting, false, v -> runMatting());
        bErase = toolBtn("移除", R.drawable.ic_erase, false, v -> toggleSticky("erase", () -> enterRemove()));
        TextView[] row2b = {bCrop, bRotate, bMirror, bMosaic, bText, bMatting, bErase};
        for (TextView t : row2b) {
            t.setTextSize(narrow ? 10 : 11); // 小屏缩字号防挤压
            t.setPadding(d(2), compact ? d(3) : d(6), d(2), compact ? d(3) : d(6));
            row2.addView(t, row(1));
        }
        root.addView(row2);

        // ===== 上下文面板（布局流，不遮挡图片） =====
        panelBox = new LinearLayout(this);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        panelBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(sidePad, d(2), sidePad, d(4));
        root.addView(panelBox, plp);

        // ===== 画布区（FrameLayout：画布铺满 + 进度浮层悬浮底部，出现/消失不改变布局 = 无闪动） =====
        android.widget.FrameLayout canvasWrap = new android.widget.FrameLayout(this);
        if (view == null) view = new EditorView(this);
        view.setListener(this);
        canvasWrap.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setPadding(d(14), d(8), d(14), d(10));
        progressBox.setVisibility(View.GONE);
        GradientDrawable pgd = new GradientDrawable();
        pgd.setCornerRadius(d(12));
        pgd.setColor(C_PANEL);
        pgd.setStroke(d(1), C_LINE);
        progressBox.setBackground(pgd);
        progressText = new TextView(this);
        progressText.setTextSize(13);
        progressText.setTextColor(C_TXT);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBox.addView(progressText);
        progressBox.addView(progressBar);
        android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(-2, -2);
        flp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        flp.setMargins(d(16), 0, d(16), d(14));
        canvasWrap.addView(progressBox, flp);
        root.addView(canvasWrap, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        if (busyText != null) showProgress(busyText, busyPct); // 后台任务进行中 → 新浮层恢复显示（重建不丢进度）
    }

    /** 折叠屏展开 / 横竖屏 / 分屏 / 字号缩放 / 显示大小（密度）变化：不重建 Activity，仅按新参数重建 UI */
    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildUi();
    }

    private int d(float v) { return Math.round(Util.dp(this, v)); }

    private LinearLayout.LayoutParams row(float w) {
        return new LinearLayout.LayoutParams(0, -2, w);
    }

    /** 高级质感：渐变半透明底 + 大圆角；accent=主题绿渐变 */
    private GradientDrawable btnBg(boolean accent) {
        GradientDrawable gd = new GradientDrawable();
        gd.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        gd.setCornerRadius(d(14));
        if (accent) {
            gd.setColors(new int[]{0xFF3FC183, 0xFF189B52});
            gd.setStroke(d(1), 0x33FFFFFF);
        } else {
            gd.setColors(new int[]{0x59FFFFFF, 0x2EFFFFFF});
            gd.setStroke(d(1), 0x1F1C3A26);
        }
        return gd;
    }

    private android.graphics.drawable.Drawable ripple(android.graphics.drawable.GradientDrawable gd) {
        return new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22000000), gd, null);
    }

    /** 网页同款按钮：Lucide 矢量图标在上 + 文字在下；accent=主题绿实底白字 */
    private TextView toolBtn(String label, int iconRes, boolean accent, View.OnClickListener c) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(d(10), d(9), d(10), d(9));
        t.setClickable(true);
        if (iconRes != 0) {
            t.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0);
            t.setCompoundDrawablePadding(d(3));
        }
        t.setTextColor(accent ? 0xFFFFFFFF : C_TXT);
        if (iconRes != 0) t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(accent ? 0xFFFFFFFF : 0xFF3C4A40));
        t.setBackground(ripple(btnBg(accent)));
        t.setOnClickListener(c);
        return t;
    }

    /** 粘性模式按钮选中态切换（网页 mode-on：绿底白字 + 图标同步变白） */
    private void setToolOn(TextView t, boolean on) {
        t.setSelected(on);
        t.setTextColor(on ? 0xFFFFFFFF : C_TXT);
        t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(on ? 0xFFFFFFFF : 0xFF3C4A40));
        t.setBackground(ripple(btnBg(on)));
    }

    /** 粘性模式：再次点击同一按钮 = 退出该模式回浏览态 */
    private void toggleSticky(String key, Runnable enter) {
        boolean active = ("crop".equals(key) && view.getMode() == EditorView.MODE_CROP)
            || ("mosaic".equals(key) && view.getMode() == EditorView.MODE_MOSAIC)
            || ("text".equals(key) && view.getMode() == EditorView.MODE_TEXT)
            || ("erase".equals(key) && view.getMode() == EditorView.MODE_MASK);
        if (active) { exitModes(); return; }
        enter.run();
    }

    private void exitModes() {
        view.setMode(EditorView.MODE_VIEW);
        panelBox.removeAllViews();
        panelBox.setVisibility(View.GONE);
        refreshToolHighlight();
        view.requestFit();
    }

    /** 依当前模式刷新行2选中态 */
    private void refreshToolHighlight() {
        setToolOn(bCrop, view.getMode() == EditorView.MODE_CROP);
        setToolOn(bMosaic, view.getMode() == EditorView.MODE_MOSAIC);
        setToolOn(bText, view.getMode() == EditorView.MODE_TEXT);
        setToolOn(bErase, view.getMode() == EditorView.MODE_MASK);
    }

    // ================= 面板模式 =================
    private void setPanelMode(int mode) {
        view.setMode(mode);
        panelBox.removeAllViews();
        if (mode == EditorView.MODE_MOSAIC) {
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setBackground(roundPanel());
            wrap.setPadding(d(4), d(2), d(4), d(2));
            wrap.addView(buildBrushViews("马赛克笔刷大小"));
            panelBox.addView(wrap);
        }
        else if (mode == EditorView.MODE_MASK) buildMaskPanel();
        else if (mode == EditorView.MODE_TEXT) buildTextPanel();
        panelBox.setVisibility(mode == EditorView.MODE_MOSAIC || mode == EditorView.MODE_MASK
            || mode == EditorView.MODE_TEXT ? View.VISIBLE : View.GONE);
        refreshToolHighlight();
        view.requestFit(); // 面板展开 → fitView 让图自动缩小显示（真实分辨率不变）
    }

    // ================= 导入 =================
    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        importUri(data.getData());
    }

    /** 解码导入（>24MP 采样钳位防 OOM）。图出现即反馈，不再弹成功提示 */
    private void importUri(Uri uri) {
        showProgress("导入图片…", -1);
        exec.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
                long mp = (long) o.outWidth * o.outHeight;
                int sample = 1;
                while (mp / ((long) sample * sample) > 24_000_000) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bmp;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
                if (bmp == null) throw new IllegalStateException("图片解码失败，请换一张试试");
                Bitmap mut = bmp.copy(Bitmap.Config.ARGB_8888, true);
                if (mut != bmp) bmp.recycle();
                // 导入文件名（去扩展名）→ 导出命名 {原名}_edit_{W}x{H}.{ext}（网页同款）
                String dn = "image";
                try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (i >= 0 && c.getString(i) != null) dn = c.getString(i);
                    }
                } catch (Exception ignored) {}
                int dot = dn.lastIndexOf('.');
                if (dot > 0) dn = dn.substring(0, dot);
                final String fname = dn;
                runUi(() -> {
                    lastUri = uri;
                    importedName = fname;
                    view.setBase(mut, false);
                    view.clearTexts();
                    undo.clear();
                    undo.setBudget((long) mut.getWidth() * mut.getHeight() * 4);
                    exitModes();
                    hideProgress();
                });
            } catch (Throwable t) {
                runUi(() -> { hideProgress(); Util.toast(this, "导入失败：" + t.getMessage()); });
            }
        });
    }

    /** 还原原图：从导入源重新解码（图与文字、撤销历史全部复位）。无进度无成功提示（图复位即反馈），防连点 */
    private boolean resetBusy;
    private void doReset() {
        if (lastUri == null) { Util.toast(this, "还没有导入过图片"); return; }
        if (resetBusy) return;
        resetBusy = true;
        exec.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (java.io.InputStream in = getContentResolver().openInputStream(lastUri)) { BitmapFactory.decodeStream(in, null, o); }
                long mp = (long) o.outWidth * o.outHeight;
                int sample = 1;
                while (mp / ((long) sample * sample) > 24_000_000) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bmp;
                try (java.io.InputStream in = getContentResolver().openInputStream(lastUri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
                if (bmp == null) throw new IllegalStateException("解码失败");
                Bitmap mut = bmp.copy(Bitmap.Config.ARGB_8888, true);
                if (mut != bmp) bmp.recycle();
                runUi(() -> {
                    Bitmap old = view.base;
                    view.setBase(mut, false);
                    if (old != null && old != mut) old.recycle();
                    view.clearTexts();
                    undo.clear();
                    undo.setBudget((long) mut.getWidth() * mut.getHeight() * 4);
                    exitModes();
                });
            } catch (Throwable t) {
                runUi(() -> Util.toast(this, "还原失败：" + t.getMessage()));
            } finally {
                resetBusy = false;
            }
        });
    }

    // ================= 裁剪 =================
    private static final String[][] RATIOS = {
        {"原图", "orig"}, {"自由", "free"}, {"1:1", "1"}, {"16:9", "1.7778"}, {"9:16", "0.5625"},
        {"4:3", "1.3333"}, {"3:4", "0.75"}, {"16:10", "1.6"}, {"10:16", "0.625"}, {"3:2", "1.5"}, {"2:3", "0.6667"}
    };

    private void enterCrop() {
        if (noImage()) { refreshToolHighlight(); return; }
        view.setMode(EditorView.MODE_CROP);
        view.cropRatio = (float) view.base.getWidth() / view.base.getHeight(); // 默认"原图"：UI 高亮与内部值同步（坑4）
        view.crop = view.fitRatioRect(view.base.getWidth() * .1f, view.base.getHeight() * .1f,
            view.base.getWidth() * .8f, view.base.getHeight() * .8f);
        view.requestFit();

        panelBox.removeAllViews();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(roundPanel());
        wrap.setPadding(d(10), d(6), d(10), d(6));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        TextView[] chips = new TextView[RATIOS.length];
        for (int i = 0; i < RATIOS.length; i++) {
            TextView t = chipBtn(RATIOS[i][0]);
            final int fi = i;
            t.setOnClickListener(v -> { // 点击即同时设高亮与内部值（坑4）
                view.cropRatio = ratioValue(fi);
                for (TextView c : chips) setChipOn(c, false);
                setChipOn(t, true);
                view.crop = view.fitRatioRect(view.crop[0], view.crop[1], view.crop[2], view.crop[3]);
                view.invalidate();
            });
            setChipOn(t, i == 0);
            chips[i] = t;
            row.addView(t);
        }
        hs.addView(row);
        wrap.addView(hs);
        wrap.addView(actionRow("应用裁剪", v -> applyCrop(), "取消", v -> exitModes()));
        panelBox.addView(wrap);
        panelBox.setVisibility(View.VISIBLE);
        refreshToolHighlight();
        view.requestFit();
    }

    private TextView chipBtn(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setPadding(d(13), d(7), d(13), d(7));
        t.setClickable(true);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(d(16));
        gd.setColor(0x59FFFFFF);
        gd.setStroke(d(1), C_LINE);
        t.setBackground(ripple(gd));
        return t;
    }

    private void setChipOn(TextView t, boolean on) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(d(16));
        if (on) { gd.setColor(0x4C2FBF71); gd.setStroke(d(1), C_ACC); }
        else { gd.setColor(0x59FFFFFF); gd.setStroke(d(1), C_LINE); }
        t.setBackground(ripple(gd));
    }

    private GradientDrawable roundPanel() {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(d(12));
        gd.setColor(C_PANEL);
        gd.setStroke(d(1), C_LINE);
        return gd;
    }

    private float ratioValue(int i) {
        if (RATIOS[i][1].equals("free")) return 0;
        if (RATIOS[i][1].equals("orig")) return (float) view.base.getWidth() / view.base.getHeight();
        return Float.parseFloat(RATIOS[i][1]);
    }

    private void applyCrop() {
        if (view.crop == null || view.crop[2] < 8 || view.crop[3] < 8) { Util.toast(this, "裁剪区域太小"); return; }
        showProgress("裁剪中…", -1);
        final int sx = Math.round(view.crop[0]), sy = Math.round(view.crop[1]);
        final int sw = Math.round(view.crop[2]), sh = Math.round(view.crop[3]);
        exec.execute(() -> {
            try {
                undo.push(view.base);
                Bitmap out = ImageOps.crop(view.base, sx, sy, sw, sh);
                runUi(() -> {
                    Bitmap old = view.base;
                    view.setBase(out, false); // mask 同步重建清空（失效点：裁剪）
                    if (old != out) old.recycle();
                    view.crop = null;
                    undo.setBudget((long) out.getWidth() * out.getHeight() * 4);
                    exitModes();
                    hideProgress();
                });
            } catch (Throwable t) {
                runUi(() -> { hideProgress(); Util.toast(this, "裁剪失败：" + t.getMessage()); });
            }
        });
    }

    // ================= 旋转/镜像 =================
    private boolean geoBusy; // 快操作不弹进度（无感），只防连点重复执行
    private void applyGeo(boolean mirror) {
        if (noImage() || geoBusy) return;
        geoBusy = true;
        exec.execute(() -> {
            try {
                undo.push(view.base);
                Bitmap out = mirror ? ImageOps.mirror(view.base) : ImageOps.rotateCCW(view.base);
                runUi(() -> {
                    Bitmap old = view.base;
                    view.setBase(out, false); // transform 失效 mask（坑14 失效点）
                    if (old != out) old.recycle();
                });
            } catch (Throwable t) {
                runUi(() -> Util.toast(this, "操作失败：" + t.getMessage()));
            } finally {
                geoBusy = false;
            }
        });
    }

    // ================= 撤销 =================
    private void doUndo() {
        Bitmap prev = undo.undo();
        if (prev == null) { Util.toast(this, "没有可撤销的操作"); return; }
        Bitmap old = view.base;
        view.setBase(prev, false); // mask 失效（undo 失效点）
        if (old != null && old != prev) old.recycle();
        view.clearTexts();
        Util.toast(this, "已撤销");
    }

    // ================= 马赛克/移除面板共用 =================
    private LinearLayout buildBrushViews(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label + "：" + Math.round(view.brush) + "px");
        t.setTextSize(13);
        t.setPadding(d(16), d(6), d(16), d(2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(199);
        sb.setProgress((int) view.brush - 1);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                view.brush = p + 1;
                t.setText(label + "：" + (p + 1) + "px");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(t);
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    // ================= 移除面板 =================
    private void buildMaskPanel() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(roundPanel());
        wrap.setPadding(d(4), d(2), d(4), d(2));
        wrap.addView(buildBrushViews("涂抹笔刷大小"));
        wrap.addView(actionRow("执行移除", v -> runErase(), "清除涂抹", v -> {
            view.resetMask();
            view.invalidate();
            Util.toast(this, "已清除涂抹");
        }));
        panelBox.addView(wrap);
        panelBox.setVisibility(View.VISIBLE);
        Util.toast(this, "在图上涂抹要移除的区域（绿色高亮处）");
    }

    private void enterRemove() {
        if (noImage()) { refreshToolHighlight(); return; }
        setPanelMode(EditorView.MODE_MASK);
    }

    private void runErase() {
        if (noImage()) return;
        if (view.maskBB == null) { Util.toast(this, "请先用笔刷涂抹要移除的区域"); return; }
        final float[] bb = view.maskBB.clone();
        final Bitmap mask = view.maskBitmap();
        showProgress("移除中…", -1);
        exec.execute(() -> {
            try {
                undo.push(view.base);
                AiManager.erase(this, view.base, mask, bb, msg -> runUi(() -> showProgress(msg, -1)));
                runUi(() -> {
                    view.resetMask(); // mask 失效点
                    view.invalidate();
                    hideProgress();
                    Util.toast(this, "移除完成，不满意可撤销后多涂一点再试");
                });
            } catch (Throwable t) {
                android.util.Log.e("L1Editor", "erase failed", t); // 供 adb logcat -s L1Editor 取证
                runUi(() -> {
                    view.resetMask(); // 移除结束（含失败）自动清上次涂抹
                    view.invalidate();
                    hideProgress();
                    Util.toast(this, "移除失败：" + t.getMessage());
                });
            }
        });
    }

    // ================= 抠图 =================
    private void runMatting() {
        if (noImage()) return;
        showProgress("正在抠图…", -1);
        exec.execute(() -> {
            try {
                undo.push(view.base);
                AiManager.matting(this, view.base, msg -> runUi(() -> showProgress(msg, -1)));
                runUi(() -> {
                    view.invalidate();
                    exitModes();
                    hideProgress();
                    Util.toast(this, "抠图完成，导出 PNG 可保留透明背景");
                });
            } catch (Throwable t) {
                android.util.Log.e("L1Editor", "matting failed", t);
                runUi(() -> { hideProgress(); Util.toast(this, "抠图失败：" + t.getMessage()); });
            }
        });
    }

    // ================= 文字面板 =================
    private float textFontSize = 48;
    private int textColor = 0xFFFF0000; // 默认红（网页同款）
    private boolean textBold = true;    // 默认加粗
    private EditText txtInput;
    private SeekBar textSizeSb;
    private TextView textSizeVal;
    private CheckBox boldCb;
    private TextView colorBtn;
    private boolean syncingTextUI = false;

    private void buildTextPanel() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(roundPanel());
        wrap.setPadding(d(10), d(6), d(10), d(6));

        LinearLayout row1 = new LinearLayout(this);
        row1.setPadding(d(6), d(4), d(6), d(2));
        txtInput = new EditText(this);
        txtInput.setHint("输入文字，点图上文字可选中编辑");
        txtInput.setTextSize(14);
        row1.addView(txtInput, new LinearLayout.LayoutParams(0, -2, 1f));
        wrap.addView(row1);

        // 大小滑条（选中文字时实时生效）
        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(d(6), d(2), d(6), d(2));
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView lb = new TextView(this);
        lb.setText("大小");
        lb.setTextSize(13);
        textSizeSb = new SeekBar(this);
        textSizeSb.setMax(188);
        textSizeVal = new TextView(this);
        textSizeVal.setText(Math.round(textFontSize) + "");
        textSizeVal.setTextSize(13);
        textSizeVal.setPadding(d(6), 0, 0, 0);
        textSizeSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                textFontSize = p + 12;
                textSizeVal.setText(Math.round(textFontSize) + "");
                if (!syncingTextUI && view.selectedText != null) { // 实时应用到选中文字
                    view.selectedText.size = ImageOps.textImageSize(textFontSize, view.base);
                    view.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row2.addView(lb);
        row2.addView(textSizeSb, new LinearLayout.LayoutParams(0, -2, 1f));
        row2.addView(textSizeVal);
        wrap.addView(row2);

        // 颜色按钮（调色盘） + 加粗
        LinearLayout row3 = new LinearLayout(this);
        row3.setPadding(d(6), d(2), d(6), d(2));
        row3.setGravity(Gravity.CENTER_VERTICAL);
        TextView cl = new TextView(this);
        cl.setText("颜色");
        cl.setTextSize(13);
        colorBtn = new TextView(this);
        colorBtn.setText("　　");
        GradientDrawable cgd = new GradientDrawable();
        cgd.setCornerRadius(d(6));
        cgd.setColor(textColor);
        cgd.setStroke(d(1), C_LINE);
        colorBtn.setBackground(cgd);
        colorBtn.setOnClickListener(v -> showPalette());
        boldCb = new CheckBox(this);
        boldCb.setText("加粗");
        boldCb.setChecked(textBold);
        boldCb.setOnCheckedChangeListener((b2, c2) -> {
            textBold = c2;
            if (!syncingTextUI && view.selectedText != null) { // 实时应用到选中文字
                view.selectedText.bold = c2;
                view.invalidate();
            }
        });
        row3.addView(cl);
        row3.addView(colorBtn, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout spacer = new LinearLayout(this);
        row3.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        row3.addView(boldCb);
        wrap.addView(row3);

        // 操作行：删除选中 / 添加 / 合并
        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(d(6), d(4), d(6), d(6));
        TextView del = flatBtn("删除选中", false, v -> {
            if (view.selectedText == null) { Util.toast(this, "先点选图上的文字"); return; }
            view.removeSelected();
            syncTextControls();
            Util.toast(this, "已删除选中文字");
        });
        TextView add = flatBtn("添加文字", true, v -> addTextFromPanel());
        TextView merge = flatBtn("合并到图", true, v -> mergeTexts());
        acts.addView(del, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(0, -2, 1f);
        addLp.setMargins(d(8), 0, d(8), 0);
        acts.addView(add, addLp);
        acts.addView(merge, new LinearLayout.LayoutParams(0, -2, 1f));
        wrap.addView(acts);

        panelBox.addView(wrap);
        panelBox.setVisibility(View.VISIBLE);
        syncTextControls();
    }

    private void addTextFromPanel() {
        if (noImage()) return;
        EditorView.TextItem t = new EditorView.TextItem();
        t.content = txtInput.getText().toString().isEmpty() ? "文字" : txtInput.getText().toString();
        t.size = ImageOps.textImageSize(textFontSize, view.base);
        t.color = textColor;
        t.bold = textBold;
        t.x = view.base.getWidth() / 2f;
        t.y = view.base.getHeight() / 2f;
        view.addText(t); // 新文字自动选中 → 虚框 + 实时编辑
        syncTextControls();
    }

    /** 选中态变化/面板重建 → 把选中文字的属性回填到控件（防止控件→属性回写回环） */
    private void syncTextControls() {
        if (textSizeSb == null) return;
        syncingTextUI = true;
        EditorView.TextItem t = view.selectedText;
        if (t != null) {
            int ui = Math.round(t.size * 800f / Math.max(1, view.base.getWidth()));
            textSizeSb.setProgress(Math.max(0, Math.min(188, ui - 12)));
            textSizeVal.setText(Math.max(12, ui) + "");
            boldCb.setChecked(t.bold);
            setSwatch(t.color);
        } else {
            textSizeSb.setProgress(Math.max(0, Math.min(188, (int) textFontSize - 12)));
            textSizeVal.setText(Math.round(textFontSize) + "");
            boldCb.setChecked(textBold);
            setSwatch(textColor);
        }
        syncingTextUI = false;
    }

    private void setSwatch(int color) {
        GradientDrawable gd = (GradientDrawable) colorBtn.getBackground();
        gd.setColor(color);
    }

    /** 调色盘（网页 <input type=color> 同角色）：常用色网格 */
    private void showPalette() {
        int[] colors = {
            0xFFFF0000, 0xFFFF6D00, 0xFFFBC02D, 0xFFCDDC39, 0xFF2FBF71, 0xFF1FA75F, 0xFF009688,
            0xFF1976D2, 0xFF3F51B5, 0xFF9C27B0, 0xFFE91E63, 0xFF795548, 0xFF000000, 0xFF424242,
            0xFF757575, 0xFFBDBDBD, 0xFFECEFF1, 0xFFFFFFFF, 0xFF0D47A1, 0xFF1B5E20, 0xFFBF360C, 0xFF880E4F
        };
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(d(16), d(10), d(16), d(4));
        final int perRow = 8;
        LinearLayout cur = null;
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < colors.length; i++) {
            if (i % perRow == 0) { cur = new LinearLayout(this); grid.addView(cur); }
            TextView sw = new TextView(this);
            sw.setText("　　");
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(d(6));
            gd.setColor(colors[i]);
            gd.setStroke(d(1), C_LINE);
            sw.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(d(3), d(3), d(3), d(3));
            sw.setLayoutParams(lp);
            final int c = colors[i];
            sw.setOnClickListener(v -> {
                textColor = c;
                setSwatch(c);
                if (view.selectedText != null) { // 实时应用到选中文字
                    view.selectedText.color = c;
                    view.invalidate();
                }
                if (dlg[0] != null) dlg[0].dismiss(); // 点色即应用并关闭（无需再点关闭）
            });
            cur.addView(sw);
        }
        dlg[0] = new AlertDialog.Builder(this)
            .setTitle("文字颜色")
            .setView(grid)
            .show();
    }

    private void mergeTexts() {
        if (noImage() || view.texts.isEmpty()) { Util.toast(this, "没有待合并的文字"); return; }
        undo.push(view.base);
        final java.util.ArrayList<EditorView.TextItem> items = new java.util.ArrayList<>(view.texts);
        exec.execute(() -> { // 大图上逐条画字是重操作，后台执行防主线程卡顿/ANR
            for (EditorView.TextItem t : items) ImageOps.mergeTexts(view.base, t);
            runUi(() -> {
                view.clearTexts();
                view.invalidate();
                syncTextControls();
            });
        });
    }

    // ================= 导出（完全复刻 PC 网页逻辑 + 半透明质感面板 + 自绘选择器） =================
    private Dialog exportDialog, pickerDialog;
    private Runnable estPending;

    private void openExport() {
        if (noImage()) return;
        final Bitmap base = view.base;

        // --- 半透明渐变圆角面板 ---
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(d(22), d(18), d(22), d(16));
        GradientDrawable pbg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF5FFFFFF, 0xE6FFFFFF});
        pbg.setCornerRadius(d(20));
        pbg.setStroke(d(1), C_LINE);
        panel.setBackground(pbg);

        TextView title = new TextView(this);
        title.setText("导出图片");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TXT);
        panel.addView(title);

        // 尺寸预设（网页 buildSizes 同款分组与顺序）
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<int[]> dims = new java.util.ArrayList<>();
        labels.add("原始尺寸 " + base.getWidth() + "×" + base.getHeight());
        dims.add(new int[]{base.getWidth(), base.getHeight()});
        labels.add("自定义");
        dims.add(new int[]{-1, -1});
        int[][][] groups = {
            {{500,500},{600,600},{800,800},{1000,1000},{1200,1200},{1500,1500},{2000,2000},{3000,3000}},
            {{960,540},{1280,720},{1600,900},{1920,1080},{2560,1440},{3840,2160}},
            {{540,960},{720,1280},{1080,1920},{1440,2560},{2160,3840}},
            {{640,480},{800,600},{1024,768},{1600,1200},{2048,1536},{480,640},{600,800},{768,1024},{1200,1600},{1536,2048}},
            {{900,600},{1350,900},{1500,1000},{3000,2000},{600,900},{1000,1500},{2000,3000}}
        };
        String[] groupNames = {"1:1", "16:9 横版", "9:16 竖版", "4:3 / 3:4", "3:2 / 2:3"};
        for (int g = 0; g < groups.length; g++) // 选择器行数据自建，但 labels/dims 仍按索引被 onPick/联动读取，必须填满
            for (int[] wh : groups[g]) { labels.add(groupNames[g] + "  " + wh[0] + "×" + wh[1]); dims.add(wh); }

        LinearLayout sizeRow = expRow("尺寸");
        TextView sizeField = selectField(labels.get(0));
        sizeRow.addView(sizeField, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(sizeRow);

        // 宽 × 高 + 锁定比例（默认勾选，锁定基准 = 打开面板时的当前数值）
        LinearLayout whRow = new LinearLayout(this);
        whRow.setGravity(Gravity.CENTER_VERTICAL);
        whRow.setPadding(0, d(10), 0, 0);
        EditText wIn = numField(String.valueOf(base.getWidth()));
        EditText hIn = numField(String.valueOf(base.getHeight()));
        TextView xLb = new TextView(this);
        xLb.setText(" × ");
        xLb.setTextColor(C_TXT);
        CheckBox lock = new CheckBox(this);
        lock.setText("锁定比例");
        lock.setTextSize(12);
        lock.setTextColor(C_TXT);
        lock.setButtonTintList(android.content.res.ColorStateList.valueOf(C_ACC));
        lock.setChecked(true);
        whRow.addView(wIn, new LinearLayout.LayoutParams(0, -2, 1f));
        whRow.addView(xLb);
        whRow.addView(hIn, new LinearLayout.LayoutParams(0, -2, 1f));
        whRow.addView(lock, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(whRow);

        // 格式（网页 FMTS 同序）+ 备注；默认跟随导入格式
        final int[] fmtPos = {0};
        String mime = lastUri != null ? getContentResolver().getType(lastUri) : null;
        fmtPos[0] = "image/png".equals(mime) ? 1 : "image/webp".equals(mime) ? 2 : 0;
        LinearLayout fmtRow = expRow("格式");
        TextView fmtField = selectField(Exporter.FMT_LABELS[fmtPos[0]]);
        fmtRow.addView(fmtField, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView fmtNote = new TextView(this);
        fmtNote.setTextSize(11);
        fmtNote.setTextColor(0xFF8A938C);
        fmtRow.addView(fmtNote, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(fmtRow);

        // 质量 30-100 默认 92（网页 expQ 同款），无质量档格式隐藏
        LinearLayout qRow = new LinearLayout(this);
        qRow.setGravity(Gravity.CENTER_VERTICAL);
        qRow.setPadding(0, d(10), 0, 0);
        TextView qTag = new TextView(this);
        qTag.setText("质量");
        qTag.setTextSize(13);
        qTag.setTextColor(C_TXT);
        SeekBar q = new SeekBar(this);
        q.setMax(70);
        q.setProgress(62);
        q.setProgressTintList(android.content.res.ColorStateList.valueOf(C_ACC));
        q.setThumbTintList(android.content.res.ColorStateList.valueOf(C_ACC));
        TextView qVal = new TextView(this);
        qVal.setText("92% 可升可降");
        qVal.setTextSize(12);
        qVal.setTextColor(C_TXT);
        qRow.addView(qTag, new LinearLayout.LayoutParams(d(58), -2));
        qRow.addView(q, new LinearLayout.LayoutParams(0, -2, 1f));
        qRow.addView(qVal);
        panel.addView(qRow);

        // 大小不超过（MB）
        LinearLayout mbRow = expRow("不超过");
        EditText mbIn = numField("");
        mbIn.setHint("MB，留空不限");
        mbRow.addView(mbIn, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView mbUnit = new TextView(this);
        mbUnit.setText(" MB");
        mbUnit.setTextSize(13);
        mbUnit.setTextColor(C_TXT);
        mbRow.addView(mbUnit);
        panel.addView(mbRow);

        // 估算信息行（网页 expInfo）
        TextView info = new TextView(this);
        info.setTextSize(12);
        info.setTextColor(C_TXT);
        info.setPadding(0, d(12), 0, d(2));
        panel.addView(info);
        View hr = new View(this);
        hr.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x001C3A26, 0x1F1C3A26, 0x001C3A26}));
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(-1, d(1));
        hrLp.setMargins(0, d(10), 0, 0);
        hr.setLayoutParams(hrLp);
        panel.addView(hr);

        // 底部操作
        LinearLayout foot = new LinearLayout(this);
        foot.setPadding(0, d(12), 0, 0);
        TextView cancel = flatBtn("取消", false, v -> exportDialog.dismiss());
        TextView go = flatBtn("导出并保存", true, v -> submitExport(base, fmtPos, wIn, hIn, q, mbIn));
        foot.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(0, -2, 1f);
        goLp.setMargins(d(10), 0, 0, 0);
        foot.addView(go, goLp);
        panel.addView(foot);

        // --- 状态联动（网页语义逐条复刻） ---
        final float[] lockAr = {(float) base.getWidth() / base.getHeight()};
        final boolean[] syncing = {false};
        lock.setOnCheckedChangeListener((b2, c2) -> { // 重新勾选锁定：以当前数值比例为准
            if (c2) {
                try {
                    int w = Integer.parseInt(wIn.getText().toString()), h = Integer.parseInt(hIn.getText().toString());
                    if (w >= 16 && h >= 16) lockAr[0] = (float) w / h;
                } catch (Exception ignored) {}
            }
            scheduleEstimate(info, base, fmtPos, q, wIn, hIn, mbIn);
        });
        // 尺寸选择器（居中半透明面板，替代原生 Spinner 弹层）
        sizeField.setOnClickListener(v -> {
            java.util.List<Object> rows = new java.util.ArrayList<>();
            rows.add(labels.get(0));
            rows.add(labels.get(1));
            for (int g = 0; g < groups.length; g++) {
                rows.add(new Object[]{groupNames[g]});
                for (int[] wh : groups[g]) rows.add(wh[0] + "×" + wh[1]);
            }
            int cur = 1; // 自定义
            try {
                int w = Integer.parseInt(wIn.getText().toString()), h = Integer.parseInt(hIn.getText().toString());
                if (w == base.getWidth() && h == base.getHeight()) cur = 0;
                else for (int i = 2; i < dims.size(); i++)
                    if (dims.get(i)[0] == w && dims.get(i)[1] == h) { cur = i; break; }
            } catch (Exception ignored) {}
            showPicker("选择尺寸", rows, cur, idx -> {
                int[] d2 = dims.get(idx);
                if (d2[0] <= 0) return; // 自定义不改数值
                syncing[0] = true;
                wIn.setText(String.valueOf(d2[0]));
                hIn.setText(String.valueOf(d2[1]));
                syncing[0] = false;
                if (lock.isChecked()) lockAr[0] = (float) d2[0] / d2[1]; // 勾选状态下选预设：以预设为新锁定基准
                sizeField.setText(labels.get(idx) + "  ▾");
                scheduleEstimate(info, base, fmtPos, q, wIn, hIn, mbIn);
            });
        });
        // 格式选择器
        fmtField.setOnClickListener(v -> {
            java.util.List<Object> rows = new java.util.ArrayList<>(java.util.Arrays.asList(Exporter.FMT_LABELS));
            showPicker("选择格式", rows, fmtPos[0], idx -> {
                fmtPos[0] = idx;
                fmtField.setText(Exporter.FMT_LABELS[idx] + "  ▾");
                qRow.setVisibility(Exporter.FMT_HAS_Q[idx] ? View.VISIBLE : View.GONE);
                fmtNote.setText(idx == 4 ? "最大 256×256" : "");
                scheduleEstimate(info, base, fmtPos, q, wIn, hIn, mbIn);
            });
        });
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (syncing[0]) return;
                syncing[0] = true;
                try {
                    if (lock.isChecked()) {
                        if (s == wIn.getEditableText()) {
                            int w = Integer.parseInt(wIn.getText().toString());
                            if (w >= 16) hIn.setText(String.valueOf(Math.max(16, Math.round(w / lockAr[0]))));
                        } else {
                            int h = Integer.parseInt(hIn.getText().toString());
                            if (h >= 16) wIn.setText(String.valueOf(Math.max(16, Math.round(h * lockAr[0]))));
                        }
                    }
                    int w = Integer.parseInt(wIn.getText().toString()), h = Integer.parseInt(hIn.getText().toString());
                    String hit = labels.get(0); // 网页 syncSizeSel 语义：orig → 预设 → 自定义，实时联动
                    if (w != base.getWidth() || h != base.getHeight()) {
                        hit = "自定义";
                        for (int i = 2; i < dims.size(); i++)
                            if (dims.get(i)[0] == w && dims.get(i)[1] == h) { hit = labels.get(i); break; }
                    }
                    sizeField.setText(hit + "  ▾");
                } catch (Exception ignored) {}
                syncing[0] = false;
                scheduleEstimate(info, base, fmtPos, q, wIn, hIn, mbIn);
            }
        };
        wIn.addTextChangedListener(tw);
        hIn.addTextChangedListener(tw);
        mbIn.addTextChangedListener(tw);
        q.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int pr, boolean u) { qVal.setText((pr + 30) + "% 可升可降"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { scheduleEstimate(info, base, fmtPos, q, wIn, hIn, mbIn); }
        });

        qRow.setVisibility(Exporter.FMT_HAS_Q[fmtPos[0]] ? View.VISIBLE : View.GONE);
        fmtNote.setText(fmtPos[0] == 5 ? "最大 256×256" : "");
        exportDialog = new Dialog(this);
        exportDialog.setContentView(panel);
        exportDialog.setOnDismissListener(d -> { // 释放估算缓存（大字节数组随面板生命周期）
            estCacheBase = null;
            estCacheKey = null;
            estCacheData = null;
        });
        if (exportDialog.getWindow() != null) {
            exportDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int dw = (int) Math.min(getResources().getDisplayMetrics().widthPixels * 0.88, d(560)); // 平板限宽
            exportDialog.getWindow().setLayout(dw, -2);
        }
        exportDialog.show();
        scheduleEstimate(info, base, fmtPos, q, wIn, hIn, mbIn); // 打开即估算（网页 openExport 末尾 updEst）
    }

    /** 下拉选择字段：半透明圆角 + 右侧 ▾，点击弹自绘选择器 */
    private TextView selectField(String text) {
        TextView t = new TextView(this);
        t.setText(text + "  ▾");
        t.setTextSize(13);
        t.setTextColor(C_TXT);
        t.setMaxLines(1);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(d(12), d(9), d(12), d(9));
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(d(10));
        gd.setColor(0x59FFFFFF);
        gd.setStroke(d(1), C_LINE);
        t.setBackground(ripple(gd));
        return t;
    }

    /** 居中自绘选择器：半透明圆角面板 + 分组标题 + 选中项绿底高亮（替代原生 Spinner 弹层） */
    private void showPicker(String title, java.util.List<Object> rows, int selected, java.util.function.IntConsumer onPick) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(d(22), d(18), d(22), d(10));
        GradientDrawable pbg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF7FFFFFF, 0xECFFFFFF});
        pbg.setCornerRadius(d(20));
        pbg.setStroke(d(1), C_LINE);
        panel.setBackground(pbg);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(C_TXT);
        panel.addView(t);

        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, d(8), 0, d(4));
        sc.addView(list);
        int optIdx = -1;
        for (Object r : rows) {
            if (r instanceof Object[]) { // 分组标题
                TextView h = new TextView(this);
                h.setText((String) ((Object[]) r)[0]);
                h.setTextSize(11);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setTextColor(0xFF8A938C);
                h.setPadding(d(6), d(12), d(6), d(4));
                list.addView(h);
                continue;
            }
            final int idx = ++optIdx;
            TextView it = new TextView(this);
            it.setText((String) r);
            it.setTextSize(14);
            it.setPadding(d(14), d(11), d(14), d(11));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(d(10));
            if (idx == selected) {
                bg.setColor(0x1F2FBF71);
                it.setTextColor(C_ACC);
                it.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                bg.setColor(0x00000000);
                it.setTextColor(C_TXT);
            }
            it.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000), bg, null));
            it.setOnClickListener(v -> {
                pickerDialog.dismiss();
                onPick.accept(idx);
            });
            list.addView(it);
        }
        panel.addView(sc);
        pickerDialog = new Dialog(this);
        pickerDialog.setContentView(panel);
        if (pickerDialog.getWindow() != null) {
            pickerDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int hPx = Math.min(panel.getMeasuredHeight(), (int) (getResources().getDisplayMetrics().heightPixels * 0.62));
            int wPx = (int) Math.min(getResources().getDisplayMetrics().widthPixels * 0.72, d(420)); // 平板限宽
            pickerDialog.getWindow().setLayout(wPx, hPx);
        }
        pickerDialog.show();
    }

    private LinearLayout expRow(String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, d(10), 0, 0);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(C_TXT);
        r.addView(t, new LinearLayout.LayoutParams(d(58), -2));
        return r;
    }

    private EditText numField(String text) {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setText(text);
        e.setTextSize(13);
        e.setTextColor(C_TXT);
        e.setHintTextColor(0xFF8A938C);
        e.setMaxLines(1);
        e.setPadding(d(10), d(8), d(10), d(8));
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(d(10));
        gd.setColor(0x59FFFFFF);
        gd.setStroke(d(1), C_LINE);
        e.setBackground(gd);
        return e;
    }

    /** 估算（网页 estimateSize 语义）：真实编码 + OOM 代理兜底；独立线程不阻塞导出；结果缓存供同参数导出复用 */
    private final ExecutorService estExec = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger estSeq = new java.util.concurrent.atomic.AtomicInteger();
    private Bitmap estCacheBase;
    private String estCacheKey;
    private byte[] estCacheData;

    private void scheduleEstimate(TextView out, Bitmap base, int[] fmtPos, SeekBar q, EditText wIn, EditText hIn, EditText mbIn) {
        if (estPending != null) view.removeCallbacks(estPending);
        estPending = () -> {
            try {
                int pos = fmtPos[0];
                String fkey = Exporter.FMT_KEYS[pos];
                int W = Integer.parseInt(wIn.getText().toString()), H = Integer.parseInt(hIn.getText().toString());
                if (W < 16 || H < 16 || W > 20000 || H > 20000 || (long) W * H > 80_000_000) {
                    out.setText("尺寸无效");
                    return;
                }
                String mbStr = mbIn.getText().toString();
                final double maxMB = mbStr.isEmpty() ? 0 : Double.parseDouble(mbStr);
                if (fkey.equals("ico") && Math.max(W, H) > 256) { // ICO 网页同款：先钳 256 再估
                    double ck = 256.0 / Math.max(W, H);
                    W = Math.max(16, (int) Math.round(W * ck));
                    H = Math.max(16, (int) Math.round(H * ck));
                }
                final int fw = W, fh = H;
                final String fmb = mbStr;
                final String key = fkey + "|" + fw + "x" + fh + "|" + (Exporter.FMT_HAS_Q[pos] ? q.getProgress() + 30 : 0);
                // 缓存命中即时显示，不再进线程
                if (base == estCacheBase && key.equals(estCacheKey) && estCacheData != null) {
                    applyEstimate(out, fw, fh, fmb, maxMB, estCacheData.length);
                    return;
                }
                out.setText("估算中…");
                final int seq = estSeq.incrementAndGet();
                estExec.execute(() -> {
                    if (seq != estSeq.get()) return; // 已被更新的估算/导出作废
                    byte[] d = Exporter.estimateData(base, fkey, fw, fh, q.getProgress() + 30);
                    long bytes;
                    if (d != null) {
                        estCacheBase = base;
                        estCacheKey = key;
                        estCacheData = d;
                        bytes = d.length;
                    } else {
                        bytes = Exporter.estimateProxy(base, fkey, fw, fh, q.getProgress() + 30);
                    }
                    final long fb = bytes;
                    runUi(() -> { if (seq == estSeq.get()) applyEstimate(out, fw, fh, fmb, maxMB, fb); });
                });
            } catch (Exception ignored) {}
        };
        view.postDelayed(estPending, 200);
    }

    private void applyEstimate(TextView out, int fw, int fh, String fmb, double maxMB, long fb) {
        if (fb < 0) { out.setText("估算失败"); return; }
        SpannableStringBuilder sb = new SpannableStringBuilder(String.format(java.util.Locale.US, "输出 %d×%d · 预计约 %s", fw, fh, fmtSize(fb)));
        if (maxMB > 0) {
            boolean over = fb > maxMB * 1048576.0;
            int start = sb.length();
            sb.append(over ? String.format(java.util.Locale.US, " · 超出 %sMB 限制，导出时将自动降质量/缩尺寸", fmb)
                           : String.format(java.util.Locale.US, " · 在 %sMB 限制内", fmb));
            if (over) sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFD9534F), start, sb.length(), 0);
        }
        out.setText(sb);
    }

    private static String fmtSize(long b) {
        return b >= 1048576 ? String.format(java.util.Locale.US, "%.2fMB", b / 1048576.0) : Math.round(b / 1024.0) + "KB";
    }

    /** 导出执行（网页 btnExpDo 语义）：验证 → 24 轮限 MB 循环 → 网页同款命名 → 成功关面板 */
    private void submitExport(Bitmap base, int[] fmtPos, EditText wIn, EditText hIn, SeekBar q, EditText mbIn) {
        try {
            final int pos = fmtPos[0];
            final String fkey = Exporter.FMT_KEYS[pos];
            final int W = Integer.parseInt(wIn.getText().toString()), H = Integer.parseInt(hIn.getText().toString());
            if (W < 16 || H < 16 || W > 20000 || H > 20000 || (long) W * H > 80_000_000) { Util.toast(this, "尺寸无效（宽高 16–20000）"); return; }
            String mbStr = mbIn.getText().toString();
            final double maxMB = mbStr.isEmpty() ? 0 : Double.parseDouble(mbStr);
            final int quality = q.getProgress() + 30;
            estSeq.incrementAndGet(); // 作废在途估算（结果不再回填，避免与导出竞争）
            showProgress("导出中…", -1);
            exec.execute(() -> {
                try {
                    int[] outWH = new int[2];
                    final String key = fkey + "|" + W + "x" + H + "|" + (Exporter.FMT_HAS_Q[pos] ? quality : 0);
                    byte[] data;
                    if (maxMB <= 0 && base == estCacheBase && key.equals(estCacheKey) && estCacheData != null) {
                        data = estCacheData; // 估算刚编码过同参数 → 直接复用，导出瞬时完成
                        outWH[0] = W;
                        outWH[1] = H;
                    } else {
                        data = Exporter.encodeLimited(base, fkey, W, H, quality, maxMB, outWH);
                        if (data != null && maxMB <= 0) { // 回填缓存，重复导出免二次编码
                            estCacheBase = base;
                            estCacheKey = key;
                            estCacheData = data;
                        }
                    }
                    if (data == null) throw new IllegalStateException(maxMB > 0
                        ? "无法压到 " + mbStr + "MB 内，请缩小尺寸或放宽限制" : "编码失败");
                    String mime;
                    switch (fkey) {
                        case "png": mime = "image/png"; break;
                        case "webp": mime = "image/webp"; break;
                        case "bmp": mime = "image/bmp"; break;
                        case "ico": mime = "image/x-icon"; break;
                        default: mime = "image/jpeg"; break;
                    }
                    String name = importedName + "_edit_" + outWH[0] + "x" + outWH[1] + "." + fkey;
                    Exporter.save(this, data, name, mime);
                    android.util.Log.e("L1Editor", "export ok fmt=" + fkey + " base=" + base.getWidth() + "x" + base.getHeight()
                        + " target=" + W + "x" + H + " out=" + outWH[0] + "x" + outWH[1] + " bytes=" + data.length);
                    runUi(() -> {
                        hideProgress();
                        if (exportDialog != null) exportDialog.dismiss();
                        Util.toast(this, String.format(java.util.Locale.US, "已导出 %d×%d %s · %s（已存相册）",
                            outWH[0], outWH[1], Exporter.FMT_LABELS[pos], fmtSize(data.length)));
                    });
                } catch (Throwable t) {
                    runUi(() -> { hideProgress(); Util.toast(this, "导出失败：" + t.getMessage()); });
                }
            });
        } catch (Exception e) {
            Util.toast(this, "参数无效");
        }
    }

    // ================= 通用 =================
    private boolean noImage() {
        if (view.base == null) { Util.toast(this, "请先导入图片"); return true; }
        return false;
    }

    /** 面板操作按钮：与工具条同款质感 */
    private TextView flatBtn(String label, boolean accent, View.OnClickListener c) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(d(10), d(11), d(10), d(11));
        t.setClickable(true);
        t.setTextColor(accent ? 0xFFFFFFFF : C_TXT);
        t.setBackground(ripple(btnBg(accent)));
        t.setOnClickListener(c);
        return t;
    }

    private LinearLayout actionRow(String l1, View.OnClickListener c1, String l2, View.OnClickListener c2) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(d(6), d(8), d(6), d(8));
        r.addView(flatBtn(l1, true, c1), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp2.setMargins(d(8), 0, 0, 0);
        r.addView(flatBtn(l2, false, c2), lp2);
        return r;
    }

    private String busyText; // 进行中任务的进度文案（UI 重建后恢复浮层用）
    private int busyPct = -1;

    private void showProgress(String msg, int pct) {
        busyText = msg;
        busyPct = pct;
        progressBox.setVisibility(View.VISIBLE);
        progressText.setText(msg);
        if (pct < 0) progressBar.setIndeterminate(true);
        else { progressBar.setIndeterminate(false); progressBar.setProgress(pct); }
    }

    private void hideProgress() {
        busyText = null;
        progressBox.setVisibility(View.GONE);
    }

    private void runUi(Runnable r) { runOnUiThread(r); }

    @Override public void onNeedsPushHistory() { undo.push(view.base); }
    @Override public void onMaskChanged() {}
    @Override public void onInfo(String msg) { Util.toast(this, msg); }
    @Override public void onImportRequest() { pickImage(); }
    @Override public void onTextSelectionChanged() { syncTextControls(); }

    @Override public void onTrimMemory(int level) { // 系统内存压力：撤销快照是最大可丢弃内存，先交还
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) undo.trim();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        AiManager.shutdown();
        exec.shutdown();
        estExec.shutdownNow();
    }
}
