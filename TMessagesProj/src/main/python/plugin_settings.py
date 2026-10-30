"""Per-plugin settings storage (exteraGram's plugin_settings module).

Values live in the same place as BasePlugin.get_setting / set_setting."""

import threading

from java import jclass

_PC = jclass("org.telegram.messenger.plugins.PluginsController")

_lock = threading.RLock()


def get_setting(plugin_id, key, default=None):
    value = _PC.getSetting(plugin_id, key)
    return default if value is None else value


def set_setting(plugin_id, key, value):
    if value is None:
        _PC.removeSetting(plugin_id, key)
    elif isinstance(value, bool):
        _PC.putBoolean(plugin_id, key, value)
    elif isinstance(value, int):
        _PC.putLong(plugin_id, key, value)
    elif isinstance(value, float):
        _PC.putFloat(plugin_id, key, value)
    else:
        _PC.putString(plugin_id, key, str(value))


def get_all_settings(plugin_id):
    return {str(k): v for k, v in dict(_PC.getAllSettings(plugin_id)).items()}


def remove_setting(plugin_id, key):
    _PC.removeSetting(plugin_id, key)


class _PluginView(dict):
    """dict view of one plugin's settings; pop() removes the stored value."""

    def __init__(self, plugin_id):
        super().__init__(get_all_settings(plugin_id))
        self._plugin_id = plugin_id

    def pop(self, key, *default):
        if key in self:
            _PC.removeSetting(self._plugin_id, key)
        return super().pop(key, *default)

    def __setitem__(self, key, value):
        set_setting(self._plugin_id, key, value)
        super().__setitem__(key, value)

    def __delitem__(self, key):
        _PC.removeSetting(self._plugin_id, key)
        super().__delitem__(key)


class _Cache(dict):
    def __contains__(self, plugin_id):
        return True

    def __getitem__(self, plugin_id):
        return _PluginView(plugin_id)

    def get(self, plugin_id, default=None):
        return _PluginView(plugin_id)


_settings_cache = _Cache()


def _save_settings_to_file():
    """Settings are written immediately; kept for compatibility."""
