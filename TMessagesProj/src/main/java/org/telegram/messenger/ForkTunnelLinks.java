package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Turns share links (vless://, vmess://, trojan://, ss://, hysteria2://, hy2://, tuic://)
// into sing-box outbound objects. Pure Java + org.json, no Android classes.
public final class ForkTunnelLinks {

    private ForkTunnelLinks() {
    }

    public static final class Parsed {
        public final String link;
        public final String name;
        public final String type;
        public final JSONObject outbound; // without "tag"

        Parsed(String link, String name, String type, JSONObject outbound) {
            this.link = link;
            this.name = name;
            this.type = type;
            this.outbound = outbound;
        }
    }

    private static final class Url {
        String scheme = "";
        String user = "";
        String host = "";
        int port;
        String path = "";
        String fragment = "";
        Map<String, String> query = new HashMap<>();

        String q(String key) {
            String v = query.get(key);
            return v == null ? "" : v;
        }
    }

    // ---------- public API ----------

    // Splits a pasted text or downloaded subscription into supported links.
    public static List<String> extractLinks(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        text = text.trim();
        if (!text.contains("://")) {
            String decoded = decodeBase64(text.replaceAll("\\s", ""));
            if (decoded != null && decoded.contains("://")) {
                text = decoded;
            }
        }
        for (String line : text.split("[\\r\\n\\s]+")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("vless://") || lower.startsWith("vmess://") || lower.startsWith("trojan://")
                    || lower.startsWith("ss://") || lower.startsWith("hysteria2://") || lower.startsWith("hy2://")
                    || lower.startsWith("tuic://")) {
                out.add(line);
            }
        }
        return out;
    }

    public static boolean isSupportedLink(String s) {
        return s != null && !extractLinks(s).isEmpty();
    }

    // Returns null when the link is broken or uses something sing-box can't do (xhttp, kcp...).
    public static Parsed parse(String link) {
        try {
            String lower = link.toLowerCase(Locale.ROOT);
            if (lower.startsWith("vmess://")) {
                return parseVmess(link);
            }
            if (lower.startsWith("ss://")) {
                return parseShadowsocks(link);
            }
            Url u = parseUrl(link);
            if (u == null || u.host.isEmpty() || u.port <= 0 || u.port > 65535) {
                return null;
            }
            switch (u.scheme) {
                case "vless": return parseVless(link, u);
                case "trojan": return parseTrojan(link, u);
                case "hysteria2":
                case "hy2": return parseHysteria2(link, u);
                case "tuic": return parseTuic(link, u);
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    // Builds the whole sing-box config. tags.get(i) is the tag of servers.get(i).
    public static JSONObject buildConfig(List<Parsed> servers, List<String> tags, int socksPort, String socksUser, String socksPass,
                                         int apiPort, String apiSecret, String selected) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("level", "warn").put("timestamp", false));

        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();
        dnsServers.put(new JSONObject().put("type", "udp").put("tag", "dns-yandex").put("server", "77.88.8.8"));
        dnsServers.put(new JSONObject().put("type", "udp").put("tag", "dns-google").put("server", "8.8.8.8"));
        dns.put("servers", dnsServers);
        dns.put("strategy", "prefer_ipv4");
        root.put("dns", dns);

        JSONObject socks = new JSONObject()
                .put("type", "socks")
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("listen_port", socksPort)
                .put("users", new JSONArray().put(new JSONObject().put("username", socksUser).put("password", socksPass)));
        root.put("inbounds", new JSONArray().put(socks));

        JSONArray outbounds = new JSONArray();
        JSONArray all = new JSONArray();
        for (int i = 0; i < servers.size(); i++) {
            JSONObject o = new JSONObject(servers.get(i).outbound.toString());
            o.put("tag", tags.get(i));
            outbounds.put(o);
            all.put(tags.get(i));
        }
        JSONObject urltest = new JSONObject()
                .put("type", "urltest")
                .put("tag", "auto")
                .put("outbounds", new JSONArray(all.toString()))
                .put("url", "https://www.gstatic.com/generate_204")
                .put("interval", "3m")
                .put("tolerance", 80)
                .put("idle_timeout", "30m")
                .put("interrupt_exist_connections", true);
        JSONArray selectorList = new JSONArray().put("auto");
        for (int i = 0; i < all.length(); i++) {
            selectorList.put(all.get(i));
        }
        JSONObject selector = new JSONObject()
                .put("type", "selector")
                .put("tag", "proxy")
                .put("outbounds", selectorList)
                .put("default", selected != null && tags.contains(selected) ? selected : "auto")
                .put("interrupt_exist_connections", true);
        outbounds.put(urltest);
        outbounds.put(selector);
        root.put("outbounds", outbounds);

        root.put("route", new JSONObject()
                .put("final", "proxy")
                .put("default_domain_resolver", new JSONObject().put("server", "dns-yandex")));

        root.put("experimental", new JSONObject().put("clash_api", new JSONObject()
                .put("external_controller", "127.0.0.1:" + apiPort)
                .put("secret", apiSecret)
                .put("default_mode", "rule")));
        return root;
    }

    // ---------- protocols ----------

    private static Parsed parseVless(String link, Url u) throws JSONException {
        if (u.user.isEmpty()) {
            return null;
        }
        String enc = u.q("encryption");
        if (!enc.isEmpty() && !"none".equals(enc)) {
            return null; // VLESS post-quantum encryption is not supported by sing-box
        }
        JSONObject o = new JSONObject()
                .put("type", "vless")
                .put("server", u.host)
                .put("server_port", u.port)
                .put("uuid", u.user);
        String flow = u.q("flow");
        if (flow.startsWith("xtls-rprx-vision")) {
            o.put("flow", "xtls-rprx-vision");
        }
        o.put("packet_encoding", "xudp");
        String security = u.q("security").toLowerCase(Locale.ROOT);
        String type = u.q("type").isEmpty() ? "tcp" : u.q("type").toLowerCase(Locale.ROOT);
        if (!applyTransport(o, type, u.q("host"), u.q("path"), u.q("serviceName"), u.q("headerType"))) {
            return null;
        }
        if ("reality".equals(security) || "tls".equals(security) || "xtls".equals(security)) {
            JSONObject tls = tls(u.q("sni"), u.q("host"), u.host, u.q("alpn"), u.q("fp"), isTrue(u.q("allowInsecure")) || isTrue(u.q("insecure")));
            if ("reality".equals(security)) {
                if (u.q("pbk").isEmpty()) {
                    return null;
                }
                tls.put("reality", new JSONObject().put("enabled", true).put("public_key", u.q("pbk")).put("short_id", u.q("sid")));
                if (!tls.has("utls")) {
                    tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", "chrome"));
                }
            }
            o.put("tls", tls);
        }
        return new Parsed(link, name(u, "VLESS"), "VLESS", o);
    }

    private static Parsed parseTrojan(String link, Url u) throws JSONException {
        if (u.user.isEmpty()) {
            return null;
        }
        JSONObject o = new JSONObject()
                .put("type", "trojan")
                .put("server", u.host)
                .put("server_port", u.port)
                .put("password", u.user);
        String type = u.q("type").isEmpty() ? "tcp" : u.q("type").toLowerCase(Locale.ROOT);
        if (!applyTransport(o, type, u.q("host"), u.q("path"), u.q("serviceName"), u.q("headerType"))) {
            return null;
        }
        String security = u.q("security").toLowerCase(Locale.ROOT);
        if (!"none".equals(security)) {
            JSONObject tls = tls(u.q("sni").isEmpty() ? u.q("peer") : u.q("sni"), u.q("host"), u.host, u.q("alpn"), u.q("fp"),
                    isTrue(u.q("allowInsecure")) || isTrue(u.q("insecure")));
            if ("reality".equals(security)) {
                if (u.q("pbk").isEmpty()) {
                    return null;
                }
                tls.put("reality", new JSONObject().put("enabled", true).put("public_key", u.q("pbk")).put("short_id", u.q("sid")));
                if (!tls.has("utls")) {
                    tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", "chrome"));
                }
            }
            o.put("tls", tls);
        }
        return new Parsed(link, name(u, "Trojan"), "Trojan", o);
    }

    private static Parsed parseHysteria2(String link, Url u) throws JSONException {
        JSONObject o = new JSONObject()
                .put("type", "hysteria2")
                .put("server", u.host)
                .put("server_port", u.port);
        if (!u.user.isEmpty()) {
            o.put("password", u.user);
        } else if (!u.q("auth").isEmpty()) {
            o.put("password", u.q("auth"));
        }
        String obfs = u.q("obfs");
        if ("salamander".equalsIgnoreCase(obfs)) {
            o.put("obfs", new JSONObject().put("type", "salamander").put("password", u.q("obfs-password")));
        }
        String alpn = u.q("alpn").isEmpty() ? "h3" : u.q("alpn");
        JSONObject tls = tls(u.q("sni"), "", u.host, alpn, "", isTrue(u.q("insecure")) || isTrue(u.q("allowInsecure")));
        tls.remove("utls");
        o.put("tls", tls);
        return new Parsed(link, name(u, "Hysteria2"), "Hysteria2", o);
    }

    private static Parsed parseTuic(String link, Url u) throws JSONException {
        String uuid = u.user;
        String password = "";
        int colon = uuid.indexOf(':');
        if (colon >= 0) {
            password = uuid.substring(colon + 1);
            uuid = uuid.substring(0, colon);
        }
        if (uuid.isEmpty()) {
            return null;
        }
        JSONObject o = new JSONObject()
                .put("type", "tuic")
                .put("server", u.host)
                .put("server_port", u.port)
                .put("uuid", uuid)
                .put("password", password);
        if (!u.q("congestion_control").isEmpty()) {
            o.put("congestion_control", u.q("congestion_control"));
        }
        if (!u.q("udp_relay_mode").isEmpty()) {
            o.put("udp_relay_mode", u.q("udp_relay_mode"));
        }
        String alpn = u.q("alpn").isEmpty() ? "h3" : u.q("alpn");
        JSONObject tls = tls(u.q("sni"), "", u.host, alpn, "", isTrue(u.q("allow_insecure")) || isTrue(u.q("insecure")) || isTrue(u.q("allowInsecure")));
        tls.remove("utls");
        o.put("tls", tls);
        return new Parsed(link, name(u, "TUIC"), "TUIC", o);
    }

    private static Parsed parseVmess(String link) throws JSONException {
        String body = link.substring("vmess://".length());
        int hash = body.indexOf('#');
        if (hash >= 0) {
            body = body.substring(0, hash);
        }
        String json = decodeBase64(body);
        if (json == null) {
            return null;
        }
        JSONObject v = new JSONObject(json);
        String host = v.optString("add");
        int port = parsePort(v.optString("port"));
        String id = v.optString("id");
        if (host.isEmpty() || port <= 0 || id.isEmpty()) {
            return null;
        }
        JSONObject o = new JSONObject()
                .put("type", "vmess")
                .put("server", host)
                .put("server_port", port)
                .put("uuid", id)
                .put("security", v.optString("scy", "auto").isEmpty() ? "auto" : v.optString("scy", "auto"))
                .put("alter_id", parsePort(v.optString("aid", "0")) < 0 ? 0 : Math.max(0, parsePort(v.optString("aid", "0"))))
                .put("packet_encoding", "xudp");
        String net = v.optString("net", "tcp");
        if (net.isEmpty()) {
            net = "tcp";
        }
        if (!applyTransport(o, net.toLowerCase(Locale.ROOT), v.optString("host"), v.optString("path"), v.optString("path"), v.optString("type"))) {
            return null;
        }
        String tlsMode = v.optString("tls");
        if ("tls".equalsIgnoreCase(tlsMode)) {
            o.put("tls", tls(v.optString("sni"), v.optString("host"), host, v.optString("alpn"), v.optString("fp"), false));
        }
        String name = v.optString("ps");
        return new Parsed(link, name.isEmpty() ? host + ":" + port : name, "VMess", o);
    }

    private static Parsed parseShadowsocks(String link) throws JSONException {
        String body = link.substring("ss://".length());
        String fragment = "";
        int hash = body.indexOf('#');
        if (hash >= 0) {
            fragment = decode(body.substring(hash + 1));
            body = body.substring(0, hash);
        }
        String query = "";
        int qm = body.indexOf('?');
        if (qm >= 0) {
            query = body.substring(qm + 1);
            body = body.substring(0, qm);
        }
        if (body.endsWith("/")) {
            body = body.substring(0, body.length() - 1);
        }
        Map<String, String> q = parseQuery(query);
        String plugin = q.get("plugin");
        if (plugin != null && !plugin.isEmpty() && !plugin.startsWith("none")) {
            return null; // obfs/v2ray plugins aren't bundled
        }
        String userInfo;
        String hostPort;
        int at = body.lastIndexOf('@');
        if (at < 0) {
            String decoded = decodeBase64(body);
            if (decoded == null) {
                return null;
            }
            at = decoded.lastIndexOf('@');
            if (at < 0) {
                return null;
            }
            userInfo = decoded.substring(0, at);
            hostPort = decoded.substring(at + 1);
        } else {
            userInfo = body.substring(0, at);
            hostPort = body.substring(at + 1);
            String decoded = decodeBase64(userInfo);
            if (decoded != null && decoded.contains(":")) {
                userInfo = decoded;
            } else {
                userInfo = decode(userInfo);
            }
        }
        int colon = userInfo.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String method = userInfo.substring(0, colon);
        String password = userInfo.substring(colon + 1);
        String[] hp = splitHostPort(hostPort);
        if (hp == null) {
            return null;
        }
        int port = parsePort(hp[1]);
        if (port <= 0) {
            return null;
        }
        JSONObject o = new JSONObject()
                .put("type", "shadowsocks")
                .put("server", hp[0])
                .put("server_port", port)
                .put("method", method)
                .put("password", password);
        return new Parsed(link, fragment.isEmpty() ? hp[0] + ":" + port : fragment, "Shadowsocks", o);
    }

    // ---------- helpers ----------

    private static boolean applyTransport(JSONObject o, String type, String host, String path, String serviceName, String headerType) throws JSONException {
        switch (type) {
            case "tcp":
            case "raw":
                if ("http".equalsIgnoreCase(headerType)) {
                    JSONObject t = new JSONObject().put("type", "http").put("method", "GET");
                    if (!host.isEmpty()) {
                        t.put("host", splitList(host));
                    }
                    if (!path.isEmpty()) {
                        t.put("path", path);
                    }
                    o.put("transport", t);
                }
                return true;
            case "ws": {
                JSONObject t = new JSONObject().put("type", "ws");
                String p = path.isEmpty() ? "/" : path;
                int ed = p.indexOf("?ed=");
                if (ed >= 0) {
                    int early = parsePort(p.substring(ed + 4));
                    p = p.substring(0, ed);
                    if (early > 0) {
                        t.put("max_early_data", early);
                        t.put("early_data_header_name", "Sec-WebSocket-Protocol");
                    }
                }
                t.put("path", p);
                if (!host.isEmpty()) {
                    t.put("headers", new JSONObject().put("Host", host));
                }
                o.put("transport", t);
                return true;
            }
            case "grpc": {
                JSONObject t = new JSONObject().put("type", "grpc");
                if (!serviceName.isEmpty()) {
                    t.put("service_name", serviceName);
                }
                o.put("transport", t);
                return true;
            }
            case "httpupgrade": {
                JSONObject t = new JSONObject().put("type", "httpupgrade");
                if (!host.isEmpty()) {
                    t.put("host", host);
                }
                t.put("path", path.isEmpty() ? "/" : path);
                o.put("transport", t);
                return true;
            }
            case "h2":
            case "http": {
                JSONObject t = new JSONObject().put("type", "http");
                if (!host.isEmpty()) {
                    t.put("host", splitList(host));
                }
                if (!path.isEmpty()) {
                    t.put("path", path);
                }
                o.put("transport", t);
                return true;
            }
            default:
                return false; // xhttp, kcp, quic...
        }
    }

    private static JSONObject tls(String sni, String hostHeader, String server, String alpn, String fp, boolean insecure) throws JSONException {
        JSONObject tls = new JSONObject().put("enabled", true);
        String serverName = !sni.isEmpty() ? sni : (!hostHeader.isEmpty() ? hostHeader : server);
        if (!serverName.isEmpty() && !isIp(serverName)) {
            tls.put("server_name", serverName);
        }
        if (insecure) {
            tls.put("insecure", true);
        }
        if (!alpn.isEmpty()) {
            tls.put("alpn", splitList(alpn));
        }
        if (!fp.isEmpty() && !"none".equalsIgnoreCase(fp)) {
            String f = fp.toLowerCase(Locale.ROOT);
            switch (f) {
                case "chrome": case "firefox": case "edge": case "safari": case "360": case "qq": case "ios": case "android": case "random": case "randomized":
                    break;
                default:
                    f = "chrome";
            }
            tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", f));
        }
        return tls;
    }

    private static JSONArray splitList(String s) {
        JSONArray a = new JSONArray();
        for (String part : s.split(",")) {
            part = part.trim();
            if (!part.isEmpty()) {
                a.put(part);
            }
        }
        return a;
    }

    private static boolean isIp(String s) {
        return s.matches("[0-9.]+") || s.contains(":");
    }

    private static boolean isTrue(String s) {
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static String name(Url u, String fallback) {
        if (!u.fragment.isEmpty()) {
            return u.fragment.trim();
        }
        return fallback + " " + u.host + ":" + u.port;
    }

    private static Url parseUrl(String s) {
        Url u = new Url();
        int sep = s.indexOf("://");
        if (sep <= 0) {
            return null;
        }
        u.scheme = s.substring(0, sep).toLowerCase(Locale.ROOT);
        String rest = s.substring(sep + 3);
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            u.fragment = decode(rest.substring(hash + 1));
            rest = rest.substring(0, hash);
        }
        int qm = rest.indexOf('?');
        if (qm >= 0) {
            u.query = parseQuery(rest.substring(qm + 1));
            rest = rest.substring(0, qm);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            u.path = rest.substring(slash);
            rest = rest.substring(0, slash);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            u.user = decode(rest.substring(0, at));
            rest = rest.substring(at + 1);
        }
        String[] hp = splitHostPort(rest);
        if (hp == null) {
            return null;
        }
        u.host = hp[0];
        u.port = parsePort(hp[1]);
        return u;
    }

    private static String[] splitHostPort(String s) {
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0 || end + 1 >= s.length() || s.charAt(end + 1) != ':') {
                return null;
            }
            return new String[]{s.substring(1, end), s.substring(end + 2)};
        }
        int colon = s.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        return new String[]{s.substring(0, colon), s.substring(colon + 1)};
    }

    // "443", "443,20000-30000" -> first usable port
    private static int parsePort(String s) {
        if (s == null) {
            return -1;
        }
        s = s.trim();
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(s.substring(0, Math.min(end, 9)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, String> parseQuery(String q) {
        HashMap<String, String> map = new HashMap<>();
        if (q == null || q.isEmpty()) {
            return map;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                map.put(decode(pair), "");
            } else {
                map.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    // Lenient base64 / base64url decoder (padding optional). Returns null if it isn't base64 text.
    public static String decodeBase64(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim().replace('-', '+').replace('_', '/');
        while (s.endsWith("=")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ') {
                continue;
            }
            int v = B64.indexOf(c);
            if (v < 0) {
                return null;
            }
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        String r = new String(out.toByteArray(), StandardCharsets.UTF_8);
        if (r.indexOf('�') >= 0) {
            return null;
        }
        return r;
    }
}
