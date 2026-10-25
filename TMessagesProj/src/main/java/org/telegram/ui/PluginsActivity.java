package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.View;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Callable;

public class PluginsActivity extends UniversalFragment {

    private static final int MASTER = 1;
    private static final int INSTALL = 2;
    private static final int PLUGIN = 1000;

    private static final int REQUEST_PICK_PLUGIN = 7301;

    private final PluginsController controller = PluginsController.getInstance();
    private final Runnable listener = () -> {
        if (listView != null) {
            listView.adapter.update(true);
        }
    };

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
        return getString(R.string.Plugins);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(MASTER, getString(R.string.PluginsEnable)).setChecked(controller.masterEnabled));
        items.add(UItem.asShadow(getString(controller.safeModeTriggered ? R.string.PluginsSafeMode : R.string.PluginsEnableInfo)));
        if (!controller.masterEnabled) {
            return;
        }
        items.add(UItem.asHeader(getString(R.string.PluginsInstalled)));
        ArrayList<PluginInfo> plugins = controller.getPlugins();
        for (int i = 0; i < plugins.size(); i++) {
            PluginInfo info = plugins.get(i);
            String subtitle = info.getSubtitle();
            if (info.enabled && info.error != null && !info.loaded) {
                subtitle = TextUtils.isEmpty(subtitle) ? getString(R.string.PluginsLoadError) : subtitle + " · " + getString(R.string.PluginsLoadError);
            }
            UItem item = UItem.asButtonCheck(PLUGIN + i, info.name, TextUtils.isEmpty(subtitle) ? info.id : subtitle).setChecked(info.enabled);
            item.object = info.id;
            items.add(item);
        }
        if (plugins.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.PluginsEmpty)));
        }
        items.add(UItem.asButton(INSTALL, R.drawable.msg_add, getString(R.string.PluginsInstallFromFile)).accent());
        items.add(UItem.asShadow(getString(R.string.PluginsInstallInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == MASTER) {
            controller.setMasterEnabled(!controller.masterEnabled);
        } else if (item.id == INSTALL) {
            pickFile();
        } else if (item.object instanceof String) {
            String id = (String) item.object;
            PluginInfo info = controller.getPlugin(id);
            if (info == null) {
                return;
            }
            boolean onSwitch = LocaleController.isRTL ? x < AndroidUtilities.dp(76) : x > view.getMeasuredWidth() - AndroidUtilities.dp(76);
            if (onSwitch) {
                controller.setEnabled(id, !info.enabled);
            } else {
                presentFragment(new PluginSettingsActivity(id));
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof String)) {
            return false;
        }
        String id = (String) item.object;
        PluginInfo info = controller.getPlugin(id);
        if (info == null) {
            return false;
        }
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_settings, getString(R.string.PluginSettings), () -> presentFragment(new PluginSettingsActivity(id)))
            .add(R.drawable.msg_log, getString(R.string.PluginLog), () -> showLog(this, id))
            .add(R.drawable.msg_share, getString(R.string.PluginShare), () -> shareFile(this, info))
            .add(R.drawable.msg_delete, getString(R.string.PluginDelete), true, () -> confirmDelete(this, info, null))
            .show();
        return true;
    }

    private void pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_PLUGIN);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_PLUGIN && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            showInstallDialog(this, () -> PluginsController.readUri(uri));
        }
    }

    // ---------------------------------------------------------------------------------
    // shared helpers

    public static void showInstallDialog(BaseFragment fragment, File file) {
        showInstallDialog(fragment, () -> PluginsController.readFile(file));
    }

    // Reads and parses the plugin in the background, then asks the user to confirm.
    public static void showInstallDialog(BaseFragment fragment, Callable<String> reader) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String source = null;
            PluginInfo parsed = null;
            String error = null;
            try {
                source = reader.call();
                parsed = PluginInfo.parse(source);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String finalSource = source;
            final PluginInfo info = parsed;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (info == null) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, formatString(R.string.PluginInvalidFile, String.valueOf(finalError))).show();
                    return;
                }
                PluginInfo existing = PluginsController.getInstance().getPlugin(info.id);
                SpannableStringBuilder message = new SpannableStringBuilder();
                message.append(AndroidUtilities.replaceTags("**" + escape(info.name) + "**"));
                String subtitle = info.getSubtitle();
                if (!TextUtils.isEmpty(subtitle)) {
                    message.append('\n').append(subtitle);
                }
                if (!TextUtils.isEmpty(info.description)) {
                    message.append("\n\n").append(info.description);
                }
                message.append("\n\n").append(getString(R.string.PluginInstallWarning));
                AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
                builder.setTitle(getString(existing != null ? R.string.PluginUpdateTitle : R.string.PluginInstallTitle));
                builder.setMessage(message);
                builder.setPositiveButton(getString(existing != null ? R.string.PluginUpdate : R.string.PluginInstall), (d, w) -> {
                    PluginsController.getInstance().install(finalSource, installError -> {
                        if (installError == null) {
                            BulletinFactory.global().createSimpleBulletin(R.raw.contact_check, formatString(R.string.PluginInstalled, info.name)).show();
                        } else {
                            BulletinFactory.global().createSimpleBulletin(R.raw.error, formatString(R.string.PluginInstallFailed, firstLine(installError))).show();
                        }
                    });
                });
                builder.setNegativeButton(getString(R.string.Cancel), null);
                fragment.showDialog(builder.create());
            });
        });
    }

    private static String escape(String s) {
        return s.replace("*", "");
    }

    private static String firstLine(String s) {
        String[] lines = s.trim().split("\n");
        return lines[lines.length - 1];
    }

    public static void showLog(BaseFragment fragment, String id) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        PluginsController controller = PluginsController.getInstance();
        String log = controller.getLog(id);
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(getString(R.string.PluginLog));
        builder.setMessage(TextUtils.isEmpty(log) ? getString(R.string.PluginLogEmpty) : log);
        builder.setPositiveButton(getString(R.string.OK), null);
        if (!TextUtils.isEmpty(log)) {
            builder.setNeutralButton(getString(R.string.Copy), (d, w) -> AndroidUtilities.addToClipboard(log));
            builder.setNegativeButton(getString(R.string.PluginLogClear), (d, w) -> controller.clearLog(id));
        }
        fragment.showDialog(builder.create());
    }

    public static void shareFile(BaseFragment fragment, PluginInfo info) {
        Activity activity = fragment.getParentActivity();
        if (activity == null || info.file == null) {
            return;
        }
        try {
            File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
            dir.mkdirs();
            File out = new File(dir, info.id + PluginsController.PLUGIN_EXT);
            try (FileOutputStream os = new FileOutputStream(out)) {
                os.write(PluginsController.readFile(info.file).getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", out);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, info.name));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void confirmDelete(BaseFragment fragment, PluginInfo info, Runnable after) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(getString(R.string.PluginDelete));
        builder.setMessage(formatString(R.string.PluginDeleteConfirm, info.name));
        builder.setPositiveButton(getString(R.string.Delete), (d, w) -> {
            PluginsController.getInstance().delete(info.id);
            BulletinFactory.global().createSimpleBulletin(R.raw.info, getString(R.string.PluginDeleted)).show();
            if (after != null) {
                after.run();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        dialog.redPositive();
    }
}
