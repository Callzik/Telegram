package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;

// Previous versions of edited messages, captured right before an edit is written to the local database.
public final class EditHistoryStore {

    public static class Version {
        public final int date;
        public final String text;

        Version(int date, String text) {
            this.date = date;
            this.text = text;
        }
    }

    private static final int MAX_MESSAGES = 5000;
    private static final int MAX_VERSIONS = 30;
    private static final EditHistoryStore[] instances = new EditHistoryStore[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;

    public static EditHistoryStore getInstance(int account) {
        EditHistoryStore store = instances[account];
        if (store == null) {
            synchronized (EditHistoryStore.class) {
                store = instances[account];
                if (store == null) {
                    instances[account] = store = new EditHistoryStore(account);
                }
            }
        }
        return store;
    }

    private EditHistoryStore(int account) {
        this.account = account;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_edits" + account, Context.MODE_PRIVATE);
    }

    private static String key(long dialogId, int id) {
        return dialogId + "_" + id;
    }

    public synchronized void addVersion(long dialogId, int id, int date, String text) {
        if (text == null) {
            text = "";
        }
        SharedPreferences p = prefs();
        String k = key(dialogId, id);
        try {
            JSONArray arr = new JSONArray(p.getString(k, "[]"));
            if (arr.length() > 0 && TextUtils.equals(arr.getJSONObject(arr.length() - 1).optString("t"), text)) {
                return;
            }
            JSONObject o = new JSONObject();
            o.put("d", date);
            o.put("t", text);
            arr.put(o);
            while (arr.length() > MAX_VERSIONS) {
                arr.remove(0);
            }
            SharedPreferences.Editor editor = p.edit().putString(k, arr.toString());
            Map<String, ?> all = p.getAll();
            if (all.size() > MAX_MESSAGES) {
                int toRemove = all.size() - MAX_MESSAGES;
                for (String old : all.keySet()) {
                    if (toRemove-- <= 0) {
                        break;
                    }
                    if (!old.equals(k)) {
                        editor.remove(old);
                    }
                }
            }
            editor.apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public synchronized ArrayList<Version> getVersions(long dialogId, int id) {
        ArrayList<Version> result = new ArrayList<>();
        String json = prefs().getString(key(dialogId, id), null);
        if (json == null) {
            return result;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new Version(o.optInt("d"), o.optString("t")));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public boolean hasVersions(long dialogId, int id) {
        return prefs().contains(key(dialogId, id));
    }
}
