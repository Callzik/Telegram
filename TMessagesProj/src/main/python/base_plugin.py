"""Base class and hook types for MyTelegram plugins.

The API follows exteraGram's plugin API, so simple exteraGram plugins work as is:

    from base_plugin import BasePlugin, HookResult, HookStrategy

    __id__ = "hello"
    __name__ = "Hello"
    __description__ = "Replaces .hi with a greeting"
    __author__ = "me"
    __version__ = "1.0"

    class HelloPlugin(BasePlugin):
        def on_plugin_load(self):
            self.add_on_send_message_hook()

        def on_send_message_hook(self, account, params):
            if params.message == ".hi":
                params.message = "Hello!"
                return HookResult(strategy=HookStrategy.MODIFY, params=params)
            return HookResult()

All plugin code, hooks and settings callbacks run on one background thread
("pluginsQueue"), never on the UI thread. Use android_utils.run_on_ui_thread for
anything that touches views. A hook that takes longer than 5 seconds is skipped.
"""

import traceback
from enum import IntEnum

from java import jclass

_PC = jclass("org.telegram.messenger.plugins.PluginsController")
_Bridge = jclass("org.telegram.messenger.plugins.PluginBridge")


class HookStrategy(IntEnum):
    DEFAULT = 0       # leave the data as it is
    CANCEL = 1        # drop the request / update / message
    MODIFY = 2        # replace the data and let other plugins see the new one
    MODIFY_FINAL = 3  # replace the data and stop calling other plugins


class HookResult:
    def __init__(self, strategy=HookStrategy.DEFAULT, request=None, response=None,
                 error=None, update=None, updates=None, params=None):
        self.strategy = strategy
        self.request = request
        self.response = response
        self.error = error
        self.update = update
        self.updates = updates
        self.params = params


class XposedHook:
    """Base of method hook callbacks (exteraGram API)."""


class MethodHook(XposedHook):
    """Hook for hook_method(). param is a Pine CallFrame with thisObject, args,
    method, getResult(), setResult(), getThrowable(), setThrowable().
    Calling param.setResult() in before_hooked_method skips the original method.

    Hooked methods run on whatever thread calls them (often the UI thread),
    so keep these callbacks short."""

    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class MethodReplacement(MethodHook):
    """Replaces the method body with replace_hooked_method(param)."""

    def replace_hooked_method(self, param):
        return None

    def before_hooked_method(self, param):
        param.setResult(self.replace_hooked_method(param))


class BaseHook(MethodHook):
    """Hook built from plain functions: BaseHook(None, before=fn, after=fn)."""

    def __init__(self, plugin=None, before=None, after=None, before_filters=None, after_filters=None):
        self.plugin = plugin
        if before is not None:
            def before_hooked_method(param):
                return before(param)
            before_hooked_method.hook_filters = list(before_filters or [])
            self.before_hooked_method = before_hooked_method
        else:
            self.before_hooked_method = None
        if after is not None:
            def after_hooked_method(param):
                return after(param)
            after_hooked_method.hook_filters = list(after_filters or [])
            self.after_hooked_method = after_hooked_method
        else:
            self.after_hooked_method = None


def hook_filters(*filters):
    """Decorator kept for compatibility: filters are not evaluated, the hook always runs."""
    def deco(fn):
        fn.hook_filters = list(filters)
        return fn
    return deco


def _overrides(obj, base, name):
    return getattr(type(obj), name, None) is not getattr(base, name, None)


class BasePlugin:

    def __init__(self):
        self.id = None
        self.name = None
        self.description = None
        self.author = None
        self.version = None
        self.path = None
        self._hooks = {}          # name -> (match_substring, priority)
        self._send_hook = None    # priority or None
        self._method_hooks = []
        self._loaded = False

    # ---- lifecycle -------------------------------------------------------------

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    # ---- hooks -----------------------------------------------------------------

    def add_hook(self, name, match_substring=False, priority=0):
        """Subscribe to a request/update by its class name, e.g. "TL_messages_readHistory"
        or "TL_updateNewMessage". Implement pre_request_hook, post_request_hook,
        on_update_hook and/or on_updates_hook to receive it."""
        self._hooks[name] = (bool(match_substring), priority)
        self._changed()

    def remove_hook(self, name):
        if self._hooks.pop(name, None) is not None:
            self._changed()

    def add_on_send_message_hook(self, priority=0):
        self._send_hook = priority
        self._changed()

    def remove_on_send_message_hook(self):
        self._send_hook = None
        self._changed()

    def pre_request_hook(self, request_name, account, request):
        return HookResult()

    def post_request_hook(self, request_name, account, response, error):
        return HookResult()

    def on_update_hook(self, update_name, account, update):
        return HookResult()

    def on_updates_hook(self, container_name, account, updates):
        return HookResult()

    def on_send_message_hook(self, account, params):
        return HookResult()

    # ---- method hooks ----------------------------------------------------------

    def hook_method(self, method, hook, priority=0):
        """Hook a java.lang.reflect.Method or Constructor (see hook_utils.get_method).
        Returns a handle for unhook_method(). Hooks are removed on unload."""
        unhook = _Bridge.hookMethod(self.id, method, hook,
                                    _overrides(hook, MethodHook, "before_hooked_method"),
                                    _overrides(hook, MethodHook, "after_hooked_method"))
        self._method_hooks.append(unhook)
        return unhook

    def hook_all_methods(self, cls, name, hook, priority=0):
        from hook_utils import find_class
        if isinstance(cls, str):
            cls = find_class(cls)
        return [self.hook_method(m, hook, priority) for m in cls.getDeclaredMethods() if m.getName() == name]

    def hook_all_constructors(self, cls, hook, priority=0):
        from hook_utils import find_class
        if isinstance(cls, str):
            cls = find_class(cls)
        return [self.hook_method(c, hook, priority) for c in cls.getDeclaredConstructors()]

    def unhook_method(self, unhook):
        try:
            unhook.unhook()
        finally:
            if unhook in self._method_hooks:
                self._method_hooks.remove(unhook)

    # ---- settings --------------------------------------------------------------

    def create_settings(self):
        """Return a list of ui.settings items (Header, Switch, Input, Selector, Text, Divider)."""
        return []

    def get_setting(self, key, default=None):
        value = _PC.getSetting(self.id, key)
        return default if value is None else value

    def set_setting(self, key, value, reload_settings=False):
        if value is None:
            _PC.removeSetting(self.id, key)
        elif isinstance(value, bool):
            _PC.putBoolean(self.id, key, value)
        elif isinstance(value, int):
            _PC.putLong(self.id, key, value)
        elif isinstance(value, float):
            _PC.putFloat(self.id, key, value)
        else:
            _PC.putString(self.id, key, str(value))

    def get_all_settings(self):
        return {str(k): v for k, v in dict(_PC.getAllSettings(self.id)).items()}

    # ---- misc ------------------------------------------------------------------

    def log(self, message):
        _PC.log(self.id, str(message))

    def _changed(self):
        if self._loaded:
            import mytg_host
            mytg_host.rebuild_hooks()

    def _report(self, where):
        _PC.reportError(self.id, where + ":\n" + traceback.format_exc())

    def _cleanup(self):
        for unhook in list(self._method_hooks):
            try:
                unhook.unhook()
            except Exception:
                pass
        self._method_hooks.clear()
        self._hooks.clear()
        self._send_hook = None
        self._loaded = False

    def _match(self, name):
        """Priority of the hook matching name, or None."""
        h = self._hooks.get(name)
        if h is not None:
            return h[1]
        for key, (sub, prio) in self._hooks.items():
            if sub and key in name:
                return prio
        return None
