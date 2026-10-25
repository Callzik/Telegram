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
