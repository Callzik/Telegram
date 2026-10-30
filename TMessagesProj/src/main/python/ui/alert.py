from java import dynamic_proxy, jclass

_Bridge = jclass("org.telegram.messenger.plugins.PluginBridge")
_AlertDialog = jclass("org.telegram.ui.ActionBar.AlertDialog")
_Builder = jclass("org.telegram.ui.ActionBar.AlertDialog$Builder")
_OnClick = jclass("android.content.DialogInterface$OnClickListener")
_OnDismiss = jclass("android.content.DialogInterface$OnDismissListener")


def show_alert(title, message, ok_text=None, cancel_text=None, on_ok=None, on_cancel=None):
    """Simple dialog. on_ok/on_cancel run on the plugin queue."""
    _Bridge.showAlert(None if title is None else str(title), None if message is None else str(message),
                      ok_text, cancel_text, on_ok, on_cancel)


class _ClickListener(dynamic_proxy(_OnClick)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder, self.fn = builder, fn

    def onClick(self, dialog, which):
        if self.fn is not None:
            self.fn(self.builder, which)


class _DismissListener(dynamic_proxy(_OnDismiss)):
    def __init__(self, builder, fn):
        super().__init__()
        self.builder, self.fn = builder, fn

    def onDismiss(self, dialog):
        if self.fn is not None:
            self.fn(self.builder)


class AlertDialogBuilder:
    """exteraGram's wrapper of AlertDialog.Builder. Use it on the UI thread
    (android_utils.run_on_ui_thread). Button callbacks get (builder, which)."""

    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3
    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context, progress_style=0, resources_provider=None):
        self._java_builder = _Builder(context, int(progress_style), resources_provider)
        self._dialog = None
        self._listeners = []

    def _keep(self, listener):
        self._listeners.append(listener)
        return listener

    def set_title(self, title):
        self._java_builder.setTitle(str(title))
        return self

    def set_message(self, message):
        self._java_builder.setMessage(message)
        return self

    def set_view(self, view, height=-2):
        self._java_builder.setView(view)
        return self

    def set_positive_button(self, text, listener=None):
        self._java_builder.setPositiveButton(str(text), self._keep(_ClickListener(self, listener)))
        return self

    def set_negative_button(self, text, listener=None):
        self._java_builder.setNegativeButton(str(text), self._keep(_ClickListener(self, listener)))
        return self

    def set_neutral_button(self, text, listener=None):
        self._java_builder.setNeutralButton(str(text), self._keep(_ClickListener(self, listener)))
        return self

    def set_on_dismiss_listener(self, listener):
        self._java_builder.setOnDismissListener(self._keep(_DismissListener(self, listener)))
        return self

    def set_cancelable(self, cancelable):
        if self._dialog is not None:
            self._dialog.setCancelable(bool(cancelable))
        else:
            self._pending_cancelable = bool(cancelable)
        return self

    def set_canceled_on_touch_outside(self, value):
        if self._dialog is not None:
            self._dialog.setCanceledOnTouchOutside(bool(value))
        else:
            self._pending_outside = bool(value)
        return self

    def make_button_red(self, which):
        dialog = self.create()
        button = dialog.getButton(int(which))
        TextView = jclass("android.widget.TextView")
        if button is not None and isinstance(button, TextView):
            from java import cast
            Theme = jclass("org.telegram.ui.ActionBar.Theme")
            cast(TextView, button).setTextColor(Theme.getColor(Theme.key_text_RedBold))
        return self

    def create(self):
        if self._dialog is None:
            self._dialog = self._java_builder.create()
            if hasattr(self, "_pending_cancelable"):
                self._dialog.setCancelable(self._pending_cancelable)
            if hasattr(self, "_pending_outside"):
                self._dialog.setCanceledOnTouchOutside(self._pending_outside)
        return self._dialog

    def show(self):
        dialog = self.create()
        fragment = _Bridge.getLastFragment()
        if fragment is not None:
            fragment.showDialog(dialog)
        else:
            dialog.show()
        return self

    def dismiss(self):
        if self._dialog is not None:
            self._dialog.dismiss()

    def get_dialog(self):
        return self._dialog
