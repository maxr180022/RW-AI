# Архитектура RW AI 0.1.0

## Слои

```
UI (Compose) ──► AgentController (core) ──► AgentLoop (logic)  ──► ModelClient  = DeepSeekClient (brain)
                      │                          │                ──► ToolExecutor = AndroidToolExecutor (executor)
                      │                          │                ──► Confirmer    = UiConfirmer (диалог + уведомление)
                      │                          └─► ConfirmationPolicy / UntrustedContent / ActionParser (logic)
                      └─► AgentService (foreground, пока идёт задача)
Android-интеграции: RwAccessibilityService, RwNotificationListener, VoiceInput/Speaker, RwDb (SQLite+FTS4), SecretStore (Keystore)
```

Пакет `logic/` не импортирует ничего из Android и компилируется/тестируется на обычной JVM. Всё остальное зависит от Android SDK.

## Цикл агента (`logic/AgentLoop.kt`)

1. Владелец даёт команду → `AgentController.submit` → запускает foreground-службу и `AgentLoop.run`.
2. Модель получает системный промпт (роль, JSON-протокол, каталог инструментов, правила безопасности) и историю.
3. Ответ модели разбирается `ActionParser`: допустим ровно один из видов `action` / `finish` / `ask`. Всё остальное — «ошибка
   формата» (модели возвращается замечание; после 3 подряд задача завершается ошибкой).
4. `action` проверяется по `ToolCatalog` (имя, обязательные/лишние аргументы, типы, enum, диапазоны, длины).
5. `ConfirmationPolicy` решает: **Allow / Confirm / Deny** — по тексту нажимаемого элемента, а не по заявлению модели.
6. `Confirm` → карточка в приложении + уведомление; без ответа 5 минут = отказ. Отказ немедленно останавливает задачу.
7. `ToolExecutor.execute` с тайм-аутом из каталога; исключения превращаются в результат `FAILED`.
8. Результат (для данных из внешнего мира — в блоке `<untrusted_data>`) возвращается модели. После подтверждённого
   «отправляющего» действия агент не может завершиться, пока не выполнит `inspect_screen`/`find_text` (проверка результата).
9. Защиты: лимит шагов (30), обнаружение повторов, обрезка истории по бюджету символов, журнал каждого шага (с маскированием секретов).

DeepSeek вызывается без нативных tool calls: единый JSON-протокол в тексте работает одинаково для разных моделей и режимов
thinking (в документации DeepSeek часть параметров в режиме thinking ограничена). Клиент подставляет `thinking.type`
из настроек, `temperature` — только вне thinking.

## Инструменты (закрытый список)

`launch_app`, `open_url`, `inspect_screen`, `find_text`, `tap_text`, `scroll`, `input_text`, `press_back`, `wait`,
`read_notifications`, `search_memory`, `save_excerpt`, `show_draft`, `speak`. Нет shell, нет произвольного кода, нет
нажатий по координатам. Каждый инструмент: типизированные аргументы, тайм-аут, код состояния, запись в журнал.

## Поиск элементов и защита от подмены

`tap_text` ищет элемент по подписи (`UiMatcher`). Исполнитель дополнительно сверяет класс риска **найденного** элемента с
классом запроса (`LabelClassifier.isEscalation`): если под безобидным запросом найдена кнопка «Оплатить»/«Отправить»/«Я не робот»,
нажатие отменяется.

## Данные

- `RwDb` (SQLiteOpenHelper): `activity`, `notifications`, `memory` + `memory_fts` (FTS4, токенизатор unicode61).
- Поиск: слова + грубое отбрасывание русских окончаний (`FtsQuery`). Это **не** семантический/векторный поиск.
- Настройки: SharedPreferences; ключ API: AES-256-GCM, ключ в Android Keystore (`SecretStore`).
- Отклонение от рекомендованного стека ТЗ: вместо Room/DataStore использованы SQLiteOpenHelper/SharedPreferences, чтобы
  уменьшить число зависимостей и риск несовместимости версий при первой сборке без доступа к репозиториям.

## Процессы и службы

- `AgentService` — foreground (`specialUse`), живёт только пока идёт задача; уведомление с кнопкой «Стоп».
- `RwAccessibilityService`, `RwNotificationListener` — запускаются системой после включения пользователем.
- Подтверждения из уведомления: `AgentActionReceiver` (подтверждение обычной отправки — только после разблокировки, Android 12+;
  опасные действия подтверждаются только в приложении).

## Версии

AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.3 (wrapper), Compose BOM 2024.12.01, compileSdk/targetSdk 35, minSdk 26 (Android 8.0+),
JDK 17. Версии подобраны как взаимно совместимые, но **не проверялись сборкой** в среде разработки.
