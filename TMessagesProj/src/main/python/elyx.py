"""Runtime API of structured (Elyx) plugins, compatible with exteraGram's `elyx` module.

    from elyx import settings, strings, assets, metainfo, refmap

Those five values belong to the plugin that imports them: the module finds the
calling plugin from the namespace its code runs in (ElyxPlugins.<id>.*).
"""

import json
import pathlib
import re
import sys
import traceback

from java import dynamic_proxy, jclass

_PC = jclass("org.telegram.messenger.plugins.PluginsController")

__all__ = [
    "get_environment", "import_module", "gen", "gen2", "mvel_execute",
    "Assets", "Asset", "AssetNotFoundException", "AssetsDirNotFoundException",
    "Strings", "SettingsController", "LazyDict",
    "OnClickListener", "Runnable", "Callback", "Callback2", "Callback3", "CallbackReturn",
]

_BOUND = ("settings", "metainfo", "refmap", "assets", "strings")
NAMESPACE = "ElyxPlugins"

_envs = {}          # plugin id -> environment dict, filled by mytg_host
_loading = []       # ids being imported right now (fallback for the frame lookup)


def _caller_id():
    f = sys._getframe(1)
    while f is not None:
        name = f.f_globals.get("__name__") or ""
        if name.startswith(NAMESPACE + "."):
            parts = name.split(".")
            if len(parts) > 1 and parts[1] in _envs:
                return parts[1]
        f = f.f_back
    return _loading[-1] if _loading else None


def get_environment():
    plugin_id = _caller_id()
    if plugin_id is None:
        raise RuntimeError("elyx.get_environment() called outside Elyx plugin code")
    return _envs[plugin_id]


def __getattr__(name):
    if name in _BOUND:
        plugin_id = _caller_id()
        env = _envs.get(plugin_id) if plugin_id else None
        if env is not None and env.get(name) is not None:
            return env[name]
        raise ImportError("elyx.%s is not available here" % name)
    raise AttributeError("module 'elyx' has no attribute %r" % name)


def import_module(name, package=None):
    import importlib
    plugin_id = _caller_id()
    if plugin_id is not None:
        import mytg_host
        local = mytg_host.local_module_name(plugin_id, name, package)
        if local is not None:
            return importlib.import_module(local)
    return importlib.import_module(name, package)


def mvel_execute(script, data, to_type=None, java_instance=None):
    raise NotImplementedError("MVEL is not available in MyTelegram")


class LazyDict(dict):
    def __getattr__(self, key):
        return self[key]

    def __setattr__(self, key, value):
        self[key] = value


def _lazy(value):
    if isinstance(value, dict):
        return LazyDict({k: _lazy(v) for k, v in value.items()})
    if isinstance(value, list):
        return [_lazy(v) for v in value]
    return value


# ---- settings -------------------------------------------------------------------

class SettingsController:
    def __init__(self, plugin_id):
        self.plugin_id = plugin_id

    def get_settings(self):
        return {str(k): v for k, v in dict(_PC.getAllSettings(self.plugin_id)).items()}

    def get_setting(self, key, default=None):
        value = _PC.getSetting(self.plugin_id, key)
        return default if value is None else value

    get = get_setting

    def set_setting(self, key, value, reload_settings=False):
        if value is None:
            _PC.removeSetting(self.plugin_id, key)
        elif isinstance(value, bool):
            _PC.putBoolean(self.plugin_id, key, value)
        elif isinstance(value, int):
            _PC.putLong(self.plugin_id, key, value)
        elif isinstance(value, float):
            _PC.putFloat(self.plugin_id, key, value)
        else:
            _PC.putString(self.plugin_id, key, str(value))

    set = set_setting

    def clear_settings(self):
        for key in list(self.get_settings()):
            _PC.removeSetting(self.plugin_id, key)

    def __getitem__(self, key):
        return self.get_setting(key)

    def __setitem__(self, key, value):
        self.set_setting(key, value)

    def __call__(self, key, default=None):
        return self.get_setting(key, default)


# ---- strings --------------------------------------------------------------------

def _current_locale():
    try:
        return str(jclass("org.telegram.messenger.LocaleController").getInstance()
                   .getCurrentLocale().getLanguage()).lower()
    except Exception:
        return "en"


class Strings:
    def __init__(self, all_strings):
        object.__setattr__(self, "_all", {k.lower(): dict(v) for k, v in (all_strings or {}).items()})

    def _lookup(self, key, locale=None, default=None):
        for loc in (locale or _current_locale(), "en"):
            table = self._all.get(loc)
            if table is not None and key in table:
                return table[key]
        return key if default is None else default

    def get(self, key, default=None):
        return self._lookup(key, default=default)

    def get_with_locale(self, key, locale="en", default=None):
        return self._lookup(key, locale.lower(), default)

    def catalog(self, key, **kwargs):
        text = self._lookup(key)
        return text.format(**kwargs) if kwargs and isinstance(text, str) else text

    def __call__(self, key, *args, default=None, **kwargs):
        text = self._lookup(key, default=default)
        if (args or kwargs) and isinstance(text, str):
            try:
                return text.format(*args, **kwargs)
            except (IndexError, KeyError):
                return text
        return text

    def pluralize(self, count, key):
        forms = self._lookup(key)
        if isinstance(forms, str):
            forms = forms.split("|")
        if not isinstance(forms, (list, tuple)) or not forms:
            return str(forms)
        n = abs(int(count))
        if n % 10 == 1 and n % 100 != 11:
            i = 0
        elif 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
            i = 1
        else:
            i = 2
        return str(forms[min(i, len(forms) - 1)])

    def __getitem__(self, key):
        return self._lookup(key)

    def __getattr__(self, key):
        if key.startswith("__"):
            raise AttributeError(key)
        return self._lookup(key)

    def __contains__(self, key):
        return any(key in t for t in self._all.values())


# ---- assets ---------------------------------------------------------------------

class AssetNotFoundException(Exception):
    pass


class AssetsDirNotFoundException(Exception):
    pass


def _norm(stem):
    return re.sub(r"[^A-Za-z0-9]", "_", stem)


class Asset:
    def __init__(self, dir_path, filename, name=None):
        self.path = pathlib.Path(dir_path) / filename
        self.filename = filename
        self.name = name or _norm(self.path.stem)
        self.ext = self.path.suffix[1:] if self.path.suffix else ""

    @classmethod
    def from_path(cls, path):
        if not isinstance(path, (str, pathlib.Path)):
            path = str(path.getAbsolutePath())
        p = pathlib.Path(path)
        return cls(p.parent, p.name)

    @classmethod
    def temp_asset_from_url(cls, url, filename):
        import urllib.request
        cache = pathlib.Path(str(jclass("org.telegram.messenger.ApplicationLoader")
                                 .applicationContext.getCacheDir().getAbsolutePath())) / "elyx_assets"
        cache.mkdir(parents=True, exist_ok=True)
        target = cache / filename
        with urllib.request.urlopen(url, timeout=30) as r, open(target, "wb") as f:
            f.write(r.read())
        return cls(cache, filename)

    @property
    def path_str(self):
        return str(self.path)

    @property
    def java_file(self):
        return jclass("java.io.File")(str(self.path))

    def content_bytes(self):
        return self.path.read_bytes()

    def content_string(self):
        return self.path.read_text(encoding="utf-8")

    def content_json(self):
        return json.loads(self.content_string())

    def content_yaml(self):
        return json.loads(str(_PC.yamlToJson(self.content_string())))

    def content(self):
        ext = self.ext.lower()
        if ext == "json":
            return self.content_json()
        if ext in ("yaml", "yml"):
            return self.content_yaml()
        data = self.content_bytes()
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            return data

    def to_image_location(self):
        return jclass("org.telegram.messenger.ImageLocation").getForPath(self.path_str)

    def to_drawable(self):
        return jclass("android.graphics.drawable.Drawable").createFromPath(self.path_str)

    def to_bitmap_drawable(self, width=32, height=32):
        AU = jclass("org.telegram.messenger.AndroidUtilities")
        bitmap = jclass("android.graphics.BitmapFactory").decodeFile(self.path_str)
        if bitmap is None:
            return None
        bitmap = jclass("android.graphics.Bitmap").createScaledBitmap(bitmap, AU.dp(width), AU.dp(height), True)
        ctx = jclass("org.telegram.messenger.ApplicationLoader").applicationContext
        return jclass("android.graphics.drawable.BitmapDrawable")(ctx.getResources(), bitmap)

    def to_svg_bitmap(self, width=32, height=32, white=False):
        AU = jclass("org.telegram.messenger.AndroidUtilities")
        return jclass("org.telegram.messenger.SvgHelper").getBitmap(self.java_file, AU.dp(width), AU.dp(height), bool(white))

    def to_svg_drawable(self, width=None, height=None):
        if width is not None and height is not None:
            ctx = jclass("org.telegram.messenger.ApplicationLoader").applicationContext
            return jclass("android.graphics.drawable.BitmapDrawable")(ctx.getResources(), self.to_svg_bitmap(width, height))
        return jclass("org.telegram.messenger.SvgHelper").getDrawable(self.content_string())

    def to_svg_thumb(self, color_key=None, alpha=1.0):
        return self.to_svg_drawable()

    def to_lottie_drawable(self, width=32, height=32):
        AU = jclass("org.telegram.messenger.AndroidUtilities")
        return jclass("org.telegram.ui.Components.RLottieDrawable")(
            self.java_file, None, AU.dp(width), AU.dp(height), None, False, None, 0, False)

    def __repr__(self):
        return "Asset(%r)" % self.path_str


class Assets:
    def __init__(self, dir_path, parent=None):
        p = pathlib.Path(dir_path)
        if not p.is_dir():
            raise AssetsDirNotFoundException(str(p))
        object.__setattr__(self, "dir_path", p)
        object.__setattr__(self, "_parent", parent)

    @property
    def parent(self):
        return self._parent if self._parent is not None else Assets(self.dir_path.parent)

    def _children(self):
        if not self.dir_path.is_dir():
            raise AssetsDirNotFoundException(str(self.dir_path))
        return sorted(c.name for c in self.dir_path.iterdir())

    def get(self, name):
        name = str(name).strip("/")
        if "/" in name:
            head, rest = name.split("/", 1)
            node = self.get(head)
            if not isinstance(node, Assets):
                raise AssetNotFoundException(name)
            return node.get(rest)
        direct = self.dir_path / name
        if direct.is_dir():
            return Assets(direct, self)
        if direct.is_file():
            return Asset(self.dir_path, name)
        for child in self._children():
            p = self.dir_path / child
            stem = p.stem if p.is_file() else child
            if stem == name or _norm(stem) == name:
                return Assets(p, self) if p.is_dir() else Asset(self.dir_path, child)
        raise AssetNotFoundException(name)

    def __getitem__(self, name):
        return self.get(name)

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return self.get(name)

    def __contains__(self, name):
        try:
            self.get(name)
            return True
        except AssetNotFoundException:
            return False

    def __iter__(self):
        return iter(self._children())

    def __len__(self):
        return len(self._children())

    def __repr__(self):
        return "Assets(%r)" % str(self.dir_path)


# ---- java callbacks ---------------------------------------------------------------

_proxy_cache = {}


def _log_error():
    plugin_id = _caller_id() or "python"
    _PC.log(plugin_id, traceback.format_exc())


def gen(java_class, method_name, return_value=False, default_value=None):
    if isinstance(java_class, str):
        java_class = jclass(java_class)
    key = (java_class, method_name, return_value, repr(default_value))
    cls = _proxy_cache.get(key)
    if cls is None:
        def __init__(self, fn, *extra):
            super(cls, self).__init__()
            self._fn, self._extra = fn, extra

        def call(self, *args):
            try:
                result = self._fn(*args, *self._extra)
            except Exception:
                _log_error()
                return default_value
            return result if return_value else None

        cls = type("Elyx_" + method_name, (dynamic_proxy(java_class),), {"__init__": __init__, method_name: call})
        _proxy_cache[key] = cls
    return cls


def gen2(java_class, return_value=False, **methods):
    if isinstance(java_class, str):
        java_class = jclass(java_class)

    def make(fn):
        def call(self, *args):
            try:
                result = fn(*args)
            except Exception:
                _log_error()
                return None
            return result if return_value else None
        return call

    attrs = {name: make(fn) for name, fn in methods.items()}
    cls = type("Elyx_proxy", (dynamic_proxy(java_class),), attrs)
    return cls()


OnClickListener = gen("android.view.View$OnClickListener", "onClick")
Runnable = gen("java.lang.Runnable", "run")
Callback = gen("org.telegram.messenger.Utilities$Callback", "run")
Callback2 = gen("org.telegram.messenger.Utilities$Callback2", "run")
Callback3 = gen("org.telegram.messenger.Utilities$Callback3", "run")
CallbackReturn = gen("org.telegram.messenger.Utilities$CallbackReturn", "run", return_value=True)
