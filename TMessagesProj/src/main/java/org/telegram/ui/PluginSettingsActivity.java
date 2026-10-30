package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginBridge;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

// One plugin: on/off, its own settings (create_settings), log and removal.
public class PluginSettingsActivity extends UniversalFragment {

    private static final int ENABLE = 1;
    private static final int LOG = 2;
    private static final int SHARE = 3;
    private static final int DELETE = 4;
    private static final int SETTING = 1000;

    private final String pluginId;
    private final PluginsController controller = PluginsController.getInstance();

    private JSONArray settings;
    private boolean settingsRequested;
    private boolean settingsLoading;

    private final Runnable listener = this::onPluginsChanged;

    private void onPluginsChanged() {
        PluginInfo info = controller.getPlugin(pluginId);
        if (info == null) {
            finishFragment();
            return;
        }
        if (!info.loaded) {
            settings = null;
        }
        // settings may have changed (a plugin asked to reload them): fetch them again
        settingsRequested = false;
        update();
    }

    public PluginSettingsActivity(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public boolean onFragmentCreate() {
        controller.addListener(listener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        controller.removeListener(listener);
        super.onFragmentDestroy();
    }

    @Override
    protected CharSequence getTitle() {
        PluginInfo info = controller.getPlugin(pluginId);
        return info != null ? info.name : getString(R.string.Plugins);
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void loadSettings() {
        settingsRequested = true;
        settingsLoading = true;
        controller.requestSettings(pluginId, json -> {
            settingsLoading = false;
            try {
                settings = json != null ? new JSONArray(json) : null;
            } catch (Exception e) {
                FileLog.e(e);
                settings = null;
            }
            update();
        });
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        PluginInfo info = controller.getPlugin(pluginId);
        if (info == null) {
            return;
        }
        items.add(UItem.asCheck(ENABLE, getString(R.string.PluginEnabled)).setChecked(info.enabled));
        StringBuilder about = new StringBuilder();
        if (!TextUtils.isEmpty(info.description)) {
            about.append(info.description).append("\n\n");
        }
        if (!TextUtils.isEmpty(info.author)) {
            about.append(formatString(R.string.PluginAuthor, info.author)).append('\n');
        }
        about.append(formatString(R.string.PluginAbout, TextUtils.isEmpty(info.version) ? "—" : info.version, info.id));
        items.add(UItem.asShadow(about.toString()));

        if (info.enabled && info.error != null) {
            items.add(UItem.asHeader(getString(R.string.PluginError)));
            items.add(UItem.asShadow(shorten(info.error)));
        }

        if (info.loaded && info.hasSettings) {
            if (!settingsRequested) {
                loadSettings();
            }
            items.add(UItem.asHeader(getString(R.string.PluginSettings)));
            if (settings == null) {
                items.add(UItem.asShadow(getString(settingsLoading ? R.string.PluginLoading : R.string.PluginNoSettings)));
            } else {
                fillSettings(items);
            }
        }

        items.add(UItem.asButton(LOG, R.drawable.msg_log, getString(R.string.PluginLog)));
        items.add(UItem.asButton(SHARE, R.drawable.msg_share, getString(R.string.PluginShare)));
        items.add(UItem.asButton(DELETE, R.drawable.msg_delete, getString(R.string.PluginDelete)).red());
        items.add(UItem.asShadow(null));
    }

    private static String shorten(String error) {
        String s = error.trim();
        return s.length() > 1500 ? "…" + s.substring(s.length() - 1500) : s;
    }

    private void fillSettings(ArrayList<UItem> items) {
        boolean lastWasShadow = false;
        for (int i = 0; i < settings.length(); i++) {
            JSONObject o = settings.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String type = o.optString("type");
            String text = o.isNull("text") ? null : o.optString("text");
            String subtext = o.isNull("subtext") ? null : o.optString("subtext", null);
            int icon = PluginBridge.getDrawable(o.isNull("icon") ? null : o.optString("icon", null));
            int id = SETTING + i;
            UItem item = null;
            switch (type) {
                case "header":
                    item = UItem.asHeader(text);
                    break;
                case "divider":
                    item = UItem.asShadow(text);
                    break;
                case "switch":
                    item = (subtext != null ? UItem.asButtonCheck(id, text, subtext) : UItem.asCheck(id, text)).setChecked(o.optBoolean("value"));
                    break;
                case "input": {
                    String value = o.optString("value");
                    item = icon != 0 ? UItem.asButton(id, icon, text, value) : UItem.asButton(id, text, value);
                    break;
                }
                case "selector": {
                    JSONArray options = o.optJSONArray("items");
                    int index = o.optInt("value");
                    String value = options != null && index >= 0 && index < options.length() ? options.optString(index) : "";
                    item = icon != 0 ? UItem.asButton(id, icon, text, value) : UItem.asButton(id, text, value);
                    break;
                }
                case "text":
                    item = icon != 0 ? UItem.asButton(id, icon, text) : UItem.asButton(id, text);
                    if (o.optBoolean("accent")) item.accent();
                    if (o.optBoolean("red")) item.red();
                    break;
            }
            if (item != null) {
                lastWasShadow = "divider".equals(type);
                items.add(item);
            }
        }
        if (!lastWasShadow) {
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        PluginInfo info = controller.getPlugin(pluginId);
        if (info == null) {
            return;
        }
        switch (item.id) {
            case ENABLE:
                controller.setEnabled(pluginId, !info.enabled);
                return;
            case LOG:
                PluginsActivity.showLog(this, pluginId);
                return;
            case SHARE:
                PluginsActivity.shareFile(this, info);
                return;
            case DELETE:
                PluginsActivity.confirmDelete(this, info, this::finishFragment);
                return;
        }
        if (item.id < SETTING || settings == null) {
            return;
        }
        int index = item.id - SETTING;
        JSONObject o = settings.optJSONObject(index);
        if (o == null) {
            return;
        }
        switch (o.optString("type")) {
            case "switch": {
                boolean value = !o.optBoolean("value");
                try {
                    o.put("value", value);
                } catch (Exception ignore) {
                }
                update();
                controller.settingsEvent(pluginId, index, value, this::loadSettings);
                break;
            }
            case "input":
                showInput(index, o);
                break;
            case "selector":
                showSelector(index, o);
                break;
            case "text":
                controller.settingsEvent(pluginId, index, null, this::loadSettings);
                break;
        }
    }

    private void showSelector(int index, JSONObject o) {
        JSONArray options = o.optJSONArray("items");
        if (options == null || getParentActivity() == null) {
            return;
        }
        CharSequence[] names = new CharSequence[options.length()];
        for (int i = 0; i < names.length; i++) {
            names[i] = options.optString(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(o.optString("text"));
        builder.setItems(names, (d, which) -> controller.settingsEvent(pluginId, index, which, this::loadSettings));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showInput(int index, JSONObject o) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        boolean multiline = o.optBoolean("multiline");
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setGravity(Gravity.LEFT | Gravity.TOP);
        if (multiline) {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            editText.setMaxLines(6);
        } else {
            editText.setInputType(InputType.TYPE_CLASS_TEXT);
            editText.setSingleLine(true);
            editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        }
        if (!o.isNull("hint")) {
            editText.setHint(o.optString("hint"));
        }
        editText.setText(o.optString("value"));
        editText.setSelection(editText.length());
        editText.setPadding(0, dp(4), 0, dp(6));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 24, 6, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(o.optString("text"));
        if (!o.isNull("subtext")) {
            builder.setMessage(o.optString("subtext"));
        }
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> {
            AndroidUtilities.hideKeyboard(editText);
            controller.settingsEvent(pluginId, index, editText.getText().toString(), this::loadSettings);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }));
        showDialog(dialog);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
