#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/common.sh"

usage() {
    printf '%s\n' \
        'Использование:' \
        '  ./disable.sh [-s SERIAL] [--endpoint HOST:PORT]' \
        '' \
        'Обычно endpoint определится из приложения автоматически.'
}

parse_device_args "$@"
set -- "${REMAINING_ARGS[@]}"
shift

endpoint=""
while (( $# > 0 )); do
    case "$1" in
        --endpoint)
            (( $# >= 2 )) || die "После --endpoint требуется HOST:PORT." "Пример: --endpoint 127.0.0.1:8889"
            endpoint="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "Неизвестный аргумент: $1" "Запустите ./disable.sh --help"
            ;;
    esac
done

select_device

failed=0
installed=0
status_before=""
if is_package_installed; then
    installed=1
    if ! status_before="$(adb_exec shell content call --uri "$CONTROL_URI" --method status 2>&1)"; then
        warn "Не удалось прочитать состояние приложения: $status_before"
    fi
else
    warn "Charles Tunnel не установлен; остановка приложения не требуется."
fi

if [[ -z "$endpoint" && -n "$status_before" ]]; then
    endpoint="$(bundle_value "$status_before" endpoint)"
fi
if [[ -z "$endpoint" ]]; then
    endpoint="$DEFAULT_ENDPOINT"
    warn "Endpoint не удалось определить, для удаления adb reverse используется $endpoint."
fi
parse_endpoint "$endpoint"

if (( installed == 1 )); then
    stop_output=""
    if ! stop_output="$(adb_exec shell content call --uri "$CONTROL_URI" --method stop 2>&1)"; then
        printf '%s\n' "$stop_output" >&2
        error "Не удалось вызвать ControlProvider для остановки."
        recommend "Откройте Charles Tunnel на устройстве и нажмите «Остановить»."
        failed=1
    elif [[ "$stop_output" != *"success=true"* ]]; then
        printf '%s\n' "$stop_output" >&2
        error "Приложение отклонило команду остановки."
        recommend "Откройте Charles Tunnel на устройстве и нажмите «Остановить»."
        failed=1
    else
        launch_required=1
        if [[ "$stop_output" == *"code=stop_requested"* ]]; then
            launch_required=0
        fi
        if (( launch_required == 1 )); then
            activity_output=""
            if ! activity_output="$(adb_exec shell am start -W -n "$MAIN_ACTIVITY" 2>&1)" || [[ "$activity_output" == *"Error:"* ]]; then
                printf '%s\n' "$activity_output" >&2
                error "Не удалось открыть Activity для выполнения команды остановки."
                recommend "Откройте Charles Tunnel на устройстве вручную в течение 30 секунд."
                failed=1
            fi
        fi
        if (( failed == 0 )); then
            stopped=0
            attempt=0
            while (( attempt < 15 )); do
                status_output=""
                if status_output="$(adb_exec shell content call --uri "$CONTROL_URI" --method status 2>&1)" &&
                    [[ "$status_output" == *"state=idle"* ]]; then
                    stopped=1
                    break
                fi
                sleep 1
                (( attempt += 1 ))
            done
            if (( stopped == 0 )); then
                printf '%s\n' "$status_output" >&2
                error "Туннель не подтвердил остановку за 15 секунд."
                recommend "Откройте приложение и нажмите «Остановить» вручную."
                failed=1
            fi
        fi
    fi
fi

if is_loopback_host "$ENDPOINT_HOST"; then
    reverse_output=""
    if ! reverse_output="$(adb_exec reverse --remove "tcp:$ENDPOINT_PORT" 2>&1)"; then
        warn "Reverse-настройка tcp:$ENDPOINT_PORT не удалена: $reverse_output"
        recommend "Проверьте список вручную: adb -s $DEVICE_SERIAL reverse --list"
    fi
fi

if (( failed == 1 )); then
    exit 1
fi

info "Charles Tunnel выключен."
