package org.telegram.messenger.plugins;

import android.text.TextUtils;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Metadata of an installed .plugin file. It is read from the top-level dunder
// assignments of the source (__id__ = "...", __name__ = "..." and so on) without
// running any Python, so the plugin list shows up instantly.
public class PluginInfo {

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-.]{2,64}$");
    private static final Pattern FIELD = Pattern.compile(
        "(?m)^__([a-z_]+)__\\s*=\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|'''([\\s\\S]*?)'''|\"((?:[^\"\\\\\\n]|\\\\.)*)\"|'((?:[^'\\\\\\n]|\\\\.)*)')"
    );

    public String id;
    public String name;
    public String description;
    public String author;
    public String version;
    public String icon;
    public String minVersion;
    public File file;

    // runtime state, owned by PluginsController
    public volatile boolean enabled;
    public volatile boolean loaded;
    public volatile boolean hasSettings;
    public volatile String error;

    public static PluginInfo parse(String source) throws IllegalArgumentException {
        PluginInfo info = new PluginInfo();
        Matcher m = FIELD.matcher(source);
        while (m.find()) {
            String key = m.group(1);
            String value = null;
            for (int g = 2; g <= 5 && value == null; g++) {
                value = m.group(g);
            }
            if (value == null) {
                continue;
            }
            if (m.group(4) != null || m.group(5) != null) {
                value = unescape(value);
            }
            value = value.trim();
            switch (key) {
                case "id": if (info.id == null) info.id = value; break;
                case "name": if (info.name == null) info.name = value; break;
                case "description": if (info.description == null) info.description = value; break;
                case "author": if (info.author == null) info.author = value; break;
                case "version": if (info.version == null) info.version = value; break;
                case "icon": if (info.icon == null) info.icon = value; break;
                case "min_version": if (info.minVersion == null) info.minVersion = value; break;
            }
        }
        if (info.id == null || !ID_PATTERN.matcher(info.id).matches()) {
            throw new IllegalArgumentException("__id__ is missing or invalid");
        }
        if (TextUtils.isEmpty(info.name)) {
            info.name = info.id;
        }
        return info;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public String getSubtitle() {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(version)) {
            sb.append('v').append(version);
        }
        if (!TextUtils.isEmpty(author)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(author);
        }
        return sb.toString();
    }
}
