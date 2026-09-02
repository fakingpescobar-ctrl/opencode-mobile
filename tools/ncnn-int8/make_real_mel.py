# Генерирует реалистичный log-mel вход (128x3000) через правильный whisper fbank
# конвейер (тот же FbankDirectCallWrapper, что в export_ncnn.py) из синтетического
# речеподобного wav. Выход: mel_input.npy (float32, C-order, shape (3000,128)).
import numpy as np
import torch
import torchaudio
from transformers import WhisperFeatureExtractor
import warnings
warnings.filterwarnings("ignore")

SR = 16000
n_samp = int(SR * 30.0)
t = np.arange(n_samp) / SR
y = np.zeros(n_samp)
f0 = 110.0
for (t0, t1, f1, f2, amp) in [
    (0.5, 1.2, 550.0, 950.0, 0.9), (1.5, 2.3, 700.0, 1300.0, 0.8),
    (2.6, 3.4, 400.0, 800.0, 1.0), (3.8, 4.6, 600.0, 1150.0, 0.7),
    (5.0, 6.0, 750.0, 1500.0, 0.85),
]:
    seg = (t >= t0) & (t < t1)
    env = np.exp(-((t - (t0 + t1) / 2) / (0.45 * (t1 - t0))) ** 2)
    y[seg] += amp * env[seg] * (np.sin(2*np.pi*f1*t[seg]) + 0.6*np.sin(2*np.pi*f2*t[seg]) + 0.3*np.sin(2*np.pi*f0*t[seg]))
y *= 0.5 + 0.5*np.sin(2*np.pi*4.0*t)
rng = np.random.default_rng(42)
y += 0.02*rng.standard_normal(n_samp)
y = y / (np.max(np.abs(y)) + 1e-9) * 0.6
y = np.clip(y, -1, 1)
wav = torch.from_numpy(y.astype(np.float32)).unsqueeze(0)

model_name = "whisper-large-v3-turbo"
fx = WhisperFeatureExtractor.from_pretrained('openai/' + model_name)

window = torch.hann_window(fx.n_fft)
power = torchaudio.functional.spectrogram(wav, pad=0, window=window, n_fft=fx.n_fft,
    hop_length=fx.hop_length, win_length=fx.n_fft, power=2.0, normalized=False,
    center=True, pad_mode="reflect")
# power: (1, n_fft/2+1=201, time)
mel_f = torch.from_numpy(fx.mel_filters.astype(np.float32))  # (201, 128) as stored
mel_spec = torch.matmul(mel_f.t(), power.squeeze(0))          # (128, time)
log_spec = torch.clamp(mel_spec, min=1e-10).log10()
log_spec = torch.maximum(log_spec, log_spec.max() - 8.0)
mel = ((log_spec + 4.0) / 4.0).numpy()          # (128, T)
mel = mel[:, :3000]                             # drop last

assert mel.shape[0] == 128 and mel.shape[1] == 3000, mel.shape
mel_t = np.ascontiguousarray(mel.T, dtype=np.float32)  # (3000,128)
np.save("mel_input.npy", mel_t)
print("saved", mel_t.shape, "range", float(mel_t.min()), float(mel_t.max()), "mean", float(mel_t.mean()))