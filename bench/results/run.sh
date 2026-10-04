#!/system/bin/sh
# TTS bench on-device: SupertonicTTS 3 int8, lang=ru, CPU EP.
# ASCII only. Usage: sh run.sh <num_threads> <sid> <runs>
# NOTE: Android toybox date has no %N, timing uses /proc/uptime (10ms resolution).
set -u
B=/data/local/tmp/ttsbench
BIN=$B/sherpa-onnx-offline-tts
M=$B/supertonic
OUT=$B/out
export LD_LIBRARY_PATH=$B
THREADS=${1:-4}
SID=${2:-0}
RUNS=${3:-3}
mkdir -p $OUT

uptime_ms() { awk '{printf "%d", $1*1000}' /proc/uptime; }

bench() {
  label=$1
  txtfile=$2
  T=$(cat $txtfile)
  i=1
  while [ $i -le $RUNS ]; do
    WAV=$OUT/$label-t$THREADS-s$SID-$i.wav
    LOGF=$B/last-$label-t$THREADS-s$SID-$i.log
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
      --sid=$SID --lang=ru --num-threads=$THREADS \
      --output-filename=$WAV "$T" > $LOGF 2>&1
    rc=$?
    end=$(uptime_ms)
    ms=$((end-start))
    rtf=$(grep 'Real-time factor' $LOGF | tail -1 | sed 's/.*RTF): //')
    if [ -f $WAV ]; then sz=$(wc -c < $WAV); else sz=0; fi
    printf "%s\tt%s\ts%s\t%s\t%s\t%s\t%s\trc=%s\tRTF:%s\n" \
      "$label" "$THREADS" "$SID" "$i" "$ms" "$sz" "-" "$rc" "$rtf"
    i=$((i+1))
  done
}

echo "=== config threads=$THREADS sid=$SID runs=$RUNS ==="
echo "label	threads	sid	run	wall_ms	wav_bytes	peak_rss_kb	rc	rtf"
bench short $B/short.txt
bench long $B/long.txt
echo "=== done threads=$THREADS sid=$SID ==="
