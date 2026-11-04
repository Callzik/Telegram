package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ForkTunnel;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

// Settings -> Fork settings -> Bypass blocking
public class ForkTunnelActivity extends UniversalFragment {

    private static final int ENABLE = 1;
    private static final int STATUS = 2;
    private static final int PASTE = 3;
    private static final int ADD_SUB = 4;
    private static final int UPDATE_SUBS = 5;
    private static final int TEST = 6;
    private static final int REMOVE_DEAD = 7;
    private static final int REMOVE_ALL = 8;
    private static final int AUTO = 9;
    private static final int SHOW_ALL = 10;
    private static final int LOG = 11;
    private static final int MT_PASTE = 20;
    private static final int MT_URL = 21;
    private static final int MT_CHECK = 22;
    private static final int MT_LIST = 23;
    private static final int PRESET_BASE = 100;
    private static final int SUB_BASE = 200;
    private static final int SERVER_BASE = 10000;

    private static final int COLLAPSED_LIMIT = 30;

    private boolean showAll;
    private final Runnable listener = () -> {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    };

    @Override
    public boolean onFragmentCreate() {
        ForkTunnel.load();
        ForkTunnel.addListener(listener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        ForkTunnel.removeListener(listener);
        super.onFragmentDestroy();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ForkTunnel);
    }

    private CharSequence statusText() {
        if (!ForkTunnel.isCoreAvailable()) {
            return getString(R.string.ForkTunnelNoCore);
        }
        switch (ForkTunnel.getState()) {
            case STARTING:
                return getString(R.string.ForkTunnelStarting);
            case RUNNING: {
                ForkTunnel.Server s = ForkTunnel.findServer(ForkTunnel.activeTag);
                if (s == null) {
                    return getString(R.string.ForkTunnelRunning);
                }
                Integer d = ForkTunnel.delays.get(s.tag);
                String name = s.name.length() > 28 ? s.name.substring(0, 28) + "…" : s.name;
                return d != null && d > 0 ? name + " · " + formatString(R.string.ForkTunnelMs, d) : name;
            }
            case ERROR:
                return getString(R.string.ForkTunnelError);
            default:
                return getString(R.string.ForkTunnelStopped);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        int usable = ForkTunnel.usableServerCount();

        items.add(UItem.asHeader(getString(R.string.ForkTunnelHeader)));
        items.add(UItem.asCheck(ENABLE, getString(R.string.ForkTunnelEnable)).setChecked(ForkTunnel.enabled));
        if (ForkTunnel.enabled) {
            items.add(UItem.asButton(STATUS, getString(R.string.ForkTunnelStatus), statusText()));
            if (ForkTunnel.getState() == ForkTunnel.State.ERROR) {
                items.add(UItem.asButton(LOG, getString(R.string.ForkTunnelShowLog)));
            }
        }
        items.add(UItem.asShadow(getString(R.string.ForkTunnelInfo)));

        items.add(UItem.asHeader(getString(R.string.ForkTunnelAddHeader)));
        items.add(UItem.asButton(PASTE, R.drawable.msg_copy, getString(R.string.ForkTunnelPaste)));
        items.add(UItem.asButton(ADD_SUB, R.drawable.msg_link, getString(R.string.ForkTunnelAddSub)));
        for (int i = 0; i < ForkTunnel.PRESETS.length; i++) {
            ForkTunnel.Preset p = ForkTunnel.PRESETS[i];
            boolean added = false;
            for (ForkTunnel.Subscription s : ForkTunnel.subscriptions) {
                if (s.url.equals(p.url)) {
                    added = true;
                    break;
                }
            }
            if (!added) {
                items.add(UItem.asButton(PRESET_BASE + i, R.drawable.msg_download, getString(p.nameRes)));
            }
        }
        items.add(UItem.asShadow(getString(R.string.ForkTunnelAddInfo)));

        if (!ForkTunnel.subscriptions.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.ForkTunnelSubsHeader)));
            for (int i = 0; i < ForkTunnel.subscriptions.size(); i++) {
                ForkTunnel.Subscription s = ForkTunnel.subscriptions.get(i);
                String name = TextUtils.isEmpty(s.name) ? Uri(s.url) : s.name;
                String value = s.error != null && s.count == 0 ? getString(R.string.ForkTunnelSubFailed) : String.valueOf(s.count);
                UItem item = UItem.asButton(SUB_BASE + i, name, value);
                item.object = s;
                items.add(item);
            }
            items.add(UItem.asButton(UPDATE_SUBS, R.drawable.msg_retry, getString(R.string.ForkTunnelUpdateSubs)));
            items.add(UItem.asShadow(getString(R.string.ForkTunnelSubsInfo)));
        }

        if (!ForkTunnel.servers.isEmpty()) {
            items.add(UItem.asHeader(formatString(R.string.ForkTunnelServersHeader, usable)));
            if (ForkTunnel.getState() == ForkTunnel.State.RUNNING) {
                items.add(UItem.asButton(TEST, R.drawable.msg_stats, getString(ForkTunnel.testing ? R.string.ForkTunnelTesting : R.string.ForkTunnelTest)));
            }
            items.add(UItem.asRadio(AUTO, getString(R.string.ForkTunnelAuto), getString(R.string.ForkTunnelAutoValue)).setChecked(TextUtils.isEmpty(ForkTunnel.selected)));
            List<ForkTunnel.Server> list = new ArrayList<>(ForkTunnel.servers);
            // fastest first once measured
            java.util.Collections.sort(list, (a, b) -> Integer.compare(sortKey(a), sortKey(b)));
            int shown = 0;
            for (int i = 0; i < list.size(); i++) {
                ForkTunnel.Server s = list.get(i);
                if (!showAll && shown >= COLLAPSED_LIMIT && !s.tag.equals(ForkTunnel.selected)) {
                    continue;
                }
                shown++;
                String value;
                Integer d = ForkTunnel.delays.get(s.tag);
                if (!s.supported) {
                    value = getString(R.string.ForkTunnelUnsupported);
                } else if (d == null) {
                    value = s.type;
                } else if (d <= 0) {
                    value = s.type + " · " + getString(R.string.ForkTunnelDead);
                } else {
                    value = s.type + " · " + formatString(R.string.ForkTunnelMs, d);
                }
                String name = s.name;
                if (s.tag.equals(ForkTunnel.activeTag) && ForkTunnel.getState() == ForkTunnel.State.RUNNING) {
                    name = "● " + name;
                }
                UItem item = UItem.asRadio(SERVER_BASE + ForkTunnel.servers.indexOf(s), name, value).setChecked(s.tag.equals(ForkTunnel.selected));
                item.object = s;
                items.add(item);
            }
            if (!showAll && list.size() > shown) {
                items.add(UItem.asButton(SHOW_ALL, R.drawable.arrow_more, formatString(R.string.ForkTunnelShowAll, list.size())));
            }
            items.add(UItem.asButton(REMOVE_DEAD, R.drawable.msg_clear, getString(R.string.ForkTunnelRemoveDead)));
            items.add(UItem.asButton(REMOVE_ALL, R.drawable.msg_delete, getString(R.string.ForkTunnelRemoveAll)).red());
            items.add(UItem.asShadow(getString(R.string.ForkTunnelServersInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.ForkTunnelMtHeader)));
        items.add(UItem.asButton(MT_PASTE, R.drawable.msg_copy, getString(R.string.ForkTunnelMtPaste)));
        items.add(UItem.asButton(MT_URL, R.drawable.msg_link, getString(R.string.ForkTunnelMtUrl)));
        items.add(UItem.asButton(MT_CHECK, R.drawable.msg_stats, getString(R.string.ForkTunnelMtCheck)));
        items.add(UItem.asButton(MT_LIST, R.drawable.msg_settings, getString(R.string.ForkTunnelMtList)));
        items.add(UItem.asShadow(getString(R.string.ForkTunnelMtInfo)));
    }

    private static String Uri(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 && slash < url.length() - 1 ? url.substring(slash + 1) : url;
    }

    private static int sortKey(ForkTunnel.Server s) {
        if (!s.supported) {
            return Integer.MAX_VALUE;
        }
        Integer d = ForkTunnel.delays.get(s.tag);
        if (d == null) {
            return 1_000_000;
        }
        return d <= 0 ? 2_000_000 : d;
    }

    private String clipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < clip.getItemCount(); i++) {
                CharSequence t = clip.getItemAt(i).coerceToText(getParentActivity());
                if (t != null) {
                    sb.append(t).append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private void toast(CharSequence text) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show();
    }

    private void askUrl(CharSequence title, Utilities_Callback callback) {
        Context ctx = getParentActivity();
        if (ctx == null) {
            return;
        }
        EditTextBoldCursor edit = new EditTextBoldCursor(ctx);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        edit.setHintText("https://…");
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setBackground(null);
        edit.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        edit.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        String clip = clipboardText();
        if (clip != null) {
            List<String> urls = ForkTunnel.extractSubscriptionUrls(clip);
            if (!urls.isEmpty()) {
                edit.setText(urls.get(0));
            }
        }
        FrameLayout frame = new FrameLayout(ctx);
        frame.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 8, 24, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(title);
        b.setView(frame);
        b.setNegativeButton(getString(R.string.Cancel), null);
        b.setPositiveButton(getString(R.string.OK), (d, w) -> {
            String url = edit.getText().toString().trim();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                callback.run(url);
            }
        });
        showDialog(b.create());
    }

    private interface Utilities_Callback {
        void run(String value);
    }

    private void onSubResult(int count, String error) {
        if (count > 0) {
            toast(formatString(R.string.ForkTunnelAdded, count));
            if (!ForkTunnel.enabled) {
                ForkTunnel.setEnabled(true);
            }
        } else if (error != null) {
            toast(getString(R.string.ForkTunnelSubFailed));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= SERVER_BASE && item.object instanceof ForkTunnel.Server) {
            ForkTunnel.Server s = (ForkTunnel.Server) item.object;
            if (!s.supported) {
                toast(getString(R.string.ForkTunnelUnsupportedInfo));
                return;
            }
            ForkTunnel.select(s.tag);
            return;
        }
        if (item.id >= SUB_BASE && item.id < SERVER_BASE && item.object instanceof ForkTunnel.Subscription) {
            ForkTunnel.Subscription s = (ForkTunnel.Subscription) item.object;
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(TextUtils.isEmpty(s.name) ? Uri(s.url) : s.name);
            b.setMessage(s.url);
            b.setNegativeButton(getString(R.string.Cancel), null);
            b.setPositiveButton(getString(R.string.Delete), (d, w) -> {
                ForkTunnel.removeSubscription(s);
                listView.adapter.update(true);
            });
            showDialog(b.create());
            return;
        }
        if (item.id >= PRESET_BASE && item.id < SUB_BASE) {
            ForkTunnel.Preset p = ForkTunnel.PRESETS[item.id - PRESET_BASE];
            toast(getString(R.string.ForkTunnelDownloading));
            ForkTunnel.addSubscription(p.url, getString(p.nameRes), this::onSubResult);
            return;
        }
        switch (item.id) {
            case ENABLE:
                if (!ForkTunnel.enabled && ForkTunnel.usableServerCount() == 0) {
                    toast(getString(R.string.ForkTunnelNoServers));
                    return;
                }
                ForkTunnel.setEnabled(!ForkTunnel.enabled);
                break;
            case STATUS:
            case LOG: {
                String log = ForkTunnel.getLog();
                String err = ForkTunnel.getLastError();
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(getString(R.string.ForkTunnelStatus));
                b.setMessage(TextUtils.isEmpty(log) ? (TextUtils.isEmpty(err) ? statusText() : err) : log);
                b.setPositiveButton(getString(R.string.OK), null);
                showDialog(b.create());
                return;
            }
            case PASTE: {
                String text = clipboardText();
                if (TextUtils.isEmpty(text)) {
                    toast(getString(R.string.ForkTunnelClipboardEmpty));
                    return;
                }
                int added = ForkTunnel.importText(text);
                int mt = ForkTunnel.importTelegramProxies(text);
                List<String> urls = added == 0 && mt == 0 ? ForkTunnel.extractSubscriptionUrls(text) : new ArrayList<>();
                if (!urls.isEmpty()) {
                    toast(getString(R.string.ForkTunnelDownloading));
                    for (String u : urls) {
                        ForkTunnel.addSubscription(u, null, this::onSubResult);
                    }
                } else if (added > 0 || mt > 0) {
                    toast(formatString(R.string.ForkTunnelAdded, added + mt));
                    if (added > 0) {
                        ForkTunnel.setEnabled(true); // starts, or restarts with the new servers
                    }
                } else {
                    toast(getString(R.string.ForkTunnelNothingFound));
                }
                break;
            }
            case ADD_SUB:
                askUrl(getString(R.string.ForkTunnelAddSub), url -> {
                    toast(getString(R.string.ForkTunnelDownloading));
                    ForkTunnel.addSubscription(url, null, this::onSubResult);
                });
                return;
            case UPDATE_SUBS:
                toast(getString(R.string.ForkTunnelDownloading));
                ForkTunnel.updateSubscriptions(true, this::onSubResult);
                return;
            case TEST:
                ForkTunnel.testAll(null);
                break;
            case AUTO:
                ForkTunnel.select("");
                break;
            case SHOW_ALL:
                showAll = true;
                break;
            case REMOVE_DEAD: {
                int removed = ForkTunnel.removeDead();
                toast(formatString(R.string.ForkTunnelRemoved, removed));
                break;
            }
            case REMOVE_ALL: {
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(getString(R.string.ForkTunnelRemoveAll));
                b.setMessage(getString(R.string.ForkTunnelRemoveAllConfirm));
                b.setNegativeButton(getString(R.string.Cancel), null);
                b.setPositiveButton(getString(R.string.Delete), (d, w) -> {
                    ForkTunnel.removeAllServers();
                    listView.adapter.update(true);
                });
                showDialog(b.create());
                return;
            }
            case MT_PASTE: {
                String text = clipboardText();
                int mt = ForkTunnel.importTelegramProxies(text);
                if (mt == 0 && text != null) {
                    List<String> urls = ForkTunnel.extractSubscriptionUrls(text);
                    if (!urls.isEmpty()) {
                        ForkTunnel.downloadTelegramProxies(urls.get(0), (c, e) -> toast(c > 0 ? formatString(R.string.ForkTunnelAdded, c) : getString(R.string.ForkTunnelNothingFound)));
                        return;
                    }
                }
                toast(mt > 0 ? formatString(R.string.ForkTunnelAdded, mt) : getString(R.string.ForkTunnelNothingFound));
                return;
            }
            case MT_URL:
                askUrl(getString(R.string.ForkTunnelMtUrl), url -> {
                    toast(getString(R.string.ForkTunnelDownloading));
                    ForkTunnel.downloadTelegramProxies(url, (c, e) -> toast(c > 0 ? formatString(R.string.ForkTunnelAdded, c) : getString(R.string.ForkTunnelNothingFound)));
                });
                return;
            case MT_CHECK:
                toast(getString(R.string.ForkTunnelTesting));
                ForkTunnel.checkTelegramProxies(() -> toast(getString(R.string.ForkTunnelMtChecked)));
                return;
            case MT_LIST:
                presentFragment(new ProxyListActivity());
                return;
            default:
                return;
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof ForkTunnel.Server) {
            ForkTunnel.Server s = (ForkTunnel.Server) item.object;
            AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
            b.setTitle(s.name);
            b.setItems(new CharSequence[]{getString(R.string.ForkTunnelCopyLink), getString(R.string.Delete)}, (d, which) -> {
                if (which == 0) {
                    AndroidUtilities.addToClipboard(s.link);
                    toast(getString(R.string.LinkCopied));
                } else {
                    ForkTunnel.removeServer(s);
                    listView.adapter.update(true);
                }
            });
            showDialog(b.create());
            return true;
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ForkTunnel.getState() == ForkTunnel.State.RUNNING && ForkTunnel.delays.isEmpty()) {
            ForkTunnel.testAll(null);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
