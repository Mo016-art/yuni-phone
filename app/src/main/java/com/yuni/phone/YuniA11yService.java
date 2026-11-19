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
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.List;

public class YuniA11yService extends AccessibilityService {
    public static YuniA11yService inst;

    // 支付、银行类应用：一律不读内容、不代点击
    static final String[] BLOCK = {
        "com.eg.android.AlipayGphone", "com.unionpay", "com.icbc", "com.chinamworld",
        "com.android.bankabc", "com.cmbchina", "com.bankcomm", "com.pingan", "com.paypal"
    };

    WindowManager wm;
    final Handler h = new Handler(Looper.getMainLooper());
    ImageView ball;
    WindowManager.LayoutParams ballLp;
    TextView bubble;
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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        SharedPreferences p = sp();
        if (p.getBoolean("ball_on", false) && p.getBoolean("auto", true)) showBall();
    }

    @Override
    public boolean onUnbind(Intent i) {
        h.removeCallbacks(hideBubbleRun);
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
                int size = dp(54);
                ImageView iv = new ImageView(YuniA11yService.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(avatarBitmap(size * 2));
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(Color.WHITE);
                iv.setBackground(ring);
                iv.setPadding(dp(2), dp(2), dp(2), dp(2));
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
                removeBubble();
                removeBall();
            }
        });
    }

    public void refreshBall() {
        if (ball != null) showBall();
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
                    if (!moved) openApp();
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

    void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception e) {
        }
    }

    // ---------- 气泡（char 说话） ----------
    public void say(final String text, final long ms) {
        h.post(new Runnable() {
            public void run() {
                if (wm == null) return;
                removeBubble();
                TextView t = new TextView(YuniA11yService.this);
                t.setText(text);
                t.setTextColor(Color.parseColor("#4A3B4D"));
                t.setTextSize(13);
                t.setMaxWidth(dp(230));
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
                lp.gravity = Gravity.TOP | Gravity.START;
                int sw = getResources().getDisplayMetrics().widthPixels;
                int bx = ballLp != null ? ballLp.x : dp(12);
                int by = ballLp != null ? ballLp.y : dp(200);
                lp.x = Math.max(dp(8), Math.min(bx, sw - dp(260)));
                lp.y = by + dp(62);
                try {
                    wm.addView(t, lp);
                    bubble = t;
                } catch (Exception e) {
                }
                h.removeCallbacks(hideBubbleRun);
                h.postDelayed(hideBubbleRun, ms);
            }
        });
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
        String p = String.valueOf(r.getPackageName());
        for (String b : BLOCK) {
            if (p.startsWith(b)) return true;
        }
        return false;
    }

    public String readScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        String pkg = String.valueOf(root.getPackageName());
        for (String b : BLOCK) {
            if (pkg.startsWith(b)) return "[该应用已被保护，不读取内容]";
        }
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

    public boolean globalAct(String act) {
        if ("back".equals(act)) return performGlobalAction(GLOBAL_ACTION_BACK);
        if ("home".equals(act)) return performGlobalAction(GLOBAL_ACTION_HOME);
        if ("recents".equals(act)) return performGlobalAction(GLOBAL_ACTION_RECENTS);
        return false;
    }
}
