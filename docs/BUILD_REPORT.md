# Отчёт о сборке и проверке — RW AI 0.1.0 (2026-11-07)

## Среда, в которой писался код
Linux-контейнер, JDK 21, Gradle 8.14.3, Kotlin 2.0.21 (из дистрибутива Gradle). **Нет Android SDK. Исходящий доступ к
dl.google.com, repo.maven.apache.org, services.gradle.org, plugins.gradle.org, api.deepseek.com закрыт политикой прокси** (подтверждено
журналом прокси: «gateway answered 403 to CONNECT»). Обход не применялся.

## Что выполнено
1. Создан проект Android Studio (Gradle Kotlin DSL, version catalog, Gradle Wrapper 8.14.3 сгенерирован локально, Manifest, ресурсы,
   65 файлов: исходники, тесты, документация, конфигурации).
2. Платформо-независимое ядро (`logic/`) скомпилировано компилятором Kotlin 2.0.21 и протестировано JUnit 4.13.2:

```
Time: 5.212

OK (67 tests)
```
   Воспроизведение: `scripts/verify-logic.sh /путь/к/gradle`.
3. Все XML-файлы Manifest/ресурсов проверены на корректность разметки.
4. Формат API DeepSeek сверен с официальной документацией (api-docs.deepseek.com, 2026-11-07): endpoint `/chat/completions`,
   модели `deepseek-flash` и `deepseek-v4-pro`, параметр `thinking.type`, Bearer-авторизация. Сам API из среды разработки не вызывался.

## Что НЕ выполнено
| Что | Почему | Как закрыть |
|---|---|---|
| Компиляция Android-кода (ui, executor, accessibility, notifications, brain, core, data, security, settings, voice) | Нет Android SDK/AGP/Compose из Maven | GitHub Actions (`docs/BUILD_AND_INSTALL_RU.md`) |
| Сборка Debug/Release APK | то же | то же; release — после создания вашего keystore |
| Инструментальные тесты | нет эмулятора/телефона | `connectedDebugAndroidTest` или ручной чек-лист |
| Проверка на физическом устройстве / DeepSeek API | нет устройства, нет доступа | чек-лист `docs/TEST_PLAN.md` |
| SHA-256 APK | APK не собирался | создаётся автоматически в CI (`SHA256SUMS.txt` в артефакте) |

## Известные риски первой сборки
Код Android-слоя написан без компилятора: вероятны мелкие ошибки компиляции (импорты, сигнатуры API) и несовместимость версий
библиотек. Это нормальный риск первой сборки; CI сохраняет полный журнал (`rwai-build-logs`), по нему ошибки исправляются быстро.
Версии: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, OkHttp 4.12.0, compile/targetSdk 35, minSdk 26.
