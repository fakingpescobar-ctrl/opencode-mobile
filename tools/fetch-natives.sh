#!/usr/bin/env bash
# Ставит ВЕСЬ комплект нативов в app/src/main/jniLibs/arm64-v8a/ — то же, что
# tools/fetch-opencode-binary.ps1, но без PowerShell: чтобы это же умел CI на
# ubuntu и чтобы источник правды о пинах был один (tools/natives.json).
#
# Зачем нативы отдельно: app/src/main/jniLibs/ в .gitignore (301 МБ). Если их
# просто не положить, сборка ПРОЙДЁТ, APK соберётся без сервера, приложение
# стартует — и opencode не запускается без внятной причины. Поэтому CI обязан
# их подтянуть, а не «собрать как получится».
#
# Каждый файл проверяется по размеру И sha256 из пина: размер совпадает у разных
# сборок (musl 1.2.5-r11 и -r12 весят одинаково), различает их только хеш.
#
# Использование:
#   tools/fetch-natives.sh            # скачать/проверить комплект
#   tools/fetch-natives.sh --check    # только проверить, rc=1 если чего-то нет
#   tools/fetch-natives.sh --bootstrap 2.3.0
#        # новая версия ядра: скачать, посчитать размер+sha256 и напечатать
#        # готовый блок для tools/natives.json (ничего не коммитит)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"
PINS="$ROOT/tools/natives.json"
CACHE="${NATIVES_CACHE:-$ROOT/.natives-cache}"
ALPINE="https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64"
SHERPA="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8"
NPM="https://registry.npmjs.org"

CHECK=0
BOOTSTRAP=""
case "${1:-}" in
  --check) CHECK=1 ;;
  --bootstrap) BOOTSTRAP="${2:-}"; [ -n "$BOOTSTRAP" ] || { echo "нужна версия: --bootstrap 2.3.0" >&2; exit 2; } ;;
  "") ;;
  *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
esac

command -v curl >/dev/null || { echo "нет curl" >&2; exit 1; }
command -v jq >/dev/null || { echo "нет jq" >&2; exit 1; }
[ -f "$PINS" ] || { echo "нет пинов: $PINS" >&2; exit 1; }

version="$(sed -n 's/^opencode\.binaryVersion=//p' "$ROOT/gradle.properties" | head -1 | tr -d '[:space:]')"
[ -n "$version" ] || { echo "нет opencode.binaryVersion в gradle.properties" >&2; exit 1; }
[ -n "$BOOTSTRAP" ] && version="$BOOTSTRAP"

opencode_url="$(jq -r '.opencode.url' "$PINS" | sed "s|{version}|$version|g")"

# Пины для запрошенной версии ядра. Нет пина — это отказ, а не «соберём на глаз»:
# ровно так в APK попадал бинарь другой версии и падал на устройстве.
if [ -z "$BOOTSTRAP" ]; then
  pin_bytes="$(jq -r --arg v "$version" '.opencode.pins[$v].bytes // empty' "$PINS")"
  pin_sha="$(jq -r --arg v "$version" '.opencode.pins[$v].sha256 // empty' "$PINS")"
  if [ -z "$pin_bytes" ] || [ -z "$pin_sha" ]; then
    echo "нет пина для ядра $version в tools/natives.json" >&2
    echo "сначала: tools/fetch-natives.sh --bootstrap $version  (напечатает блок с bytes/sha256)" >&2
    exit 3
  fi
fi

mkdir -p "$JNI" "$CACHE"

download() { # url dest
  local url="$1" dest="$2"
  if [ -s "$dest" ]; then return 0; fi
  echo "  качаю $(basename "$dest")"
  curl -fL --retry 4 --retry-all-errors --retry-delay 3 --max-time 1800 \
    -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
}

# member из архива: tar -x<z|j>f / zip
extract() { # src kind member dest
  local src="$1" kind="$2" member="$3" dest="$4"
  case "$kind" in
    tar) tar -xOf "$src" "$member" > "$dest" ;;
    zip) unzip -p "$src" "$member" > "$dest" ;;
    *) echo "неизвестный тип архива: $kind" >&2; exit 1 ;;
  esac
}

install_pin() { # name url kind member bytes sha256 [alsoAs]
  local name="$1" url="$2" kind="$3" member="$4" bytes="$5" sha="$6" also="${7:-}"
  local archive="$CACHE/$(basename "${url%%\?*}")"
  local target="$JNI/$name" tmp="$CACHE/tmp-$name"
  if [ "$CHECK" = "1" ]; then
    if [ ! -f "$target" ]; then echo "НЕТ $name"; return 1; fi
  else
    download "$url" "$archive"
    extract "$archive" "$kind" "$member" "$tmp"
    local got_bytes got_sha
    got_bytes="$(wc -c < "$tmp" | tr -d '[:space:]')"
    got_sha="$(sha256sum "$tmp" | cut -d' ' -f1)"
    if [ "$got_bytes" != "$bytes" ] || [ "$got_sha" != "$sha" ]; then
      echo "ПИН НЕ СОШЁЛСЯ (${member}):" >&2
      echo "  размер $got_bytes, ожидался $bytes" >&2
      echo "  sha256 $got_sha, пин $sha" >&2
      rm -f "$tmp"
      exit 4
    fi
    mv "$tmp" "$target"
    if [ -n "$also" ]; then cp "$target" "$JNI/$also"; fi
  fi
  local size sha_now
  size="$(wc -c < "$target" | tr -d '[:space:]')"
  sha_now="$(sha256sum "$target" | cut -d' ' -f1)"
  if [ "$size" != "$bytes" ] || [ "$sha_now" != "$sha" ]; then
    echo "ПРОВЕРКА $name: размер $size (пин $bytes), sha256 $sha_now (пин $sha)" >&2
    return 1
  fi
  echo "  ok $name ($size б, ${sha_now:0:12}…)"
}

# Ядро: у него пин зависит от версии из gradle.properties.
if [ -n "$BOOTSTRAP" ]; then
  # Новая версия: ничего не проверяем и не устанавливаем — считаем пин и печатаем
  # готовый блок. Коммит пина остаётся за человеком, а не за скриптом.
  archive="$CACHE/$(basename "$opencode_url")"
  tmp="$CACHE/tmp-libopencode-bootstrap.so"
  download "$opencode_url" "$archive"
  extract "$archive" tar "package/bin/opencode" "$tmp"
  boot_bytes="$(wc -c < "$tmp" | tr -d '[:space:]')"
  boot_sha="$(sha256sum "$tmp" | cut -d' ' -f1)"
  for marker in 'libstdc++.so.6' 'libgcc_s.so.1'; do
    grep -q "$marker" "$tmp" || { echo "нет маркера $marker — не musl-сборка" >&2; exit 5; }
  done
  rm -f "$tmp"
  echo "ядро $version: $boot_bytes б, sha256 $boot_sha"
  echo "блок для tools/natives.json → .opencode.pins[\"$version\"]:"
  printf '      "%s": {\n        "bytes": %s,\n        "sha256": "%s"\n      }\n' "$version" "$boot_bytes" "$boot_sha"
  exit 0
fi

install_pin libopencode.so "$opencode_url" tar "package/bin/opencode" "$pin_bytes" "$pin_sha"

# Дёшево проверяем, что это musl-сборка, а не windows-x64/v1: без этих строк
# приложение падает на устройстве с «Error loading shared library libstdc++.so.6».
if [ -f "$JNI/libopencode.so" ]; then
  for marker in 'libstdc++.so.6' 'libgcc_s.so.1'; do
    if ! grep -q "$marker" "$JNI/libopencode.so"; then
      echo "libopencode.so: нет маркера $marker — это не musl-сборка" >&2
      exit 5
    fi
  done
fi

mapfile -t pins < <(jq -c '.files[]' "$PINS")
for pin in "${pins[@]}"; do
  name="$(jq -r '.name' <<<"$pin")"
  url="$(jq -r '.url' <<<"$pin" | sed "s|{alpine}|$ALPINE|g; s|{sherpa}|$SHERPA|g")"
  install_pin "$name" "$url" "$(jq -r '.kind' <<<"$pin")" "$(jq -r '.member' <<<"$pin")" \
    "$(jq -r '.bytes' <<<"$pin")" "$(jq -r '.sha256' <<<"$pin")" "$(jq -r '.alsoAs // ""' <<<"$pin")"
done

bun_url="$NPM/@oven/bun-linux-aarch64-musl/-/bun-linux-aarch64-musl-$(jq -r '.bun.version' "$PINS").tgz"
install_pin libbun-musl.so "$bun_url" tar "package/bin/bun" \
  "$(jq -r '.bun.bytes' "$PINS")" "$(jq -r '.bun.sha256' "$PINS")"

if [ "$CHECK" = "1" ]; then
  echo "комплект на месте, версия ядра $version"
  exit 0
fi
echo "готово: $JNI (ядро $version)"
