#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/common.sh"

usage() {
    printf '%s\n' \
        'Использование:' \
        '  ./enable.sh [-s SERIAL] [--endpoint HOST:PORT] [--host-port PORT] [--no-reverse]' \
        '' \
        'Примеры:' \
        '  ./enable.sh' \
        '  ./enable.sh -s DEVICE_SERIAL --endpoint 127.0.0.1:9999 --host-port 8889' \
        '  ./enable.sh --endpoint 192.168.1.10:8889 --no-reverse'
}

parse_device_args "$@"
set -- "${REMAINING_ARGS[@]}"
shift

endpoint="${CHUNNEL_ENDPOINT:-$DEFAULT_ENDPOINT}"
host_port="${CHARLES_PORT:-}"
no_reverse=0
endpoint_set=0

while (( $# > 0 )); do
    case "$1" in
        --endpoint)
            (( $# >= 2 )) || die "После --endpoint требуется HOST:PORT." "Пример: --endpoint 127.0.0.1:8889"
            endpoint="$2"
            endpoint_set=1
            shift 2
            ;;
        --host-port)
            (( $# >= 2 )) || die "После --host-port требуется порт Charles на компьютере." "Пример: --host-port 8889"
            host_port="$2"
            shift 2
            ;;
        --no-reverse)
            no_reverse=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            die "Неизвестный аргумент: $1" "Запустите ./enable.sh --help"
            ;;
        *)
            if (( endpoint_set == 1 )); then
                die "Лишний аргумент: $1" "Запустите ./enable.sh --help"
            fi
            endpoint="$1"
            endpoint_set=1
            shift
            ;;
    esac
done

parse_endpoint "$endpoint"
if [[ -z "$host_port" ]]; then
    host_port="$ENDPOINT_PORT"
fi
validate_port "$host_port"

start_timeout="${CHUNNEL_START_TIMEOUT:-30}"
if [[ ! "$start_timeout" =~ ^[0-9]+$ ]] || (( start_timeout < 1 )); then
    die "Некорректный CHUNNEL_START_TIMEOUT: $start_timeout" "Укажите количество секунд, например CHUNNEL_START_TIMEOUT=60."
fi

select_device
ensure_package_installed
grant_notification_permission

if is_loopback_host "$ENDPOINT_HOST" && (( no_reverse == 0 )); then
    if command -v nc >/dev/null 2>&1 && ! nc -z 127.0.0.1 "$host_port" >/dev/null 2>&1; then
        warn "На компьютере никто не принимает TCP-соединения на 127.0.0.1:$host_port."
        recommend "Запустите Charles и включите его SOCKS Proxy на порту $host_port. Туннель запускается, но без прокси интернет на устройстве работать не будет."
    fi

    reverse_output=""
    if ! reverse_output="$(adb_exec reverse "tcp:$ENDPOINT_PORT" "tcp:$host_port" 2>&1)"; then
        printf '%s\n' "$reverse_output" >&2
        die \
            "Не удалось настроить adb reverse." \
            "Проверьте подключение командой: adb -s $DEVICE_SERIAL devices" \
            "Убедитесь, что порт устройства $ENDPOINT_PORT не занят другой reverse-настройкой."
    fi
fi

start_output=""
if ! start_output="$(adb_exec shell content call \
    --uri "$CONTROL_URI" \
    --method start \
    --arg "$endpoint" 2>&1)"; then
    printf '%s\n' "$start_output" >&2
    die \
        "Не удалось вызвать ControlProvider." \
        "Переустановите APK командой: ./install.sh -s $DEVICE_SERIAL" \
        "На некоторых OEM-прошивках shell-вызов provider может быть ограничен; тогда откройте приложение и запустите туннель вручную."
fi

if [[ "$start_output" != *"success=true"* ]]; then
    case "$start_output" in
        *invalid_endpoint*) recommend "Проверьте формат: host:port или [IPv6]:port." ;;
        *invalid_host*) recommend "Проверьте hostname или IP-адрес в endpoint." ;;
        *invalid_port*) recommend "Порт должен быть числом от 1 до 65535." ;;
        *) recommend "Проверьте полный ответ ContentProvider выше." ;;
    esac
    die "Приложение отклонило команду запуска."
fi

activity_output=""
if ! activity_output="$(adb_exec shell am start -W -n "$MAIN_ACTIVITY" 2>&1)" || [[ "$activity_output" == *"Error:"* ]]; then
    printf '%s\n' "$activity_output" >&2
    die \
        "Не удалось открыть Activity для выполнения команды." \
        "Откройте Charles Tunnel на устройстве вручную в течение 30 секунд." \
        "Если приложение не найдено, повторите ./install.sh -s $DEVICE_SERIAL"
fi

permission_hint_shown=0
status_output=""
attempt=0
while (( attempt < start_timeout )); do
    if status_output="$(adb_exec shell content call --uri "$CONTROL_URI" --method status 2>&1)"; then
        if [[ "$status_output" == *"state=running"* ]]; then
            info "Туннель запущен: $endpoint"
            exit 0
        fi
        if [[ "$status_output" == *"state=error"* ]]; then
            printf '%s\n' "$status_output" >&2
            die \
                "Charles Tunnel сообщил об ошибке запуска." \
                "Проверьте, что Charles SOCKS Proxy работает и порт $host_port указан верно." \
                "Для сброса выполните: ./disable.sh -s $DEVICE_SERIAL"
        fi
        if [[ "$status_output" == *"vpn_permission_granted=false"* ]] && (( permission_hint_shown == 0 )); then
            warn "Ожидается системное разрешение VPN — подтвердите диалог на устройстве."
            permission_hint_shown=1
        fi
    fi
    sleep 1
    (( attempt += 1 ))
done

printf '%s\n' "$status_output" >&2
die \
    "Туннель не перешёл в состояние running за $start_timeout секунд." \
    "Проверьте экран устройства и подтвердите системный VPN-диалог." \
    "Если диалога нет, выполните ./disable.sh -s $DEVICE_SERIAL и повторите запуск." \
    "Для более долгого ожидания задайте CHUNNEL_START_TIMEOUT=60."
