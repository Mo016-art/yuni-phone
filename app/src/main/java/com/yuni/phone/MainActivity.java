package com.yuni.phone;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.Manifest;
import android.app.AppOpsManager;
import android.os.Process;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.io.ByteArrayOutputStream;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final int REQ_FILE = 1001;
    public static MainActivity inst;
    WebView w;
    ValueCallback<Uri[]> filePathCb;

    static final String DL_JS =
        "(function(){if(window.__yuniDL)return;window.__yuniDL=1;"
        + "var oc=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){var a=this;"
        + "if(a.download&&a.href&&a.href.indexOf('blob:')===0&&window.YuniBridge){"
        + "fetch(a.href).then(function(r){return r.blob()}).then(function(b){"
        + "var fr=new FileReader();fr.onload=function(){"
        + "window.YuniBridge.postMessage(JSON.stringify({id:0,m:'saveFile',a:{name:a.download,"
        + "mime:b.type||'application/octet-stream',b64:String(fr.result).split(',')[1]||''}}))"
        + "};fr.readAsDataURL(b)});return}"
        + "return oc.apply(this,arguments)};})();";

    SharedPreferences sp() {
        return getSharedPreferences("yuni", MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        w = new WebView(this);
        setContentView(w);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(DL_JS, null);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams p) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCb = null;
                    return false;
                }
                return true;
            }
        });

        // 只有本应用自己的页面（主框架）才能调用原生功能，内嵌的外部网页调不到
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Set<String> rules = new HashSet<String>();
            rules.add("https://" + HOST);
            WebViewCompat.addWebMessageListener(w, "YuniBridge", rules,
                new WebViewCompat.WebMessageListener() {
                    @Override
                    public void onPostMessage(WebView v, WebMessageCompat msg, Uri origin,
                                              boolean isMain, JavaScriptReplyProxy reply) {
                        if (!isMain || origin == null || !HOST.equals(origin.getHost())) return;
                        String idRaw = "0";
                        String res = "null";
                        try {
                            JSONObject o = new JSONObject(msg.getData());
                            idRaw = String.valueOf(o.opt("id"));
                            JSONObject a = o.optJSONObject("a");
                            if (a == null) a = new JSONObject();
                            res = handle(o.optString("m"), a);
                        } catch (Exception e) {
                            res = "null";
                        }
                        reply.postMessage("{\"id\":" + idRaw + ",\"r\":" + res + "}");
                    }
                });
        }

        w.loadUrl("https://" + HOST + "/assets/index.html");
    }

    String handle(String m, JSONObject a) throws Exception {
        YuniA11yService s = YuniA11yService.inst;
        if ("saveFile".equals(m)) {
            saveFile(a.optString("name"), a.optString("mime", "application/octet-stream"),
                a.optString("b64"));
            return "true";
        }
        if ("status".equals(m)) {
            JSONObject o = new JSONObject();
            o.put("a11y", a11yOn());
            o.put("live", s != null);
            o.put("ball", sp().getBoolean("ball_on", false));
            o.put("auto", sp().getBoolean("auto", true));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("api", sp().getString("api_url", "").length() > 0);
            o.put("see", sp().getBoolean("opt_see", false));
            o.put("act", sp().getBoolean("opt_act", false));
            o.put("img", sp().getBoolean("opt_img", false));
            o.put("charName", sp().getString("char_name", ""));
            o.put("avatar", sp().getString("char_avatar", "").length() > 0);
            o.put("ctxLen", sp().getString("char_context", "").length());
            o.put("sysLen", sp().getString("char_sysmain", "").length());
            NotificationManager nmg = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            o.put("notif", nmg != null && nmg.areNotificationsEnabled());
            o.put("pro", sp().getBoolean("pro_on", false));
            o.put("usage", hasUsage());
            o.put("co", sp().getBoolean("co_on", false));
            o.put("autolock", sp().getBoolean("opt_autolock", false));
            return o.toString();
        }
        if ("openA11y".equals(m)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return "true";
        }
        if ("openAppInfo".equals(m)) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
            return "true";
        }
        if ("openBattery".equals(m)) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return "true";
        }
        if ("getApps".equals(m)) {
            PackageManager pm = getPackageManager();
            Intent mi = new Intent(Intent.ACTION_MAIN);
            mi.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> l = pm.queryIntentActivities(mi, 0);
            JSONArray arr = new JSONArray();
            HashSet<String> seen = new HashSet<String>();
            for (ResolveInfo r : l) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName()) || !seen.add(p)) continue;
                JSONObject o = new JSONObject();
                o.put("p", p);
                o.put("n", String.valueOf(r.loadLabel(pm)));
                try {
                    Drawable d = r.loadIcon(pm);
                    Bitmap bm = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(bm);
                    d.setBounds(0, 0, 64, 64);
                    d.draw(cv);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
                    o.put("i", "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                } catch (Exception ie) {
                }
                arr.put(o);
            }
            return arr.toString();
        }
        if ("setChar".equals(m)) {
            sp().edit().putString("char_name", a.optString("name"))
                .putString("char_avatar", a.optString("avatar"))
                .putString("char_persona", a.optString("persona"))
                .putString("user_name", a.optString("userName"))
                .putString("char_sysmain", a.optString("sysMain"))
                .putString("char_context", a.optString("context")).apply();
            if (s != null) s.refreshBall();
            return "true";
        }
        if ("setBall".equals(m)) {
            boolean on = a.optBoolean("on");
            sp().edit().putBoolean("ball_on", on).apply();
            if (s != null) {
                if (on) s.showBall();
                else s.hideBall();
            }
            return "{\"live\":" + (s != null) + "}";
        }
        if ("setApi".equals(m)) {
            sp().edit().putString("api_proto", a.optString("proto", "openai"))
                .putString("api_url", a.optString("url"))
                .putString("api_key", a.optString("key"))
                .putString("api_model", a.optString("model")).apply();
            return "true";
        }
        if ("setOpts".equals(m)) {
            sp().edit().putBoolean("opt_see", a.optBoolean("see"))
                .putBoolean("opt_act", a.optBoolean("act"))
                .putBoolean("opt_img", a.optBoolean("img")).apply();
            return "true";
        }
        if ("clearChat".equals(m)) {
            if (s != null && s.agent != null) s.agent.clearHist();
            else sp().edit().remove("chat_hist").apply();
            return "true";
        }
        if ("setProactive".equals(m)) {
            sp().edit().putBoolean("pro_on", a.optBoolean("on"))
                .putInt("pro_min", Math.max(5, a.optInt("minutes", 60)))
                .putInt("pro_qs", a.optInt("qs", 23))
                .putInt("pro_qe", a.optInt("qe", 8))
                .putBoolean("pro_screen", a.optBoolean("screenOnly", true)).apply();
            if (s != null) s.scheduleProactive();
            return "{\"live\":" + (s != null) + "}";
        }
        if ("setCompanion".equals(m)) {
            sp().edit().putBoolean("co_on", a.optBoolean("on"))
                .putInt("co_sec", Math.max(20, a.optInt("sec", 45))).apply();
            if (s != null) s.startCompanion();
            return "{\"live\":" + (s != null) + "}";
        }
        if ("setAutoLock".equals(m)) {
            sp().edit().putBoolean("opt_autolock", a.optBoolean("on")).apply();
            return "true";
        }
        if ("openUsage".equals(m)) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return "true";
        }
        if ("proactiveNow".equals(m)) {
            if (s == null || s.agent == null) return "false";
            s.agent.proactive(true);
            return "true";
        }
        if ("drainInbox".equals(m)) {
            String box = sp().getString("inbox", "[]");
            sp().edit().putString("inbox", "[]").apply();
            return box;
        }
        if ("askNotif".equals(m)) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            } else {
                Intent ni = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                ni.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(ni);
            }
            return "true";
        }
        if ("openNotif".equals(m)) {
            Intent ni = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            ni.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(ni);
            return "true";
        }
        if ("setAuto".equals(m)) {
            sp().edit().putBoolean("auto", a.optBoolean("on")).apply();
            return "true";
        }
        if ("setGuard".equals(m)) {
            JSONArray arr = a.optJSONArray("list");
            sp().edit().putString("guard", arr == null ? "[]" : arr.toString()).apply();
            return "true";
        }
        if ("lockApp".equals(m)) {
            String pkg = a.optString("pkg");
            int min = a.optInt("minutes", 30);
            if (min < 1) min = 1;
            if (min > 10080) min = 10080;
            if (!inGuard(pkg)) return "false";
            JSONObject lk = new JSONObject(sp().getString("locks", "{}"));
            lk.put(pkg, System.currentTimeMillis() + min * 60000L);
            JSONObject rs = new JSONObject(sp().getString("lock_reasons", "{}"));
            rs.put(pkg, "你自己设置的冻结");
            sp().edit().putString("locks", lk.toString()).putString("lock_reasons", rs.toString()).apply();
            return "true";
        }
        if ("unlockApp".equals(m)) {
            JSONObject lk = new JSONObject(sp().getString("locks", "{}"));
            lk.remove(a.optString("pkg"));
            sp().edit().putString("locks", lk.toString()).apply();
            return "true";
        }
        if ("getLocks".equals(m)) {
            JSONObject lk = new JSONObject(sp().getString("locks", "{}"));
            JSONObject out = new JSONObject();
            JSONObject keep = new JSONObject();
            long now = System.currentTimeMillis();
            Iterator<String> it = lk.keys();
            while (it.hasNext()) {
                String k = it.next();
                long until = lk.optLong(k, 0);
                if (until > now) {
                    keep.put(k, until);
                    out.put(k, (until - now + 59999) / 60000);
                }
            }
            sp().edit().putString("locks", keep.toString()).apply();
            return out.toString();
        }
        if ("say".equals(m)) {
            if (s == null) return "false";
            s.say(a.optString("text"), 5000);
            return "true";
        }
        if ("readScreen".equals(m)) {
            if (s == null) return JSONObject.quote("");
            return JSONObject.quote(s.readScreen());
        }
        if ("tap".equals(m)) {
            return String.valueOf(s != null && s.tap(a.optInt("x"), a.optInt("y")));
        }
        if ("swipe".equals(m)) {
            return String.valueOf(s != null && s.swipe(a.optInt("x1"), a.optInt("y1"),
                a.optInt("x2"), a.optInt("y2"), a.optInt("ms", 300)));
        }
        if ("clickText".equals(m)) {
            return String.valueOf(s != null && s.clickText(a.optString("text")));
        }
        if ("globalAct".equals(m)) {
            return String.valueOf(s != null && s.globalAct(a.optString("act")));
        }
        if ("readAfter".equals(m)) {
            if (s == null) return "false";
            final YuniA11yService sv = s;
            sv.h.postDelayed(new Runnable() {
                public void run() {
                    String t = sv.readScreen();
                    sp().edit().putString("last_read", t).apply();
                    sv.say("读到 " + t.length() + " 个字，回到 Yuni Phone 查看", 4000);
                }
            }, a.optInt("ms", 3000));
            return "true";
        }
        if ("getLastRead".equals(m)) {
            return JSONObject.quote(sp().getString("last_read", ""));
        }
        return "null";
    }

    boolean hasUsage() {
        try {
            AppOpsManager ao = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    boolean inGuard(String pkg) {
        try {
            JSONArray g = new JSONArray(sp().getString("guard", "[]"));
            for (int i = 0; i < g.length(); i++) {
                if (pkg.equals(g.optString(i))) return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    boolean a11yOn() {
        String s = Settings.Secure.getString(getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String full = getPackageName() + "/" + YuniA11yService.class.getName();
        String shortN = getPackageName() + "/." + YuniA11yService.class.getSimpleName();
        return s.contains(full) || s.contains(shortN);
    }

    void saveFile(String name, String mime, String b64) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            if (name == null || name.length() == 0) name = "yuni_export";
            OutputStream os;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                os = getContentResolver().openOutputStream(u);
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                os = new FileOutputStream(new File(dir, name));
            }
            os.write(bytes);
            os.close();
            Toast.makeText(this, "已保存到下载目录：" + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && filePathCb != null) {
            filePathCb.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(res, data));
            filePathCb = null;
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (w.canGoBack()) w.goBack();
        else super.onBackPressed();
    }
}
