package com.yuni.phone;

/** 工具名：模型只接受 [A-Za-z0-9_-]，且有长度限制。服务名和工具名拼成一个全名，回头能对回去。 */
final class McpNames {
    static String san(String s) {
        String r = (s == null ? "" : s).replaceAll("[^A-Za-z0-9_-]", "_");
        return r.length() > 40 ? r.substring(0, 40) : r;
    }

    static String full(String server, String tool) {
        String r = san(server) + "__" + san(tool);
        return r.length() > 64 ? r.substring(0, 64) : r;
    }

    static String clip(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }
}
