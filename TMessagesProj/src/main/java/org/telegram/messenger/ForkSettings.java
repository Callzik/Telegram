package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

// Runtime switches of the fork, editable in Settings -> Fork settings.
public final class ForkSettings {

    public static boolean ghostMode;
    public static boolean ghostNoRead;
    public static boolean ghostNoTyping;
    public static boolean ghostOffline;
    public static boolean saveDeleted;
    public static boolean saveEdits;

    // what to hide in the interface
    public static boolean hideStories;
    public static boolean hidePremiumSettings;
    public static boolean hideHelpSettings;
    public static boolean hideShareButton;

    private static boolean loaded;

    private ForkSettings() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_settings", Context.MODE_PRIVATE);
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        SharedPreferences p = prefs();
        ghostMode = p.getBoolean("ghostMode", false);
        ghostNoRead = p.getBoolean("ghostNoRead", true);
        ghostNoTyping = p.getBoolean("ghostNoTyping", true);
        ghostOffline = p.getBoolean("ghostOffline", true);
        saveDeleted = p.getBoolean("saveDeleted", true);
        saveEdits = p.getBoolean("saveEdits", true);
        hideStories = p.getBoolean("hideStories", true);
        hidePremiumSettings = p.getBoolean("hidePremiumSettings", true);
        hideHelpSettings = p.getBoolean("hideHelpSettings", false);
        hideShareButton = p.getBoolean("hideShareButton", false);
        loaded = true;
    }

    public static void save() {
        prefs().edit()
            .putBoolean("ghostMode", ghostMode)
            .putBoolean("ghostNoRead", ghostNoRead)
            .putBoolean("ghostNoTyping", ghostNoTyping)
            .putBoolean("ghostOffline", ghostOffline)
            .putBoolean("saveDeleted", saveDeleted)
            .putBoolean("saveEdits", saveEdits)
            .putBoolean("hideStories", hideStories)
            .putBoolean("hidePremiumSettings", hidePremiumSettings)
            .putBoolean("hideHelpSettings", hideHelpSettings)
            .putBoolean("hideShareButton", hideShareButton)
            .apply();
    }

    public static boolean isSaveDeleted() {
        load();
        return saveDeleted;
    }

    public static boolean isSaveEdits() {
        load();
        return saveEdits;
    }

    public static boolean hideStories() {
        load();
        return hideStories;
    }

    public static boolean hidePremiumSettings() {
        load();
        return hidePremiumSettings;
    }

    public static boolean hideHelpSettings() {
        load();
        return hideHelpSettings;
    }

    public static boolean hideShareButton() {
        load();
        return hideShareButton;
    }

    // Called for every outgoing request. Returns true if the request must not be sent.
    public static boolean filterRequest(TLObject object) {
        load();
        if (!ghostMode) {
            return false;
        }
        if (ghostOffline && object instanceof TL_account.updateStatus) {
            ((TL_account.updateStatus) object).offline = true;
            return false;
        }
        if (ghostNoTyping && object instanceof TLRPC.TL_messages_setTyping) {
            return true;
        }
        if (ghostNoRead && (
            object instanceof TLRPC.TL_messages_readHistory ||
            object instanceof TLRPC.TL_channels_readHistory ||
            object instanceof TLRPC.TL_messages_readDiscussion ||
            object instanceof TLRPC.TL_messages_readMessageContents ||
            object instanceof TLRPC.TL_channels_readMessageContents ||
            object instanceof TL_stories.TL_stories_readStories ||
            object instanceof TL_stories.TL_stories_incrementStoryViews
        )) {
            return true;
        }
        return false;
    }
}
