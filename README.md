# RW AI — персональный ИИ-агент для Android

Нативное приложение на Kotlin + Jetpack Compose. Принимает текстовые и голосовые команды, строит план с помощью DeepSeek
и выполняет его через Accessibility Service, чтение уведомлений и системные Intents; ведёт журнал, просит подтверждение
на опасные действия и не доверяет содержимому писем и сообщений.

**Версия 0.1.0 — первый рабочий этап (ядро + управление интерфейсом + уведомления + память + голос).**

## Статус (честно)

| Область | Состояние |
|---|---|
| Ядро агента: цикл «решение → политика → подтверждение → инструмент → проверка», разбор ответов модели, защита от prompt injection, матрица подтверждений | Реализовано. **Скомпилировано и протестировано** (67 JUnit-тестов, см. `docs/TEST_PLAN.md`) |
| Клиент DeepSeek, хранение ключа (Android Keystore), настройки, SQLite+FTS память, служба уведомлений, Accessibility, исполнитель инструментов, голос, интерфейс Compose | Реализовано в коде. **НЕ скомпилировано и НЕ проверено на устройстве** — в среде разработки не было Android SDK и доступа к Maven |
| APK | **Не собран в среде разработки.** Собирается автоматически в GitHub Actions (`docs/BUILD_AND_INSTALL_RU.md`) |
| Проверка на физическом Samsung Galaxy | Не выполнена (нужна вам, чек-лист в `docs/TEST_PLAN.md`) |

Список того, чего ещё нет, — в [TODO.md](TODO.md) и [docs/LIMITATIONS.md](docs/LIMITATIONS.md).

## Как получить APK

См. **[docs/BUILD_AND_INSTALL_RU.md](docs/BUILD_AND_INSTALL_RU.md)** — пошагово: через GitHub Actions (без установки программ)
или через Android Studio.

## Структура

```
app/src/main/java/com/rwai/agent/
  logic/          чистый Kotlin без Android: Json, каталог инструментов, ActionParser, ConfirmationPolicy,
                  UntrustedContent (защита от инъекций), AgentLoop, UiMatcher  ← тестируется на обычной JVM
  brain/          DeepSeekClient (OkHttp)
  core/           AgentController, AgentService (foreground), уведомления подтверждений
  executor/       AndroidToolExecutor — реальные инструменты поверх Accessibility/Intents/БД
  accessibility/  RwAccessibilityService
  notifications/  RwNotificationListener
  voice/          распознавание речи и озвучивание
  data/ security/ settings/   SQLite+FTS4, Android Keystore, настройки
  ui/             Jetpack Compose: Ассистент, Входящие, Память, Журнал, Доступ, Настройки
docs/             архитектура, разрешения, безопасность, тесты, ограничения, руководство пользователя
scripts/          verify-logic.sh (локальная проверка ядра без SDK), build-debug.sh, verify-project.sh
.github/workflows/build-apk.yml   облачная сборка APK
```

## Проверка ядра без Android SDK

```bash
scripts/verify-logic.sh /путь/к/gradle   # компилирует logic/ и тесты, запускает JUnit
```

## Безопасность в двух словах

API-ключ шифруется ключом из Android Keystore и вводится только в приложении; всё содержимое экрана/уведомлений/писем
передаётся модели в помеченных блоках «недоверенные данные»; отправка, оплата, удаление требуют подтверждения; CAPTCHA,
биометрия, PIN и экран блокировки не обходятся. Подробнее — `docs/SECURITY.md`.
