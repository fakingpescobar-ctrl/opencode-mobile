#!/system/bin/sh
# Peak RSS measurement: one long-text run at given threads, polls /proc/pid/status VmHWM.
# Usage: sh rss.sh <num_threads> <label>
set -u
B=/data/local/tmp/ttsbench
BIN=$B/sherpa-onnx-offline-tts
M=$B/supertonic
OUT=$B/out
export LD_LIBRARY_PATH=$B
THREADS=${1:-2}
LABEL=${2:-long}
mkdir -p $OUT
T=$(cat $B/$LABEL.txt)
WAV=$OUT/rss-t$THREADS-$LABEL.wav
LOGF=$B/last-rss-$LABEL.log
rm -f $WAV
uptime_ms() { awk '{printf "%d", $1*1000}' /proc/uptime; }
start=$(uptime_ms)
$BIN \
  --supertonic-duration-predictor=$M/duration_predictor.int8.onnx \
  --supertonic-text-encoder=$M/text_encoder.int8.onnx \
  --supertonic-vector-estimator=$M/vector_estimator.int8.onnx \
  --supertonic-vocoder=$M/vocoder.int8.onnx \
  --supertonic-tts-json=$M/tts.json \
  --supertonic-unicode-indexer=$M/unicode_indexer.bin \
  --supertonic-voice-style=$M/voice.bin \
  --sid=0 --lang=ru --num-threads=$THREADS \
  --output-filename=$WAV "$T" > $LOGF 2>&1 &
pid=$!
peak=0
while kill -0 $pid 2>/dev/null; do
  v=$(grep VmHWM /proc/$pid/status 2>/dev/null | tr -dc '0-9')
  if [ -n "$v" ] && [ "$v" -gt "$peak" ]; then peak=$v; fi
  sleep 0.2
done
wait $pid
end=$(uptime_ms)
printf "rss\tthreads=%s\ttext=%s\twall_ms=%s\tpeak_rss_kb=%s\tpeak_rss_mb=%s\twav_bytes=%s\n" \
  "$THREADS" "$LABEL" "$((end-start))" "$peak" "$((peak/1024))" "$(wc -c < $WAV 2>/dev/null || echo 0)"
echo "=== rss done ==="
