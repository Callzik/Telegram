from java import jclass

_Bridge = jclass("org.telegram.messenger.plugins.PluginBridge")


class BulletinHelper:
    @staticmethod
    def show_info(text, fragment=None):
        _Bridge.showBulletin(_Bridge.BULLETIN_INFO, str(text))

    @staticmethod
    def show_error(text, fragment=None):
        _Bridge.showBulletin(_Bridge.BULLETIN_ERROR, str(text))

    @staticmethod
    def show_success(text, fragment=None):
        _Bridge.showBulletin(_Bridge.BULLETIN_SUCCESS, str(text))

    @staticmethod
    def show_copied_to_clipboard(text=None, fragment=None):
        _Bridge.showBulletin(_Bridge.BULLETIN_SUCCESS, str(text) if text else "Copied")

    @staticmethod
    def show_simple(text, icon_res_id=0, fragment=None):
        """Bulletin with a raw (lottie) icon, e.g. R.raw.error."""
        _Bridge.showBulletinRaw(str(text), int(icon_res_id or 0), None, None)

    @staticmethod
    def show_with_button(text, icon_res_id, button_text, on_click=None, fragment=None):
        """on_click() runs on the plugin queue."""
        _Bridge.showBulletinRaw(str(text), int(icon_res_id or 0), str(button_text), on_click)
