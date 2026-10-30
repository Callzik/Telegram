"""Android helpers for plugins."""

import traceback

from java import dynamic_proxy, jclass

_PC = jclass("org.telegram.messenger.plugins.PluginsController")
_Bridge = jclass("org.telegram.messenger.plugins.PluginBridge")
_AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
Runnable = jclass("java.lang.Runnable")


class R(dynamic_proxy(Runnable)):
    """java.lang.Runnable that calls a Python function and logs its exceptions."""

    def __init__(self, fn, *args, **kwargs):
        super().__init__()
        self.fn, self.args, self.kwargs = fn, args, kwargs

    def run(self):
        try:
            self.fn(*self.args, **self.kwargs)
        except Exception:
            _PC.log("python", traceback.format_exc())


def run_on_ui_thread(fn, delay=0):
    """Run fn on the Android main thread. Keep it short: the UI waits for it."""
    r = R(fn)
    if delay > 0:
        _AndroidUtilities.runOnUIThread(r, int(delay))
    else:
        _AndroidUtilities.runOnUIThread(r)
    return r


def cancel_run_on_ui_thread(r):
    _AndroidUtilities.cancelRunOnUIThread(r)


def log(message):
    _PC.log("python", str(message))


def copy_to_clipboard(text):
    _Bridge.copyToClipboard(str(text))


def dp(value):
    return _AndroidUtilities.dp(float(value))


def get_activity():
    return _Bridge.getActivity()


_JOnClickListener = jclass("android.view.View$OnClickListener")


class OnClickListener(dynamic_proxy(_JOnClickListener)):
    """View.OnClickListener calling fn(view), as in exteraGram."""
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onClick(self, view):
        try:
            self.fn(view)
        except Exception:
            _PC.log("python", traceback.format_exc())


OnClick = OnClickListener


class OnLongClickListener(dynamic_proxy(jclass("android.view.View$OnLongClickListener"))):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onLongClick(self, view):
        try:
            return bool(self.fn(view))
        except Exception:
            _PC.log("python", traceback.format_exc())
            return False
