#!/system/bin/sh
# Thread sweep, interleaved to fight thermal drift. Short text only.
# Usage: sh sweep.sh <runs> <sleep_between_sec>
set -u
B=/data/local/tmp/ttsbench
BIN=$B/sherpa-onnx-offline-tts
M=$B/supertonic
OUT=$B/out
export LD_LIBRARY_PATH=$B
RUNS=${1:-3}
NAP=${2:-15}
SID=${3:-0}
mkdir -p $OUT

uptime_ms() { awk '{printf "%d", $1*1000}' /proc/uptime; }

one() {
  t=$1
  i=$2
  T=$(cat $B/short.txt)
  WAV=$OUT/sweep-t$t-$i.wav
  LOGF=$B/last-sweep-t$t-$i.log
  rm -f $WAV
  start=$(uptime_ms)
  $BIN \
    --supertonic-duration-predictor=$M/duration_predictor.int8.onnx \
    --supertonic-text-encoder=$M/text_encoder.int8.onnx \
    --supertonic-vector-estimator=$M/vector_estimator.int8.onnx \
    --supertonic-vocoder=$M/vocoder.int8.onnx \
    --supertonic-tts-json=$M/tts.json \
    --supertonic-unicode-indexer=$M/unicode_indexer.bin \
    --supertonic-voice-style=$M/voice.bin \
    --sid=$SID --lang=ru --num-threads=$t \
    --output-filename=$WAV "$T" > $LOGF 2>&1
  rc=$?
  end=$(uptime_ms)
  rtf=$(grep 'Real-time factor' $LOGF | tail -1 | sed 's/.*RTF): //')
  printf "sweep\tt%s\ts%s\t%s\t%s\t%s\t%s\t%s\trc=%s\tRTF:%s\n" \
    "$t" "$SID" "$i" "$((end-start))" "$(wc -c < $WAV 2>/dev/null || echo 0)" "-" "-" "$rc" "$rtf"
}

echo "label	threads	sid	run	wall_ms	wav_bytes	peak_rss_kb	rc	rtf"
i=1
while [ $i -le $RUNS ]; do
  for t in 2 3 4 6; do
    one $t $i
  done
  i=$((i+1))
  sleep $NAP
done
echo "=== sweep done ==="
