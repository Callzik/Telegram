package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Built-in anti-censorship tunnel: runs the bundled sing-box core (libsingbox.so) as a child process
// that listens on a local SOCKS5 port, and points Telegram's own proxy setting at it.
// No system VPN is used; only this app's traffic goes through the tunnel.
public final class ForkTunnel {

    public static final int MAX_SERVERS_IN_CONFIG = 120;
    private static final long SUB_REFRESH_MS = 12 * 60 * 60 * 1000L;

    public static final class Server {
        public final String link;
        public final String sub; // subscription url or ""
        public final String tag;
        public final String name;
        public final String type;
        public final boolean supported;

        Server(String link, String sub) {
            this.link = link;
            this.sub = sub == null ? "" : sub;
            this.tag = tagOf(link);
            ForkTunnelLinks.Parsed p = ForkTunnelLinks.parse(link);
            this.supported = p != null;
            this.name = p != null ? p.name : link.substring(0, Math.min(40, link.length()));
            this.type = p != null ? p.type : "?";
        }
    }

    public static final class Subscription {
        public final String url;
        public String name;
        public long updated;
        public int count;
        public String error;

        Subscription(String url, String name, long updated, int count) {
            this.url = url;
            this.name = name;
            this.updated = updated;
            this.count = count;
        }
    }

    public static final class Preset {
        public final int nameRes;
        public final String url;

        Preset(int nameRes, String url) {
            this.nameRes = nameRes;
            this.url = url;
        }
    }

    // Public auto-checked lists for Russia (github.com/igareck/vpn-configs-for-russia)
    public static final Preset[] PRESETS = {
            new Preset(R.string.ForkTunnelPresetBlack, "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt"),
            new Preset(R.string.ForkTunnelPresetWhite, "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt"),
            new Preset(R.string.ForkTunnelPresetOther, "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_SS+All_RUS.txt"),
    };

    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    private static final Object lock = new Object();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final ExecutorService testExecutor = Executors.newSingleThreadExecutor();

    private static boolean loaded;
    public static boolean enabled;
    public static String selected = ""; // tag, "" = auto
    public static final ArrayList<Server> servers = new ArrayList<>();
    public static final ArrayList<Subscription> subscriptions = new ArrayList<>();
    public static final Map<String, Integer> delays = Collections.synchronizedMap(new HashMap<>());
    public static volatile String activeTag = "";
    public static volatile boolean testing;
    private static final java.util.Set<String> configTags = Collections.synchronizedSet(new java.util.HashSet<>());

    private static int socksPort;
    private static int apiPort;
    private static String socksUser;
    private static String socksPass;
    private static String apiSecret;

    private static volatile State state = State.STOPPED;
    private static volatile String lastError = "";
    private static Process process;
    private static int generation;
    private static int restarts;
    private static final LinkedList<String> logTail = new LinkedList<>();
    private static final ArrayList<Runnable> listeners = new ArrayList<>();

    private ForkTunnel() {
    }

    // ---------- persistence ----------

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_tunnel", Context.MODE_PRIVATE);
    }

    public static void load() {
        synchronized (lock) {
            if (loaded) {
                return;
            }
            loaded = true;
            SharedPreferences p = prefs();
            enabled = p.getBoolean("enabled", false);
            selected = p.getString("selected", "");
            socksPort = p.getInt("socksPort", 0);
            apiPort = p.getInt("apiPort", 0);
            socksUser = p.getString("socksUser", "");
            socksPass = p.getString("socksPass", "");
            apiSecret = p.getString("apiSecret", "");
            if (socksPort == 0 || TextUtils.isEmpty(socksUser)) {
                SecureRandom r = new SecureRandom();
                socksPort = 20000 + r.nextInt(30000);
                apiPort = socksPort + 1;
                socksUser = randomString(r, 12);
                socksPass = randomString(r, 24);
                apiSecret = randomString(r, 24);
            }
            servers.clear();
            try {
                JSONArray a = new JSONArray(p.getString("servers", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    servers.add(new Server(o.getString("l"), o.optString("s")));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            subscriptions.clear();
            try {
                JSONArray a = new JSONArray(p.getString("subs", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    subscriptions.add(new Subscription(o.getString("u"), o.optString("n"), o.optLong("t"), o.optInt("c")));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            save();
        }
    }

    public static void save() {
        synchronized (lock) {
            try {
                JSONArray a = new JSONArray();
                for (Server s : servers) {
                    JSONObject o = new JSONObject().put("l", s.link);
                    if (!s.sub.isEmpty()) {
                        o.put("s", s.sub);
                    }
                    a.put(o);
                }
                JSONArray subs = new JSONArray();
                for (Subscription s : subscriptions) {
                    subs.put(new JSONObject().put("u", s.url).put("n", s.name).put("t", s.updated).put("c", s.count));
                }
                prefs().edit()
                        .putBoolean("enabled", enabled)
                        .putString("selected", selected)
                        .putInt("socksPort", socksPort)
                        .putInt("apiPort", apiPort)
                        .putString("socksUser", socksUser)
                        .putString("socksPass", socksPass)
                        .putString("apiSecret", apiSecret)
                        .putString("servers", a.toString())
                        .putString("subs", subs.toString())
                        .apply();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static String randomString(SecureRandom r, int len) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    static String tagOf(String link) {
        long h = 1125899906842597L;
        for (int i = 0; i < link.length(); i++) {
            h = 31 * h + link.charAt(i);
        }
        return "s" + Long.toHexString(h);
    }

    // ---------- listeners ----------

    public static void addListener(Runnable r) {
        synchronized (listeners) {
            listeners.add(r);
        }
    }

    public static void removeListener(Runnable r) {
        synchronized (listeners) {
            listeners.remove(r);
        }
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<Runnable> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Runnable r : copy) {
                r.run();
            }
        });
    }

    public static State getState() {
        return state;
    }

    public static String getLastError() {
        return lastError;
    }

    public static boolean isCoreAvailable() {
        return coreFile().exists();
    }

    public static boolean isTunnelProxy(SharedConfig.ProxyInfo info) {
        return info != null && info.settings.getType() == ProxySettings.Type.SOCKS5
                && "127.0.0.1".equals(info.settings.getAddress()) && info.settings.getPort() == socksPort;
    }

    public static int usableServerCount() {
        int c = 0;
        synchronized (lock) {
            for (Server s : servers) {
                if (s.supported) {
                    c++;
                }
            }
        }
        return c;
    }

    public static Server findServer(String tag) {
        synchronized (lock) {
            for (Server s : servers) {
                if (s.tag.equals(tag)) {
                    return s;
                }
            }
        }
        return null;
    }

    // ---------- servers / subscriptions ----------

    // Accepts share links, a base64 blob, or subscription URLs. Returns how many servers were added.
    public static int importText(String text) {
        load();
        if (text == null) {
            return 0;
        }
        int added = addLinks(ForkTunnelLinks.extractLinks(text), "");
        save();
        return added;
    }

    public static List<String> extractSubscriptionUrls(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = Pattern.compile("https?://[^\\s\"'<>]+").matcher(text);
        while (m.find()) {
            String u = m.group();
            String lower = u.toLowerCase(Locale.ROOT);
            if (lower.contains("t.me/") || lower.contains("telegram.me/")) {
                continue;
            }
            out.add(u);
        }
        return out;
    }

    private static int addLinks(List<String> links, String sub) {
        int added = 0;
        synchronized (lock) {
            HashMap<String, Boolean> existing = new HashMap<>();
            for (Server s : servers) {
                existing.put(s.tag, true);
            }
            for (String l : links) {
                Server s = new Server(l, sub);
                if (existing.containsKey(s.tag)) {
                    continue;
                }
                existing.put(s.tag, true);
                servers.add(s);
                added++;
            }
        }
        return added;
    }

    public static void removeServer(Server s) {
        synchronized (lock) {
            servers.remove(s);
            if (s.tag.equals(selected)) {
                selected = "";
            }
        }
        delays.remove(s.tag);
        save();
        restartIfRunning();
    }

    public static void removeAllServers() {
        synchronized (lock) {
            servers.clear();
            subscriptions.clear();
            selected = "";
        }
        delays.clear();
        save();
        if (enabled) {
            setEnabled(false);
        }
        notifyChanged();
    }

    public static int removeDead() {
        int removed = 0;
        synchronized (lock) {
            for (int i = servers.size() - 1; i >= 0; i--) {
                Server s = servers.get(i);
                Integer d = delays.get(s.tag);
                if (!s.supported || (d != null && d <= 0)) {
                    servers.remove(i);
                    removed++;
                    if (s.tag.equals(selected)) {
                        selected = "";
                    }
                }
            }
        }
        save();
        restartIfRunning();
        return removed;
    }

    public static void removeSubscription(Subscription sub) {
        synchronized (lock) {
            subscriptions.remove(sub);
            for (int i = servers.size() - 1; i >= 0; i--) {
                if (servers.get(i).sub.equals(sub.url)) {
                    servers.remove(i);
                }
            }
        }
        save();
        restartIfRunning();
    }

    public interface ResultCallback {
        void onResult(int count, String error);
    }

    public static void addSubscription(String url, String name, ResultCallback callback) {
        load();
        synchronized (lock) {
            boolean exists = false;
            for (Subscription s : subscriptions) {
                if (s.url.equals(url)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                subscriptions.add(new Subscription(url, name == null ? "" : name, 0, 0));
            }
        }
        save();
        updateSubscriptions(true, callback);
    }

    public static void updateSubscriptions(boolean force, ResultCallback callback) {
        load();
        executor.execute(() -> {
            int total = 0;
            String error = null;
            ArrayList<Subscription> subs;
            synchronized (lock) {
                subs = new ArrayList<>(subscriptions);
            }
            boolean changed = false;
            for (Subscription sub : subs) {
                if (!force && System.currentTimeMillis() - sub.updated < SUB_REFRESH_MS) {
                    continue;
                }
                String body = download(sub.url);
                List<String> links = body == null ? new ArrayList<>() : ForkTunnelLinks.extractLinks(body);
                if (links.isEmpty()) {
                    sub.error = "empty";
                    error = sub.url;
                    continue;
                }
                sub.error = null;
                if (TextUtils.isEmpty(sub.name) && body != null) {
                    Matcher m = Pattern.compile("#\\s*profile-title:\\s*(.+)").matcher(body);
                    if (m.find()) {
                        sub.name = m.group(1).trim();
                    }
                }
                synchronized (lock) {
                    // replace this subscription's servers, keep the user's own ones
                    for (int i = servers.size() - 1; i >= 0; i--) {
                        if (servers.get(i).sub.equals(sub.url)) {
                            servers.remove(i);
                        }
                    }
                }
                int added = addLinks(links, sub.url);
                sub.count = added;
                sub.updated = System.currentTimeMillis();
                total += added;
                changed = true;
            }
            synchronized (lock) {
                if (!selected.isEmpty() && findServer(selected) == null) {
                    selected = "";
                }
            }
            save();
            if (changed) {
                restartIfRunning();
            }
            notifyChanged();
            if (callback != null) {
                final int t = total;
                final String e = error;
                AndroidUtilities.runOnUIThread(() -> callback.onResult(t, e));
            }
        });
    }

    // Direct download first, then GitHub CDN mirrors (raw.githubusercontent.com is often blocked in RU).
    private static String download(String url) {
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(url);
        Matcher m = Pattern.compile("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$").matcher(url);
        if (m.find()) {
            candidates.add("https://cdn.jsdelivr.net/gh/" + m.group(1) + "/" + m.group(2) + "@" + m.group(3) + "/" + m.group(4));
            candidates.add("https://raw.githack.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3) + "/" + m.group(4));
        }
        for (String u : candidates) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u.replace("+", "%2B")).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "sing-box");
                if (c.getResponseCode() != 200) {
                    continue;
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 8 * 1024 * 1024) {
                    out.write(buf, 0, n);
                }
                in.close();
                String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                if (!ForkTunnelLinks.extractLinks(body).isEmpty()) {
                    return body;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        return null;
    }

    // ---------- MTProto proxy lists ----------

    private static final Pattern TG_PROXY = Pattern.compile("(?:tg://(?:proxy|socks)|https?://(?:t\\.me|telegram\\.me|telegram\\.dog)/(?:proxy|socks))\\?[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    // Adds every tg://proxy / t.me/proxy link from the text to Telegram's own proxy list.
    public static int importTelegramProxies(String text) {
        if (text == null) {
            return 0;
        }
        Matcher m = TG_PROXY.matcher(text.replace("&amp;", "&"));
        int added = 0;
        while (m.find()) {
            try {
                ProxySettings settings = ProxySettings.fromUri(Uri.parse(m.group()));
                if (settings == null || !settings.isValid()) {
                    continue;
                }
                boolean exists = false;
                for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                    if (info.settings.equals(settings)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
                    added++;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (added > 0) {
            SharedConfig.saveProxyList();
            if (!SharedConfig.proxyRotationEnabled) {
                SharedConfig.proxyRotationEnabled = true;
                SharedConfig.saveConfig();
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }
        return added;
    }

    public static void downloadTelegramProxies(String url, ResultCallback callback) {
        executor.execute(() -> {
            String body = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 8 * 1024 * 1024) {
                    out.write(buf, 0, n);
                }
                body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            final String b = body;
            AndroidUtilities.runOnUIThread(() -> {
                int count = b == null ? 0 : importTelegramProxies(b);
                if (callback != null) {
                    callback.onResult(count, b == null ? url : null);
                }
            });
        });
    }

    // Pings every proxy in Telegram's list and removes the ones that don't answer.
    public static void checkTelegramProxies(Runnable done) {
        final ArrayList<SharedConfig.ProxyInfo> list = new ArrayList<>(SharedConfig.proxyList);
        if (list.isEmpty()) {
            if (done != null) {
                done.run();
            }
            return;
        }
        final int[] left = {list.size()};
        for (SharedConfig.ProxyInfo info : list) {
            info.checking = true;
            ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(info.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                info.availableCheckTime = SystemClock.elapsedRealtime();
                info.checking = false;
                info.available = time != -1;
                info.ping = time == -1 ? 0 : time;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info);
                if (--left[0] == 0) {
                    for (SharedConfig.ProxyInfo p : list) {
                        if (!p.available && p != SharedConfig.currentProxy && !isTunnelProxy(p)) {
                            SharedConfig.deleteProxy(p);
                        }
                    }
                    SharedConfig.saveProxyList();
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    if (done != null) {
                        done.run();
                    }
                }
            }));
        }
    }

    // ---------- core process ----------

    private static File coreFile() {
        return new File(ApplicationLoader.applicationContext.getApplicationInfo().nativeLibraryDir, "libsingbox.so");
    }

    private static File workDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "tunnel");
        dir.mkdirs();
        return dir;
    }

    public static void startIfEnabled() {
        load();
        if (enabled) {
            executor.execute(ForkTunnel::startInternal);
            updateSubscriptions(false, null);
        }
    }

    public static void setEnabled(boolean value) {
        load();
        enabled = value;
        restarts = 0;
        save();
        if (value) {
            executor.execute(ForkTunnel::startInternal);
        } else {
            executor.execute(ForkTunnel::stopInternal);
            AndroidUtilities.runOnUIThread(ForkTunnel::detachProxy);
        }
        notifyChanged();
    }

    public static void select(String tag) {
        load();
        selected = tag == null ? "" : tag;
        save();
        if (state == State.RUNNING) {
            testExecutor.execute(() -> {
                try {
                    api("PUT", "/proxies/proxy", new JSONObject().put("name", selected.isEmpty() ? "auto" : selected).toString());
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                refreshActive();
                notifyChanged();
            });
        }
        notifyChanged();
    }

    private static void restartIfRunning() {
        if (enabled) {
            executor.execute(ForkTunnel::startInternal);
        }
        notifyChanged();
    }

    private static void stopInternal() {
        Process p;
        synchronized (lock) {
            generation++;
            p = process;
            process = null;
        }
        if (p != null) {
            p.destroy();
            try {
                p.waitFor();
            } catch (InterruptedException ignore) {
            }
        }
        killStale();
        state = State.STOPPED;
        activeTag = "";
        notifyChanged();
    }

    private static void killStale() {
        int pid = prefs().getInt("pid", 0);
        if (pid > 0) {
            try {
                File cmd = new File("/proc/" + pid + "/cmdline");
                if (cmd.exists()) {
                    byte[] data = new byte[256];
                    java.io.FileInputStream in = new java.io.FileInputStream(cmd);
                    int n = in.read(data);
                    in.close();
                    if (n > 0 && new String(data, 0, n).contains("libsingbox")) {
                        android.os.Process.killProcess(pid);
                        Thread.sleep(300);
                    }
                }
            } catch (Throwable ignore) {
            }
            prefs().edit().remove("pid").apply();
        }
    }

    private static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void startInternal() {
        stopInternal();
        if (!enabled) {
            return;
        }
        File core = coreFile();
        if (!core.exists()) {
            fail("core not found: " + core.getAbsolutePath());
            return;
        }
        ArrayList<ForkTunnelLinks.Parsed> parsed = new ArrayList<>();
        ArrayList<String> tags = new ArrayList<>();
        synchronized (lock) {
            // the chosen server first, then the ones that answered fastest last time
            ArrayList<Server> ordered = new ArrayList<>(servers);
            Collections.sort(ordered, (a, b) -> {
                if (a.tag.equals(selected)) return -1;
                if (b.tag.equals(selected)) return 1;
                Integer da = delays.get(a.tag), db = delays.get(b.tag);
                int va = da == null ? 50000 : (da <= 0 ? 100000 : da);
                int vb = db == null ? 50000 : (db <= 0 ? 100000 : db);
                return Integer.compare(va, vb);
            });
            for (Server s : ordered) {
                if (parsed.size() >= MAX_SERVERS_IN_CONFIG) {
                    break;
                }
                ForkTunnelLinks.Parsed p = ForkTunnelLinks.parse(s.link);
                if (p != null) {
                    parsed.add(p);
                    tags.add(s.tag);
                }
            }
        }
        configTags.clear();
        configTags.addAll(tags);
        if (parsed.isEmpty()) {
            fail("no servers");
            return;
        }
        if (!portFree(socksPort) || !portFree(apiPort)) {
            SecureRandom r = new SecureRandom();
            for (int i = 0; i < 20; i++) {
                int p = 20000 + r.nextInt(30000);
                if (portFree(p) && portFree(p + 1)) {
                    socksPort = p;
                    apiPort = p + 1;
                    break;
                }
            }
            save();
        }
        state = State.STARTING;
        lastError = "";
        notifyChanged();
        final int gen;
        try {
            JSONObject config = ForkTunnelLinks.buildConfig(parsed, tags, socksPort, socksUser, socksPass, apiPort, apiSecret, selected);
            File dir = workDir();
            File cfg = new File(dir, "config.json");
            try (OutputStream out = new FileOutputStream(cfg)) {
                out.write(config.toString().getBytes(StandardCharsets.UTF_8));
            }
            ProcessBuilder pb = new ProcessBuilder(core.getAbsolutePath(), "run", "-c", cfg.getAbsolutePath(), "-D", dir.getAbsolutePath(), "--disable-color");
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", dir.getAbsolutePath());
            pb.environment().put("ENABLE_DEPRECATED_SPECIAL_OUTBOUNDS", "true");
            Process p = pb.start();
            synchronized (lock) {
                process = p;
                gen = ++generation;
            }
            int pid = pidOf(p);
            if (pid > 0) {
                prefs().edit().putInt("pid", pid).apply();
            }
            Thread reader = new Thread(() -> readLog(p, gen), "tunnel-log");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable e) {
            FileLog.e(e);
            fail(e.getMessage());
            return;
        }
        // wait until the API answers
        for (int i = 0; i < 40; i++) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignore) {
            }
            synchronized (lock) {
                if (gen != generation) {
                    return;
                }
            }
            try {
                api("GET", "/version", null);
                state = State.RUNNING;
                restarts = 0;
                AndroidUtilities.runOnUIThread(ForkTunnel::attachProxy);
                notifyChanged();
                testExecutor.execute(() -> {
                    refreshActive();
                    notifyChanged();
                });
                return;
            } catch (Throwable ignore) {
            }
        }
        fail(lastError.isEmpty() ? "core did not start" : lastError);
    }

    private static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Throwable ignore) {
        }
        Matcher m = Pattern.compile("pid=(\\d+)").matcher(p.toString());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static void readLog(Process p, int gen) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (logTail) {
                    logTail.add(line);
                    while (logTail.size() > 80) {
                        logTail.removeFirst();
                    }
                }
                if (line.contains("FATAL") || line.contains("ERROR")) {
                    lastError = line;
                }
            }
        } catch (Throwable ignore) {
        }
        int code = -1;
        try {
            code = p.waitFor();
        } catch (InterruptedException ignore) {
        }
        boolean current;
        synchronized (lock) {
            current = gen == generation;
            if (current) {
                process = null;
            }
        }
        if (current && enabled) {
            // the core died on its own: restart a few times with a pause
            state = State.ERROR;
            if (lastError.isEmpty()) {
                lastError = "core exited with code " + code;
            }
            notifyChanged();
            if (restarts < 5) {
                restarts++;
                final long delay = 2000L * restarts;
                AndroidUtilities.runOnUIThread(() -> {
                    if (enabled && state == State.ERROR) {
                        executor.execute(ForkTunnel::startInternal);
                    }
                }, delay);
            }
        }
    }

    public static String getLog() {
        synchronized (logTail) {
            return TextUtils.join("\n", logTail);
        }
    }

    private static void fail(String error) {
        state = State.ERROR;
        lastError = error == null ? "error" : error;
        notifyChanged();
    }

    // ---------- Telegram proxy wiring ----------

    private static ProxySettings localSettings() {
        return ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress("127.0.0.1")
                .setPort(socksPort)
                .setUser(socksUser)
                .setPassword(socksPass)
                .build();
    }

    private static void attachProxy() {
        if (!enabled) {
            return;
        }
        ProxySettings settings = localSettings();
        SharedConfig.ProxyInfo info = null;
        for (SharedConfig.ProxyInfo p : new ArrayList<>(SharedConfig.proxyList)) {
            if (p.settings.equals(settings)) {
                info = p;
            } else if (p.settings.getType() == ProxySettings.Type.SOCKS5 && "127.0.0.1".equals(p.settings.getAddress())
                    && socksUser.equals(p.settings.getUser())) {
                SharedConfig.deleteProxy(p); // our entry with an old port
            }
        }
        if (info == null) {
            info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
        }
        boolean wasEnabled = SharedConfig.isProxyEnabled();
        if (SharedConfig.currentProxy == info && wasEnabled) {
            return;
        }
        SharedConfig.currentProxy = info;
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.commit();
        SharedConfig.saveProxyList();
        ConnectionsManager.setProxySettings(true, info.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private static void detachProxy() {
        SharedConfig.ProxyInfo ours = null;
        for (SharedConfig.ProxyInfo p : SharedConfig.proxyList) {
            if (isTunnelProxy(p)) {
                ours = p;
                break;
            }
        }
        if (ours == null) {
            return;
        }
        boolean wasCurrent = SharedConfig.currentProxy == ours;
        SharedConfig.deleteProxy(ours);
        SharedConfig.saveProxyList();
        if (wasCurrent) {
            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", false);
            editor.commit();
            ConnectionsManager.setProxySettings(false, ProxySettings.EMPTY);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    // ---------- clash API ----------

    private static String api(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + apiPort + path).openConnection(java.net.Proxy.NO_PROXY);
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(2000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", "Bearer " + apiSecret);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                in.close();
            }
            String result = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new Exception("HTTP " + code + " " + result);
            }
            return result;
        } finally {
            c.disconnect();
        }
    }

    private static void refreshActive() {
        try {
            JSONObject proxy = new JSONObject(api("GET", "/proxies/proxy", null));
            String now = proxy.optString("now");
            if ("auto".equals(now)) {
                now = new JSONObject(api("GET", "/proxies/auto", null)).optString("now");
            }
            activeTag = now;
        } catch (Throwable e) {
            activeTag = "";
        }
    }

    // Measures every server through the running core. Unanswered servers get -1.
    public static void testAll(Runnable done) {
        if (state != State.RUNNING || testing) {
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
            return;
        }
        testing = true;
        notifyChanged();
        testExecutor.execute(() -> {
            try {
                String r = api("GET", "/group/auto/delay?timeout=6000&url=" + Uri.encode("https://www.gstatic.com/generate_204"), null);
                JSONObject o = new JSONObject(r);
                ArrayList<String> tagsInConfig = new ArrayList<>(configTags);
                for (String tag : tagsInConfig) {
                    int d = o.optInt(tag, 0);
                    delays.put(tag, d > 0 ? d : -1);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            refreshActive();
            testing = false;
            notifyChanged();
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }
}
