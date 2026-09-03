#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/common.sh"

parse_device_args "$@"
set -- "${REMAINING_ARGS[@]}"
shift
if (( $# > 0 )); then
    die "Неизвестный аргумент: $1" "Использование: ./install.sh [-s SERIAL]"
fi

if [[ ! -f "$APK_PATH" ]]; then
    die \
        "APK не найден: $APK_PATH" \
        "Сначала соберите его командой: ./build.sh"
fi

select_device

local_sdk=""
if local_sdk="$(adb_exec shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')" &&
    [[ "$local_sdk" =~ ^[0-9]+$ ]] && (( local_sdk < 26 )); then
    die \
        "На устройстве Android API $local_sdk, а Charles Tunnel требует API 26+." \
        "Используйте устройство с Android 8.0 или новее."
fi

info "Устанавливаю $APK_PATH..."
install_output=""
if ! install_output="$(adb_exec install -r "$APK_PATH" 2>&1)"; then
    printf '%s\n' "$install_output" >&2
    error "ADB не смог установить Charles Tunnel."
    case "$install_output" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
            recommend "На устройстве установлена версия с другой подписью. Удалите её командой: adb -s $DEVICE_SERIAL uninstall $PACKAGE_NAME"
            recommend "Удаление очистит настройки приложения. Затем снова выполните ./install.sh -s $DEVICE_SERIAL"
            ;;
        *INSTALL_FAILED_VERSION_DOWNGRADE*)
            recommend "Устанавливается более старая версия. При допустимом downgrade используйте: adb -s $DEVICE_SERIAL install -r -d $APK_PATH"
            ;;
        *INSTALL_FAILED_OLDER_SDK*)
            recommend "Charles Tunnel требует Android 8.0 (API 26) или новее."
            ;;
        *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
            recommend "Освободите место на устройстве и повторите установку."
            ;;
        *)
            recommend "Проверьте USB debugging, свободное место и состояние устройства командой adb devices."
            ;;
    esac
    exit 1
fi

printf '%s\n' "$install_output"
grant_notification_permission

info "Charles Tunnel установлен на $DEVICE_SERIAL."
info "Для запуска туннеля выполните: ./enable.sh -s $DEVICE_SERIAL"
