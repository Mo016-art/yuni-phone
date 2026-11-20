package com.yuni.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class YuniA11yService extends AccessibilityService {
    public static YuniA11yService inst;
    public YuniAgent agent;

    // 支付、银行类应用：一律不读内容、不代点击、不代打开
    static final String[] BLOCK = {
        "com.eg.android.AlipayGphone", "com.unionpay", "com.icbc", "com.chinamworld",
        "com.android.bankabc", "com.cmbchina", "com.bankcomm", "com.pingan", "com.paypal"
    };

    static boolean isBlocked(String pkg) {
        if (pkg == null) return false;
        for (String b : BLOCK) {
            if (pkg.startsWith(b)) return true;
        }
        return false;
    }

    WindowManager wm;
    final Handler h = new Handler(Looper.getMainLooper());
    ImageView ball;
    WindowManager.LayoutParams ballLp;
    TextView bubble;
    LinearLayout panel;
    EditText panelEt;
    long lastKick = 0;
    String lastPkg = "";
    final Runnable hideBubbleRun = new Runnable() {
        public void run() {
            removeBubble();
        }
    };

    SharedPreferences sp() {
        return getSharedPreferences("yuni", MODE_PRIVATE);
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // 任意线程 -> 主线程执行并等结果
    public <T> T onMain(Callable<T> c, T def) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return c.call();
            } catch (Exception e) {
                return def;
            }
        }
        FutureTask<T> f = new FutureTask<T>(c);
        h.post(f);
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        agent = new YuniAgent(this);
        SharedPreferences p = sp();
        if (p.getBoolean("ball_on", false) && p.getBoolean("auto", true)) showBall();
    }

    @Override
    public boolean onUnbind(Intent i) {
        if (agent != null) agent.stop();
        h.removeCallbacks(hideBubbleRun);
        closePanel();
        removeBubble();
        removeBall();
        inst = null;
        return super.onUnbind(i);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cs = e.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")) return;
        lastPkg = pkg;
        checkLock(pkg);
    }

    // ---------- APP 锁 ----------
    long lockUntil(String pkg) {
        try {
            return new JSONObject(sp().getString("locks", "{}")).optLong(pkg, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    void checkLock(String pkg) {
        long now = System.currentTimeMillis();
        long until = lockUntil(pkg);
        if (until <= now) return;
        if (now - lastKick < 800) return;
        lastKick = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
        long mins = (until - now + 59999) / 60000;
        String nm = sp().getString("char_name", "char");
        say("【" + nm + "】把这个 APP 锁住啦，还剩 " + mins + " 分钟", 4000);
    }

    public void relock() {
        final String p = lastPkg;
        h.post(new Runnable() {
            public void run() {
                if (p != null && p.length() > 0) checkLock(p);
            }
        });
    }

    // ---------- 悬浮球 ----------
    Bitmap avatarBitmap(int size) {
        String av = sp().getString("char_avatar", "");
        try {
            if (av.length() > 0) {
                int k = av.indexOf("base64,");
                if (k >= 0) av = av.substring(k + 7);
                byte[] b = Base64.decode(av, Base64.DEFAULT);
                Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bm != null) return Bitmap.createScaledBitmap(bm, size, size, true);
            }
        } catch (Exception e) {
        }
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#E8B4C8"));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.5f);
        p.setTextAlign(Paint.Align.CENTER);
        String nm = sp().getString("char_name", "char");
        String ch = nm.length() > 0 ? nm.substring(0, 1) : "C";
        c.drawText(ch, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p);
        return bm;
    }

    public void showBall() {
        h.post(new Runnable() {
            public void run() {
                if (wm == null) return;
                removeBall();
                int size = dp(56);
                ImageView iv = new ImageView(YuniA11yService.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(avatarBitmap(size * 2));
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        o.setOval(0, 0, v.getWidth(), v.getHeight());
                    }
                });
                iv.setClipToOutline(true);
                iv.setAlpha(0.95f);

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = sp().getInt("ball_x",
                    getResources().getDisplayMetrics().widthPixels - size - dp(4));
                lp.y = sp().getInt("ball_y", dp(220));
                ballLp = lp;
                iv.setOnTouchListener(new Drag());
                try {
                    wm.addView(iv, lp);
                    ball = iv;
                } catch (Exception e) {
                }
            }
        });
    }

    public void hideBall() {
        h.post(new Runnable() {
            public void run() {
                closePanel();
                removeBubble();
                removeBall();
            }
        });
    }

    public void refreshBall() {
        if (ball != null) showBall();
    }

    public void setBusy(final boolean busy) {
        h.post(new Runnable() {
            public void run() {
                if (ball != null) ball.setAlpha(busy ? 0.55f : 0.95f);
            }
        });
    }

    void removeBall() {
        if (ball != null && wm != null) {
            try {
                wm.removeView(ball);
            } catch (Exception e) {
            }
        }
        ball = null;
    }

    class Drag implements View.OnTouchListener {
        int sx, sy;
        float tx, ty;
        boolean moved;

        public boolean onTouch(View v, MotionEvent e) {
            if (ballLp == null) return false;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ballLp.x;
                    sy = ballLp.y;
                    tx = e.getRawX();
                    ty = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - tx;
                    float dy = e.getRawY() - ty;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        ballLp.x = sx + (int) dx;
                        ballLp.y = sy + (int) dy;
                        try {
                            wm.updateViewLayout(ball, ballLp);
                        } catch (Exception x) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) onBallTap();
                    else snap();
                    return true;
            }
            return false;
        }
    }

    void snap() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int size = ballLp.width;
        ballLp.x = (ballLp.x + size / 2 < w / 2) ? dp(4) : w - size - dp(4);
        try {
            wm.updateViewLayout(ball, ballLp);
        } catch (Exception x) {
        }
        sp().edit().putInt("ball_x", ballLp.x).putInt("ball_y", ballLp.y).apply();
    }

    void onBallTap() {
        if (agent != null && agent.isRunning()) {
            agent.stop();
            subtitle("好，我停下了。");
            subtitleEnd(2500);
            return;
        }
        if (panel != null) {
            closePanel();
        } else {
            openPanel();
        }
    }

    void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception e) {
        }
    }

    // ---------- 输入框 ----------
    class Panel extends LinearLayout {
        Panel(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) closePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }

    void openPanel() {
        if (wm == null || panel != null) return;
        removeBubble();
        SharedPreferences p = sp();
        String name = p.getString("char_name", "char");
        boolean act = p.getBoolean("opt_act", false);
        boolean see = p.getBoolean("opt_see", false);

        Panel root = new Panel(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FAFFFFFF"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#E8D5E8"));
        root.setBackground(bg);
        int pd = dp(12);
        root.setPadding(pd, dp(10), pd, pd);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(name + (act ? " · 可以帮你操作手机" : (see ? " · 能看屏幕" : " · 在听")));
        title.setTextColor(Color.parseColor("#4A3B4D"));
        title.setTextSize(13);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView back = new TextView(this);
        back.setText("回小手机");
        back.setTextColor(Color.parseColor("#8A7A8D"));
        back.setTextSize(12);
        back.setPadding(dp(8), dp(4), dp(8), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
                openApp();
            }
        });
        top.addView(back);
        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextColor(Color.parseColor("#8A7A8D"));
        x.setTextSize(15);
        x.setPadding(dp(8), dp(4), dp(4), dp(4));
        x.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closePanel();
            }
        });
        top.addView(x);
        root.addView(top);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText et = new EditText(this);
        et.setHint("对 " + name + " 说点什么…");
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setImeOptions(EditorInfo.IME_ACTION_SEND);
        et.setTextColor(Color.parseColor("#2A2230"));
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(Color.parseColor("#F3EEF3"));
        eg.setCornerRadius(dp(14));
        et.setBackground(eg);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(et, elp);
        TextView send = new TextView(this);
        send.setText("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(14);
        send.setGravity(Gravity.CENTER);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(Color.parseColor("#3A3A42"));
        sg.setCornerRadius(dp(14));
        send.setBackground(sg);
        send.setPadding(dp(16), dp(9), dp(16), dp(9));
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                submit();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8);
        row.addView(send, slp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        root.addView(row, rlp);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            dm.widthPixels - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(12);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        try {
            wm.addView(root, lp);
            panel = root;
            panelEt = et;
        } catch (Exception e) {
            return;
        }
        et.requestFocus();
        final EditText fet = et;
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    im.showSoftInput(fet, InputMethodManager.SHOW_IMPLICIT);
                } catch (Exception e) {
                }
            }
        }, 250);
    }

    void submit() {
        if (panelEt == null) return;
        String s = panelEt.getText().toString().trim();
        closePanel();
        if (s.length() == 0 || agent == null) return;
        agent.start(s);
    }

    void closePanel() {
        if (panel == null) return;
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (panelEt != null) im.hideSoftInputFromWindow(panelEt.getWindowToken(), 0);
        } catch (Exception e) {
        }
        try {
            wm.removeView(panel);
        } catch (Exception e) {
        }
        panel = null;
        panelEt = null;
    }

    // ---------- 字幕（char 说话，实时更新） ----------
    public void subtitle(final String text) {
        h.post(new Runnable() {
            public void run() {
                if (wm == null) return;
                h.removeCallbacks(hideBubbleRun);
                if (bubble == null) createBubble();
                if (bubble != null) {
                    String t = text == null ? "" : text;
                    if (t.length() > 280) t = "…" + t.substring(t.length() - 280);
                    bubble.setText(t);
                }
            }
        });
    }

    public void subtitleEnd(final long ms) {
        h.post(new Runnable() {
            public void run() {
                h.removeCallbacks(hideBubbleRun);
                h.postDelayed(hideBubbleRun, ms);
            }
        });
    }

    public void say(String text, long ms) {
        subtitle(text);
        subtitleEnd(ms);
    }

    void createBubble() {
        TextView t = new TextView(this);
        t.setTextColor(Color.parseColor("#4A3B4D"));
        t.setTextSize(13.5f);
        t.setMaxWidth(dp(250));
        t.setMaxLines(10);
        t.setLineSpacing(0f, 1.15f);
        int pd = dp(10);
        t.setPadding(pd, pd, pd, pd);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#F7FFFFFF"));
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), Color.parseColor("#E8D5E8"));
        t.setBackground(g);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int bx = ballLp != null ? ballLp.x : dp(12);
        int by = ballLp != null ? ballLp.y : dp(200);
        lp.x = Math.max(dp(8), Math.min(bx, dm.widthPixels - dp(270)));
        if (by > dm.heightPixels * 0.55f) {
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.y = dm.heightPixels - by + dp(6);
        } else {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.y = by + dp(62);
        }
        try {
            wm.addView(t, lp);
            bubble = t;
        } catch (Exception e) {
        }
    }

    void removeBubble() {
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception e) {
            }
        }
        bubble = null;
    }

    // ---------- 读屏 / 点击 ----------
    boolean blockedNow() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        return isBlocked(String.valueOf(r.getPackageName()));
    }

    public String readScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        String pkg = String.valueOf(root.getPackageName());
        if (isBlocked(pkg)) return "[该应用已被保护，不读取内容]";
        StringBuilder sb = new StringBuilder();
        sb.append("[当前应用 ").append(pkg).append("]\n");
        walk(root, sb, 0);
        if (sb.length() > 6000) return sb.substring(0, 6000);
        return sb.toString();
    }

    void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || sb.length() > 6000 || depth > 40) return;
        if (!n.isPassword() && n.isVisibleToUser()) {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String s = (t != null && t.length() > 0) ? t.toString() : (d != null ? d.toString() : "");
            if (s.length() > 0) {
                sb.append(s.replace('\n', ' '));
                if (n.isClickable()) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    sb.append(" [可点 ").append(r.centerX()).append(",").append(r.centerY()).append("]");
                }
                sb.append('\n');
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), sb, depth + 1);
        }
    }

    public boolean tap(int x, int y) {
        if (blockedNow()) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 60);
        return dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        if (blockedNow()) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription s =
            new GestureDescription.StrokeDescription(p, 0, Math.max(100, ms));
        return dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    public boolean clickText(String t) {
        if (t == null || t.length() == 0 || blockedNow()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(t);
        if (l == null) return false;
        for (AccessibilityNodeInfo n : l) {
            AccessibilityNodeInfo c = n;
            while (c != null && !c.isClickable()) c = c.getParent();
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    public boolean inputText(String t) {
        if (t == null || blockedNow()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    public boolean globalAct(String act) {
        if ("back".equals(act)) return performGlobalAction(GLOBAL_ACTION_BACK);
        if ("home".equals(act)) return performGlobalAction(GLOBAL_ACTION_HOME);
        if ("recents".equals(act)) return performGlobalAction(GLOBAL_ACTION_RECENTS);
        return false;
    }
}
