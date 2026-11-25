package com.yuni.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 远程 MCP。支持两种传输：Streamable HTTP（默认、推荐）和 SSE（旧版）。本地 stdio 服务手机上跑不了。 */
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

    // 返回可能是 JSON，也可能是 SSE（data: 行），统一转成一个 JSON 对象
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

    static JSONObject initPayload(int id) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "initialize")
            .put("params", new JSONObject().put("protocolVersion", PROTO).put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject().put("name", "yuni-phone").put("version", "1.0")));
    }

    static JSONObject notifPayload() throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized");
    }

    // ---------- Streamable HTTP ----------
    static JSONObject httpCall(String url, String hdr, String val, JSONObject req) throws Exception {
        Resp r1 = post(url, hdr, val, "", initPayload(1), 15000);
        JSONObject j1 = parse(r1);
        if (j1.has("error")) throw new Exception("initialize 失败：" + clip(String.valueOf(j1.opt("error")), 120));
        try {
            post(url, hdr, val, r1.session, notifPayload(), 10000);
        } catch (Exception e) {
        }
        return parse(post(url, hdr, val, r1.session, req, 25000));
    }

    // ---------- SSE（旧版）：GET 长连接拿到 endpoint，请求 POST 到 endpoint，响应从长连接读 ----------
    static final class Sse {
        HttpURLConnection get;
        BufferedReader br;
        String endpoint;
    }

    static Sse openSse(String url, String hdr, String val) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "text/event-stream");
        if (val != null && val.length() > 0) {
            c.setRequestProperty(hdr == null || hdr.length() == 0 ? "Authorization" : hdr, val);
        }
        Sse s = new Sse();
        s.get = c;
        s.br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String ev = "";
        String line;
        while ((line = s.br.readLine()) != null) {
            if (line.startsWith("event:")) {
                ev = line.substring(6).trim();
            } else if (line.startsWith("data:") && "endpoint".equals(ev)) {
                String d = line.substring(5).trim();
                s.endpoint = new URL(new URL(url), d).toString();
                break;
            }
        }
        if (s.endpoint == null) throw new Exception("SSE 没有返回 endpoint，可能不是 SSE 服务");
        return s;
    }

    static JSONObject waitId(Sse s, int id, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        String ev = "";
        String line;
        while (System.currentTimeMillis() < end && (line = s.br.readLine()) != null) {
            if (line.startsWith("event:")) {
                ev = line.substring(6).trim();
            } else if (line.startsWith("data:") && !"endpoint".equals(ev)) {
                String d = line.substring(5).trim();
                if (d.startsWith("{")) {
                    JSONObject j = new JSONObject(d);
                    if (j.optInt("id", -1) == id) return j;
                }
            } else if (line.length() == 0) {
                ev = "";
            }
        }
        throw new Exception("SSE 等待响应超时");
    }

    static JSONObject sseCall(String url, String hdr, String val, JSONObject req, int id) throws Exception {
        Sse s = openSse(url, hdr, val);
        try {
            post(s.endpoint, hdr, val, "", initPayload(1), 15000);
            JSONObject r1 = waitId(s, 1, 20000);
            if (r1.has("error")) throw new Exception("initialize 失败：" + clip(String.valueOf(r1.opt("error")), 120));
            post(s.endpoint, hdr, val, "", notifPayload(), 10000);
            post(s.endpoint, hdr, val, "", req, 20000);
            return waitId(s, id, 25000);
        } finally {
            try {
                s.get.disconnect();
            } catch (Exception e) {
            }
        }
    }

    static JSONObject call(String url, String hdr, String val, String transport, JSONObject req, int id) throws Exception {
        if ("sse".equals(transport)) return sseCall(url, hdr, val, req, id);
        return httpCall(url, hdr, val, req);
    }

    static JSONObject listTools(String url, String hdr, String val, String transport) throws Exception {
        JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list").put("params", new JSONObject());
        JSONObject j = call(url, hdr, val, transport, req, 2);
        if (j.has("error")) throw new Exception("tools/list 失败：" + clip(String.valueOf(j.opt("error")), 120));
        JSONObject res = j.optJSONObject("result");
        JSONArray tools = res == null ? null : res.optJSONArray("tools");
        JSONObject out = new JSONObject();
        out.put("tools", tools == null ? new JSONArray() : tools);
        return out;
    }

    static String callTool(String url, String hdr, String val, String transport, String tool, JSONObject args) throws Exception {
        JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
            .put("params", new JSONObject().put("name", tool).put("arguments", args));
        JSONObject j = call(url, hdr, val, transport, req, 3);
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
