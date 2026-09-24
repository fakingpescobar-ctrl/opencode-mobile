#!/usr/bin/env python3
"""Генерирует бенч-набор wav для STT (PR5): тишина / шум / речеподобный тон / en-речь.

Выход: app/src/androidTest/assets/bench/{silence,noise,tone,jfk,wav} (+ ru.wav при --ru).

Все файлы: 16 кГц, mono, PCM16 — формат, который ест WhisperTranscribeService
(FloatArray -1..1). jfk.wav копируется из локального whisper.cpp (en-речь,
11 с). Для русской речи положите свой wav и передайте --ru <путь> (или просто
поместите ru.wav в выходную папку).

Запуск:
    python tools/gen_bench_wavs.py
    python tools/gen_bench_wavs.py --ru C:/path/to/my_ru_speech.wav
"""
import argparse
import shutil
import struct
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

SR = 16000


def write_wav(path: Path, data: np.ndarray) -> None:
    """PCM16 mono 16k из float -1..1."""
    pcm = np.clip(data, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(f"  {path.name}: {len(data) / SR:.1f}s, {path.stat().st_size} bytes")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parent.parent / "app" / "src" / "androidTest" / "assets" / "bench"),
        help="выходная папка (assets/bench by default)",
    )
    parser.add_argument("--ru", default=None, help="путь к своему русскому wav (16k mono)")
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    rng = np.random.default_rng(42)

    # 1. Тишина: почти нули + крошечный цифровой шум (реальный микрофон не даёт 0.0).
    n = int(SR * 1.5)
    silence = 0.003 * rng.standard_normal(n)
    write_wav(out / "silence.wav", silence)

    # 2. Белый шум (диагностика: модель обязана вернуть пустоту, а не галлюцинацию).
    noise = 0.2 * rng.standard_normal(n)
    write_wav(out / "noise.wav", noise)

    # 3. Речеподобный AM-тон (паттерн make_real_mel.py): гарантированно «похож на речь»
    #    для пайплайна, но без смысла — проверка, что движок не петляет на мусоре.
    n3 = int(SR * 3.0)
    t = np.arange(n3) / SR
    y = np.zeros(n3)
    f0 = 110.0
    for t0, t1, f1, f2, amp in [
        (0.3, 0.8, 550.0, 950.0, 0.9),
        (1.0, 1.5, 700.0, 1300.0, 0.8),
        (1.8, 2.4, 400.0, 800.0, 1.0),
        (2.6, 2.9, 600.0, 1150.0, 0.7),
    ]:
        seg = (t >= t0) & (t < t1)
        env = np.exp(-((t - (t0 + t1) / 2) / (0.45 * (t1 - t0))) ** 2)
        y[seg] += amp * env[seg] * (
            np.sin(2 * np.pi * f1 * t[seg]) + 0.6 * np.sin(2 * np.pi * f2 * t[seg]) + 0.3 * np.sin(2 * np.pi * f0 * t[seg])
        )
    y *= 0.5 + 0.5 * np.sin(2 * np.pi * 4.0 * t)
    y += 0.02 * rng.standard_normal(n3)
    y /= np.max(np.abs(y)) + 1e-9
    write_wav(out / "tone.wav", y)

    # 4. En-речь: jfk.wav из локального клона whisper.cpp (11 с).
    # 4. En-речь: jfk.wav из локального клона whisper.cpp (11 с).
    jfk = Path(r"C:\Projects\whisper.cpp\samples\jfk.wav")
    jfk_track = None
    if jfk.exists():
        shutil.copy2(jfk, out / "jfk.wav")
        print(f"  jfk.wav: {jfk.stat().st_size} bytes (скопирован из whisper.cpp)")
    else:
        print("  jfk.wav ПРОПУЩЕН: C:\\Projects\\whisper.cpp\\samples\\jfk.wav не найден", file=sys.stderr)

    # 4b. Длинная речь для ЭКСП-5 (чанкинг): 3×jfk с паузами ~2с.
    #     Итог ~35c > 30с — ncnn-encoder в одиночку молча обрезал бы хвост
    #     (extract_fbank_feature фиксирован на 480000 сэмплов). Сегментер с VAD
    #     должен разбить на 3 высказывания и распознать ВСЕ.
    if jfk.exists():
        with wave.open(str(jfk), "rb") as w:
            jfk_data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32767.0
        gap = 0.02 * rng.standard_normal(int(SR * 2.0))  # «комнатная тишина» с лёгким шумом
        long_data = np.concatenate([jfk_data, gap, jfk_data, gap, jfk_data])
        write_wav(out / "long.wav", long_data)

    # 5. Русская речь (опционально): свой файл или --ru.
    if args.ru:
        ru = Path(args.ru)
        if ru.exists() and ru.suffix.lower() == ".wav":
            shutil.copy2(ru, out / "ru.wav")
            print(f"  ru.wav: {ru.stat().st_size} bytes (скопирован из {ru})")
        else:
            print(f"  ru.wav ПРОПУЩЕН: {ru} не существует или не .wav", file=sys.stderr)

    print(f"\nГотово: {out} — закину в APK (androidTest assets).")
    return 0


if __name__ == "__main__":
    sys.exit(main())