package com.l1.editor;

import android.content.Context;
import android.widget.Toast;

/** 小工具：dp 换算与统一 toast（自动消失，符合"基础提示自动消失"要求） */
public final class Util {
    private static Toast last;

    public static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }

    public static void toast(Context c, String msg) {
        if (last != null) last.cancel();
        last = Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_SHORT);
        last.show();
    }
}
