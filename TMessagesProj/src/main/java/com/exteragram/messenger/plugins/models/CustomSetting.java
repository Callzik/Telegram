package com.exteragram.messenger.plugins.models;

import android.view.View;

import org.telegram.ui.Components.UItem;

// exteraGram's custom settings row. MyTelegram's plugin settings screen doesn't render
// custom rows; the types exist so plugins that reference them can load.
public class CustomSetting {

    public Object factoryArgs;

    public static class Factory extends UItem.UItemFactory<View> {
        public UItem create(Object plugin, CustomSetting setting, Object args) {
            return null;
        }

        public void onClick(Object plugin, UItem item, View view) {
        }
    }
}
