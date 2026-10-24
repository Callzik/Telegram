package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.collection.LongSparseArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

// Remembers messages that other people deleted while "save deleted messages" is on.
// Key is "<channelDialogId or 0>_<messageId>", same scheme as server delete updates.
public final class DeletedMessagesStore {

    private static final int MAX_ENTRIES = 20000;
    private static final DeletedMessagesStore[] instances = new DeletedMessagesStore[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;
    private LinkedHashSet<String> ids;

    public static DeletedMessagesStore getInstance(int account) {
        DeletedMessagesStore store = instances[account];
        if (store == null) {
            synchronized (DeletedMessagesStore.class) {
                store = instances[account];
                if (store == null) {
                    instances[account] = store = new DeletedMessagesStore(account);
                }
            }
        }
        return store;
    }

    private DeletedMessagesStore(int account) {
        this.account = account;
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_deleted" + account, Context.MODE_PRIVATE);
    }

    private synchronized LinkedHashSet<String> ids() {
        if (ids == null) {
            ids = new LinkedHashSet<>(prefs().getStringSet("ids", new HashSet<>()));
        }
        return ids;
    }

    private static String key(long dialogId, int id) {
        return dialogId + "_" + id;
    }

    // dialogId is 0 for private chats and basic groups, -channelId for channels/supergroups
    public synchronized void add(LongSparseArray<ArrayList<Integer>> deleted) {
        LinkedHashSet<String> set = ids();
        for (int a = 0; a < deleted.size(); a++) {
            long dialogId = deleted.keyAt(a);
            ArrayList<Integer> list = deleted.valueAt(a);
            if (list == null) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                set.add(key(dialogId, list.get(i)));
            }
        }
        while (set.size() > MAX_ENTRIES) {
            set.remove(set.iterator().next());
        }
        Set<String> copy = new HashSet<>(set);
        prefs().edit().putStringSet("ids", copy).apply();
    }

    public synchronized boolean isDeleted(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.getId() <= 0 || messageObject.scheduled) {
            return false;
        }
        LinkedHashSet<String> set = ids();
        if (set.isEmpty()) {
            return false;
        }
        long channelId = messageObject.messageOwner.peer_id != null ? messageObject.messageOwner.peer_id.channel_id : 0;
        return set.contains(key(channelId != 0 ? -channelId : 0, messageObject.getId()));
    }
}
