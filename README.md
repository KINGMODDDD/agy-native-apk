# AGY Native (`com.agy.nativeapp`)

Нативное Android-приложение (Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 34`) — прямая графическая оболочка для консольного агента `agy` **без локальных HTTP-серверов, без портов, без Python и без `localhost`**.

## Принцип работы

1. Пользователь выбирает рабочую папку на смартфоне через Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE` с автоматическим преобразованием URI в путь `/storage/emulated/0/...`) или создаёт новую папку в `/storage/emulated/0/AGYProjects/...`.
2. При нажатии **«Отправить»**:
   - Текст задачи сохраняется в `.agy_prompt.txt` внутри выбранной папки (полностью исключает ошибки экранирования shell).
   - Приложение отправляет разовую фоновую команду в Termux через `com.termux.RUN_COMMAND` (`RUN_COMMAND_PATH = "/data/data/com.termux/files/usr/bin/bash"`, `RUN_COMMAND_BACKGROUND = true`), которая переходит в рабочую папку и запускает:
     ```bash
     agy -p "Read instructions from .agy_prompt.txt in current directory and execute all tasks completely." --dangerously-skip-permissions > .agy_out.log 2>&1
     ```
     с записью кода выхода в файл-маркер `.agy_done`.
   - Пока `agy` работает, приложение каждые **500 мс** читает `.agy_out.log` и обновляет живой терминальный лог и карточки создаваемых файлов в чате.
3. По кнопке **«Стоп»** отправляется разовая команда `pkill -9 -f "agy"`, мгновенно останавливающая выполнение.

## Возможности

- **Тёмный минималистичный интерфейс**: выбор папки в шапке, лента сообщений со сворачиваемым терминальным логом `.agy_out.log` и карточками созданных файлов.
- **Встроенный Preview (`WebView`)**: при наличии `index.html` в папке появляется кнопка **«Запустить сайт (Preview)»**, открывающая `file:///.../index.html` напрямую без сервера.
- **Проводник файлов и экспорт в `.zip`**: просмотр структуры файлов, встроенный просмотр текстовых/кодовых файлов и упаковка проекта в `.zip` через `FileProvider` + копирование в `Download`.

## Подготовка Termux на устройстве

1. В `~/.termux/termux.properties` добавьте:
   ```properties
   allow-external-apps = true
   ```
   и выполните:
   ```bash
   termux-reload-settings
   termux-setup-storage
   ```
2. Выдайте приложению **AGY Native** разрешения `com.termux.permission.RUN_COMMAND` и доступ ко всем файлам (`MANAGE_EXTERNAL_STORAGE`) в разделе настроек приложения.

## Сборка APK и GitHub Actions

- В репозитории настроен workflow [build_apk.yml](.github/workflows/build_apk.yml), который запускает `gradle testDebugUnitTest` и `gradle assembleDebug` и публикует подписанный debug-ключом артефакт `AGY-Native-debug.apk`.
- Локальная сборка на ПК с Android SDK и JDK 17:
  ```bash
  gradle assembleDebug
  gradle testDebugUnitTest
  ```
