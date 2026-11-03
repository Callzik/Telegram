package org.telegram.messenger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fork: offline part of the input field calculator (math, units, time zones). No Android dependencies. */
final class ForkCalcCore {

    private ForkCalcCore() {
    }

    // ---------- number formatting ----------

    static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        BigDecimal bd = new BigDecimal(v).round(new MathContext(10));
        if (bd.abs().compareTo(new BigDecimal("1e15")) >= 0 || (bd.signum() != 0 && bd.abs().compareTo(new BigDecimal("1e-9")) < 0)) {
            return String.format(Locale.US, "%.6e", v);
        }
        String s = bd.stripTrailingZeros().toPlainString();
        if (s.equals("-0")) s = "0";
        return s;
    }

    static double parseNumber(String s) {
        s = s.replace(" ", "").replace(" ", "");
        if (s.indexOf(',') >= 0 && s.indexOf('.') < 0) {
            s = s.replace(',', '.');
        } else {
            s = s.replace(",", "");
        }
        return Double.parseDouble(s);
    }

    // ---------- math ----------

    /** Returns null unless the text is an expression with at least one operator or function. */
    static String math(String expr) {
        String e = expr.replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
                .replace("**", "^").replace(" ", "").replace("\u00a0", "").toLowerCase(Locale.ROOT);
        if (!e.matches("[0-9.,+\\-*/^%()a-zπ!]+")) {
            return null;
        }
        e = e.replaceAll("(?<=[0-9)])x(?=[0-9(])", "*");
        boolean hasOp = e.matches(".*[0-9)π!%a-z][+\\-*/^].*") || e.matches(".*[a-z]{2,}.*") || e.matches(".*[!%π].*");
        if (!hasOp) {
            return null;
        }
        e = e.replace(',', '.');
        double v = new Parser(e).parse();
        return format(v);
    }

    private static final class Parser {
        private final String s;
        private int pos;
        private boolean lastPercent;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double v = expression();
            if (pos != s.length()) {
                throw new IllegalArgumentException("unexpected " + s.charAt(pos));
            }
            return v;
        }

        private boolean eat(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        // "200+10%" means 200 + 10% of 200
        private double expression() {
            double v = term();
            while (true) {
                boolean plus;
                if (eat('+')) plus = true;
                else if (eat('-')) plus = false;
                else return v;
                lastPercent = false;
                double t = term();
                if (lastPercent) t = v * t;
                v = plus ? v + t : v - t;
                lastPercent = false;
            }
        }

        private double term() {
            double v = factor();
            while (true) {
                if (eat('*')) {
                    v *= factor();
                } else if (eat('/')) {
                    v /= factor();
                } else {
                    return v;
                }
                lastPercent = false;
            }
        }

        private double factor() {
            if (eat('+')) return factor();
            if (eat('-')) return -factor();
            double v = postfix();
            if (eat('^')) {
                v = Math.pow(v, factor());
                lastPercent = false;
            }
            return v;
        }

        private double postfix() {
            double v = primary();
            while (true) {
                if (eat('!')) {
                    if (v < 0 || v > 170 || v != Math.floor(v)) throw new IllegalArgumentException("factorial");
                    double f = 1;
                    for (int i = 2; i <= v; i++) f *= i;
                    v = f;
                    lastPercent = false;
                } else if (eat('%')) {
                    v = v / 100;
                    lastPercent = true;
                } else {
                    return v;
                }
            }
        }

        private double primary() {
            lastPercent = false;
            if (eat('(')) {
                double v = expression();
                if (!eat(')')) throw new IllegalArgumentException(") expected");
                lastPercent = false;
                return v;
            }
            if (eat('π')) return Math.PI;
            int start = pos;
            if (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                while (pos < s.length() && Character.isLetter(s.charAt(pos))) pos++;
                String name = s.substring(start, pos);
                switch (name) {
                    case "pi": return Math.PI;
                    case "e": return Math.E;
                }
                double a = factor();
                lastPercent = false;
                switch (name) {
                    case "sqrt": return Math.sqrt(a);
                    case "cbrt": return Math.cbrt(a);
                    case "sin": return Math.sin(Math.toRadians(a));
                    case "cos": return Math.cos(Math.toRadians(a));
                    case "tan": case "tg": return Math.tan(Math.toRadians(a));
                    case "asin": return Math.toDegrees(Math.asin(a));
                    case "acos": return Math.toDegrees(Math.acos(a));
                    case "atan": return Math.toDegrees(Math.atan(a));
                    case "ln": return Math.log(a);
                    case "log": case "lg": return Math.log10(a);
                    case "abs": return Math.abs(a);
                    case "exp": return Math.exp(a);
                    case "round": return Math.round(a);
                    case "floor": return Math.floor(a);
                    case "ceil": return Math.ceil(a);
                }
                throw new IllegalArgumentException("function " + name);
            }
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (start == pos) throw new IllegalArgumentException("number expected");
            return Double.parseDouble(s.substring(start, pos));
        }
    }

    // ---------- units ----------

    static final String NUM = "([-+]?[0-9][0-9  ]*(?:[.,][0-9]+)?)";
    private static final Pattern CONVERT = Pattern.compile("^" + NUM + "\\s*([^\\d\\s]+?)\\s*(?:\\s(?:in|to|в|во|->|=>)\\s*|\\s+|->|=>)([^\\d\\s]+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static final class Unit {
        final String category;
        final double factor;

        Unit(String category, double factor) {
            this.category = category;
            this.factor = factor;
        }
    }

    static final HashMap<String, Unit> UNITS = new HashMap<>();
    private static final HashMap<String, String> UNIT_LABEL = new HashMap<>();

    private static void unit(String category, double factor, String label, String... names) {
        Unit u = new Unit(category, factor);
        for (String n : names) {
            UNITS.put(n, u);
            UNIT_LABEL.put(n, label);
        }
    }

    static {
        // length, base metre
        unit("len", 1e-3, "mm", "mm", "мм");
        unit("len", 1e-2, "cm", "cm", "см");
        unit("len", 1, "m", "m", "м", "meter", "meters", "metre", "metres", "метр", "метров", "метра");
        unit("len", 1000, "km", "km", "км", "километр", "километров", "километра");
        unit("len", 0.0254, "in", "in", "inch", "inches", "\"", "дюйм", "дюймов", "дюйма");
        unit("len", 0.3048, "ft", "ft", "foot", "feet", "фут", "футов", "фута");
        unit("len", 0.9144, "yd", "yd", "yard", "yards", "ярд", "ярдов");
        unit("len", 1609.344, "mi", "mi", "mile", "miles", "миля", "миль", "мили");
        unit("len", 1852, "nmi", "nmi", "морская");
        // mass, base kilogram
        unit("mass", 1e-6, "mg", "mg", "мг");
        unit("mass", 1e-3, "g", "g", "г", "гр", "gram", "grams", "грамм");
        unit("mass", 1, "kg", "kg", "кг", "kilogram", "kilograms", "килограмм");
        unit("mass", 1000, "t", "t", "т", "ton", "tons", "тонна", "тонн");
        unit("mass", 0.028349523125, "oz", "oz", "ounce", "ounces", "унция", "унций");
        unit("mass", 0.45359237, "lb", "lb", "lbs", "pound", "pounds", "фунт", "фунтов");
        unit("mass", 6.35029318, "st", "st", "stone");
        // volume, base litre
        unit("vol", 1e-3, "ml", "ml", "мл");
        unit("vol", 1, "l", "l", "л", "liter", "liters", "litre", "litres", "литр", "литров", "литра");
        unit("vol", 3.785411784, "gal", "gal", "gallon", "gallons", "галлон", "галлонов");
        unit("vol", 0.0295735295625, "fl oz", "floz");
        unit("vol", 0.473176473, "pt", "pt", "pint", "pints", "пинта", "пинт");
        unit("vol", 0.2365882365, "cup", "cup", "cups");
        unit("vol", 1000, "m³", "m3", "м3");
        // speed, base m/s
        unit("speed", 1, "m/s", "m/s", "м/с", "mps");
        unit("speed", 1 / 3.6, "km/h", "km/h", "kmh", "kph", "км/ч");
        unit("speed", 0.44704, "mph", "mph", "миль/ч");
        unit("speed", 0.514444, "kn", "kn", "kt", "knot", "knots", "узел", "узлов");
        // data, base byte
        unit("data", 0.125, "bit", "bit", "bits", "бит");
        unit("data", 1, "B", "b", "byte", "bytes", "байт");
        unit("data", 1024, "KB", "kb", "кб", "kib");
        unit("data", 1024d * 1024, "MB", "mb", "мб", "mib");
        unit("data", 1024d * 1024 * 1024, "GB", "gb", "гб", "gib");
        unit("data", 1024d * 1024 * 1024 * 1024, "TB", "tb", "тб", "tib");
        // time, base second
        unit("time", 1e-3, "ms", "ms", "мс");
        unit("time", 1, "s", "s", "sec", "с", "сек", "second", "seconds", "секунд", "секунды");
        unit("time", 60, "min", "min", "мин", "minute", "minutes", "минут", "минуты");
        unit("time", 3600, "h", "h", "hr", "ч", "час", "часа", "часов", "hour", "hours");
        unit("time", 86400, "d", "d", "day", "days", "д", "дн", "день", "дня", "дней");
        unit("time", 604800, "wk", "wk", "week", "weeks", "нед", "неделя", "недели", "недель");
        unit("time", 31557600, "yr", "yr", "year", "years", "год", "года", "лет");
        // area, base m²
        unit("area", 1, "m²", "m2", "м2", "sqm");
        unit("area", 1e6, "km²", "km2", "км2");
        unit("area", 100, "a", "сотка", "соток", "сотки", "ar");
        unit("area", 10000, "ha", "ha", "га");
        unit("area", 4046.8564224, "ac", "ac", "acre", "acres", "акр", "акров");
        unit("area", 0.09290304, "ft²", "ft2", "sqft");
        // temperature, handled separately
        unit("temp", 0, "°C", "c", "°c", "celsius", "цельсий", "ц");
        unit("temp", 1, "°F", "f", "°f", "fahrenheit", "фаренгейт");
        unit("temp", 2, "K", "k", "kelvin", "кельвин");
    }

    static String units(String expr) {
        Matcher m = CONVERT.matcher(expr.trim());
        if (!m.matches()) {
            return null;
        }
        String fromName = m.group(2).toLowerCase(Locale.ROOT);
        String toName = m.group(3).toLowerCase(Locale.ROOT);
        Unit from = UNITS.get(fromName);
        Unit to = UNITS.get(toName);
        if (from == null || to == null || !from.category.equals(to.category)) {
            return null;
        }
        double v = parseNumber(m.group(1));
        double r;
        if (from.category.equals("temp")) {
            double c;
            if (from.factor == 0) c = v;
            else if (from.factor == 1) c = (v - 32) * 5 / 9;
            else c = v - 273.15;
            if (to.factor == 0) r = c;
            else if (to.factor == 1) r = c * 9 / 5 + 32;
            else r = c + 273.15;
        } else {
            r = v * from.factor / to.factor;
        }
        String f = format(r);
        return f == null ? null : f + " " + UNIT_LABEL.get(toName);
    }

    // ---------- time zones ----------

    private static final Pattern TIME = Pattern.compile("^(?:(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?\\s*|(?:now|time|сейчас|время)\\s*)?([a-zа-я/_+\\-0-9]+?)?\\s*(?:\\s(?:in|to|в|во)\\s+|\\s*->\\s*)([a-zа-я/_+\\-0-9]+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final HashMap<String, String> ZONES = new HashMap<>();

    static {
        String[][] z = {
            {"utc", "UTC"}, {"gmt", "GMT"}, {"msk", "Europe/Moscow"}, {"мск", "Europe/Moscow"}, {"москва", "Europe/Moscow"}, {"moscow", "Europe/Moscow"},
            {"cet", "Europe/Berlin"}, {"cest", "Europe/Berlin"}, {"eet", "Europe/Kiev"}, {"bst", "Europe/London"}, {"london", "Europe/London"}, {"лондон", "Europe/London"},
            {"berlin", "Europe/Berlin"}, {"берлин", "Europe/Berlin"}, {"paris", "Europe/Paris"}, {"париж", "Europe/Paris"}, {"kyiv", "Europe/Kiev"}, {"киев", "Europe/Kiev"},
            {"minsk", "Europe/Minsk"}, {"минск", "Europe/Minsk"}, {"istanbul", "Europe/Istanbul"}, {"стамбул", "Europe/Istanbul"},
            {"est", "America/New_York"}, {"edt", "America/New_York"}, {"et", "America/New_York"}, {"ny", "America/New_York"}, {"nyc", "America/New_York"}, {"нью-йорк", "America/New_York"},
            {"cst", "America/Chicago"}, {"cdt", "America/Chicago"}, {"mst", "America/Denver"}, {"mdt", "America/Denver"},
            {"pst", "America/Los_Angeles"}, {"pdt", "America/Los_Angeles"}, {"pt", "America/Los_Angeles"}, {"la", "America/Los_Angeles"},
            {"jst", "Asia/Tokyo"}, {"tokyo", "Asia/Tokyo"}, {"токио", "Asia/Tokyo"}, {"kst", "Asia/Seoul"}, {"ist", "Asia/Kolkata"},
            {"dubai", "Asia/Dubai"}, {"дубай", "Asia/Dubai"}, {"gst", "Asia/Dubai"}, {"beijing", "Asia/Shanghai"}, {"пекин", "Asia/Shanghai"},
            {"almaty", "Asia/Almaty"}, {"алматы", "Asia/Almaty"}, {"tashkent", "Asia/Tashkent"}, {"ташкент", "Asia/Tashkent"},
            {"ekb", "Asia/Yekaterinburg"}, {"екб", "Asia/Yekaterinburg"}, {"екатеринбург", "Asia/Yekaterinburg"},
            {"новосибирск", "Asia/Novosibirsk"}, {"нск", "Asia/Novosibirsk"}, {"владивосток", "Asia/Vladivostok"}, {"vladivostok", "Asia/Vladivostok"},
            {"самара", "Europe/Samara"}, {"калининград", "Europe/Kaliningrad"}, {"sydney", "Australia/Sydney"}, {"сидней", "Australia/Sydney"},
            {"aest", "Australia/Sydney"}, {"local", ""}, {"здесь", ""}, {"local time", ""},
        };
        for (String[] e : z) ZONES.put(e[0], e[1]);
    }

    private static TimeZone zone(String name) {
        if (name == null) {
            return TimeZone.getDefault();
        }
        String n = name.toLowerCase(Locale.ROOT);
        String id = ZONES.get(n);
        if (id != null) {
            return id.isEmpty() ? TimeZone.getDefault() : TimeZone.getTimeZone(id);
        }
        Matcher off = Pattern.compile("^(?:utc|gmt)([+-])(\\d{1,2})(?::?(\\d{2}))?$").matcher(n);
        if (off.matches()) {
            return TimeZone.getTimeZone(String.format(Locale.US, "GMT%s%02d:%02d", off.group(1), Integer.parseInt(off.group(2)), off.group(3) == null ? 0 : Integer.parseInt(off.group(3))));
        }
        if (name.contains("/")) {
            for (String tzId : TimeZone.getAvailableIDs()) {
                if (tzId.equalsIgnoreCase(name)) return TimeZone.getTimeZone(tzId);
            }
        }
        return null;
    }

    static String timeZone(String expr) {
        Matcher m = TIME.matcher(expr.trim());
        if (!m.matches()) {
            return null;
        }
        boolean hasTime = m.group(1) != null;
        boolean hasNowWord = !hasTime && expr.trim().toLowerCase(Locale.ROOT).matches("^(now|time|сейчас|время)\\s.*");
        if (!hasTime && !hasNowWord && m.group(4) == null) {
            return null;
        }
        TimeZone fromZone = zone(m.group(4));
        TimeZone toZone = zone(m.group(5));
        if (fromZone == null || toZone == null || (!hasTime && !hasNowWord && m.group(4) == null)) {
            return null;
        }
        if (!hasTime && m.group(4) != null && UNITS.containsKey(m.group(4).toLowerCase(Locale.ROOT))) {
            return null;
        }
        Calendar cal = Calendar.getInstance(fromZone);
        if (hasTime) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            String ampm = m.group(3);
            if (ampm != null) {
                if (h == 12) h = 0;
                if (ampm.equalsIgnoreCase("pm")) h += 12;
            }
            if (h > 23 || min > 59) return null;
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, min);
            cal.set(Calendar.SECOND, 0);
        } else if (m.group(4) != null && !hasNowWord) {
            // "msk in utc" without a time means the current time
        }
        Date date = cal.getTime();
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(toZone);
        String res = f.format(date);
        SimpleDateFormat day = new SimpleDateFormat("yyyyDDD", Locale.US);
        day.setTimeZone(toZone);
        SimpleDateFormat dayFrom = new SimpleDateFormat("yyyyDDD", Locale.US);
        dayFrom.setTimeZone(fromZone);
        int diff = Integer.compare(Integer.parseInt(day.format(date)), Integer.parseInt(dayFrom.format(date)));
        if (diff > 0) res += " (+1)";
        else if (diff < 0) res += " (-1)";
        return res + " " + m.group(5).toUpperCase(Locale.ROOT);
    }

}
