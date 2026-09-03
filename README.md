# Charles Tunnel

Минимальная утилита для Android 8.0+, которая направляет трафик устройства в Charles через локальный VPN и SOCKS5. Основана на [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel); готовые нативные библиотеки и их происхождение описаны в [NATIVE_BINARIES.md](NATIVE_BINARIES.md).

## Локальная сборка

Подключите разблокированное устройство с включённым USB debugging и запустите SOCKS5-прокси в Charles на порту `8889`.

```bash
git clone https://github.com/griigoriiy/chunnel.git
cd chunnel
./build.sh
./install.sh
./enable.sh
```

При первом запуске подтвердите системный VPN-диалог на устройстве. `enable.sh` сам настроит `adb reverse`, выдаст разрешение на уведомления и закроет Activity после запуска туннеля.

Остановить туннель:

```bash
./disable.sh
```

## Только APK

Репозиторий скачивать не нужно.

1. Скачать APK в `Downloads`, установить и выдать разрешение на уведомления:

```bash
mkdir -p "$HOME/Downloads"
curl -fL https://github.com/griigoriiy/chunnel/releases/latest/download/charles-tunnel.apk -o "$HOME/Downloads/charles-tunnel.apk"
adb install -r "$HOME/Downloads/charles-tunnel.apk"
adb shell pm grant com.mobileapp.charlestunnel android.permission.POST_NOTIFICATIONS
```

2. Настроить проброс и запустить туннель:

```bash
adb reverse tcp:8889 tcp:8889
adb shell content call --uri content://com.mobileapp.charlestunnel.control --method start --arg 127.0.0.1:8889 >/dev/null
adb shell am start -n com.mobileapp.charlestunnel/.MainActivity >/dev/null
```

3. Остановить туннель и удалить проброс:

```bash
adb shell content call --uri content://com.mobileapp.charlestunnel.control --method stop >/dev/null
adb reverse --remove tcp:8889
```

Те же блоки как aliases для `~/.zshrc` или `~/.bashrc`:

```bash
alias chunnel-install='mkdir -p "$HOME/Downloads" && curl -fL https://github.com/griigoriiy/chunnel/releases/latest/download/charles-tunnel.apk -o "$HOME/Downloads/charles-tunnel.apk" && adb install -r "$HOME/Downloads/charles-tunnel.apk" && (adb shell pm grant com.mobileapp.charlestunnel android.permission.POST_NOTIFICATIONS 2>/dev/null || true)'
alias chunnel-on='adb reverse tcp:8889 tcp:8889 && adb shell content call --uri content://com.mobileapp.charlestunnel.control --method start --arg 127.0.0.1:8889 >/dev/null && adb shell am start -n com.mobileapp.charlestunnel/.MainActivity >/dev/null'
alias chunnel-off='adb shell content call --uri content://com.mobileapp.charlestunnel.control --method stop >/dev/null; adb reverse --remove tcp:8889'
```

<details>
<summary>Ручная установка</summary>

Для сборки нужны JDK 17+ и Android SDK с компонентами:

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Если SDK не найден автоматически, задайте `ANDROID_HOME` или создайте `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Собрать APK без скрипта:

```bash
gradle :app:assembleDistribution
```

APK появится в `build/dist/charles-tunnel.apk`. Установка и настройка для Android 13+:

```bash
adb install -r build/dist/charles-tunnel.apk
adb shell pm grant com.mobileapp.charlestunnel android.permission.POST_NOTIFICATIONS
adb reverse tcp:8889 tcp:8889
adb shell am start -n com.mobileapp.charlestunnel/.MainActivity
```

В приложении оставьте endpoint `127.0.0.1:8889` и нажмите «Запустить».

Готовый APK также опубликован в [GitHub Releases](https://github.com/griigoriiy/chunnel/releases/latest).

</details>

<details>
<summary>Если что-то пошло не так</summary>

Если подключено несколько устройств, укажите serial:

```bash
./install.sh -s DEVICE_SERIAL
./enable.sh -s DEVICE_SERIAL
./disable.sh -s DEVICE_SERIAL
```

Если SOCKS5 в Charles работает не на `8889`:

```bash
./enable.sh --host-port 9999
```

Если SOCKS5 доступен напрямую по сети, `adb reverse` не нужен:

```bash
./enable.sh --endpoint 192.168.1.10:8889 --no-reverse
```

Проверить состояние и счётчики трафика:

```bash
adb shell content call \
  --uri content://com.mobileapp.charlestunnel.control \
  --method status
```

- Если `enable.sh` ждёт запуска, разблокируйте устройство и подтвердите VPN-диалог.
- Если Charles не видит HTTPS, установите его сертификат и убедитесь, что тестовая сборка приложения ему доверяет. Certificate pinning нужно отключать отдельно.
- QUIC/HTTP3 не отображается в Charles как обычный HTTP-трафик. Для анализа используйте TCP/HTTP2.
- Android разрешает только один активный VPN.
- При `INSTALL_FAILED_UPDATE_INCOMPATIBLE` удалите старую версию: `adb uninstall com.mobileapp.charlestunnel`.

Скрипты печатают подробный ответ ADB только при ошибке и завершаются с ненулевым exit code.

</details>
