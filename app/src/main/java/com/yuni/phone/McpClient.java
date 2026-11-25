package com.yuni.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 远程 MCP（Streamable HTTP，JSON-RPC 2.0）。只支持网址型服务；本地 stdio 服务手机上跑不了。 */
final class McpClient {
    static final String PROTO = "2025-03-26";

    static final class Resp {
        int code;
        String session = "";
        String body = "";
    }

    static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    static Resp post(String url, String hdr, String val, String session, JSONObject payload, int ms) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(ms);
        c.setReadTimeout(ms);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        c.setRequestProperty("MCP-Protocol-Version", PROTO);
        if (val != null && val.length() > 0) {
            c.setRequestProperty(hdr == null || hdr.length() == 0 ? "Authorization" : hdr, val);
        }
        if (session != null && session.length() > 0) c.setRequestProperty("Mcp-Session-Id", session);
        OutputStream os = c.getOutputStream();
        os.write(payload.toString().getBytes("UTF-8"));
        os.close();
        Resp r = new Resp();
        r.code = c.getResponseCode();
        String s = c.getHeaderField("Mcp-Session-Id");
        if (s != null) r.session = s;
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            br.close();
        }
        r.body = sb.toString();
        if (r.code >= 400) throw new Exception("HTTP " + r.code + " " + clip(r.body, 120));
        return r;
    }

    // 返回值可能是 JSON，也可能是 SSE（data: 行），都转成一个 JSON 对象
    static JSONObject parse(Resp r) throws Exception {
        String b = r.body.trim();
        if (b.length() == 0) return new JSONObject();
        if (b.startsWith("{")) return new JSONObject(b);
        JSONObject last = null;
        for (String l : b.split("\n")) {
            String t = l.trim();
            if (t.startsWith("data:")) {
                String d = t.substring(5).trim();
                if (d.startsWith("{")) last = new JSONObject(d);
            }
        }
        if (last == null) throw new Exception("服务返回的内容看不懂");
        return last;
    }

    static String initSession(String url, String hdr, String val) throws Exception {
        JSONObject init = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
            .put("params", new JSONObject().put("protocolVersion", PROTO).put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "yuni-phone").put("version", "1.0")));
        Resp r = post(url, hdr, val, "", init, 15000);
        JSONObject j = parse(r);
        if (j.has("error")) throw new Exception("initialize 失败：" + clip(String.valueOf(j.opt("error")), 120));
        try {
            post(url, hdr, val, r.session, new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), 10000);
        } catch (Exception e) {
        }
        return r.session;
    }

    static JSONObject listTools(String url, String hdr, String val) throws Exception {
        String sess = initSession(url, hdr, val);
        Resp r = post(url, hdr, val, sess,
            new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list").put("params", new JSONObject()), 15000);
        JSONObject j = parse(r);
        if (j.has("error")) throw new Exception("tools/list 失败：" + clip(String.valueOf(j.opt("error")), 120));
        JSONObject res = j.optJSONObject("result");
        JSONArray tools = res == null ? null : res.optJSONArray("tools");
        JSONObject out = new JSONObject();
        out.put("tools", tools == null ? new JSONArray() : tools);
        return out;
    }

    static String callTool(String url, String hdr, String val, String tool, JSONObject args) throws Exception {
        String sess = initSession(url, hdr, val);
        JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
            .put("params", new JSONObject().put("name", tool).put("arguments", args));
        Resp r = post(url, hdr, val, sess, req, 25000);
        JSONObject j = parse(r);
        if (j.has("error")) throw new Exception(clip(String.valueOf(j.opt("error")), 120));
        JSONObject res = j.optJSONObject("result");
        if (res == null) return "";
        StringBuilder sb = new StringBuilder();
        JSONArray c = res.optJSONArray("content");
        if (c != null) {
            for (int i = 0; i < c.length(); i++) {
                JSONObject o = c.optJSONObject(i);
                if (o != null && "text".equals(o.optString("type"))) sb.append(o.optString("text")).append('\n');
            }
        }
        return sb.toString();
    }
}
