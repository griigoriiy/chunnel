#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/common.sh"

if (( $# > 0 )); then
    die "build.sh не принимает аргументы." "Запустите: ./build.sh"
fi

cd "$PROJECT_ROOT"

info "Проверяю JDK и Android SDK..."
check_build_environment
select_gradle

info "Собираю distribution APK..."
if ! run_gradle :app:assembleDistribution; then
    error "Gradle не смог собрать APK."
    recommend "Посмотрите первую ошибку выше; строки вида 'What went wrong' обычно содержат причину."
    recommend "При ошибке загрузки зависимостей проверьте интернет/VPN и повторите ./build.sh."
    exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
    die \
        "Gradle завершился успешно, но APK не найден: $APK_PATH" \
        "Проверьте вывод задачи copyDistributionApk и содержимое app/build/outputs/apk/distribution."
fi

info "Готово: $APK_PATH"
