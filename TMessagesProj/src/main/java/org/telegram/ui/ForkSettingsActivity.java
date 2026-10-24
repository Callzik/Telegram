package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.ForkSettings;
import org.telegram.messenger.R;
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
    private static final int HIDE_STORIES = 10;
    private static final int HIDE_PREMIUM = 11;
    private static final int HIDE_HELP = 12;
    private static final int HIDE_SHARE = 13;

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

        items.add(UItem.asHeader(getString(R.string.ForkHideHeader)));
        items.add(UItem.asCheck(HIDE_STORIES, getString(R.string.ForkHideStories)).setChecked(ForkSettings.hideStories));
        items.add(UItem.asCheck(HIDE_PREMIUM, getString(R.string.ForkHidePremium)).setChecked(ForkSettings.hidePremiumSettings));
        items.add(UItem.asCheck(HIDE_HELP, getString(R.string.ForkHideHelp)).setChecked(ForkSettings.hideHelpSettings));
        items.add(UItem.asCheck(HIDE_SHARE, getString(R.string.ForkHideShare)).setChecked(ForkSettings.hideShareButton));
        items.add(UItem.asShadow(getString(R.string.ForkHideInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case GHOST_MODE: ForkSettings.ghostMode = !ForkSettings.ghostMode; break;
            case GHOST_NO_READ: ForkSettings.ghostNoRead = !ForkSettings.ghostNoRead; break;
            case GHOST_NO_TYPING: ForkSettings.ghostNoTyping = !ForkSettings.ghostNoTyping; break;
            case GHOST_OFFLINE: ForkSettings.ghostOffline = !ForkSettings.ghostOffline; break;
            case SAVE_DELETED: ForkSettings.saveDeleted = !ForkSettings.saveDeleted; break;
            case SAVE_EDITS: ForkSettings.saveEdits = !ForkSettings.saveEdits; break;
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
