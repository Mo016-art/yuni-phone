package com.yuni.phone;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/** 管控申请与规则：char 提申请，用户同意 / 拒绝；同意后按「每天最多 N 分钟」执行。 */
final class YuniRules {
    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("yuni", Context.MODE_PRIVATE);
    }

    static synchronized JSONArray pending(Context c) {
        try {
            return new JSONArray(sp(c).getString("reqs", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // pkg -> {name, limit(分钟/天), until(到期时间), reason, snooze(解冻后到这个时间前不再执行)}
    static synchronized JSONObject rules(Context c) {
        JSONObject r;
        try {
            r = new JSONObject(sp(c).getString("rules", "{}"));
        } catch (Exception e) {
            r = new JSONObject();
        }
        long now = System.currentTimeMillis();
        ArrayList<String> dead = new ArrayList<String>();
        Iterator<String> it = r.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject o = r.optJSONObject(k);
            if (o == null || o.optLong("until", 0) < now) dead.add(k);
        }
        if (dead.size() > 0) {
            for (String k : dead) r.remove(k);
            sp(c).edit().putString("rules", r.toString()).apply();
        }
        return r;
    }

    // 返回 {"ok", id} 或 {"err", 原因}
    static synchronized String[] ask(Context c, String pkg, String name, int limit, int days, String reason) {
        long now = System.currentTimeMillis();
        try {
            if (rules(c).has(pkg)) return new String[]{"err", "这个应用已经在管控里了"};
            JSONArray pd = pending(c);
            for (int i = 0; i < pd.length(); i++) {
                if (pkg.equals(pd.getJSONObject(i).optString("pkg"))) {
                    return new String[]{"err", "这个应用已经有一条待处理的申请"};
                }
            }
            SharedPreferences s = sp(c);
            JSONObject bl = new JSONObject(s.getString("ask_block", "{}"));
            if (bl.optLong(pkg, 0) > now) return new String[]{"err", "对方刚拒绝过，3 天内别再提"};
            String day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
            int cnt = day.equals(s.getString("ask_day", "")) ? s.getInt("ask_cnt", 0) : 0;
            if (cnt >= 2) return new String[]{"err", "今天已经提过两次了，明天再说"};
            String id = String.valueOf(now);
            JSONObject q = new JSONObject();
            q.put("id", id);
            q.put("pkg", pkg);
            q.put("name", name);
            q.put("limit", limit);
            q.put("days", days);
            q.put("reason", reason);
            q.put("ts", now);
            pd.put(q);
            s.edit().putString("reqs", pd.toString()).putString("ask_day", day).putInt("ask_cnt", cnt + 1).apply();
            return new String[]{"ok", id};
        } catch (Exception e) {
            return new String[]{"err", "申请失败"};
        }
    }

    // 处理申请，返回被处理的那条；找不到返回 null
    static synchronized JSONObject answer(Context c, String id, boolean ok) {
        try {
            JSONArray pd = pending(c);
            JSONArray keep = new JSONArray();
            JSONObject hit = null;
            for (int i = 0; i < pd.length(); i++) {
                JSONObject q = pd.getJSONObject(i);
                if (id != null && id.equals(q.optString("id"))) hit = q;
                else keep.put(q);
            }
            if (hit == null) return null;
            SharedPreferences.Editor ed = sp(c).edit().putString("reqs", keep.toString());
            long now = System.currentTimeMillis();
            String pkg = hit.optString("pkg");
            if (ok) {
                JSONObject rl = rules(c);
                JSONObject r = new JSONObject();
                r.put("name", hit.optString("name"));
                r.put("limit", hit.optInt("limit", 60));
                r.put("until", now + hit.optInt("days", 7) * 86400000L);
                r.put("reason", hit.optString("reason"));
                r.put("snooze", 0);
                rl.put(pkg, r);
                ed.putString("rules", rl.toString());
            } else {
                JSONObject bl = new JSONObject(sp(c).getString("ask_block", "{}"));
                bl.put(pkg, now + 3 * 86400000L);
                ed.putString("ask_block", bl.toString());
            }
            ed.apply();
            return hit;
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized void revoke(Context c, String pkg) {
        try {
            JSONObject rl = rules(c);
            rl.remove(pkg);
            JSONObject lk = new JSONObject(sp(c).getString("locks", "{}"));
            lk.remove(pkg);
            sp(c).edit().putString("rules", rl.toString()).putString("locks", lk.toString()).apply();
        } catch (Exception e) {
        }
    }

    // 用户手动解冻后，今天剩下的时间不再按这条规则锁
    static synchronized void snooze(Context c, String pkg) {
        try {
            JSONObject rl = rules(c);
            JSONObject r = rl.optJSONObject(pkg);
            if (r == null) return;
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
            r.put("snooze", cal.getTimeInMillis());
            sp(c).edit().putString("rules", rl.toString()).apply();
        } catch (Exception e) {
        }
    }

    // 兜底：自己累计的前台时长（没授权使用情况时也能执行）
    static synchronized void addOwnUse(Context c, String pkg, long ms) {
        try {
            String day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
            JSONObject o = new JSONObject(sp(c).getString("own_use", "{}"));
            if (!day.equals(o.optString("day"))) {
                o = new JSONObject();
                o.put("day", day);
            }
            o.put(pkg, o.optLong(pkg, 0) + ms);
            sp(c).edit().putString("own_use", o.toString()).apply();
        } catch (Exception e) {
        }
    }

    static synchronized long ownUse(Context c, String pkg) {
        try {
            String day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
            JSONObject o = new JSONObject(sp(c).getString("own_use", "{}"));
            if (!day.equals(o.optString("day"))) return 0;
            return o.optLong(pkg, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static String summary(Context c) {
        JSONObject rl = rules(c);
        if (rl.length() == 0) return "";
        StringBuilder sb = new StringBuilder("【对方同意让你管控的应用】");
        long now = System.currentTimeMillis();
        Iterator<String> it = rl.keys();
        boolean first = true;
        while (it.hasNext()) {
            String k = it.next();
            JSONObject r = rl.optJSONObject(k);
            if (r == null) continue;
            if (!first) sb.append("；");
            first = false;
            sb.append(r.optString("name")).append("（").append(k).append("）每天最多 ")
                .append(r.optInt("limit")).append(" 分钟，还有 ")
                .append(Math.max(0, (r.optLong("until") - now) / 86400000L)).append(" 天");
        }
        return sb.append("。").toString();
    }
}
