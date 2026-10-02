#!/bin/sh
# run-verify.sh — запуск tools/verify.mjs через раннер Bun.
#
# Зачем он существует
# -------------------
# verify.mjs — обычный .mjs, а Bun в этом окружении не самостоятельный
# бинарник и не в PATH: libbun-musl.so запускается через libldmusl.so как
# загрузчик musl. Путь к каталогу с библиотеками лежит в /data/app, который
# не листается, и меняется при каждой переустановке APK, поэтому он берётся
# из /proc/<pid>/maps запущенного процесса.
#
# Путь здесь не зашит намеренно. Он был зашит в files/loop/env.sh, после
# переустановки каталог исчез, и гейт стал падать с «inaccessible or not
# found» на каждом запуске — петля выглядела живой, а на деле не могла
# запустить ни один тест. Это LOOP.md, правило 4.
#
# Запуск
# ------
#   sh /storage/emulated/0/Documents/OpencodeTerminal/tools/run-verify.sh
#   sh .../run-verify.sh --density=3.5 /sdcard/d_settings.xml
#
# Из PowerShell (второй шаг после дампера, который кладёт d_*.xml на sdcard):
#
#   adb shell sh /storage/emulated/0/Documents/OpencodeTerminal/tools/run-verify.sh
#
# Требуется запущенное приложение: без него нет процесса с libldmusl.so в
# maps, и скрипт скажет об этом сам, а не упадёт на пустом пути.
set -u

ROOT=/storage/emulated/0/Documents/OpencodeTerminal

pid=$(toybox pidof libldmusl.so 2>/dev/null | tr ' ' '\n' | head -1)
[ -n "${pid:-}" ] || { echo "нет процесса с libldmusl.so — приложение не запущено"; exit 2; }

line=$(toybox grep -m1 'libldmusl\.so' "/proc/$pid/maps" 2>/dev/null | tr -s ' ' | cut -d' ' -f6-)
[ -n "${line:-}" ] || { echo "не нашли libldmusl.so в /proc/$pid/maps"; exit 2; }

D=$(dirname "$line")
for so in libldmusl.so libbun-musl.so; do
  [ -r "$D/$so" ] || { echo "нет $so в $D — приложение переустановлено?"; exit 2; }
done

# `run` в команде не нужен: Bun исполняет .mjs по расширению, а `bun run`
# ждёт запись в package.json и падает с «Script not found».
exec "$D/libldmusl.so" "$D/libbun-musl.so" "$ROOT/tools/verify.mjs" "$@"
