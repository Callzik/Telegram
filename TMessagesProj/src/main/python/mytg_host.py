"""Glue between PluginsController (Java) and plugins. Everything here runs on the
plugin queue, PluginsController never calls it from another thread."""

import json
import sys
import traceback
import types

from java import jclass

from base_plugin import BasePlugin, HookStrategy

_PC = jclass("org.telegram.messenger.plugins.PluginsController")

DEFAULT = int(HookStrategy.DEFAULT)
CANCEL = int(HookStrategy.CANCEL)
MODIFY = int(HookStrategy.MODIFY)
MODIFY_FINAL = int(HookStrategy.MODIFY_FINAL)

plugins = {}      # id -> BasePlugin
_settings = {}    # id -> items of the last create_settings()
_has_update_hooks = False


def _overrides(plugin, name):
    return getattr(type(plugin), name, None) is not getattr(BasePlugin, name, None)


def _module_name(plugin_id):
    return "plugin_" + "".join(c if c.isalnum() else "_" for c in plugin_id)


def _find_class(module):
    own = module.__dict__.get("__name__")
    found = None
    fallback = None
    for value in list(module.__dict__.values()):
        if isinstance(value, type) and issubclass(value, BasePlugin) and value is not BasePlugin:
            fallback = value
            if value.__module__ == own:
                found = value
    return found or fallback


def load_plugin(plugin_id, path):
    unload_plugin(plugin_id)
    plugin = None
    try:
        with open(path, encoding="utf-8") as f:
            source = f.read()
        name = _module_name(plugin_id)
        module = types.ModuleType(name)
        module.__file__ = path
        sys.modules[name] = module
        exec(compile(source, path, "exec"), module.__dict__)
        cls = _find_class(module)
        if cls is None:
            raise ValueError("no BasePlugin subclass in the plugin")
        plugin = cls()
        g = module.__dict__
        plugin.id = plugin_id
        plugin.name = g.get("__name__") if g.get("__name__") != name else plugin_id
        plugin.description = g.get("__description__")
        plugin.author = g.get("__author__")
        plugin.version = g.get("__version__")
        plugin.path = path
        plugins[plugin_id] = plugin
        plugin.on_plugin_load()
        plugin._loaded = True
        rebuild_hooks()
        return [None, _overrides(plugin, "create_settings")]
    except BaseException:
        error = traceback.format_exc()
        plugins.pop(plugin_id, None)
        if plugin is not None:
            plugin._cleanup()
        sys.modules.pop(_module_name(plugin_id), None)
        rebuild_hooks()
        return [error, False]


def unload_plugin(plugin_id):
    plugin = plugins.pop(plugin_id, None)
    _settings.pop(plugin_id, None)
    if plugin is None:
        return
    try:
        plugin.on_plugin_unload()
    except BaseException:
        plugin._report("on_plugin_unload")
    plugin._cleanup()
    sys.modules.pop(_module_name(plugin_id), None)
    rebuild_hooks()


def rebuild_hooks():
    global _has_update_hooks
    pre, post, update, updates = set(), set(), set(), set()
    send = False
    for p in plugins.values():
        for name, (sub, _prio) in p._hooks.items():
            key = "~" + name if sub else name
            if _overrides(p, "pre_request_hook"):
                pre.add(key)
            if _overrides(p, "post_request_hook"):
                post.add(key)
            if _overrides(p, "on_update_hook"):
                update.add(key)
            if _overrides(p, "on_updates_hook"):
                updates.add(key)
        if p._send_hook is not None and _overrides(p, "on_send_message_hook"):
            send = True
    _has_update_hooks = bool(update)
    _PC.setHooks(list(pre), list(post), list(update), list(updates), send)


def _subscribers(name, method):
    found = []
    for p in plugins.values():
        prio = p._match(name)
        if prio is not None and _overrides(p, method):
            found.append((prio, p))
    found.sort(key=lambda x: -x[0])
    return [p for _, p in found]


def _send_subscribers():
    found = [(p._send_hook, p) for p in plugins.values()
             if p._send_hook is not None and _overrides(p, "on_send_message_hook")]
    found.sort(key=lambda x: -x[0])
    return [p for _, p in found]


# ---- hooks called by PluginsController ----------------------------------------
# each returns [code, value...]; code is DEFAULT, MODIFY or CANCEL

def pre_request(name, account, request):
    code = DEFAULT
    for p in _subscribers(name, "pre_request_hook"):
        try:
            r = p.pre_request_hook(name, account, request)
        except Exception:
            p._report("pre_request_hook")
            continue
        if r is None or r.strategy == HookStrategy.DEFAULT:
            continue
        if r.strategy == HookStrategy.CANCEL:
            return [CANCEL, None]
        if r.request is not None:
            request = r.request
        code = MODIFY
        if r.strategy == HookStrategy.MODIFY_FINAL:
            break
    return [code, request]


def post_request(name, account, response, error):
    code = DEFAULT
    for p in _subscribers(name, "post_request_hook"):
        try:
            r = p.post_request_hook(name, account, response, error)
        except Exception:
            p._report("post_request_hook")
            continue
        if r is None or r.strategy == HookStrategy.DEFAULT:
            continue
        if r.strategy == HookStrategy.CANCEL:
            return [CANCEL, None, None]
        if r.response is not None:
            response = r.response
        if r.error is not None:
            error = r.error
        code = MODIFY
        if r.strategy == HookStrategy.MODIFY_FINAL:
            break
    return [code, response, error]


def _run_update_hooks(name, account, update):
    """Returns (cancelled, update)."""
    for p in _subscribers(name, "on_update_hook"):
        try:
            r = p.on_update_hook(name, account, update)
        except Exception:
            p._report("on_update_hook")
            continue
        if r is None or r.strategy == HookStrategy.DEFAULT:
            continue
        if r.strategy == HookStrategy.CANCEL:
            return True, None
        if r.update is not None:
            update = r.update
        if r.strategy == HookStrategy.MODIFY_FINAL:
            break
    return False, update


def on_updates(container_name, account, updates):
    for p in _subscribers(container_name, "on_updates_hook"):
        try:
            r = p.on_updates_hook(container_name, account, updates)
        except Exception:
            p._report("on_updates_hook")
            continue
        if r is None or r.strategy == HookStrategy.DEFAULT:
            continue
        if r.strategy == HookStrategy.CANCEL:
            return [CANCEL, None]
        if r.updates is not None:
            updates = r.updates
        if r.strategy == HookStrategy.MODIFY_FINAL:
            break

    if _has_update_hooks:
        single = updates.update
        if single is not None:
            cancelled, new = _run_update_hooks(single.getClass().getSimpleName(), account, single)
            if cancelled:
                return [CANCEL, None]
            if new is not single:
                updates.update = new
        items = updates.updates
        if items is not None:
            i = 0
            while i < items.size():
                u = items.get(i)
                if u is not None:
                    cancelled, new = _run_update_hooks(u.getClass().getSimpleName(), account, u)
                    if cancelled:
                        items.remove(i)
                        continue
                    if new is not u:
                        items.set(i, new)
                i += 1
    return [MODIFY, updates]


def on_send_message(account, params):
    code = DEFAULT
    for p in _send_subscribers():
        try:
            r = p.on_send_message_hook(account, params)
        except Exception:
            p._report("on_send_message_hook")
            continue
        if r is None or r.strategy == HookStrategy.DEFAULT:
            continue
        if r.strategy == HookStrategy.CANCEL:
            return [CANCEL, None]
        if r.params is not None:
            params = r.params
        code = MODIFY
        if r.strategy == HookStrategy.MODIFY_FINAL:
            break
    return [code, params]


# ---- settings -------------------------------------------------------------------

def get_settings_json(plugin_id):
    plugin = plugins.get(plugin_id)
    if plugin is None:
        return None
    try:
        items = list(plugin.create_settings() or [])
    except Exception:
        plugin._report("create_settings")
        return None
    _settings[plugin_id] = items
    out = []
    for item in items:
        try:
            out.append(item._to_json(plugin))
        except Exception:
            plugin._report("create_settings")
            out.append({"type": "divider", "text": "error: " + type(item).__name__})
    return json.dumps(out)


def settings_event(plugin_id, index, value):
    plugin = plugins.get(plugin_id)
    items = _settings.get(plugin_id)
    if plugin is None or items is None or not 0 <= index < len(items):
        return
    try:
        items[index]._event(plugin, value)
    except Exception:
        plugin._report("settings")
