package org.telegram.messenger.plugins;

import android.text.TextUtils;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

// Menu items that plugins add with BasePlugin.add_menu_item (exteraGram API). The client
// asks for the items of a menu when it builds it; a click calls the plugin's on_click with
// a context dict on the UI thread. MVEL "condition" expressions are not evaluated.
public final class PluginMenus {

    public static final String MESSAGE_CONTEXT_MENU = "MESSAGE_CONTEXT_MENU";
    public static final String DRAWER_MENU = "DRAWER_MENU";
    public static final String MAIN_MENU = "MAIN_MENU";
    public static final String CHAT_ACTION_MENU = "CHAT_ACTION_MENU";
    public static final String PROFILE_ACTION_MENU = "PROFILE_ACTION_MENU";

    // ids given to the client's option/menu lists; never clash with built-in ids
    public static final int OPTION_BASE = 0x5100_0000;

    public static class Item {
        public final String pluginId;
        public final String id;
        public final String menu;
        public final String text;
        public final String subtext;
        public final String icon;
        public final int priority;
        final PyObject onClick;
        int option;

        Item(String pluginId, String id, String menu, String text, String subtext, String icon, int priority, PyObject onClick) {
            this.pluginId = pluginId;
            this.id = id;
            this.menu = menu;
            this.text = text;
            this.subtext = subtext;
            this.icon = icon;
            this.priority = priority;
            this.onClick = onClick;
        }

        public int getIconRes() {
            return PluginBridge.getDrawable(icon);
        }
    }

    private static final ArrayList<Item> items = new ArrayList<>();
    private static int nextOption = OPTION_BASE;
    private static int nextId;

    private PluginMenus() {
    }

    // ---- called from Python (base_plugin) ----

    public static String add(String pluginId, String itemId, String menu, String text, String subtext, String icon, int priority, PyObject onClick) {
        synchronized (items) {
            String id = TextUtils.isEmpty(itemId) ? pluginId + "_menu_" + (nextId++) : itemId;
            removeLocked(pluginId, id);
            Item item = new Item(pluginId, id, menu, text, subtext, icon, priority, onClick);
            item.option = nextOption++;
            items.add(item);
            Collections.sort(items, (a, b) -> Integer.compare(b.priority, a.priority));
            return id;
        }
    }

    public static boolean remove(String pluginId, String itemId) {
        synchronized (items) {
            return removeLocked(pluginId, itemId);
        }
    }

    private static boolean removeLocked(String pluginId, String itemId) {
        boolean removed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it.pluginId.equals(pluginId) && it.id.equals(itemId)) {
                items.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    public static void removeAll(String pluginId) {
        synchronized (items) {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).pluginId.equals(pluginId)) {
                    items.remove(i);
                }
            }
        }
    }

    // ---- called by the client ----

    public static List<Item> get(String menu) {
        ArrayList<Item> list = new ArrayList<>();
        if (!PluginsController.getInstance().masterEnabled) {
            return list;
        }
        synchronized (items) {
            for (Item it : items) {
                if (it.menu.equals(menu)) {
                    list.add(it);
                }
            }
        }
        return list;
    }

    public static boolean isOption(int option) {
        return option >= OPTION_BASE && option < OPTION_BASE + 0x0100_0000;
    }

    private static Item find(int option) {
        synchronized (items) {
            for (Item it : items) {
                if (it.option == option) return it;
            }
        }
        return null;
    }

    public static int optionOf(Item item) {
        return item.option;
    }

    // message context menu: appends the plugin items to the client's parallel lists
    public static void addMessageItems(ArrayList<CharSequence> texts, ArrayList<Integer> options, ArrayList<Integer> icons) {
        for (Item it : get(MESSAGE_CONTEXT_MENU)) {
            texts.add(it.text);
            options.add(it.option);
            icons.add(it.getIconRes());
        }
    }

    public static void addSubItems(ActionBarMenuItem menu, String type) {
        addSubItems(menu, type, true);
    }

    public static void addSubItems(ActionBarMenuItem menu, String type, boolean lazily) {
        if (menu == null) return;
        for (Item it : get(type)) {
            if (lazily) {
                menu.lazilyAddSubItem(it.option, it.getIconRes(), it.text);
            } else {
                menu.addSubItem(it.option, it.getIconRes(), it.text);
            }
        }
    }

    public static HashMap<String, Object> context(BaseFragment fragment) {
        HashMap<String, Object> ctx = new HashMap<>();
        if (fragment != null) {
            ctx.put("account", fragment.getCurrentAccount());
            ctx.put("fragment", fragment);
            ctx.put("context", fragment.getParentActivity());
        }
        return ctx;
    }

    public static HashMap<String, Object> messageContext(BaseFragment fragment, long dialogId, MessageObject message, MessageObject.GroupedMessages group) {
        HashMap<String, Object> ctx = context(fragment);
        ctx.put("dialog_id", dialogId);
        ctx.put("message", message);
        ctx.put("groupedMessages", group);
        return ctx;
    }

    // returns false if the option is not a plugin item
    public static boolean click(int option, HashMap<String, Object> context) {
        Item item = find(option);
        if (item == null) {
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                item.onClick.call(context);
            } catch (Throwable e) {
                FileLog.e(e);
                PluginsController.reportError(item.pluginId, "menu item " + item.id + ": " + e);
            }
        });
        return true;
    }
}
