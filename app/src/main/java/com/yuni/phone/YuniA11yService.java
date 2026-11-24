package com.yuni.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.os.Process;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import android.content.pm.PackageManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.PowerManager;
import java.util.Calendar;
import org.json.JSONArray;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    WindowManager.LayoutParams panelLp;
    EditText panelEt;
    int ballBaseY = 0;
    int panelBaseY = 0;
    int liftPx = 0;
    boolean insetsSeen = false;
    long lastKick = 0;
    String lastPkg = "";
    BroadcastReceiver tickRx;
    String curPkg = "";
    String rejPkg = "";
    long curSince = 0;
    long lastCo = 0;
    final Runnable coRun = new Runnable() {
        public void run() {
            try {
                coTick();
            } catch (Exception e) {
            }
            if (sp().getBoolean("co_on", false)) h.postDelayed(this, 5000);
        }
    };
    static final String ACT_TICK = "com.yuni.phone.PROACTIVE_TICK";
    static final String ACT_REQ = "com.yuni.phone.REQ_ANSWER";
    BroadcastReceiver reqRx;
    final Runnable enfRun = new Runnable() {
        public void run() {
            try {
                enforceTick();
            } catch (Exception e) {
            }
            h.postDelayed(this, 30000);
        }
    };
    String lastLine = "";
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
        scheduleProactive();
        startCompanion();
        registerReqRx();
        h.removeCallbacks(enfRun);
        h.postDelayed(enfRun, 30000);
    }

    @Override
    public boolean onUnbind(Intent i) {
        if (agent != null) agent.stop();
        h.removeCallbacks(hideBubbleRun);
        h.removeCallbacks(coRun);
        h.removeCallbacks(enfRun);
        if (reqRx != null) {
            try {
                unregisterReceiver(reqRx);
            } catch (Exception e) {
            }
            reqRx = null;
        }
        closePanel();
        removeBubble();
        removeBall();
        cancelProactive();
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
        if (!pkg.equals(curPkg) && !pkg.equals(rejPkg)) {
            boolean isApp = false;
            try {
                isApp = getPackageManager().getLaunchIntentForPackage(pkg) != null;
            } catch (Exception ex) {
            }
            if (isApp) {
                curPkg = pkg;
                curSince = System.currentTimeMillis();
            } else {
                rejPkg = pkg;
            }
        }
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
        String reason = "";
        try {
            reason = new JSONObject(sp().getString("lock_reasons", "{}")).optString(pkg, "");
        } catch (Exception e) {
        }
        say("【" + nm + "】锁了这个 APP" + (reason.length() > 0 ? "：" + reason : "") + "（还剩 " + mins + " 分钟）", 5000);
    }

    public void relock() {
        final String p = lastPkg;
        h.post(new Runnable() {
            public void run() {
                if (p != null && p.length() > 0) checkLock(p);
            }
        });
    }

    // ---------- 后台主动找你：定时 + 通知 ----------
    PendingIntent tickPi() {
        Intent it = new Intent(ACT_TICK).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, 7, it,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    void cancelProactive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(tickPi());
        } catch (Exception e) {
        }
        if (tickRx != null) {
            try {
                unregisterReceiver(tickRx);
            } catch (Exception e) {
            }
            tickRx = null;
        }
    }

    public void scheduleProactive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = tickPi();
            if (am != null) am.cancel(pi);
            if (!sp().getBoolean("pro_on", false)) return;
            if (tickRx == null) {
                tickRx = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        onProTick();
                    }
                };
                IntentFilter f = new IntentFilter(ACT_TICK);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(tickRx, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(tickRx, f);
                }
            }
            int min = Math.max(5, sp().getInt("pro_min", 60));
            long ms = (long) (min * 60000L * (0.8 + Math.random() * 0.4));
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + ms, pi);
        } catch (Exception e) {
        }
    }

    void onProTick() {
        scheduleProactive();
        if (agent == null || agent.isRunning()) return;
        SharedPreferences p = sp();
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int qs = p.getInt("pro_qs", 23);
        int qe = p.getInt("pro_qe", 8);
        boolean quiet = qs == qe ? false : (qs > qe ? (hr >= qs || hr < qe) : (hr >= qs && hr < qe));
        if (quiet) return;
        if (p.getBoolean("pro_screen", true)) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return;
        }
        agent.proactive(false);
    }

    // char 主动说的话：悬浮球字幕 + 系统通知 + 存进收件箱（打开小手机后同步进传讯）
    public void deliver(final String msg) {
        addInbox(msg);
        h.post(new Runnable() {
            public void run() {
                if (ball != null) say(msg, 12000);
                notifyMsg(msg);
            }
        });
    }

    void addInbox(String msg) {
        try {
            JSONArray a = new JSONArray(sp().getString("inbox", "[]"));
            JSONObject o = new JSONObject();
            o.put("t", msg);
            o.put("ts", System.currentTimeMillis());
            a.put(o);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - 30); i < a.length(); i++) keep.put(a.get(i));
            sp().edit().putString("inbox", keep.toString()).apply();
        } catch (Exception e) {
        }
    }

    void notifyMsg(String msg) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("char_msg", "char 的消息",
                    NotificationManager.IMPORTANCE_HIGH));
            }
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "char_msg") : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(sp().getString("char_name", "char"))
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setLargeIcon(avatarBitmap(dp(48) * 2))
                .setContentIntent(pi)
                .setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            nm.notify((int) (System.currentTimeMillis() / 1000L), b.build());
        } catch (Exception e) {
        }
    }

    // ---------- 管控申请：通知上的同意 / 拒绝 ----------
    void registerReqRx() {
        if (reqRx != null) return;
        reqRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                handleReq(i.getStringExtra("id"), i.getBooleanExtra("ok", false));
            }
        };
        IntentFilter f = new IntentFilter(ACT_REQ);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(reqRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(reqRx, f);
        }
    }

    PendingIntent reqPi(String id, boolean ok) {
        Intent it = new Intent(ACT_REQ).setPackage(getPackageName()).putExtra("id", id).putExtra("ok", ok);
        return PendingIntent.getBroadcast(this, (Math.abs(id.hashCode()) % 100000) * 2 + (ok ? 1 : 0), it,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public void handleReq(String id, boolean ok) {
        JSONObject hit = YuniRules.answer(this, id, ok);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && id != null) nm.cancel(Math.abs(id.hashCode()));
        } catch (Exception e) {
        }
        if (hit == null) return;
        String app = hit.optString("name");
        if (agent != null) {
            agent.note(ok
                ? "对方同意了你对「" + app + "」的管控申请：每天最多 " + hit.optInt("limit") + " 分钟，管 " + hit.optInt("days") + " 天。"
                : "对方拒绝了你对「" + app + "」的管控申请，3 天内别再提这个应用。");
        }
        say(ok ? "好，" + app + " 我来管" : "知道了，不管了", 3000);
    }

    // 带「同意 / 拒绝」按钮的通知；没开通知权限时，仍可在设置页处理
    public void notifyRequest(String body, String id) {
        try {
            JSONArray pd = YuniRules.pending(this);
            JSONObject q = null;
            for (int i = 0; i < pd.length(); i++) {
                if (id.equals(pd.getJSONObject(i).optString("id"))) q = pd.getJSONObject(i);
            }
            if (q == null) return;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("char_msg", "char 的消息",
                    NotificationManager.IMPORTANCE_HIGH));
            }
            String sum = sp().getString("char_name", "char") + " 想管一下你的「" + q.optString("name")
                + "」：每天最多 " + q.optInt("limit") + " 分钟，持续 " + q.optInt("days") + " 天。理由：" + q.optString("reason");
            String text = (body != null && body.length() > 0) ? body + "\n\n" + sum : sum;
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "char_msg") : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("管控申请")
                .setContentText(sum)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setLargeIcon(avatarBitmap(dp(48) * 2))
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒绝", reqPi(id, false))
                .addAction(android.R.drawable.ic_menu_send, "同意", reqPi(id, true))
                .setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            nm.notify(Math.abs(id.hashCode()), b.build());
        } catch (Exception e) {
        }
    }

    // char 主动说的话 + 管控申请：字幕、收件箱、带按钮的通知
    public void deliverRequest(final String msg, final String id) {
        addInbox(msg);
        h.post(new Runnable() {
            public void run() {
                if (ball != null) say(msg, 12000);
                notifyRequest(msg, id);
            }
        });
    }

    // ---------- 已同意的管控：到每天上限就锁 ----------
    long usageToday(String pkg) {
        if (!hasUsageAccess()) return 0;
        try {
            UsageStatsManager um = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            Map<String, UsageStats> m = um.queryAndAggregateUsageStats(c.getTimeInMillis(), System.currentTimeMillis());
            UsageStats u = m.get(pkg);
            return u == null ? 0 : u.getTotalTimeInForeground();
        } catch (Exception e) {
            return 0;
        }
    }

    void enforceTick() {
        if (curPkg.length() == 0) return;
        JSONObject rules = YuniRules.rules(this);
        if (rules.length() == 0) return;
        JSONObject r = rules.optJSONObject(curPkg);
        if (r == null) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        YuniRules.addOwnUse(this, curPkg, 30000L);
        long now = System.currentTimeMillis();
        if (now < r.optLong("snooze", 0)) return;
        long limMs = r.optInt("limit", 60) * 60000L;
        long used = Math.max(usageToday(curPkg), YuniRules.ownUse(this, curPkg));
        if (used < limMs) return;
        try {
            JSONObject lk = new JSONObject(sp().getString("locks", "{}"));
            if (lk.optLong(curPkg, 0) > now) return;
            lk.put(curPkg, now + 60 * 60000L);
            JSONObject rs = new JSONObject(sp().getString("lock_reasons", "{}"));
            rs.put(curPkg, "你同意过每天最多 " + r.optInt("limit", 60) + " 分钟，今天已经到了");
            sp().edit().putString("locks", lk.toString()).putString("lock_reasons", rs.toString()).apply();
        } catch (Exception e) {
            return;
        }
        checkLock(curPkg);
    }

    // ---------- 使用时长 ----------
    public boolean hasUsageAccess() {
        try {
            AppOpsManager ao = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static String fmtMin(long min) {
        if (min < 60) return min + " 分钟";
        return (min / 60) + " 小时" + (min % 60 > 0 ? " " + (min % 60) + " 分" : "");
    }

    public String currentAppName() {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(curPkg, 0)));
        } catch (Exception e) {
            return curPkg;
        }
    }

    public long currentAppMinutes() {
        if (curSince == 0) return 0;
        return Math.max(0, (System.currentTimeMillis() - curSince) / 60000L);
    }

    public String usageSummary() {
        if (!hasUsageAccess()) return "";
        try {
            UsageStatsManager um = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            Map<String, UsageStats> m = um.queryAndAggregateUsageStats(c.getTimeInMillis(), System.currentTimeMillis());
            ArrayList<UsageStats> list = new ArrayList<UsageStats>();
            PackageManager pm = getPackageManager();
            long total = 0;
            for (UsageStats u : m.values()) {
                if (u.getTotalTimeInForeground() < 60000L) continue;
                String p = u.getPackageName();
                if (p.equals(getPackageName()) || pm.getLaunchIntentForPackage(p) == null) continue;
                list.add(u);
                total += u.getTotalTimeInForeground();
            }
            Collections.sort(list, new Comparator<UsageStats>() {
                public int compare(UsageStats a, UsageStats b) {
                    return Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground());
                }
            });
            StringBuilder sb = new StringBuilder("【对方的手机使用情况（真实数据，今天截至现在）】");
            sb.append("共约 ").append(fmtMin(total / 60000L)).append("：");
            int n = 0;
            for (UsageStats u : list) {
                if (n++ >= 8) break;
                String nm;
                try {
                    nm = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(u.getPackageName(), 0)));
                } catch (Exception e) {
                    nm = u.getPackageName();
                }
                if (n > 1) sb.append("、");
                sb.append(nm).append(" ").append(fmtMin(u.getTotalTimeInForeground() / 60000L));
            }
            if (curPkg.length() > 0) {
                sb.append("。此刻在用：").append(currentAppName()).append("，已连续约 ").append(currentAppMinutes()).append(" 分钟");
            }
            long lastUser = sp().getLong("last_user_ts", 0);
            if (lastUser > 0) {
                sb.append("。对方上次对你说话是 ").append(fmtMin((System.currentTimeMillis() - lastUser) / 60000L)).append("前");
            }
            return sb.append("。").toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 陪玩 ----------
    public void startCompanion() {
        h.removeCallbacks(coRun);
        if (sp().getBoolean("co_on", false)) h.postDelayed(coRun, 5000);
    }

    public void markCo() {
        lastCo = System.currentTimeMillis();
    }

    void coTick() {
        if (agent == null || agent.isRunning() || panel != null) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        if (lastPkg.length() == 0 || isBlocked(lastPkg) || lastPkg.equals(getPackageName())) return;
        int sec = Math.max(20, sp().getInt("co_sec", 45));
        if (System.currentTimeMillis() - lastCo < sec * 1000L) return;
        agent.companion();
    }

    // ---------- 通用样式 ----------
    GradientDrawable frost(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#B8FFFFFF"));
        g.setCornerRadius(radius);
        g.setStroke(dp(1), Color.parseColor("#66FFFFFF"));
        return g;
    }

    void blur(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                lp.setBlurBehindRadius(dp(22));
            } catch (Throwable t) {
            }
        }
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
                int size = dp(44);
                ImageView iv = new ImageView(YuniA11yService.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(avatarBitmap(size * 3));
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
        boolean down;
        boolean longDone;
        final Runnable longRun = new Runnable() {
            public void run() {
                if (down && !moved) {
                    longDone = true;
                    openApp();
                }
            }
        };

        public boolean onTouch(View v, MotionEvent e) {
            if (ballLp == null) return false;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ballLp.x;
                    sy = ballLp.y;
                    tx = e.getRawX();
                    ty = e.getRawY();
                    moved = false;
                    down = true;
                    longDone = false;
                    h.postDelayed(longRun, 650);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - tx;
                    float dy = e.getRawY() - ty;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        moved = true;
                        h.removeCallbacks(longRun);
                    }
                    if (moved && panel == null) {
                        ballLp.x = sx + (int) dx;
                        ballLp.y = sy + (int) dy;
                        try {
                            wm.updateViewLayout(ball, ballLp);
                        } catch (Exception x) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    h.removeCallbacks(longRun);
                    if (longDone) return true;
                    if (!moved) onBallTap();
                    else if (panel == null) snap();
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
        if (agent != null && agent.isBusyUser()) {
            agent.stop();
            subtitle("好，我停下了。");
            subtitleEnd(2500);
            return;
        }
        if (panel != null) closePanel();
        else openPanel();
    }

    void openApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception e) {
        }
    }

    // ---------- 输入框：从头像侧边延展出来，被键盘顶起 ----------
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
        if (wm == null || panel != null || ballLp == null) return;
        removeBubble();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = ballLp.width;
        boolean right = ballLp.x + size / 2 >= dm.widthPixels / 2;
        int space = right ? ballLp.x : dm.widthPixels - ballLp.x - size;
        int pw = Math.min(dp(228), space - dp(12));
        if (pw < dp(150)) pw = dp(150);
        int ph = dp(40);
        String name = sp().getString("char_name", "char");

        Panel root = new Panel(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackground(frost(dp(20)));
        root.setPadding(dp(14), 0, dp(4), 0);

        EditText et = new EditText(this);
        et.setHint("对 " + name + " 说…");
        et.setHintTextColor(Color.parseColor("#99555555"));
        et.setTextColor(Color.parseColor("#2A2230"));
        et.setTextSize(13.5f);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setPadding(0, 0, 0, 0);
        et.setMinHeight(0);
        et.setMinimumHeight(0);
        et.setIncludeFontPadding(false);
        et.setImeOptions(EditorInfo.IME_ACTION_SEND);
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        root.addView(et, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView send = new TextView(this);
        send.setText("↑");
        send.setTextColor(Color.WHITE);
        send.setTextSize(16);
        send.setGravity(Gravity.CENTER);
        GradientDrawable sg = new GradientDrawable();
        sg.setShape(GradientDrawable.OVAL);
        sg.setColor(Color.parseColor("#CC3A3A42"));
        send.setBackground(sg);
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                submit();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(30), dp(30));
        slp.leftMargin = dp(6);
        root.addView(send, slp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            pw, ph,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = right ? ballLp.x - dp(6) - pw : ballLp.x + size + dp(6);
        panelBaseY = ballLp.y + (size - ph) / 2;
        ballBaseY = ballLp.y;
        liftPx = 0;
        insetsSeen = false;
        lp.y = panelBaseY;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        blur(lp);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                insetsSeen = true;
                int kb;
                if (Build.VERSION.SDK_INT >= 30) kb = ins.getInsets(WindowInsets.Type.ime()).bottom;
                else kb = ins.getSystemWindowInsetBottom();
                liftFor(kb);
                return ins;
            }
        });

        try {
            wm.addView(root, lp);
            panel = root;
            panelLp = lp;
            panelEt = et;
        } catch (Exception e) {
            return;
        }
        root.requestApplyInsets();
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
        // 系统没把键盘高度告诉我们时，按常见键盘高度估一个
        h.postDelayed(new Runnable() {
            public void run() {
                if (panel != null && !insetsSeen) {
                    liftFor((int) (getResources().getDisplayMetrics().heightPixels * 0.40f));
                }
            }
        }, 700);
    }

    void liftFor(int kb) {
        if (panel == null || panelLp == null || ballLp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int lift = 0;
        if (kb > dp(120)) {
            int limit = dm.heightPixels - kb - dp(12);
            int bottom = Math.max(panelBaseY + panelLp.height, ballBaseY + ballLp.height);
            lift = Math.max(0, bottom - limit);
        }
        if (lift == liftPx) return;
        liftPx = lift;
        panelLp.y = panelBaseY - lift;
        ballLp.y = ballBaseY - lift;
        try {
            wm.updateViewLayout(panel, panelLp);
        } catch (Exception e) {
        }
        try {
            if (ball != null) wm.updateViewLayout(ball, ballLp);
        } catch (Exception e) {
        }
    }

    void submit() {
        if (panelEt == null) return;
        String s = panelEt.getText().toString().trim();
        closePanel();
        if (s.length() == 0 || agent == null) return;
        startWithCtx(s);
    }

    // 发送前向小手机页面要一份最新的「人设 / 关系 / 记忆 / 最近聊天」
    void startWithCtx(final String s) {
        final AtomicBoolean started = new AtomicBoolean(false);
        final Runnable go = new Runnable() {
            public void run() {
                if (started.compareAndSet(false, true)) agent.start(s);
            }
        };
        MainActivity a = MainActivity.inst;
        if (a == null || a.w == null) {
            go.run();
            return;
        }
        h.postDelayed(go, 1500);
        try {
            a.w.evaluateJavascript(
                "(function(){try{return window.__ntCtx?window.__ntCtx():''}catch(e){return ''}})()",
                new ValueCallback<String>() {
                    public void onReceiveValue(String v) {
                        try {
                            Object o = new JSONTokener(v).nextValue();
                            if (o instanceof String && ((String) o).length() > 0) {
                                String raw = (String) o;
                                try {
                                    JSONObject j = new JSONObject(raw);
                                    sp().edit().putString("char_sysmain", j.optString("sys"))
                                        .putString("char_context", j.optString("ctx")).apply();
                                } catch (Exception pe) {
                                    sp().edit().putString("char_context", raw).apply();
                                }
                            }
                        } catch (Exception e) {
                        }
                        h.removeCallbacks(go);
                        go.run();
                    }
                });
        } catch (Exception e) {
            h.removeCallbacks(go);
            go.run();
        }
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
        panelLp = null;
        panelEt = null;
        if (ballLp != null && liftPx != 0) {
            ballLp.y = ballBaseY;
            liftPx = 0;
            try {
                if (ball != null) wm.updateViewLayout(ball, ballLp);
            } catch (Exception e) {
            }
        }
    }

    // ---------- 字幕：贴在头像侧边，边说边长 ----------
    public void subtitle(final String text) {
        h.post(new Runnable() {
            public void run() {
                if (wm == null) return;
                h.removeCallbacks(hideBubbleRun);
                if (bubble == null) createBubble();
                if (bubble != null) {
                    String t = text == null ? "" : text;
                    if (t.trim().length() == 0) t = "…";
                    if (t.length() > 260) t = "…" + t.substring(t.length() - 260);
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
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = ballLp != null ? ballLp.width : dp(44);
        int bx = ballLp != null ? ballLp.x : dm.widthPixels - size - dp(4);
        int by = ballLp != null ? ballLp.y : dp(220);
        boolean right = bx + size / 2 >= dm.widthPixels / 2;
        int space = right ? bx : dm.widthPixels - bx - size;

        TextView t = new TextView(this);
        t.setTextColor(Color.parseColor("#3A2F3D"));
        t.setTextSize(13f);
        t.setMaxWidth(Math.max(dp(120), Math.min(dp(210), space - dp(10))));
        t.setMaxLines(10);
        t.setLineSpacing(0f, 1.12f);
        t.setPadding(dp(11), dp(8), dp(11), dp(8));
        t.setBackground(frost(dp(16)));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        boolean low = by > dm.heightPixels * 0.62f;
        lp.gravity = (low ? Gravity.BOTTOM : Gravity.TOP) | (right ? Gravity.END : Gravity.START);
        lp.x = right ? dm.widthPixels - bx + dp(6) : bx + size + dp(6);
        lp.y = low ? dm.heightPixels - (by + size) : by;
        blur(lp);
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
        lastLine = "";
        walk(root, sb, 0, false);
        if (sb.length() > 6000) return sb.substring(0, 6000);
        return sb.toString();
    }

    void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth, boolean ancClick) {
        if (n == null || sb.length() > 6000 || depth > 40) return;
        boolean canClick = ancClick || n.isClickable();
        if (!n.isPassword() && n.isVisibleToUser()) {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String s = (t != null && t.length() > 0) ? t.toString() : (d != null ? d.toString() : "");
            if (s.length() > 0) {
                StringBuilder ln = new StringBuilder(s.replace('\n', ' '));
                if (n.isSelected() || n.isChecked()) ln.append(" [已选中]");
                if (n.isEditable()) ln.append(" [输入框]");
                if (canClick) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    ln.append(" [可点 ").append(r.centerX()).append(",").append(r.centerY()).append("]");
                }
                String line = ln.toString();
                if (!line.equals(lastLine)) {
                    sb.append(line).append('\n');
                    lastLine = line;
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), sb, depth + 1, canClick);
        }
    }

    public boolean tap(int x, int y) {
        if (blockedNow()) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 50);
        return dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    public int[] screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        } catch (Exception e) {
            dm = getResources().getDisplayMetrics();
        }
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    // 多指手势：每根手指 {x1,y1,x2,y2,ms}。先移动到终点，再按住到 ms 结束（推摇杆、蓄力、长按）
    public boolean gesture(int[][] st) {
        if (st == null || st.length == 0 || blockedNow()) return false;
        try {
            GestureDescription.StrokeDescription[] s1 = new GestureDescription.StrokeDescription[st.length];
            GestureDescription.Builder b1 = new GestureDescription.Builder();
            boolean cont = false;
            for (int i = 0; i < st.length; i++) {
                int ms = Math.max(100, st[i][4]);
                Path p = new Path();
                p.moveTo(st[i][0], st[i][1]);
                if (st[i][0] != st[i][2] || st[i][1] != st[i][3]) p.lineTo(st[i][2], st[i][3]);
                if (Build.VERSION.SDK_INT >= 26 && ms > 450) {
                    s1[i] = new GestureDescription.StrokeDescription(p, 0, 250, true);
                    cont = true;
                } else {
                    s1[i] = new GestureDescription.StrokeDescription(p, 0, ms);
                }
                b1.addStroke(s1[i]);
            }
            GestureDescription g1 = b1.build();
            if (!cont) return dispatchGesture(g1, null, null);
            final GestureDescription.Builder b2 = new GestureDescription.Builder();
            for (int i = 0; i < st.length; i++) {
                int ms = Math.max(100, st[i][4]);
                if (Build.VERSION.SDK_INT >= 26 && ms > 450) {
                    Path q = new Path();
                    q.moveTo(st[i][2], st[i][3]);
                    b2.addStroke(s1[i].continueStroke(q, 0, ms - 250, false));
                }
            }
            return dispatchGesture(g1, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    dispatchGesture(b2.build(), null, null);
                }
            }, null);
        } catch (Exception e) {
            return false;
        }
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

    // 点含文字的控件：优先完全匹配、只看可见的；找不到可点的父级就直接点它的位置
    public boolean clickText(String t) {
        if (t == null || t.length() == 0 || blockedNow()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(t);
        if (l == null) return false;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : l) {
            if (!n.isVisibleToUser()) continue;
            CharSequence tx = n.getText();
            if (tx == null) tx = n.getContentDescription();
            String s = tx == null ? "" : tx.toString();
            int score = s.equals(t) ? 0 : (10 + Math.abs(s.length() - t.length()));
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best == null) return false;
        AccessibilityNodeInfo c = best;
        while (c != null && !c.isClickable()) c = c.getParent();
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        best.getBoundsInScreen(r);
        return tap(r.centerX(), r.centerY());
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

    public boolean imeEnter() {
        if (Build.VERSION.SDK_INT < 30 || blockedNow()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) return false;
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    public boolean globalAct(String act) {
        if ("back".equals(act)) return performGlobalAction(GLOBAL_ACTION_BACK);
        if ("home".equals(act)) return performGlobalAction(GLOBAL_ACTION_HOME);
        if ("recents".equals(act)) return performGlobalAction(GLOBAL_ACTION_RECENTS);
        return false;
    }

    // ---------- 截图（识图用，Android 11+） ----------
    public String screenshotB64() {
        if (Build.VERSION.SDK_INT < 30 || blockedNow()) return "";
        final String[] out = {""};
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult r) {
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                            if (hw != null) {
                                Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                                int w = 800;
                                int hh = Math.round(sw.getHeight() * (w / (float) sw.getWidth()));
                                Bitmap sc = Bitmap.createScaledBitmap(sw, w, hh, true);
                                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                                sc.compress(Bitmap.CompressFormat.JPEG, 60, bo);
                                out[0] = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                            }
                            r.getHardwareBuffer().close();
                        } catch (Throwable e) {
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        latch.countDown();
                    }
                });
            latch.await(4, TimeUnit.SECONDS);
        } catch (Throwable e) {
        }
        return out[0];
    }
}
