"""Settings items for BasePlugin.create_settings(). Values are stored per plugin and
read back with self.get_setting(key, default). Callbacks run on the plugin thread."""


class _Item:
    type = None

    def _to_json(self, plugin):
        return {"type": self.type}

    def _event(self, plugin, value):
        pass


class Header(_Item):
    type = "header"

    def __init__(self, text):
        self.text = text

    def _to_json(self, plugin):
        return {"type": self.type, "text": str(self.text)}


class Divider(_Item):
    type = "divider"

    def __init__(self, text=None):
        self.text = text

    def _to_json(self, plugin):
        return {"type": self.type, "text": None if self.text is None else str(self.text)}


class Switch(_Item):
    type = "switch"

    def __init__(self, key, text, default=False, subtext=None, icon=None, on_change=None):
        self.key, self.text, self.default = key, text, default
        self.subtext, self.icon, self.on_change = subtext, icon, on_change

    def _to_json(self, plugin):
        return {"type": self.type, "text": str(self.text), "subtext": self.subtext,
                "icon": self.icon, "value": bool(plugin.get_setting(self.key, self.default))}

    def _event(self, plugin, value):
        value = bool(value)
        plugin.set_setting(self.key, value)
        if self.on_change:
            self.on_change(value)


class Input(_Item):
    type = "input"

    def __init__(self, key, text, default="", subtext=None, icon=None, on_change=None, hint=None, multiline=False):
        self.key, self.text, self.default = key, text, default
        self.subtext, self.icon, self.on_change = subtext, icon, on_change
        self.hint, self.multiline = hint, multiline

    def _to_json(self, plugin):
        return {"type": self.type, "text": str(self.text), "subtext": self.subtext, "icon": self.icon,
                "hint": self.hint, "multiline": bool(self.multiline),
                "value": str(plugin.get_setting(self.key, self.default) or "")}

    def _event(self, plugin, value):
        value = "" if value is None else str(value)
        plugin.set_setting(self.key, value)
        if self.on_change:
            self.on_change(value)


# exteraGram has a separate EditText item; here it is an Input
EditText = Input


class Selector(_Item):
    type = "selector"

    def __init__(self, key, text, default=0, items=(), icon=None, on_change=None):
        self.key, self.text, self.default = key, text, default
        self.items, self.icon, self.on_change = list(items), icon, on_change

    def _to_json(self, plugin):
        return {"type": self.type, "text": str(self.text), "icon": self.icon,
                "items": [str(i) for i in self.items],
                "value": int(plugin.get_setting(self.key, self.default) or 0)}

    def _event(self, plugin, value):
        value = int(value)
        plugin.set_setting(self.key, value)
        if self.on_change:
            self.on_change(value)


class Text(_Item):
    type = "text"

    def __init__(self, text, icon=None, accent=False, red=False, on_click=None, subtext=None):
        self.text, self.icon, self.accent, self.red = text, icon, accent, red
        self.on_click, self.subtext = on_click, subtext

    def _to_json(self, plugin):
        return {"type": self.type, "text": str(self.text), "icon": self.icon, "subtext": self.subtext,
                "accent": bool(self.accent), "red": bool(self.red)}

    def _event(self, plugin, value):
        if self.on_click:
            try:
                self.on_click(None)
            except TypeError:
                self.on_click()
