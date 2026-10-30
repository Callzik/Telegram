package org.telegram.messenger.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The part of YAML that plugin metadata, refmaps and string tables use: nested block
// mappings and lists, plain/quoted scalars, | and > block scalars, flow [a, b] lists and
// comments. Anchors, tags and multi-document streams are not supported. Scalars stay
// strings, except true/false/null and plain numbers.
public class MiniYaml {

    private final List<String> lines = new ArrayList<>();
    private int pos;

    private MiniYaml(String text) {
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            lines.add(line.replace("\t", "  "));
        }
    }

    public static Object parse(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        MiniYaml y = new MiniYaml(text);
        y.skipBlank();
        if (y.pos >= y.lines.size()) {
            return new LinkedHashMap<String, Object>();
        }
        return y.parseBlock(indentOf(y.lines.get(y.pos)));
    }

    // YAML or JSON text to a JSON string, for Python
    public static String toJson(String text) {
        String t = text.trim();
        try {
            if (t.startsWith("{")) {
                return new JSONObject(t).toString();
            }
            if (t.startsWith("[")) {
                return new JSONArray(t).toString();
            }
        } catch (Exception ignore) {
        }
        return JSONObject.wrap(parse(text)).toString();
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static boolean isBlank(String line) {
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#") || t.equals("---") || t.equals("...");
    }

    private void skipBlank() {
        while (pos < lines.size() && isBlank(lines.get(pos))) pos++;
    }

    private Object parseBlock(int indent) {
        String t = lines.get(pos).trim();
        if (t.equals("-") || t.startsWith("- ")) {
            return parseList(indent);
        }
        return parseMap(indent);
    }

    private List<Object> parseList(int indent) {
        ArrayList<Object> list = new ArrayList<>();
        while (true) {
            skipBlank();
            if (pos >= lines.size()) break;
            String line = lines.get(pos);
            int ind = indentOf(line);
            String t = line.trim();
            if (ind != indent || !(t.equals("-") || t.startsWith("- "))) break;
            String rest = t.length() > 1 ? t.substring(2).trim() : "";
            if (rest.isEmpty()) {
                pos++;
                skipBlank();
                if (pos < lines.size() && indentOf(lines.get(pos)) > indent) {
                    list.add(parseBlock(indentOf(lines.get(pos))));
                } else {
                    list.add(null);
                }
            } else if (keyEnd(rest) > 0 && !rest.startsWith("\"") && !rest.startsWith("'") && !rest.startsWith("[") && !rest.startsWith("{")) {
                // "- key: value" starts a mapping indented to the key
                int childIndent = ind + (t.length() - rest.length());
                lines.set(pos, spaces(childIndent) + rest);
                list.add(parseMap(childIndent));
            } else {
                pos++;
                list.add(scalarOrBlock(rest, indent));
            }
        }
        return list;
    }

    private Map<String, Object> parseMap(int indent) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        while (true) {
            skipBlank();
            if (pos >= lines.size()) break;
            String line = lines.get(pos);
            int ind = indentOf(line);
            if (ind != indent) break;
            String t = line.trim();
            int colon = keyEnd(t);
            if (colon <= 0) {
                pos++;
                continue;
            }
            String key = unquote(t.substring(0, colon).trim());
            String rest = stripComment(t.substring(colon + 1)).trim();
            pos++;
            if (rest.isEmpty()) {
                skipBlank();
                if (pos < lines.size()) {
                    String next = lines.get(pos);
                    int ni = indentOf(next);
                    String nt = next.trim();
                    if (ni > indent || (ni == indent && (nt.equals("-") || nt.startsWith("- ")))) {
                        map.put(key, parseBlock(ni));
                        continue;
                    }
                }
                map.put(key, null);
            } else {
                map.put(key, scalarOrBlock(rest, indent));
            }
        }
        return map;
    }

    private Object scalarOrBlock(String rest, int indent) {
        if (rest.startsWith("|") || rest.startsWith(">")) {
            return blockScalar(rest.charAt(0) == '>', rest.contains("-"), indent);
        }
        if (rest.startsWith("[") && rest.endsWith("]")) {
            ArrayList<Object> list = new ArrayList<>();
            String inner = rest.substring(1, rest.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String part : splitFlow(inner)) {
                    list.add(scalar(part.trim()));
                }
            }
            return list;
        }
        if (rest.startsWith("{") && rest.endsWith("}")) {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            String inner = rest.substring(1, rest.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String part : splitFlow(inner)) {
                    int c = keyEnd(part.trim());
                    if (c > 0) {
                        map.put(unquote(part.trim().substring(0, c).trim()), scalar(part.trim().substring(c + 1).trim()));
                    }
                }
            }
            return map;
        }
        // a quoted string may continue on the next lines
        if ((rest.startsWith("\"") || rest.startsWith("'")) && !closed(rest)) {
            StringBuilder sb = new StringBuilder(rest);
            while (pos < lines.size() && !closed(sb.toString())) {
                String next = lines.get(pos++).trim();
                sb.append(next.isEmpty() ? "\n" : " " + next);
            }
            return scalar(sb.toString());
        }
        return scalar(rest);
    }

    private String blockScalar(boolean folded, boolean strip, int parentIndent) {
        ArrayList<String> body = new ArrayList<>();
        int blockIndent = -1;
        while (pos < lines.size()) {
            String line = lines.get(pos);
            if (line.trim().isEmpty()) {
                body.add("");
                pos++;
                continue;
            }
            int ind = indentOf(line);
            if (ind <= parentIndent) break;
            if (blockIndent < 0) blockIndent = ind;
            if (ind < blockIndent) break;
            body.add(line.substring(blockIndent));
            pos++;
        }
        while (!body.isEmpty() && body.get(body.size() - 1).isEmpty()) {
            body.remove(body.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.size(); i++) {
            String l = body.get(i);
            if (i > 0) {
                boolean join = folded && !l.isEmpty() && !body.get(i - 1).isEmpty() && !l.startsWith(" ");
                sb.append(join ? " " : "\n");
            }
            sb.append(l);
        }
        if (!strip) sb.append('\n');
        return sb.toString();
    }

    private static boolean closed(String s) {
        char q = s.charAt(0);
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q == '"' && c == '\\') {
                i++;
            } else if (c == q) {
                if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> splitFlow(String s) {
        ArrayList<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    // index of the ':' that ends a mapping key, or -1
    private static int keyEnd(String t) {
        char quote = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') i++;
                else if (c == quote) quote = 0;
            } else if (i == 0 && (c == '"' || c == '\'')) {
                quote = c;
            } else if (c == '#' && i > 0 && t.charAt(i - 1) == ' ') {
                return -1;
            } else if (c == ':' && (i + 1 == t.length() || t.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') i++;
                else if (c == quote) quote = 0;
            } else if ((c == '"' || c == '\'') && s.substring(0, i).trim().isEmpty()) {
                quote = c;
            } else if (c == '#' && (i == 0 || s.charAt(i - 1) == ' ')) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static Object scalar(String s) {
        s = stripComment(s).trim();
        if (s.isEmpty() || s.equals("~") || s.equals("null")) return s.isEmpty() ? "" : null;
        if (s.startsWith("\"") || s.startsWith("'")) return unquote(s);
        if (s.equals("true") || s.equals("True")) return Boolean.TRUE;
        if (s.equals("false") || s.equals("False")) return Boolean.FALSE;
        if (s.matches("-?\\d{1,18}")) return Long.parseLong(s);
        if (s.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignore) {
            }
        }
        return s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            String in = s.substring(1, s.length() - 1);
            StringBuilder sb = new StringBuilder(in.length());
            for (int i = 0; i < in.length(); i++) {
                char c = in.charAt(i);
                if (c == '\\' && i + 1 < in.length()) {
                    char n = in.charAt(++i);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '0': sb.append('\0'); break;
                        case 'u':
                            if (i + 4 < in.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(in.substring(i + 1, i + 5), 16));
                                    i += 4;
                                    break;
                                } catch (NumberFormatException ignore) {
                                }
                            }
                            sb.append(n);
                            break;
                        default: sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return s;
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }
}
