# Как получить APK и установить RW AI на Samsung Galaxy

> Важно: в среде разработки не было Android SDK, поэтому APK **ещё не собирался**. Первая сборка выполняется на стороне
> GitHub (способ А) или у вас в Android Studio (способ Б). Если сборка завершится ошибкой компиляции — это ожидаемый
> риск первого запуска: пришлите мне файл лога (`rwai-build-logs`), и я исправлю код.

## Способ А. Облачная сборка в GitHub (ничего устанавливать не нужно)

1. Зарегистрируйтесь на https://github.com (бесплатно).
2. Нажмите **+ → New repository**. Имя: `rwai`. Выберите **Private** (закрытый). Нажмите **Create repository**.
3. Распакуйте архив `RWAI-project.zip` на компьютере. В репозитории нажмите **uploading an existing file** и перетащите в окно
   **содержимое** папки `RWAI` (включая скрытую папку `.github`, файл `gradlew` и папку `gradle`). Нажмите **Commit changes**.
   - Папка `.github` обязательна: в ней находится сценарий сборки. Если ваша система скрывает папки с точкой, включите
     показ скрытых файлов (Windows: Вид → Показать → Скрытые элементы; macOS: Cmd+Shift+.).
4. Откройте вкладку **Actions**. Если GitHub просит, нажмите **I understand my workflows, enable them**.
5. Слева выберите **Build APK** → **Run workflow** → **Run workflow** (сборка также запускается сама после загрузки файлов).
6. Подождите 8–15 минут (первая сборка скачивает зависимости). Зелёная галочка — успех. Красный крестик — ошибка:
   откройте запуск и скачайте артефакт **rwai-build-logs**, пришлите его мне.
7. Откройте успешный запуск, внизу в разделе **Artifacts** скачайте **rwai-apk** (zip). Внутри:
   `RWAI-0.1.0-debug.apk`, `SHA256SUMS.txt`, отчёт о тестах и журналы сборки.

## Способ Б. Android Studio

1. Установите Android Studio (https://developer.android.com/studio), откройте папку `RWAI`.
2. Дождитесь «Gradle sync». При запросе установите предложенные компоненты SDK (Android 15 / API 35).
3. Меню **Build → Build Bundle(s) / APK(s) → Build APK(s)**. Файл: `app/build/outputs/apk/debug/app-debug.apk`.
4. Через терминал: `./gradlew testDebugUnitTest assembleDebug`.

## Установка на телефон

1. Передайте `RWAI-0.1.0-debug.apk` на Samsung (скачайте на телефоне из GitHub, по кабелю или через облако). Если вы скачали
   zip — откройте его в «Мои файлы» и нажмите «Извлечь».
2. Сверьте контрольную сумму (по желанию): в `SHA256SUMS.txt` указана SHA-256 файла.
3. Нажмите на APK. Android предложит **разрешить установку из этого источника** (для «Мои файлы» или браузера) — разрешите.
4. Google Play Protect может написать «Приложение от неизвестного разработчика» — нажмите **Всё равно установить**
   (APK подписан отладочным ключом, это нормально для личной сборки).
5. Откройте **RW AI** и пройдите настройку: `docs/USER_GUIDE_RU.md`.

## Подписанная release-сборка (необязательно)

Release-APK собирается, только если вы добавили в GitHub секреты репозитория (Settings → Secrets and variables → Actions):
`RWAI_KEYSTORE_B64` (файл keystore в base64), `RWAI_KEYSTORE_PASSWORD`, `RWAI_KEY_ALIAS`, `RWAI_KEY_PASSWORD`.
Создание собственного ключа (на компьютере с JDK):

```bash
keytool -genkeypair -v -keystore rwai-release.jks -alias rwai -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 rwai-release.jks   # результат вставьте в секрет RWAI_KEYSTORE_B64
```

Храните `rwai-release.jks` и пароли только у себя; не загружайте их в репозиторий. Потерянный ключ восстановить нельзя.
