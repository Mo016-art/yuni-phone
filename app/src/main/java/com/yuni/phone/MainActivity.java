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
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import java.util.ArrayList;
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
    static final int REQ_CAM = 1002;
    Uri camUri;
    WebChromeClient.FileChooserParams pendingParams;
    PermissionRequest pendingPerm;
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
                boolean wantImage = false;
                String[] types = p.getAcceptTypes();
                if (types != null) {
                    for (String t : types) {
                        if (t != null && t.startsWith("image")) wantImage = true;
                    }
                }
                // 「拍摄」：页面带 capture 要求时，直接调相机
                if (p.isCaptureEnabled() && wantImage && Build.VERSION.SDK_INT >= 29) {
                    launchCamera(p);
                    return true;
                }
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCb = null;
                    return false;
                }
                return true;
            }

            // 页面里的摄像头、麦克风（视频通话、语音消息）：只给本应用自己的页面
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        Uri o = request.getOrigin();
                        if (o == null || !HOST.equals(o.getHost())) {
                            request.deny();
                            return;
                        }
                        ArrayList<String> need = new ArrayList<String>();
                        for (String r : request.getResources()) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                need.add(Manifest.permission.CAMERA);
                            }
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                need.add(Manifest.permission.RECORD_AUDIO);
                            }
                        }
                        if (need.isEmpty()) {
                            request.grant(request.getResources());
                        } else {
                            pendingPerm = request;
                            requestPermissions(need.toArray(new String[0]), 3002);
                        }
                    }
                });
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
                        String mm = "";
                        JSONObject aa = new JSONObject();
                        try {
                            JSONObject o = new JSONObject(msg.getData());
                            idRaw = String.valueOf(o.opt("id"));
                            mm = o.optString("m");
                            JSONObject a0 = o.optJSONObject("a");
                            if (a0 != null) aa = a0;
                        } catch (Exception e) {
                        }
                        if (mm.startsWith("mcp")) {
                            final String fid = idRaw;
                            final String fm = mm;
                            final JSONObject fa = aa;
                            final JavaScriptReplyProxy fr = reply;
                            new Thread(new Runnable() {
                                public void run() {
                                    String rr;
                                    try {
                                        rr = handleMcp(fm, fa);
                                    } catch (Exception ex) {
                                        rr = JSONObject.quote("ERR:" + ex.getMessage());
                                    }
                                    final String out = "{\"id\":" + fid + ",\"r\":" + rr + "}";
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            fr.postMessage(out);
                                        }
                                    });
                                }
                            }).start();
                            return;
                        }
                        String res = "null";
                        try {
                            res = handle(mm, aa);
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
            o.put("err", sp().getString("last_err", ""));
            NotificationManager nmg = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            o.put("notif", nmg != null && nmg.areNotificationsEnabled());
            o.put("pro", sp().getBoolean("pro_on", false));
            o.put("proRes", sp().getString("pro_res", "还没尝试过"));
            o.put("notifBlock", !(nmg != null && nmg.areNotificationsEnabled()));
            o.put("usage", hasUsage());
            o.put("co", sp().getBoolean("co_on", false));
            o.put("autolock", sp().getBoolean("opt_autolock", false));
            o.put("ask", sp().getBoolean("opt_ask", true));
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
                .putString("char_id", a.optString("charId"))
                .putString("char_ban", a.optString("ban", "[]"))
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
                .putBoolean("pro_screen", a.optBoolean("screenOnly", false)).apply();
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
        if ("clearErr".equals(m)) {
            sp().edit().remove("last_err").apply();
            return "true";
        }
        if ("setAsk".equals(m)) {
            sp().edit().putBoolean("opt_ask", a.optBoolean("on", true)).apply();
            return "true";
        }
        if ("getReqs".equals(m)) {
            JSONObject out = new JSONObject();
            out.put("pending", YuniRules.pending(this));
            out.put("charName", sp().getString("char_name", "char"));
            JSONArray ra = new JSONArray();
            JSONObject rl = YuniRules.rules(this);
            long now = System.currentTimeMillis();
            Iterator<String> it = rl.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject r = rl.optJSONObject(k);
                if (r == null) continue;
                JSONObject o = new JSONObject();
                o.put("pkg", k);
                o.put("name", r.optString("name"));
                o.put("limit", r.optInt("limit"));
                o.put("daysLeft", Math.max(0, (r.optLong("until") - now) / 86400000L));
                ra.put(o);
            }
            out.put("rules", ra);
            return out.toString();
        }
        if ("answerReq".equals(m)) {
            if (s != null) {
                s.handleReq(a.optString("id"), a.optBoolean("ok"));
            } else {
                YuniRules.answer(this, a.optString("id"), a.optBoolean("ok"));
            }
            return "true";
        }
        if ("revokeRule".equals(m)) {
            YuniRules.revoke(this, a.optString("pkg"));
            return "true";
        }
        if ("cfgSave".equals(m)) {
            sp().edit().putString("ui_cfg", a.optString("json")).apply();
            return "true";
        }
        if ("cfgLoad".equals(m)) {
            return JSONObject.quote(sp().getString("ui_cfg", ""));
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
        if ("unlockApp".equals(m)) {
            JSONObject lk = new JSONObject(sp().getString("locks", "{}"));
            lk.remove(a.optString("pkg"));
            sp().edit().putString("locks", lk.toString()).apply();
            YuniRules.snooze(this, a.optString("pkg"));
            return "true";
        }
        if ("getLocks".equals(m)) {
            JSONObject lk = new JSONObject(sp().getString("locks", "{}"));
            JSONObject rs = new JSONObject(sp().getString("lock_reasons", "{}"));
            JSONObject out = new JSONObject();
            JSONObject keep = new JSONObject();
            long now = System.currentTimeMillis();
            Iterator<String> it = lk.keys();
            while (it.hasNext()) {
                String k = it.next();
                long until = lk.optLong(k, 0);
                if (until > now) {
                    keep.put(k, until);
                    JSONObject lo = new JSONObject();
                    lo.put("m", (until - now + 59999) / 60000);
                    lo.put("r", rs.optString(k, ""));
                    out.put(k, lo);
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

    String handleMcp(String mm, JSONObject a) throws Exception {
        if ("mcpList".equals(mm)) return sp().getString("mcp_servers", "[]");
        if ("mcpSave".equals(mm)) {
            sp().edit().putString("mcp_servers", a.optString("json", "[]")).apply();
            return "true";
        }
        if ("mcpTools".equals(mm)) {
            sp().edit().putString("diag_start", String.valueOf(System.currentTimeMillis())).apply();
            try {
                return mcpToolsJson(a);
            } catch (Exception ex) {
                sp().edit().putString("diag_err", "mcpTools: " + String.valueOf(ex) + " @" + System.currentTimeMillis()).apply();
                return JSONObject.quote("ERR:" + ex);
            }
        }
        if ("mcpTools_unused".equals(mm)) {
            String cid = a.optString("charId", sp().getString("char_id", ""));
            JSONArray out = new JSONArray();
            JSONArray arr = new JSONArray(sp().getString("mcp_servers", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null || !s.optBoolean("enabled", true)) continue;
                String sc = s.optString("scope", "all");
                if (!(sc.equals("all") || sc.equals(cid))) continue;
                JSONArray ts = s.optJSONArray("tools");
                if (ts == null) continue;
                for (int k = 0; k < ts.length(); k++) {
                    JSONObject t = ts.optJSONObject(k);
                    if (t == null || !t.optBoolean("enabled", false)) continue;
                    JSONObject params = t.optJSONObject("inputSchema");
                    if (params == null) params = new JSONObject().put("type", "object").put("properties", new JSONObject());
                    JSONObject fn = new JSONObject()
                        .put("name", McpNames.full(s.optString("name"), t.optString("name")))
                        .put("description", McpNames.clip(t.optString("description"), 300))
                        .put("parameters", params);
                    out.put(new JSONObject().put("type", "function").put("function", fn));
                }
            }
            sp().edit().putString("diag_tools", new JSONObject().put("t", System.currentTimeMillis())
                .put("n", out.length()).put("cid", cid).toString()).apply();
            return out.toString();
        }
        if ("mcpDiag".equals(mm)) {
            return "{\"ver\":\"native-26\",\"tools\":" + sp().getString("diag_tools", "null") + ",\"call\":" + sp().getString("diag_call", "null")
                + ",\"start\":" + JSONObject.quote(sp().getString("diag_start", "")) + ",\"err\":" + JSONObject.quote(sp().getString("diag_err", "")) + "}";
        }
        if ("mcpCallDirect".equals(mm)) {
            String tl = a.optString("tool");
            try {
                JSONObject args = a.optJSONObject("args");
                String res = McpClient.callTool(a.optString("url"), a.optString("hdr", ""), a.optString("val", ""),
                    a.optString("transport", "http"), tl, args == null ? new JSONObject() : args);
                sp().edit().putString("diag_call", new JSONObject().put("t", System.currentTimeMillis())
                    .put("name", tl).put("ok", true).put("len", res.length()).toString()).apply();
                return JSONObject.quote(McpNames.clip(res, 4000));
            } catch (Exception ex) {
                sp().edit().putString("diag_call", new JSONObject().put("t", System.currentTimeMillis())
                    .put("name", tl).put("ok", false).put("err", McpNames.clip(String.valueOf(ex.getMessage()), 120)).toString()).apply();
                return JSONObject.quote("工具出错：" + ex.getMessage());
            }
        }
        if ("mcpCallFull".equals(mm)) {
            String full = a.optString("name");
            String cid = a.optString("charId", sp().getString("char_id", ""));
            JSONArray arr = new JSONArray(sp().getString("mcp_servers", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null || !s.optBoolean("enabled", true)) continue;
                String sc = s.optString("scope", "all");
                if (!(sc.equals("all") || sc.equals(cid))) continue;
                JSONArray ts = s.optJSONArray("tools");
                if (ts == null) continue;
                for (int k = 0; k < ts.length(); k++) {
                    JSONObject t = ts.optJSONObject(k);
                    if (t == null || !t.optBoolean("enabled", false)) continue;
                    if (!full.equals(McpNames.full(s.optString("name"), t.optString("name")))) continue;
                    JSONObject args = a.optJSONObject("args");
                    try {
                        String res = McpClient.callTool(s.optString("url"), s.optString("hdr", ""), s.optString("val", ""),
                            s.optString("transport", "http"), t.optString("name"), args == null ? new JSONObject() : args);
                        sp().edit().putString("diag_call", new JSONObject().put("t", System.currentTimeMillis())
                            .put("name", full).put("ok", true).put("len", res.length()).toString()).apply();
                        return JSONObject.quote(McpNames.clip(res, 4000));
                    } catch (Exception ex) {
                        sp().edit().putString("diag_call", new JSONObject().put("t", System.currentTimeMillis())
                            .put("name", full).put("ok", false).put("err", McpNames.clip(String.valueOf(ex.getMessage()), 120)).toString()).apply();
                        return JSONObject.quote("工具出错：" + ex.getMessage());
                    }
                }
            }
            return JSONObject.quote("没有找到这个工具，或者它没有开启");
        }
        if ("mcpTest".equals(mm)) {
            JSONObject out = new JSONObject();
            try {
                JSONObject r = McpClient.listTools(a.optString("url"), a.optString("hdr"), a.optString("val"), a.optString("transport", "http"));
                out.put("ok", true);
                out.put("tools", r.optJSONArray("tools"));
            } catch (Exception e) {
                out.put("ok", false);
                out.put("error", String.valueOf(e.getMessage()));
            }
            return out.toString();
        }
        return "null";
    }

    String mcpToolsJson(JSONObject a) throws Exception {
        String cid = a.optString("charId", sp().getString("char_id", ""));
        JSONArray out = new JSONArray();
        JSONArray arr = new JSONArray(sp().getString("mcp_servers", "[]"));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null || !s.optBoolean("enabled", true)) continue;
            String sc = s.optString("scope", "all");
            if (!(sc.equals("all") || sc.equals(cid))) continue;
            JSONArray ts = s.optJSONArray("tools");
            if (ts == null) continue;
            for (int k = 0; k < ts.length(); k++) {
                JSONObject t = ts.optJSONObject(k);
                if (t == null || !t.optBoolean("enabled", false)) continue;
                JSONObject params = t.optJSONObject("inputSchema");
                if (params == null) params = new JSONObject().put("type", "object").put("properties", new JSONObject());
                JSONObject fn = new JSONObject()
                    .put("name", McpNames.full(s.optString("name"), t.optString("name")))
                    .put("description", McpNames.clip(t.optString("description"), 300))
                    .put("parameters", params);
                out.put(new JSONObject().put("type", "function").put("function", fn));
            }
        }
        sp().edit().putString("diag_tools", new JSONObject().put("t", System.currentTimeMillis())
            .put("n", out.length()).put("cid", cid).toString()).apply();
        return out.toString();
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

    void launchCamera(WebChromeClient.FileChooserParams p) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingParams = p;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 3001);
            return;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "yuni_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YuniPhone");
            camUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            Intent it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
            startActivityForResult(it, REQ_CAM);
        } catch (Exception e) {
            // 相机起不来就退回相册选图
            try {
                startActivityForResult(p.createIntent(), REQ_FILE);
            } catch (Exception e2) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = null;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        boolean ok = results.length > 0;
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        }
        if (req == 3001 && pendingParams != null) {
            WebChromeClient.FileChooserParams p = pendingParams;
            pendingParams = null;
            if (ok) {
                launchCamera(p);
            } else {
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    if (filePathCb != null) filePathCb.onReceiveValue(null);
                    filePathCb = null;
                }
            }
        } else if (req == 3002 && pendingPerm != null) {
            PermissionRequest pr = pendingPerm;
            pendingPerm = null;
            if (ok) pr.grant(pr.getResources());
            else pr.deny();
        } else {
            super.onRequestPermissionsResult(req, perms, results);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_CAM && filePathCb != null) {
            if (res == RESULT_OK && camUri != null) {
                filePathCb.onReceiveValue(new Uri[]{camUri});
            } else {
                filePathCb.onReceiveValue(null);
                if (camUri != null) {
                    try {
                        getContentResolver().delete(camUri, null, null);
                    } catch (Exception e) {
                    }
                }
            }
            filePathCb = null;
            camUri = null;
        } else if (req == REQ_FILE && filePathCb != null) {
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
