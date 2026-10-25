# Плагины MyTelegram

Плагин — это один файл `.plugin` с кодом на Python 3.13 (Chaquopy). API повторяет exteraGram, поэтому простые плагины exteraGram работают без правок. Плагины, которые используют классы самого exteraGram (`com.exteragram.*`) или его модули `markdown_utils`, `ui.alert.AlertDialogBuilder` и подобные, придётся адаптировать.

Установка: Настройки → Плагины → «Установить из файла», или открыть файл `.plugin` в любом чате.

## Как это работает

- Python запускается только если включён хотя бы один плагин, и в фоне.
- Весь код плагина (загрузка, хуки, настройки) выполняется в одном фоновом потоке `pluginsQueue`, не в UI-потоке.
- Клиент отдаёт плагину только то, на что он подписался (`add_hook`, `add_on_send_message_hook`). Остальные запросы и апдейты идут мимо Python.
- Если хук не ответил за 5 секунд, данные проходят без изменений, а хуки приостанавливаются, пока плагин не вернётся.
- Ошибки плагина не роняют приложение: они пишутся в журнал плагина (удерживайте плагин → «Журнал»).
- Если приложение 3 раза подряд упало при запуске, плагины выключаются сами.

## Минимальный плагин

```python
from base_plugin import BasePlugin, HookResult, HookStrategy

__id__ = "hello"            # обязательно: латиница, цифры, _ - .
__name__ = "Hello"
__description__ = "Заменяет .hi на приветствие"
__author__ = "me"
__version__ = "1.0"

class HelloPlugin(BasePlugin):
    def on_plugin_load(self):
        self.add_on_send_message_hook()

    def on_send_message_hook(self, account, params):
        if params.message == ".hi":
            params.message = "Привет!"
            return HookResult(strategy=HookStrategy.MODIFY, params=params)
        return HookResult()
```

## BasePlugin

| Метод | Что делает |
|---|---|
| `on_plugin_load()` / `on_plugin_unload()` | включение и выключение плагина |
| `add_hook(name, match_substring=False, priority=0)` | подписка на запрос или апдейт по имени класса: `TL_messages_readHistory`, `TL_updateNewMessage`, `TL_updates` |
| `add_on_send_message_hook(priority=0)` | перехват отправки сообщений |
| `pre_request_hook(name, account, request)` | до отправки запроса; можно заменить (`request=`) или отменить |
| `post_request_hook(name, account, response, error)` | после ответа; можно заменить `response`/`error` |
| `on_update_hook(name, account, update)` | каждый апдейт внутри контейнера; `CANCEL` выкидывает его |
| `on_updates_hook(name, account, updates)` | контейнер апдейтов целиком |
| `on_send_message_hook(account, params)` | `params` — `SendMessagesHelper.SendMessageParams` |
| `hook_method(method, MethodHook())` | хук Java-метода (Pine), снимается при выгрузке |
| `hook_all_methods(cls, name, hook)`, `hook_all_constructors(cls, hook)` | то же для всех перегрузок |
| `create_settings()` | список элементов настроек |
| `get_setting(key, default)`, `set_setting(key, value)` | хранилище плагина |
| `log(text)` | журнал плагина |

`HookStrategy`: `DEFAULT` — ничего не менять, `CANCEL` — отменить, `MODIFY` — заменить и передать дальше другим плагинам, `MODIFY_FINAL` — заменить и не звать остальных.

## Модули

- `ui.settings`: `Header(text)`, `Switch(key, text, default, subtext, icon, on_change)`, `Input(key, text, default, subtext, icon, on_change, hint, multiline)`, `Selector(key, text, default, items, icon, on_change)`, `Text(text, icon, accent, red, on_click)`, `Divider(text)`.
- `ui.bulletin.BulletinHelper`: `show_info`, `show_error`, `show_success`.
- `ui.alert.show_alert(title, message, ok_text, cancel_text, on_ok, on_cancel)`.
- `android_utils`: `run_on_ui_thread(fn, delay)`, `log`, `copy_to_clipboard`, `dp`, `R(fn)` (Runnable), `OnClick(fn)`.
- `client_utils`: `send_message({...})`, `send_text(peer, text)`, `send_request(req, callback)`, `run_on_queue(fn, queue)`, `get_messages_controller()`, `get_connections_manager()`, `get_last_fragment()` и другие `get_*`.
- `hook_utils`: `find_class(name)`, `get_method(cls, name, *types)`, `get_constructor`, `get_private_field`, `set_private_field`, `get_static_private_field`, `set_static_private_field`.
- Любые Java-классы: `from java import jclass`.

## Хук метода

```python
from base_plugin import BasePlugin, MethodHook
from hook_utils import get_method

class Hook(MethodHook):
    def after_hooked_method(self, param):
        # param.thisObject, param.args, param.getResult(), param.setResult(x)
        pass

class P(BasePlugin):
    def on_plugin_load(self):
        self.hook_method(get_method("org.telegram.ui.ChatActivity", "onResume"), Hook())
```

Хуки методов выполняются в том потоке, где вызван метод (часто это UI), поэтому они должны быть быстрыми. Сильно инлайненные R8 методы могут не перехватываться.

Примеры: [commands.plugin](commands.plugin), [message_log.plugin](message_log.plugin).
