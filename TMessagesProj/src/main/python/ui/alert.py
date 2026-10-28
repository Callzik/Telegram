from java import jclass

_Bridge = jclass("org.telegram.messenger.plugins.PluginBridge")


def show_alert(title, message, ok_text=None, cancel_text=None, on_ok=None, on_cancel=None):
    """Simple dialog. on_ok/on_cancel run on the plugin queue."""
    _Bridge.showAlert(None if title is None else str(title), None if message is None else str(message),
                      ok_text, cancel_text, on_ok, on_cancel)
