package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fork: results right in the input field. Typing "=" at the end of an expression appends its result:
 * "2+2*3=" -> "2+2*3=8", "100 usd rub=" -> "100 usd rub=9 250.12 RUB", "5 km in mi=", "15:00 msk in utc=".
 */
public final class ForkCalc {

    private ForkCalc() {
    }

    public interface Callback {
        void onResult(String result);
    }

    /** Text watcher that appends the result when "=" is typed at the end of the text. */
    public static TextWatcher watcher(EditText editText) {
        return new TextWatcher() {
            private boolean typedEquals;
            private boolean inside;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                typedEquals = !inside && count == 1 && before == 0 && start + count == s.length() && s.length() > 1
                        && (s.charAt(s.length() - 1) == '=');
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!typedEquals || inside || !ForkSettings.inputCalc) {
                    return;
                }
                typedEquals = false;
                final String text = editable.toString();
                String body = text.substring(0, text.length() - 1);
                int nl = body.lastIndexOf('\n');
                String expr = (nl >= 0 ? body.substring(nl + 1) : body).trim();
                if (expr.isEmpty() || expr.length() > 200) {
                    return;
                }
                compute(expr, result -> {
                    if (result == null || !text.equals(editText.getText().toString())) {
                        return;
                    }
                    inside = true;
                    try {
                        editText.getText().append(result);
                        editText.setSelection(editText.length());
                    } catch (Exception e) {
                        FileLog.e(e);
                    } finally {
                        inside = false;
                    }
                });
            }
        };
    }

    /** Computes the result; the callback runs on the UI thread (maybe later, for currencies). */
    public static void compute(String expr, Callback callback) {
        String r;
        try {
            r = ForkCalcCore.timeZone(expr);
            if (r == null) r = ForkCalcCore.units(expr);
            if (r == null) {
                if (currency(expr, callback)) {
                    return;
                }
                r = ForkCalcCore.math(expr);
            }
        } catch (Exception e) {
            r = null;
        }
        callback.onResult(r);
    }

    private static String formatMoney(double v) {
        BigDecimal bd = new BigDecimal(v).setScale(Math.abs(v) >= 1 ? 2 : 6, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return bd.toPlainString();
    }

    // ---------- currencies ----------

    private static final HashMap<String, String> CURRENCY_ALIASES = new HashMap<>();

    static {
        String[][] a = {
            {"$", "USD"}, {"usd", "USD"}, {"доллар", "USD"}, {"долларов", "USD"}, {"доллара", "USD"}, {"бакс", "USD"}, {"баксов", "USD"},
            {"€", "EUR"}, {"eur", "EUR"}, {"евро", "EUR"},
            {"₽", "RUB"}, {"rub", "RUB"}, {"руб", "RUB"}, {"рубль", "RUB"}, {"рублей", "RUB"}, {"рубля", "RUB"}, {"р", "RUB"},
            {"£", "GBP"}, {"gbp", "GBP"}, {"фунтов стерлингов", "GBP"},
            {"¥", "JPY"}, {"jpy", "JPY"}, {"йен", "JPY"}, {"иен", "JPY"},
            {"₴", "UAH"}, {"uah", "UAH"}, {"грн", "UAH"}, {"гривен", "UAH"},
            {"₸", "KZT"}, {"kzt", "KZT"}, {"тенге", "KZT"},
            {"byn", "BYN"}, {"cny", "CNY"}, {"юаней", "CNY"}, {"юань", "CNY"}, {"try", "TRY"}, {"лир", "TRY"}, {"₺", "TRY"},
        };
        for (String[] e : a) CURRENCY_ALIASES.put(e[0], e[1]);
    }

    private static final Pattern MONEY = Pattern.compile("^(?:([$€₽£¥₴₸₺])\\s*" + ForkCalcCore.NUM + "|" + ForkCalcCore.NUM + "\\s*([a-zа-я$€₽£¥₴₸₺]+))\\s*(?:(?:\\s(?:in|to|в|во)\\s+|\\s+|->|=>)\\s*([a-zа-я$€₽£¥₴₸₺]+))?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static String currencyCode(String s) {
        if (s == null) return null;
        String l = s.toLowerCase(Locale.ROOT);
        String c = CURRENCY_ALIASES.get(l);
        if (c != null) return c;
        if (l.matches("[a-z]{3}")) {
            String up = l.toUpperCase(Locale.ROOT);
            JSONObject rates = cachedRates;
            if (rates == null || rates.has(up)) return up;
        }
        return null;
    }

    private static JSONObject cachedRates;
    private static long cachedAt;

    private static String defaultTarget(String from) {
        String lang = LocaleController.getInstance().getCurrentLocale().getLanguage();
        String local = "ru".equals(lang) ? "RUB" : "uk".equals(lang) ? "UAH" : "be".equals(lang) ? "BYN" : "kk".equals(lang) ? "KZT" : "USD";
        return local.equals(from) ? ("USD".equals(from) ? "EUR" : "USD") : local;
    }

    /** Returns true when the text is a currency conversion; the result is delivered to the callback later. */
    static boolean currency(String expr, Callback callback) {
        Matcher m = MONEY.matcher(expr.trim());
        if (!m.matches()) {
            return false;
        }
        String amount = m.group(2) != null ? m.group(2) : m.group(3);
        String fromRaw = m.group(1) != null ? m.group(1) : m.group(4);
        String from = currencyCode(fromRaw);
        String to = m.group(5) != null ? currencyCode(m.group(5)) : null;
        if (from == null || (m.group(5) != null && to == null)) {
            return false;
        }
        if (ForkCalcCore.UNITS.containsKey(fromRaw.toLowerCase(Locale.ROOT)) && !CURRENCY_ALIASES.containsKey(fromRaw.toLowerCase(Locale.ROOT))) {
            return false;
        }
        final double value = ForkCalcCore.parseNumber(amount);
        final String target = to != null ? to : defaultTarget(from);
        loadRates(rates -> {
            String result = null;
            try {
                if (rates != null && rates.has(from) && rates.has(target)) {
                    double r = value / rates.getDouble(from) * rates.getDouble(target);
                    result = formatMoney(r) + " " + target;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            callback.onResult(result);
        });
        return true;
    }

    private interface RatesCallback {
        void onRates(JSONObject rates);
    }

    private static void loadRates(RatesCallback callback) {
        if (cachedRates != null && System.currentTimeMillis() - cachedAt < 6 * 3600 * 1000L) {
            callback.onRates(cachedRates);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            JSONObject rates = null;
            SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("fork_settings", 0);
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://open.er-api.com/v6/latest/USD").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    JSONObject json = new JSONObject(out.toString("UTF-8"));
                    rates = json.getJSONObject("rates");
                    prefs.edit().putString("calcRates", rates.toString()).putLong("calcRatesAt", System.currentTimeMillis()).apply();
                }
            } catch (Exception e) {
                FileLog.e(e);
                try {
                    String saved = prefs.getString("calcRates", null);
                    if (saved != null) rates = new JSONObject(saved);
                } catch (Exception ignore) {
                }
            }
            final JSONObject result = rates;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    cachedRates = result;
                    cachedAt = System.currentTimeMillis();
                }
                callback.onRates(result);
            });
        });
    }
}
