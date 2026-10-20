package com.l1.editor;

import android.graphics.Bitmap;

import java.util.ArrayDeque;

/**
 * 快照撤销栈（与网页端同方案：位图快照 + 按内存自适应条数）。
 * 网页端 HIST_MAX 5–15；安卓位图在 native 堆，12MP 一张 48MB，预算 160MB → 大图自动降到 3 张。
 */
public final class UndoManager {
    private final ArrayDeque<Bitmap> stack = new ArrayDeque<>();
    private int maxEntries = 10;

    public void setBudget(long bytes) { // 由 MainActivity 在导入图后按单张快照大小调用
        // 总预算 160MB ÷ 单张字节数 = 可存条数（小图多存、大图少存，3~12 张）
        maxEntries = (int) Math.max(3, Math.min(12, 160L * 1024 * 1024 / Math.max(1, bytes)));
    }

    /** 系统内存压力回调：回收全部快照（图本体在 view.base，不受影响） */
    public void trim() {
        clear();
    }

    /** 变更操作前调用：入栈当前位图副本 */
    public void push(Bitmap current) {
        if (current == null || !current.isMutable()) return;
        Bitmap copy = current.copy(Bitmap.Config.ARGB_8888, true);
        if (copy == null) return;
        stack.push(copy);
        while (stack.size() > maxEntries) {
            Bitmap old = stack.pollLast();
            if (old != null) old.recycle();
        }
    }

    /** 返回上一张（弹栈），无历史返回 null */
    public Bitmap undo() {
        Bitmap b = stack.poll();
        return b; // 调用方负责 recycle 被替换的旧 base
    }

    public void clear() {
        for (Bitmap b : stack) b.recycle();
        stack.clear();
    }
}
