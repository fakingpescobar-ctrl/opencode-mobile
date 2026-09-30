#!/system/bin/sh
# Device-side runner для проверок памяти: доверие (provenance) и миграция
# старой базы. Оба теста обязаны зелёными и на ПК, и на устройстве -
# иначе «работает на моей машине» превращается в «работает у меня».
#
# Почему это файл, а не adb-однострочник: команда вкладывает кавычки в
# SELFHARNESS_BUN_CMD, и вложенные кавычки через три шелла
# (PowerShell -> adb -> sh) ломались дважды.
#
# Пути повторяют OpencodeRuntime:
#   filesDir/musl    - DT_NEEDED-библиотеки, которых нет в системе
#                      (musl libc, libgcc, libstdc++). Без этого каталога
#                      libbun-musl.so падает при релокации с
#                      "symbol not found", что выглядит как сломанный APK.
#   nativeLibraryDir - где лежат libldmusl.so и libbun-musl.so.
APP_DIR="/data/data/org.opencode.mobile.debug"
# nativeLibraryDir передаётся снаружи: из-под run-as каталог /data/app закрыт,
# и glob внутри молча разворачивается в пустоту, а запуск падает с 127.
NATIVE_DIR="$1"
if [ -z "$NATIVE_DIR" ] || [ ! -d "$NATIVE_DIR" ]; then
  echo "НЕ НАЙДЕН nativeLibraryDir: '$NATIVE_DIR' (передай путём первым аргументом)"
  exit 2
fi

export LD_LIBRARY_PATH="$APP_DIR/files/musl:$NATIVE_DIR"
export SELFHARNESS_BUN_CMD="$NATIVE_DIR/libldmusl.so $NATIVE_DIR/libbun-musl.so run %s"
export SELFHARNESS_TEST_TMP="$APP_DIR/files/provmig/tmp"
export TMPDIR="$SELFHARNESS_TEST_TMP"
# На устройстве скрипты лежат рядом с тестами, а не в дереве исходников, поэтому
# тестам передаются явные пути - иначе проверка падает на своём импорте.
export PROVENANCE_JS_PATH="$APP_DIR/files/provmig/provenance.js"
export MEMORY_JS_PATH="$APP_DIR/files/provmig/memory.js"
mkdir -p "$TMPDIR"

cd "$APP_DIR/files/provmig" || exit 1
echo "== musl-bun на устройстве: $NATIVE_DIR"
FAILED=0
for test_file in provenance.test.mjs memory-migration.test.mjs; do
  echo ""
  echo "== $test_file"
  "$NATIVE_DIR/libldmusl.so" "$NATIVE_DIR/libbun-musl.so" run "$test_file" || FAILED=1
done
echo ""
if [ "$FAILED" -eq 0 ]; then
  echo "УСТРОЙСТВО: всё зелёное"
else
  echo "УСТРОЙСТВО: ЕСТЬ КРАСНОЕ"
fi
exit "$FAILED"