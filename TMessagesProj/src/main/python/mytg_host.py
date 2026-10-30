"""Glue between PluginsController (Java) and plugins. Everything here runs on the
plugin queue, PluginsController never calls it from another thread."""

import builtins
import importlib
import importlib.util
import json
import os
import re
import sys
import traceback
import types
import zipfile

from java import jclass

from base_plugin import BasePlugin, HookStrategy

_PC = jclass("org.telegram.messenger.plugins.PluginsController")
_Compat = jclass("com.exteragram.messenger.plugins.PluginsController")

DEFAULT = int(HookStrategy.DEFAULT)
CANCEL = int(HookStrategy.CANCEL)
MODIFY = int(HookStrategy.MODIFY)
MODIFY_FINAL = int(HookStrategy.MODIFY_FINAL)

plugins = {}      # id -> BasePlugin
_settings = {}    # id -> items of the last create_settings()
_aliases = {}     # id -> module registered as sys.modules[id], so plugins can `import zwylib`
_has_update_hooks = False

ELYX_NS = "ElyxPlugins"


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


def _register_alias(plugin_id, module):
    """Lets other plugins import a plugin by its id, as exteraGram does for libraries."""
    if not plugin_id.isidentifier() or plugin_id in sys.modules:
        return
    try:
        if importlib.util.find_spec(plugin_id) is not None:
            return
    except (ImportError, ValueError):
        pass
    sys.modules[plugin_id] = module
    _aliases[plugin_id] = module


def _drop_alias(plugin_id):
    module = _aliases.pop(plugin_id, None)
    if module is not None and sys.modules.get(plugin_id) is module:
        del sys.modules[plugin_id]


def _finish_load(plugin_id, plugin, module, alias=True):
    plugins[plugin_id] = plugin
    _Compat.getPythonEngine().pluginInstances.put(plugin_id, plugin)
    plugin.on_plugin_load()
    plugin._loaded = True
    if alias:
        _register_alias(plugin_id, module)
    rebuild_hooks()
    return [None, _overrides(plugin, "create_settings")]


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
        return _finish_load(plugin_id, plugin, module)
    except BaseException:
        error = traceback.format_exc()
        plugins.pop(plugin_id, None)
        _Compat.getPythonEngine().pluginInstances.remove(plugin_id)
        if plugin is not None:
            plugin._cleanup()
        sys.modules.pop(_module_name(plugin_id), None)
        rebuild_hooks()
        return [error, False]


def unload_plugin(plugin_id):
    plugin = plugins.pop(plugin_id, None)
    _settings.pop(plugin_id, None)
    _Compat.getPythonEngine().pluginInstances.remove(plugin_id)
    if plugin is None:
        _forget_elyx(plugin_id)
        return
    try:
        plugin.on_plugin_unload()
    except BaseException:
        plugin._report("on_plugin_unload")
    plugin._cleanup()
    sys.modules.pop(_module_name(plugin_id), None)
    _drop_alias(plugin_id)
    _forget_elyx(plugin_id)
    rebuild_hooks()


# ---- structured (Elyx) plugins ---------------------------------------------------
# An unpacked plugin lives in plugins/ElyxPlugins/<id>/ and is imported as the package
# ElyxPlugins.<id>, so same-named files of different plugins don't collide. A plain
# `import helpers` inside plugin code is resolved against the plugin's own files first.

_elyx_roots = {}  # id -> unpacked folder


def _ensure_namespace(elyx_dir):
    ns = sys.modules.get(ELYX_NS)
    if ns is None:
        ns = types.ModuleType(ELYX_NS)
        ns.__path__ = []
        ns.__package__ = ELYX_NS
        sys.modules[ELYX_NS] = ns
    if elyx_dir not in ns.__path__:
        ns.__path__.append(elyx_dir)
    # as in exteraGram, a plugin folder with __init__.py is importable by its id (import zwylib)
    if elyx_dir not in sys.path:
        sys.path.append(elyx_dir)
    plugins_dir = os.path.dirname(elyx_dir)
    if plugins_dir not in sys.path:
        sys.path.append(plugins_dir)


def _module_dir(module_name):
    m = sys.modules.get(module_name)
    path = getattr(m, "__path__", None) if m is not None else None
    if path:
        for p in path:
            return p
    return None


def local_module_name(plugin_id, name, package=None, importer_globals=None):
    """Full ElyxPlugins.<id>.* name of a plugin's own module, or None."""
    root = _elyx_roots.get(plugin_id)
    if root is None or not name or name.startswith("."):
        if name and name.startswith(".") and package:
            return importlib.util.resolve_name(name, package)
        return None
    top = name.partition(".")[0]
    base = ELYX_NS + "." + plugin_id
    candidates = []
    pkg = (importer_globals or {}).get("__package__") or package
    if pkg and pkg.startswith(base):
        d = _module_dir(pkg)
        if d:
            candidates.append((pkg, d))
    env = elyx_env(plugin_id)
    if env is not None and env.get("_main_pkg"):
        candidates.append(env["_main_pkg"])
    candidates.append((base, root))
    for prefix, d in candidates:
        if (os.path.isfile(os.path.join(d, top + ".py")) or os.path.isdir(os.path.join(d, top))
                or os.path.isfile(os.path.join(d, top + ".pyc"))):
            return prefix + "." + name
    return None


def elyx_env(plugin_id):
    import elyx
    return elyx._envs.get(plugin_id)


_orig_import = builtins.__import__


def _plugin_import(name, globals=None, locals=None, fromlist=(), level=0):
    if level == 0 and globals is not None and name and not name.startswith(ELYX_NS):
        owner = globals.get("__name__") or ""
        if owner.startswith(ELYX_NS + "."):
            plugin_id = owner.split(".")[1]
            local = local_module_name(plugin_id, name, None, globals)
            if local is not None:
                module = _orig_import(local, globals, locals, ["__name__"], 0)
                if fromlist:
                    return module
                return sys.modules[local[:len(local) - len(name)] + name.partition(".")[0]]
    return _orig_import(name, globals, locals, fromlist, level)


builtins.__import__ = _plugin_import


def _install_wheels(plugin_id, root, wheels_dir):
    src = os.path.join(root, wheels_dir)
    if not os.path.isdir(src):
        return []
    libs = os.path.join(os.path.dirname(os.path.dirname(root)), "elyx_local_libs", plugin_id)
    os.makedirs(libs, exist_ok=True)
    wanted = []
    for name in sorted(os.listdir(src)):
        if not name.endswith(".whl"):
            continue
        stem = name[:-4]
        target = os.path.join(libs, stem)
        if not os.path.isdir(target):
            with zipfile.ZipFile(os.path.join(src, name)) as z:
                z.extractall(target + ".tmp")
            os.replace(target + ".tmp", target)
        wanted.append(stem)
    import shutil
    for old in os.listdir(libs):
        if old not in wanted:
            shutil.rmtree(os.path.join(libs, old), ignore_errors=True)
    paths = [os.path.join(libs, w) for w in wanted]
    for p in paths:
        if p not in sys.path:
            sys.path.append(p)
    return paths


def _forget_elyx(plugin_id):
    import elyx
    env = elyx._envs.pop(plugin_id, None)
    _elyx_roots.pop(plugin_id, None)
    prefix = ELYX_NS + "." + plugin_id
    for name in [n for n in sys.modules if n == prefix or n.startswith(prefix + ".")]:
        del sys.modules[name]
    root = env.get("path") if env is not None else None
    if root:
        # the same files imported by id through sys.path (import zwylib)
        for name in [n for n in sys.modules if n == plugin_id or n.startswith(plugin_id + ".")]:
            f = getattr(sys.modules[name], "__file__", None) or ""
            if f.startswith(root + os.sep):
                del sys.modules[name]
    if env is not None:
        for p in env.get("_wheel_paths", ()):
            if p in sys.path:
                sys.path.remove(p)
    importlib.invalidate_caches()


def load_elyx_plugin(plugin_id, root, main, meta_json, refmap_json, strings_json, assets_dir):
    import elyx
    unload_plugin(plugin_id)
    plugin = None
    try:
        meta = json.loads(meta_json) if meta_json else {}
        refmap = json.loads(refmap_json) if refmap_json else {}
        all_strings = json.loads(strings_json) if strings_json else {}
        _ensure_namespace(os.path.dirname(root))
        importlib.invalidate_caches()
        _elyx_roots[plugin_id] = root

        strings = elyx.Strings(all_strings) if any(all_strings.values()) else None
        assets = None
        if assets_dir:
            d = os.path.join(root, assets_dir)
            if os.path.isdir(d) and os.listdir(d):
                assets = elyx.Assets(d)

        def text(key):
            value = meta.get(key)
            if value is None:
                return None
            value = str(value)
            if strings is not None:
                value = re.sub(r"\{([A-Za-z0-9_]+)\}", lambda m: str(strings.get(m.group(1))), value)
            return value

        base = ELYX_NS + "." + plugin_id
        rel = main[:-3] if main.endswith(".py") else main
        parts = [p for p in rel.replace("\\", "/").split("/") if p]
        module_name = base + "." + ".".join(parts)
        main_pkg = base + ("." + ".".join(parts[:-1]) if len(parts) > 1 else "")
        env = {
            "id": plugin_id,
            "path": root,
            "metainfo": elyx._lazy(meta),
            "refmap": elyx._lazy(refmap),
            "settings": elyx.SettingsController(plugin_id),
            "assets": assets,
            "strings": strings,
            "_main_pkg": (main_pkg, os.path.join(root, *parts[:-1])),
            "_wheel_paths": [],
        }
        elyx._envs[plugin_id] = env
        if refmap.get("wheels"):
            env["_wheel_paths"] = _install_wheels(plugin_id, root, str(refmap["wheels"]))
        if meta.get("requirements"):
            _PC.log(plugin_id, "pip requirements are not supported, skipped: %s" % meta["requirements"])

        elyx._loading.append(plugin_id)
        try:
            module = importlib.import_module(module_name)
        finally:
            elyx._loading.pop()
        cls = _find_class(module)
        if cls is None:
            raise ValueError("no BasePlugin subclass in " + main)
        plugin = cls()
        plugin.id = plugin_id
        plugin.name = text("name") or plugin_id
        plugin.description = text("description")
        plugin.author = text("author")
        plugin.version = text("version")
        plugin.path = root
        has_package = os.path.isfile(os.path.join(root, "__init__.py"))
        return _finish_load(plugin_id, plugin, module, alias=not has_package)
    except BaseException:
        error = traceback.format_exc()
        plugins.pop(plugin_id, None)
        _Compat.getPythonEngine().pluginInstances.remove(plugin_id)
        if plugin is not None:
            plugin._cleanup()
        _forget_elyx(plugin_id)
        rebuild_hooks()
        return [error, False]


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
