#!/bin/sh
# run-core-tests.sh — единый раннер тестов ядра памяти мобайл-агента.
#
# Зачем: Bun в этом окружении не бинарник в PATH, а libbun-musl.so, который
# грузится через libldmusl.so. Каталог лежит в /data/app и меняется при каждой
# переустановке APK, поэтому путь берётся из /proc/<pid>/maps живого процесса —
# ровно так же, как в MOBILE_AGENT/tools/run-verify.sh. Ничего не зашито.
#
# Запуск:
#   sh /storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT/opencode/run-core-tests.sh
#
# Включает: memory-core.test.mjs (леммы, индекс, поиск, пак, аудит, адаптив,
# негатив, guard) и memory-integration.test.mjs (staged-ассеты = протестированное
# ядро, живой memory.js, Kotlin, реальный pack). Остальные moltbook-тесты живого
# плагина здесь НЕ запускаются:
# они требуют MOLTBOOK_JS_PATH с named exports и не относятся к ядру памяти.

set -u
ROOT=/storage/emulated/0/Documents/OpencodeTerminal/MOBILE_AGENT

pid=$(toybox pidof libldmusl.so 2>/dev/null | tr ' ' '\n' | head -1)
[ -n "${pid:-}" ] || { echo "нет процесса с libldmusl.so — приложение не запущено"; exit 2; }

line=$(toybox grep -m1 'libldmusl\.so' "/proc/$pid/maps" 2>/dev/null | tr -s ' ' | cut -d' ' -f6-)
[ -n "${line:-}" ] || { echo "не нашли libldmusl.so в /proc/$pid/maps"; exit 2; }

D=$(dirname "$line")
for so in libldmusl.so libbun-musl.so; do
  [ -r "$D/$so" ] || { echo "нет $so в $D — приложение переустановлено?"; exit 2; }
done

RUN="$D/libldmusl.so $D/libbun-musl.so"
echo "Раннер Bun: $D"
echo "Тест: $ROOT/opencode/memory-core.test.mjs"
echo ""

$RUN "$ROOT/opencode/memory-core.test.mjs"
status=$?

if [ "$status" -eq 0 ]; then
  echo ""
  echo "Тест: $ROOT/opencode/memory-adaptive-store.test.mjs"
  echo ""
  $RUN "$ROOT/opencode/memory-adaptive-store.test.mjs"
  status=$?
fi

if [ "$status" -eq 0 ]; then
  echo ""
  echo "Тест: $ROOT/opencode/memory-integration.test.mjs"
  echo ""
  $RUN "$ROOT/opencode/memory-integration.test.mjs"
  status=$?
fi

if [ "$status" -eq 0 ]; then
  echo ""
  echo "Тест: $ROOT/opencode/gguf-integration.test.mjs"
  echo ""
  $RUN "$ROOT/opencode/gguf-integration.test.mjs"
  status=$?
fi

echo ""
if [ "$status" -eq 0 ]; then
  echo "ИТОГ: ядро памяти — ВСЕ ТЕСТЫ ПРОШЛИ"
else
  echo "ИТОГ: ядро памяти — ПРОВАЛ (exit=$status)"
fi
exit "$status"
