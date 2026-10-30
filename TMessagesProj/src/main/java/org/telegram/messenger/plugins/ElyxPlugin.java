package org.telegram.messenger.plugins;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.LocaleController;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Structured exteraGram plugins: a zip (.elyx / .eaf) with refmap.yml, metainfo.yml,
// the entry module (main.py by default) and optional assets/, strings/ and wheels/.
// The archive is unpacked to plugins/ElyxPlugins/<id>/ and the original is kept in
// plugins/archives/<id>.elyx for sharing. Metadata is read here without Python.
public class ElyxPlugin {

    public static final String DIR = "ElyxPlugins";
    public static final String ARCHIVES = "archives";
    public static final String EXT = ".elyx";
    public static final long MAX_SIZE = 64 * 1024 * 1024;
    private static final long MAX_UNPACKED = 256 * 1024 * 1024;

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_]{2,32}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");
    private static final String[] REFMAPS = {"refmap.yaml", "refmap.yml", "refmap.json"};
    private static final String[] METAINFOS = {"metainfo.yaml", "metainfo.yml", "metainfo.json"};

    public static boolean isElyxName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".elyx") || n.endsWith(".eaf") || n.endsWith(".elyx.zip") || n.endsWith(".eaf.zip");
    }

    public static boolean isZip(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[4];
            return in.read(b) == 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
        } catch (Exception e) {
            return false;
        }
    }

    // read access to an archive or to its unpacked folder
    private interface Source {
        String read(String path) throws Exception; // null if missing
        boolean isDir(String path);
        String[] list(String dir);
    }

    private static class DirSource implements Source {
        final File root;

        DirSource(File root) {
            this.root = root;
        }

        public String read(String path) throws Exception {
            File f = new File(root, path);
            return f.isFile() ? PluginsController.readFile(f) : null;
        }

        public boolean isDir(String path) {
            return new File(root, path).isDirectory();
        }

        public String[] list(String dir) {
            String[] names = new File(root, dir).list();
            return names == null ? new String[0] : names;
        }
    }

    private static class ZipSource implements Source {
        final ZipFile zip;

        ZipSource(ZipFile zip) {
            this.zip = zip;
        }

        public String read(String path) throws Exception {
            ZipEntry e = zip.getEntry(path);
            if (e == null || e.isDirectory()) {
                return null;
            }
            try (InputStream in = zip.getInputStream(e)) {
                return PluginsController.readStream(in);
            }
        }

        public boolean isDir(String path) {
            String prefix = path.endsWith("/") ? path : path + "/";
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                if (en.nextElement().getName().startsWith(prefix)) return true;
            }
            return false;
        }

        public String[] list(String dir) {
            String prefix = dir.endsWith("/") ? dir : dir + "/";
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith(prefix) && n.length() > prefix.length()) {
                    String rest = n.substring(prefix.length());
                    int slash = rest.indexOf('/');
                    names.add(slash < 0 ? rest : rest.substring(0, slash));
                }
            }
            return names.toArray(new String[0]);
        }
    }

    public static PluginInfo parseArchive(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            return parse(new ZipSource(zip));
        }
    }

    public static PluginInfo parseDir(File dir) throws Exception {
        return parse(new DirSource(dir));
    }

    private static PluginInfo parse(Source src) throws Exception {
        Map<String, Object> refmap = new LinkedHashMap<>();
        for (String name : REFMAPS) {
            String text = src.read(name);
            if (text != null) {
                refmap = asMap(parseText(name, text));
                break;
            }
        }
        String metaPath = str(refmap.get("metainfo"));
        String metaText = null;
        if (!TextUtils.isEmpty(metaPath)) {
            metaText = src.read(clean(metaPath));
        } else {
            for (String name : METAINFOS) {
                metaText = src.read(name);
                if (metaText != null) {
                    metaPath = name;
                    break;
                }
            }
        }
        if (metaText == null) {
            throw new IllegalArgumentException("metainfo.yml is missing");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : asMap(parseText(metaPath, metaText)).entrySet()) {
            String k = e.getKey();
            if (k.length() > 4 && k.startsWith("__") && k.endsWith("__")) {
                k = k.substring(2, k.length() - 2);
            }
            meta.put(k, e.getValue());
        }
        String main = str(refmap.get("main"));
        main = TextUtils.isEmpty(main) ? "main.py" : clean(main);
        if (src.read(main) == null) {
            if (src.read(main + "c") != null || main.endsWith(".pyc")) {
                throw new IllegalArgumentException("compiled .pyc plugins are not supported");
            }
            throw new IllegalArgumentException(main + " is missing");
        }

        PluginInfo info = new PluginInfo();
        info.elyx = true;
        info.id = str(meta.get("id"));
        if (info.id == null || !ID_PATTERN.matcher(info.id).matches()) {
            throw new IllegalArgumentException("id in metainfo is missing or invalid");
        }
        Map<String, Map<String, Object>> strings = loadStrings(src, refmap);
        info.name = resolve(str(meta.get("name")), strings);
        info.description = resolve(str(meta.get("description")), strings);
        info.author = str(meta.get("author"));
        info.version = str(meta.get("version"));
        info.icon = str(meta.get("icon"));
        info.minVersion = str(meta.get("app_version"));
        if (TextUtils.isEmpty(info.minVersion)) {
            info.minVersion = str(meta.get("min_version"));
        }
        if (TextUtils.isEmpty(info.name)) {
            info.name = info.id;
        }
        info.elyxMain = main;
        info.elyxMeta = JSONObject.wrap(meta).toString();
        info.elyxRefmap = JSONObject.wrap(refmap).toString();
        info.elyxStrings = JSONObject.wrap(strings).toString();
        String assets = str(refmap.get("assets"));
        info.elyxAssets = TextUtils.isEmpty(assets) ? (src.isDir("assets") ? "assets" : null) : clean(assets);
        return info;
    }

    private static Object parseText(String name, String text) throws Exception {
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return toJava(new JSONObject(text));
        }
        return MiniYaml.parse(text);
    }

    private static Object toJava(Object o) {
        if (o instanceof JSONObject) {
            JSONObject json = (JSONObject) o;
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                String k = it.next();
                map.put(k, toJava(json.opt(k)));
            }
            return map;
        }
        if (o instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) o;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(toJava(arr.opt(i)));
            }
            return list;
        }
        return o == JSONObject.NULL ? null : o;
    }

    // locale -> key -> value, from strings_<locale>.yml/.yaml/.json in the strings folder
    private static Map<String, Map<String, Object>> loadStrings(Source src, Map<String, Object> refmap) {
        LinkedHashMap<String, Map<String, Object>> all = new LinkedHashMap<>();
        String dir = str(refmap.get("strings"));
        dir = TextUtils.isEmpty(dir) ? "strings" : clean(dir);
        for (String file : src.list(dir)) {
            int dot = file.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = file.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!ext.equals("yml") && !ext.equals("yaml") && !ext.equals("json")) continue;
            String stem = file.substring(0, dot);
            int us = stem.lastIndexOf('_');
            String locale = us < 0 ? "en" : stem.substring(us + 1).toLowerCase(Locale.ROOT);
            try {
                String text = src.read(dir + "/" + file);
                if (text == null) continue;
                Map<String, Object> table = all.get(locale);
                if (table == null) {
                    all.put(locale, table = new LinkedHashMap<>());
                }
                table.putAll(asMap(parseText(file, text)));
            } catch (Exception ignore) {
            }
        }
        return all;
    }

    private static String resolve(String text, Map<String, Map<String, Object>> strings) {
        if (text == null || text.indexOf('{') < 0) {
            return text;
        }
        String lang = "en";
        try {
            lang = LocaleController.getInstance().getCurrentLocale().getLanguage();
        } catch (Exception ignore) {
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object v = null;
            if (strings.containsKey(lang)) v = strings.get(lang).get(key);
            if (v == null && strings.containsKey("en")) v = strings.get("en").get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? key : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // Unpacks the archive into target (which must not exist yet). Rejects paths that
    // leave the folder. DEX files are made read-only, as Android 14 requires for loading.
    public static void extract(File archive, File target) throws Exception {
        String root = target.getCanonicalPath() + File.separator;
        long total = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            byte[] buf = new byte[16384];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                File out = new File(target, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new SecurityException("bad path in archive: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("can't create " + parent);
                }
                try (InputStream in = zip.getInputStream(e); OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_UNPACKED) {
                            throw new IllegalArgumentException("plugin archive is too large");
                        }
                        os.write(buf, 0, n);
                    }
                }
                if (out.getName().endsWith(".dex")) {
                    out.setReadOnly();
                }
            }
        }
    }

    public static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    public static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static String clean(String path) {
        path = path.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
}
