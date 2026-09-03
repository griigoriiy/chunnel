# Charles Tunnel

Минимальная Android-утилита для тестировщиков. Она создаёт системный VPN-интерфейс и передаёт TCP/UDP-трафик устройства в заданный SOCKS5-прокси через встроенный `hev-socks5-tunnel`.

- package: `com.mobileapp.charlestunnel`
- Android: 8.0+ (`minSdk 26`)
- ABI: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` в одном APK
- итоговый файл: `build/dist/charles-tunnel.apk`
- Android Studio и Android NDK не требуются

## Сборка из терминала

Нужны JDK 17 или новее и Android SDK command-line tools. Установите закреплённые компоненты SDK:

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Если SDK не найден автоматически, задайте `ANDROID_HOME` или создайте локальный, не коммитящийся файл `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Проверьте окружение и соберите минифицированный APK, подписанный debug-ключом:

```bash
gradle doctor
gradle :app:assembleDistribution
```

Основной `./build.sh` сначала использует совместимый системный Gradle 9.5+, если он доступен, поэтому уже установленная версия не скачивается повторно. Если системного Gradle нет или он несовместим, скрипт переключается на Gradle Wrapper. Выбор можно задать явно через `CHUNNEL_GRADLE=system` или `CHUNNEL_GRADLE=wrapper`.

Результат: `build/dist/charles-tunnel.apk`. Установка:

```bash
adb install -r build/dist/charles-tunnel.apk
```

## Готовые скрипты

Для обычного сценария достаточно четырёх команд из корня проекта:

```bash
./build.sh
./install.sh
./enable.sh
./disable.sh
```

`enable.sh` по умолчанию:

1. находит единственное подключённое устройство;
2. проверяет, что Charles Tunnel установлен;
3. выполняет `adb reverse tcp:8889 tcp:8889`;
4. передаёт приложению `endpoint=127.0.0.1:8889`;
5. открывает Activity и ждёт состояния `running`;
6. после успешного запуска Activity закрывается автоматически.

`install.sh` и `enable.sh` автоматически разрешают приложению показывать уведомление foreground service на Android 13+. Поэтому разрешение будет восстановлено и после его ручного отзыва. При запуске без скриптов приложение запросит это разрешение при открытии. При первом запуске туннеля также подтвердите отдельный системный VPN-диалог на устройстве. Если подключено несколько устройств, передавайте serial:

```bash
./install.sh -s DEVICE_SERIAL
./enable.sh -s DEVICE_SERIAL
./disable.sh -s DEVICE_SERIAL
```

Если порт Chunnel на устройстве и SOCKS-порт Charles на компьютере различаются:

```bash
./enable.sh --endpoint 127.0.0.1:9999 --host-port 8889
```

Получится `adb reverse tcp:9999 tcp:8889`, а приложение получит endpoint `127.0.0.1:9999`. Для SOCKS5-прокси, доступного напрямую по LAN, reverse не нужен:

```bash
./enable.sh --endpoint 192.168.1.10:8889 --no-reverse
```

Все скрипты печатают причину ошибки и следующие шаги, а при неуспехе завершаются с ненулевым exit code. Можно также использовать `ANDROID_SERIAL`, `CHUNNEL_ENDPOINT`, `CHARLES_PORT`, `CHUNNEL_START_TIMEOUT` и `CHUNNEL_GRADLE`.

## Обычный запуск

Откройте Charles Tunnel на устройстве и разрешите уведомления, чтобы Android показывал постоянный статус работающего VPN. Затем укажите SOCKS5 endpoint в формате `host:port` и нажмите «Запустить». Для IPv6 используйте `[address]:port`. При первом запуске Android покажет отдельный системный диалог разрешения VPN.

Для Charles на компьютере через USB при SOCKS5-порте `8889`:

```bash
adb reverse tcp:8889 tcp:8889
```

После этого в приложении можно оставить `127.0.0.1:8889`. Если прокси доступен по сети, укажите его LAN/IP или hostname и не используйте `adb reverse`.

## Управление из агента или скрипта через ADB

API реализован экспортированным `ContentProvider`, но первым действием каждой команды проверяется Binder UID. Доступ разрешён только `adb shell` (UID 2000) и root (UID 0); обычное Android-приложение вызвать API не может.

Подготовить запуск с произвольным адресом:

```bash
adb shell content call \
  --uri content://com.mobileapp.charlestunnel.control \
  --method start \
  --arg 127.0.0.1:8889

adb shell am start -W \
  -n com.mobileapp.charlestunnel/.MainActivity
```

Endpoint передаётся через аргумент вызова provider, чтобы двоеточие в `host:port` не конфликтовало с синтаксисом `--extra key:type:value`. Первая команда сохраняет одноразовое задание на 30 секунд. Вторая открывает обычную Activity, забирает задание и при необходимости показывает системный VPN consent. Provider намеренно не запускает foreground service из фона: на разных версиях Android и OEM-прошивках это ненадёжно.

Получить состояние и счётчики трафика:

```bash
adb shell content call \
  --uri content://com.mobileapp.charlestunnel.control \
  --method status
```

Ответ содержит `state`, `endpoint`, `vpn_permission_granted`, `native_running`, `pending_command`, `error`, `tx_packets`, `tx_bytes`, `rx_packets`, `rx_bytes`. Поля `socks_host` и `socks_port` также возвращаются для обратной совместимости; старый формат входных параметров пока поддерживается.

Остановить:

```bash
adb shell content call \
  --uri content://com.mobileapp.charlestunnel.control \
  --method stop
```

Команда остановки передаётся работающему VPN-сервису напрямую. Если Android не разрешит такой вызов из фона, provider вернёт `code=pending_user_action`; тогда откройте Activity командой `adb shell am start -W -n com.mobileapp.charlestunnel/.MainActivity`. Скрипт `disable.sh` обрабатывает этот fallback автоматически.

Для нескольких устройств добавляйте `adb -s SERIAL` ко всем командам. Для secondary user добавляйте `--user USER_ID` после `content call` и `am start`; APK должен быть установлен для этого пользователя.

## Ограничения перехвата

- Для расшифровки HTTPS сертификат Charles должен быть установлен и доверен тестируемым приложением.
- Certificate pinning требует тестовой сборки приложения с отключённым pinning или отдельного механизма обхода.
- QUIC/HTTP3 и UDP-сценарии могут не отображаться в Charles как обычные HTTP-запросы; для предсказуемого анализа используйте TCP/HTTP2.
- Android одновременно разрешает только один активный VPN. Запуск другой VPN остановит этот туннель.
- VPN намеренно исключает из маршрутизации само приложение Charles Tunnel, чтобы избежать петли.

## Устройство проекта

Kotlin-часть содержит только platform Android API, без AndroidX и UI-фреймворков. Конфиг HEV строится только из валидированных host/port и записывается во внутренний cache приложения; пользовательские конфигурационные файлы и произвольные параметры не принимаются.

Нативный код при сборке приложения не компилируется. Готовые `libhev-socks5-tunnel.so` версии 2.17.1 лежат в `app/src/main/jniLibs/<abi>/` и попадают в один universal APK. Они один раз собраны из закреплённого официального исходника [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.17.1); для обычной сборки Chunnel Android NDK не нужен. Точные commit, команда сборки и SHA-256 каждого файла перечислены в [NATIVE_BINARIES.md](NATIVE_BINARIES.md).
