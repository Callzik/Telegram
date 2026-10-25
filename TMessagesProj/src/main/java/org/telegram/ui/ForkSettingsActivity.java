package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ForkConfig;
import org.telegram.messenger.ForkSettings;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class ForkSettingsActivity extends UniversalFragment {

    private static final int GHOST_MODE = 1;
    private static final int GHOST_NO_READ = 2;
    private static final int GHOST_NO_TYPING = 3;
    private static final int GHOST_OFFLINE = 4;
    private static final int SAVE_DELETED = 5;
    private static final int SAVE_EDITS = 6;
    private static final int LOCAL_PREMIUM = 8;
    private static final int AMOLED = 20;
    private static final int SYSTEM_FONT = 21;
    private static final int HIDE_STORIES = 10;
    private static final int HIDE_PREMIUM = 11;
    private static final int HIDE_HELP = 12;
    private static final int HIDE_SHARE = 13;
    private static final int CHECK_UPDATES = 40;

    @Override
    public boolean onFragmentCreate() {
        ForkSettings.load();
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ForkSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.ForkGhostHeader)));
        items.add(UItem.asCheck(GHOST_MODE, getString(R.string.ForkGhostMode)).setChecked(ForkSettings.ghostMode));
        if (ForkSettings.ghostMode) {
            items.add(UItem.asCheck(GHOST_NO_READ, getString(R.string.ForkGhostNoRead)).setChecked(ForkSettings.ghostNoRead));
            items.add(UItem.asCheck(GHOST_NO_TYPING, getString(R.string.ForkGhostNoTyping)).setChecked(ForkSettings.ghostNoTyping));
            items.add(UItem.asCheck(GHOST_OFFLINE, getString(R.string.ForkGhostOffline)).setChecked(ForkSettings.ghostOffline));
        }
        items.add(UItem.asShadow(getString(R.string.ForkGhostInfo)));

        items.add(UItem.asHeader(getString(R.string.ForkMessagesHeader)));
        items.add(UItem.asCheck(SAVE_DELETED, getString(R.string.ForkSaveDeleted)).setChecked(ForkSettings.saveDeleted));
        items.add(UItem.asCheck(SAVE_EDITS, getString(R.string.ForkSaveEdits)).setChecked(ForkSettings.saveEdits));
        items.add(UItem.asShadow(getString(R.string.ForkSaveDeletedInfo)));

        items.add(UItem.asHeader(getString(R.string.ForkPremiumHeader)));
        items.add(UItem.asCheck(LOCAL_PREMIUM, getString(R.string.ForkLocalPremium)).setChecked(ForkSettings.localPremium));
        items.add(UItem.asShadow(getString(R.string.ForkLocalPremiumInfo)));

        items.add(UItem.asHeader(getString(R.string.ForkAppearanceHeader)));
        items.add(UItem.asCheck(AMOLED, getString(R.string.ForkAmoled)).setChecked(ForkSettings.amoledDark));
        items.add(UItem.asCheck(SYSTEM_FONT, getString(R.string.ForkSystemFont)).setChecked(ForkSettings.systemFont));
        items.add(UItem.asShadow(getString(R.string.ForkAppearanceInfo)));

        items.add(UItem.asHeader(getString(R.string.ForkHideHeader)));
        items.add(UItem.asCheck(HIDE_STORIES, getString(R.string.ForkHideStories)).setChecked(ForkSettings.hideStories));
        items.add(UItem.asCheck(HIDE_PREMIUM, getString(R.string.ForkHidePremium)).setChecked(ForkSettings.hidePremiumSettings));
        items.add(UItem.asCheck(HIDE_HELP, getString(R.string.ForkHideHelp)).setChecked(ForkSettings.hideHelpSettings));
        items.add(UItem.asCheck(HIDE_SHARE, getString(R.string.ForkHideShare)).setChecked(ForkSettings.hideShareButton));
        items.add(UItem.asShadow(getString(R.string.ForkHideInfo)));

        items.add(UItem.asHeader(getString(R.string.ForkUpdatesHeader)));
        items.add(UItem.asButton(CHECK_UPDATES, getString(R.string.ForkCheckUpdates), BuildVars.BUILD_VERSION_STRING + " (" + ForkConfig.FORK_BUILD + ")"));
        items.add(UItem.asShadow(getString(R.string.ForkUpdatesInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == CHECK_UPDATES) {
            if (LaunchActivity.instance != null) {
                LaunchActivity.instance.checkAppUpdate(true, new Browser.Progress());
            }
            return;
        }
        switch (item.id) {
            case GHOST_MODE: ForkSettings.ghostMode = !ForkSettings.ghostMode; break;
            case GHOST_NO_READ: ForkSettings.ghostNoRead = !ForkSettings.ghostNoRead; break;
            case GHOST_NO_TYPING: ForkSettings.ghostNoTyping = !ForkSettings.ghostNoTyping; break;
            case GHOST_OFFLINE: ForkSettings.ghostOffline = !ForkSettings.ghostOffline; break;
            case SAVE_DELETED: ForkSettings.saveDeleted = !ForkSettings.saveDeleted; break;
            case SAVE_EDITS: ForkSettings.saveEdits = !ForkSettings.saveEdits; break;
            case LOCAL_PREMIUM: ForkSettings.localPremium = !ForkSettings.localPremium; break;
            case AMOLED:
                ForkSettings.amoledDark = !ForkSettings.amoledDark;
                ForkSettings.save();
                Theme.refreshThemeColors();
                break;
            case SYSTEM_FONT: ForkSettings.systemFont = !ForkSettings.systemFont; break;
            case HIDE_STORIES: ForkSettings.hideStories = !ForkSettings.hideStories; break;
            case HIDE_PREMIUM: ForkSettings.hidePremiumSettings = !ForkSettings.hidePremiumSettings; break;
            case HIDE_HELP: ForkSettings.hideHelpSettings = !ForkSettings.hideHelpSettings; break;
            case HIDE_SHARE: ForkSettings.hideShareButton = !ForkSettings.hideShareButton; break;
            default: return;
        }
        ForkSettings.save();
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
