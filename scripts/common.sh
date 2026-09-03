#!/usr/bin/env bash

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE_NAME="com.mobileapp.charlestunnel"
MAIN_ACTIVITY="$PACKAGE_NAME/.MainActivity"
CONTROL_URI="content://$PACKAGE_NAME.control"
APK_PATH="$PROJECT_ROOT/build/dist/charles-tunnel.apk"
DEFAULT_ENDPOINT="127.0.0.1:8889"

ADB_BIN=""
DEVICE_SERIAL="${ANDROID_SERIAL:-}"
ADB_TARGET=()
REMAINING_ARGS=(__chunnel_sentinel__)
ENDPOINT_HOST=""
ENDPOINT_PORT=""
ANDROID_SDK_DIRECTORY=""
GRADLE_COMMAND=()
GRADLE_VERSION=""

info() {
    printf '[chunnel] %s\n' "$*"
}

warn() {
    printf '[chunnel][предупреждение] %s\n' "$*" >&2
}

error() {
    printf '[chunnel][ошибка] %s\n' "$*" >&2
}

recommend() {
    printf '[chunnel][что делать] %s\n' "$*" >&2
}

die() {
    error "$1"
    shift
    while (( $# > 0 )); do
        recommend "$1"
        shift
    done
    exit 1
}

parse_device_args() {
    REMAINING_ARGS=(__chunnel_sentinel__)
    while (( $# > 0 )); do
        case "$1" in
            -s|--serial)
                if (( $# < 2 )); then
                    die "После $1 нужен serial устройства." "Посмотрите serial командой: adb devices"
                fi
                DEVICE_SERIAL="$2"
                shift 2
                ;;
            *)
                REMAINING_ARGS+=("$1")
                shift
                ;;
        esac
    done
}

find_adb() {
    if command -v adb >/dev/null 2>&1; then
        ADB_BIN="$(command -v adb)"
        return
    fi

    local sdk
    for sdk in \
        "${ANDROID_HOME:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "${HOME:-}/Library/Android/sdk" \
        "${HOME:-}/Android/Sdk"; do
        if [[ -n "$sdk" && -x "$sdk/platform-tools/adb" ]]; then
            ADB_BIN="$sdk/platform-tools/adb"
            return
        fi
    done

    if [[ -f "$PROJECT_ROOT/local.properties" ]]; then
        sdk="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | head -n 1)"
        if [[ -n "$sdk" && -x "$sdk/platform-tools/adb" ]]; then
            ADB_BIN="$sdk/platform-tools/adb"
            return
        fi
    fi

    die \
        "Команда adb не найдена." \
        "Установите Android SDK Platform-Tools: sdkmanager \"platform-tools\"" \
        "Добавьте <Android SDK>/platform-tools в PATH или задайте ANDROID_HOME."
}

find_android_sdk() {
    local configured_sdk=""
    if [[ -f "$PROJECT_ROOT/local.properties" ]]; then
        configured_sdk="$(sed -n 's/^sdk\.dir=//p' "$PROJECT_ROOT/local.properties" | head -n 1)"
        if [[ -n "$configured_sdk" ]]; then
            if [[ ! -d "$configured_sdk" ]]; then
                die \
                    "В local.properties указан несуществующий Android SDK: $configured_sdk" \
                    "Исправьте sdk.dir или удалите local.properties, чтобы использовать SDK из Android Studio автоматически."
            fi
            ANDROID_SDK_DIRECTORY="$configured_sdk"
            return
        fi
    fi

    local candidate
    for candidate in \
        "${ANDROID_HOME:-}" \
        "${ANDROID_SDK_ROOT:-}" \
        "${HOME:-}/Library/Android/sdk" \
        "${HOME:-}/Android/Sdk"; do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            ANDROID_SDK_DIRECTORY="$candidate"
            return
        fi
    done

    die \
        "Android SDK не найден." \
        "Откройте Android Studio → Settings → Android SDK и посмотрите Android SDK Location." \
        "Задайте ANDROID_HOME или создайте local.properties со строкой sdk.dir=/полный/путь/к/sdk."
}

check_build_environment() {
    command -v java >/dev/null 2>&1 || die \
        "Java не найдена." \
        "Установите JDK 17+ или добавьте Android Studio JBR в JAVA_HOME."
    command -v javac >/dev/null 2>&1 || die \
        "Найдено только Java Runtime без компилятора javac." \
        "Установите полноценный JDK 17+ или используйте JBR из Android Studio."

    local javac_output
    local javac_version
    local java_major
    if ! javac_output="$(javac -version 2>&1)"; then
        printf '%s\n' "$javac_output" >&2
        die "Не удалось запустить javac." "Проверьте JAVA_HOME и установку JDK."
    fi
    javac_version="${javac_output#javac }"
    java_major="${javac_version%%.*}"
    if [[ "$java_major" == "1" ]]; then
        java_major="${javac_version#1.}"
        java_major="${java_major%%.*}"
    fi
    if [[ ! "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 17 )); then
        die \
            "Требуется JDK 17+, найден javac $javac_version." \
            "Выберите JDK из Android Studio или установите современный JDK и исправьте JAVA_HOME."
    fi

    find_android_sdk

    local missing=0
    if [[ ! -d "$ANDROID_SDK_DIRECTORY/platforms/android-36" ]]; then
        error "Не установлен Android SDK Platform 36."
        recommend "Установите: sdkmanager \"platforms;android-36\""
        missing=1
    fi
    if [[ ! -d "$ANDROID_SDK_DIRECTORY/build-tools/36.0.0" ]]; then
        error "Не установлены Android SDK Build-Tools 36.0.0."
        recommend "Установите: sdkmanager \"build-tools;36.0.0\""
        missing=1
    fi
    if [[ ! -x "$ANDROID_SDK_DIRECTORY/platform-tools/adb" ]]; then
        error "Не установлены Android SDK Platform-Tools."
        recommend "Установите: sdkmanager \"platform-tools\""
        missing=1
    fi
    if (( missing == 1 )); then
        recommend "Те же компоненты можно установить в Android Studio → Settings → Android SDK."
        exit 1
    fi

    export ANDROID_HOME="$ANDROID_SDK_DIRECTORY"
    info "JDK: $javac_version"
    info "Android SDK: $ANDROID_SDK_DIRECTORY"
}

gradle_version_is_compatible() {
    local version="$1"
    local major="${version%%.*}"
    local rest="${version#*.}"
    local minor="${rest%%.*}"
    [[ "$major" =~ ^[0-9]+$ && "$minor" =~ ^[0-9]+$ ]] || return 1
    (( major > 9 || (major == 9 && minor >= 5) ))
}

use_system_gradle() {
    local gradle_path
    local version_output
    gradle_path="$(command -v gradle 2>/dev/null)" || return 1
    if ! version_output="$("$gradle_path" --version 2>&1)"; then
        warn "Системный Gradle не запускается: $gradle_path"
        printf '%s\n' "$version_output" >&2
        return 1
    fi
    GRADLE_VERSION="$(printf '%s\n' "$version_output" | awk '$1 == "Gradle" { print $2; exit }')"
    if ! gradle_version_is_compatible "$GRADLE_VERSION"; then
        warn "Системный Gradle $GRADLE_VERSION несовместим с AGP 9.3; требуется Gradle 9.5+."
        return 1
    fi
    GRADLE_COMMAND=("$gradle_path")
    info "Gradle: системный $GRADLE_VERSION ($gradle_path)"
}

use_gradle_wrapper() {
    if [[ ! -x "$PROJECT_ROOT/gradlew" ]]; then
        die \
            "Совместимый системный Gradle не найден, а Gradle Wrapper отсутствует или не исполняемый." \
            "Установите Gradle 9.5+ или выполните chmod +x gradlew."
    fi
    GRADLE_COMMAND=("$PROJECT_ROOT/gradlew")
    info "Gradle: wrapper. При первом запуске он скачает закреплённую версию."
}

select_gradle() {
    case "${CHUNNEL_GRADLE:-auto}" in
        auto)
            if use_system_gradle; then
                return
            fi
            warn "Переключаюсь на Gradle Wrapper."
            use_gradle_wrapper
            ;;
        system)
            use_system_gradle || die \
                "CHUNNEL_GRADLE=system, но совместимый системный Gradle недоступен." \
                "Установите Gradle 9.5+ или уберите CHUNNEL_GRADLE=system для fallback на wrapper."
            ;;
        wrapper)
            use_gradle_wrapper
            ;;
        *)
            die \
                "Некорректный CHUNNEL_GRADLE: $CHUNNEL_GRADLE" \
                "Допустимые значения: auto, system, wrapper."
            ;;
    esac
}

run_gradle() {
    "${GRADLE_COMMAND[@]}" "$@"
}

select_device() {
    find_adb

    local devices_output
    if ! devices_output="$("$ADB_BIN" devices 2>&1)"; then
        printf '%s\n' "$devices_output" >&2
        die "Не удалось получить список Android-устройств." "Перезапустите ADB: adb kill-server, затем adb start-server"
    fi

    if [[ -n "$DEVICE_SERIAL" ]]; then
        local state
        if ! state="$("$ADB_BIN" -s "$DEVICE_SERIAL" get-state 2>&1)" || [[ "$state" != "device" ]]; then
            printf '%s\n' "$devices_output" >&2
            die \
                "Устройство $DEVICE_SERIAL недоступно: $state" \
                "Разблокируйте устройство и подтвердите разрешение USB debugging." \
                "Проверьте serial командой: adb devices"
        fi
    else
        local online_count
        online_count="$(printf '%s\n' "$devices_output" | awk '$2 == "device" { count++ } END { print count + 0 }')"
        if [[ "$online_count" == "0" ]]; then
            printf '%s\n' "$devices_output" >&2
            if [[ "$devices_output" == *$'\tunauthorized'* ]]; then
                die \
                    "Устройство не разрешило отладку по USB." \
                    "Разблокируйте устройство и подтвердите RSA-диалог USB debugging." \
                    "Если диалог не появляется, переподключите кабель и выполните adb kill-server."
            fi
            if [[ "$devices_output" == *$'\toffline'* ]]; then
                die \
                    "Устройство отображается как offline." \
                    "Переподключите USB и выполните: adb kill-server, затем adb start-server"
            fi
            die \
                "Не найдено подключённое Android-устройство." \
                "Включите Developer options и USB debugging, подключите устройство и выполните adb devices."
        fi
        if [[ "$online_count" != "1" ]]; then
            printf '%s\n' "$devices_output" >&2
            die \
                "Подключено несколько устройств." \
                "Выберите одно: $0 -s SERIAL"
        fi
        DEVICE_SERIAL="$(printf '%s\n' "$devices_output" | awk '$2 == "device" { print $1; exit }')"
    fi

    ADB_TARGET=("$ADB_BIN" -s "$DEVICE_SERIAL")
    info "Устройство: $DEVICE_SERIAL"
}

adb_exec() {
    "${ADB_TARGET[@]}" "$@"
}

is_package_installed() {
    local output
    output="$(adb_exec shell pm path "$PACKAGE_NAME" 2>/dev/null)" || return 1
    [[ "$output" == package:* ]]
}

ensure_package_installed() {
    if ! is_package_installed; then
        die \
            "Charles Tunnel не установлен на устройстве $DEVICE_SERIAL." \
            "Сначала выполните: $PROJECT_ROOT/install.sh -s $DEVICE_SERIAL"
    fi
}

grant_notification_permission() {
    local sdk
    if ! sdk="$(adb_exec shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')" ||
        [[ ! "$sdk" =~ ^[0-9]+$ ]] || (( sdk < 33 )); then
        return
    fi

    local permission_output
    if ! permission_output="$(adb_exec shell pm grant \
        "$PACKAGE_NAME" \
        android.permission.POST_NOTIFICATIONS 2>&1)"; then
        printf '%s\n' "$permission_output" >&2
        warn "Не удалось автоматически разрешить уведомления."
        recommend "Подтвердите системный запрос в Charles Tunnel либо включите уведомления в настройках приложения."
    fi
}

validate_port() {
    local port="$1"
    if [[ ! "$port" =~ ^[0-9]+$ ]] || (( ${#port} > 5 )); then
        die "Некорректный порт: $port" "Порт должен быть целым числом от 1 до 65535."
    fi
    local number=$((10#$port))
    if (( number < 1 || number > 65535 )); then
        die "Некорректный порт: $port" "Порт должен быть целым числом от 1 до 65535."
    fi
}

parse_endpoint() {
    local endpoint="$1"
    local ipv6_pattern='^\[([^]]+)\]:([0-9]+)$'
    local host_pattern='^([^:]+):([0-9]+)$'

    if [[ "$endpoint" =~ $ipv6_pattern ]]; then
        ENDPOINT_HOST="${BASH_REMATCH[1]}"
        ENDPOINT_PORT="${BASH_REMATCH[2]}"
    elif [[ "$endpoint" =~ $host_pattern ]]; then
        ENDPOINT_HOST="${BASH_REMATCH[1]}"
        ENDPOINT_PORT="${BASH_REMATCH[2]}"
    else
        die \
            "Некорректный endpoint: $endpoint" \
            "Используйте host:port, например 127.0.0.1:8889. IPv6 записывайте как [::1]:8889."
    fi

    if [[ "$ENDPOINT_HOST" =~ [[:space:]] ]]; then
        die "Endpoint содержит пробелы: $endpoint" "Используйте host:port без пробелов."
    fi
    validate_port "$ENDPOINT_PORT"
}

is_loopback_host() {
    case "$1" in
        127.0.0.1|localhost|LOCALHOST|Localhost|::1) return 0 ;;
        *) return 1 ;;
    esac
}

bundle_value() {
    local bundle="$1"
    local key="$2"
    printf '%s\n' "$bundle" | sed -n "s/.*$key=\([^,}]*\).*/\1/p" | head -n 1
}
