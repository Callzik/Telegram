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
    public static boolean markDeleted;
    public static boolean markDeletedIcon;
    public static boolean localPremium;
    public static boolean premiumTranslate;
    public static boolean premiumFolders;
    public static boolean premiumDownload;
    public static boolean amoledDark;
    public static boolean systemFont;

    // what to hide in the interface
    public static boolean hideStories;
    public static boolean hidePremiumSettings;
    public static boolean hideHelpSettings;
    public static boolean hideShareButton;
    public static boolean hidePremiumPromo;

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
        markDeleted = p.getBoolean("markDeleted", true);
        markDeletedIcon = p.getBoolean("markDeletedIcon", true);
        localPremium = p.getBoolean("localPremium", true);
        premiumTranslate = p.getBoolean("premiumTranslate", true);
        premiumFolders = p.getBoolean("premiumFolders", true);
        premiumDownload = p.getBoolean("premiumDownload", true);
        amoledDark = p.getBoolean("amoledDark", false);
        systemFont = p.getBoolean("systemFont", false);
        hideStories = p.getBoolean("hideStories", true);
        hidePremiumSettings = p.getBoolean("hidePremiumSettings", true);
        hideHelpSettings = p.getBoolean("hideHelpSettings", false);
        hideShareButton = p.getBoolean("hideShareButton", false);
        hidePremiumPromo = p.getBoolean("hidePremiumPromo", true);
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
            .putBoolean("markDeleted", markDeleted)
            .putBoolean("markDeletedIcon", markDeletedIcon)
            .putBoolean("localPremium", localPremium)
            .putBoolean("premiumTranslate", premiumTranslate)
            .putBoolean("premiumFolders", premiumFolders)
            .putBoolean("premiumDownload", premiumDownload)
            .putBoolean("amoledDark", amoledDark)
            .putBoolean("systemFont", systemFont)
            .putBoolean("hideStories", hideStories)
            .putBoolean("hidePremiumSettings", hidePremiumSettings)
            .putBoolean("hideHelpSettings", hideHelpSettings)
            .putBoolean("hideShareButton", hideShareButton)
            .putBoolean("hidePremiumPromo", hidePremiumPromo)
            .apply();
    }

    public static boolean isSaveDeleted() {
        load();
        return saveDeleted;
    }

    public static boolean markDeleted() {
        load();
        return markDeleted;
    }

    // trash icon next to the time instead of the "deleted" word
    public static boolean markDeletedIcon() {
        load();
        return markDeletedIcon;
    }

    public static boolean isSaveEdits() {
        load();
        return saveEdits;
    }

    // client-side only: up to 8 accounts instead of 3 and premium app icons
    public static boolean localPremium() {
        load();
        return localPremium;
    }

    // "Translate entire chat" bar; uses the same messages.translateText as per-message translation
    public static boolean premiumTranslate() {
        load();
        return localPremium && premiumTranslate;
    }

    // folders over the free limit stay usable instead of locked (the server still limits creating new ones)
    public static boolean premiumFolders() {
        load();
        return localPremium && premiumFolders;
    }

    // parallel download connections and in-flight budget of Premium (the server may still throttle)
    public static boolean premiumDownload() {
        load();
        return localPremium && premiumDownload;
    }

    public static boolean amoledDark() {
        load();
        return amoledDark;
    }

    public static boolean systemFont() {
        load();
        return systemFont;
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

    // Premium upsell: download speed banners, transcription hints
    public static boolean hidePremiumPromo() {
        load();
        return hidePremiumPromo;
    }

    // Called for every outgoing request. Returns true if the request must not be sent.
    public static boolean filterRequest(TLObject object) {
        // Client telemetry (help.saveAppLog) is never sent
        if (object instanceof TLRPC.TL_help_saveAppLog) {
            return true;
        }
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
