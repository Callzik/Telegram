"""Access to the Telegram client from plugins."""

import traceback

from java import dynamic_proxy, jclass

from android_utils import R, run_on_ui_thread

_PC = jclass("org.telegram.messenger.plugins.PluginsController")
_Bridge = jclass("org.telegram.messenger.plugins.PluginBridge")
_Utilities = jclass("org.telegram.messenger.Utilities")
_UserConfig = jclass("org.telegram.messenger.UserConfig")
_AccountInstance = jclass("org.telegram.messenger.AccountInstance")
_SendMessagesHelper = jclass("org.telegram.messenger.SendMessagesHelper")
_SendMessageParams = jclass("org.telegram.messenger.SendMessagesHelper$SendMessageParams")
_RequestDelegate = jclass("org.telegram.tgnet.RequestDelegate")

PLUGINS_QUEUE = "pluginsQueue"
STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
SEARCH_QUEUE = "searchQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
THEME_QUEUE = "themeQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"


def get_selected_account():
    return _UserConfig.selectedAccount


def get_account_instance(account=None):
    return _AccountInstance.getInstance(get_selected_account() if account is None else account)


def get_messages_controller(account=None):
    return get_account_instance(account).getMessagesController()


def get_messages_storage(account=None):
    return get_account_instance(account).getMessagesStorage()


def get_connections_manager(account=None):
    return get_account_instance(account).getConnectionsManager()


def get_send_messages_helper(account=None):
    return get_account_instance(account).getSendMessagesHelper()


def get_contacts_controller(account=None):
    return get_account_instance(account).getContactsController()


def get_media_data_controller(account=None):
    return get_account_instance(account).getMediaDataController()


def get_file_loader(account=None):
    return get_account_instance(account).getFileLoader()


def get_notification_center(account=None):
    return get_account_instance(account).getNotificationCenter()


def get_user_config(account=None):
    return get_account_instance(account).getUserConfig()


def get_last_fragment():
    return _Bridge.getLastFragment()


def get_queue_by_name(name):
    if name == PLUGINS_QUEUE:
        return None
    return getattr(_Utilities, name)


def run_on_queue(fn, queue_name=PLUGINS_QUEUE, delay=0):
    """Run fn on a background queue; the plugin queue by default."""
    r = R(fn)
    if queue_name == PLUGINS_QUEUE:
        _PC.runOnPluginQueue(r, int(delay))
    else:
        q = get_queue_by_name(queue_name)
        if delay > 0:
            q.postRunnable(r, int(delay))
        else:
            q.postRunnable(r)
    return r


class _Delegate(dynamic_proxy(_RequestDelegate)):
    def __init__(self, callback):
        super().__init__()
        self.callback = callback

    def run(self, response, error):
        if self.callback is not None:
            run_on_queue(lambda: self.callback(response, error))


def send_request(request, callback=None, account=None):
    """Send a TLObject. callback(response, error) runs on the plugin queue.
    Returns the request token."""
    return get_connections_manager(account).sendRequest(request, _Delegate(callback))


def send_message(params, account=None):
    """Send a message. params is a SendMessagesHelper.SendMessageParams or a dict:
    {"peer": dialog_id, "message": text, "replyToMsg": MessageObject, "replyToTopMsg": MessageObject, ...}.
    Extra dict keys are set as SendMessageParams fields."""
    if isinstance(params, dict):
        d = dict(params)
        p = _SendMessageParams.of(str(d.pop("message", "")), int(d.pop("peer")))
        for key, value in d.items():
            setattr(p, key, value)
        params = p
    helper = get_send_messages_helper(account)
    run_on_ui_thread(lambda: helper.sendMessage(params))


def send_text(peer, text, account=None):
    send_message({"peer": peer, "message": text}, account)


_NCDelegate = jclass("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate")


class NotificationCenterDelegate(dynamic_proxy(_NCDelegate)):
    """NotificationCenter observer. Override didReceivedNotification(id, account, args)
    or assign a function to it. It is called on the UI thread."""

    def __init__(self, fn=None):
        super().__init__()
        if fn is not None:
            self.didReceivedNotification = fn

    def didReceivedNotification(self, id, account, args):
        pass
