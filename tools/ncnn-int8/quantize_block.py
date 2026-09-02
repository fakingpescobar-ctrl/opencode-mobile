# quantize_block.py
# Конвертер whisper encoder: fp32/fp16 ncnn bin -> block-wise weight 8-bit
# quantization (NCNN_WEIGHT_QUANT, term=800 = 8bit, format0, block_size=32,
# fp32-активации). Квантуются ТОЛЬКО Gemm и MultiHeadAttention веса; прочие
# слои перезаписываются байт-в-байт.
#
# Реалии bin (проверено эмпирически + по исходникам клона ncnn):
#   type0 веса в fp32-бине лежат как tag(4B) + payload. ЗДЕСЬ все веса fp16:
#     tag = 0x01306B47 (fp16, n*2 bytes) | 0x0002C056 (fp32, n*4 bytes).
#   type1 (biases/scales/LayerNorm/MemoryData) - raw, БЕЗ тега (n*4 f32).
#   MemoryData грузится load_type=1 -> raw f32.
#   Порядок чтения слоёв = порядок строк param.
#
# Порядок load_model (важно для перепаковки):
#   Gemm: [constB] B(type0) -> [constC] C(type0) -> B_quantize_scales(type1)
#   MHA : qW -> qB -> kW -> kB -> vW -> vB -> outW -> outB
#         -> q_sc -> k_sc -> v_sc -> out_sc   (все type1)
# Печатает в stdout: "read <n> bytes ok", "packed", "wrote".

import sys, struct
import numpy as np

FP32_TAG = 0x0002C056
FP16_TAG = 0x01306B47
BLOCK = 32                      # term=800 -> block_size_code=0 -> 32

def parse_param(path):
    with open(path, encoding='utf-8') as f:
        lines = [ln.rstrip('\n') for ln in f if ln.strip()]
    nlayers = int(lines[1].split()[0])
    layers = []
    for k in range(2, 2 + nlayers):
        p = lines[k].split()
        it = iter(p)
        typ = next(it); name = next(it)
        nb = int(next(it)); nt = int(next(it))
        bottoms = [next(it) for _ in range(nb)]
        tops = [next(it) for _ in range(nt)]
        params = {}
        for tok in it:
            kk, vv = tok.split('=', 1)
            params[int(kk)] = float(vv) if '.' in vv else int(vv)
        layers.append(dict(type=typ, name=name, bottoms=bottoms, tops=tops,
                           params=params, line=lines[k]))
    return layers

class Reader:
    def __init__(self, path):
        self.f = open(path, 'rb')
        self.read = 0
    def raw(self, n):
        b = self.f.read(n)
        if len(b) != n:
            raise EOFError("short %d (got %d, read=%d)" % (n, len(b), self.read))
        self.read += n
        return b
    def f32raw(self, n):
        return np.frombuffer(self.raw(n * 4), dtype='<f4').copy()
    def f32tag(self, n):
        tag = struct.unpack('<I', self.raw(4))[0]
        if tag == FP16_TAG:
            return np.frombuffer(self.raw(n * 2), dtype='<f2').astype(np.float32), tag
        if tag == FP32_TAG:
            return np.frombuffer(self.raw(n * 4), dtype='<f4').astype(np.float32), tag
        raise ValueError("unknown tag %X at read %d, n=%d" % (tag, self.read, n))
    def close(self): self.f.close()

def pack_fp16(arr):
    return arr.astype(np.float16).tobytes()

def quant_scales(w, K, N, inv=False):
    """block-wise symmetric quant. inv=True -> писать descale=1/scale (127/max)."""
    bc = (K + BLOCK - 1) // BLOCK
    scales = np.empty(bc * N, dtype='<f4')
    q8 = bytearray(K * N)
    w = w.reshape(N, K)          # row j = вес-строка j, K эл-тов
    for j in range(N):
        row = w[j]
        for g in range(bc):
            k0 = g * BLOCK; ke = min(K, k0 + BLOCK)
            mx = np.abs(row[k0:ke]).max() if ke > k0 else 0.0
            s = mx / 127.0 if mx > 1e-8 else 1e-8
            scales[j * bc + g] = (1.0 / s) if inv else s
            q = np.clip(np.round(row[k0:ke] / s), -127, 127).astype(np.int8)
            q8[j * K + k0: j * K + ke] = q.tobytes()
    return bytes(q8), scales.tobytes()

def main():
    args = sys.argv[1:]
    if '--verify' in args:
        args.remove('--verify'); verify = True
    else:
        verify = False
    inv = '--inv' in args
    if inv:
        args.remove('--inv')
    in_param, in_bin, out_pfx = args[0], args[1], args[2]
    layers = parse_param(in_param)
    r = Reader(in_bin)

    # ----------------------------------------------------------------- reading
    # сохраняем по слоям: список блоков ("fb", bytes) копируемых без изменений
    # OR ("qgemm"/"qmha", dict) для квантования
    plan = []
    for L in layers:
        t = L['type']; p = L['params']; nm = L['name']
        if t == 'Convolution1D':
            wd = int(p.get(6, 0)); w, tag = r.f32tag(wd)
            blocks = [('fb', struct.pack('<I', tag) + ((w.astype(np.float16).tobytes()) if tag == FP16_TAG else w.tobytes()))]
            if p.get(5, 0) == 1:
                b = r.f32raw(int(p.get(0, 0)))
                blocks.append(('fb', b.tobytes()))
            plan.append((nm, 'copy', blocks))
        elif t == 'LayerNorm':
            n = int(p.get(0, 0))
            g = r.f32raw(n); b = r.f32raw(n)
            plan.append((nm, 'copy', [('fb', g.tobytes()), ('fb', b.tobytes())]))
        elif t == 'MemoryData':
            n = int(p.get(0, 0)) * int(p.get(1, 0)) * (int(p.get(11, 0)) or 1) * (int(p.get(2, 0)) or 1)
            d = r.f32raw(n)
            plan.append((nm, 'copy', [('fb', d.tobytes())]))
        elif t == 'Gemm':
            K = int(p.get(9, 0)); N = int(p.get(8, 0))
            q = {}
            q['K'] = K; q['N'] = N
            if p.get(5, 1) == 1:                       # constantB
                B, btag = r.f32tag(N * K); q['B'] = (B, btag)
            if p.get(6, 1) == 1 and p.get(10, -1) != -1:  # constantC
                bt = int(p.get(10, 0)); M = int(p.get(7, 0))
                n = {0: 1, 1: M, 2: N, 3: N * M, 4: N}.get(bt, 1)
                C, ctag = r.f32tag(n); q['C'] = (C, ctag)
            plan.append((nm, 'qgemm', q))
        elif t == 'MultiHeadAttention':
            embed = int(p.get(0, 0))
            wds = int(p.get(2, 0))                      # weight_data_size
            qdim = wds // embed if wds else embed
            kdim = embed; vdim = embed                   # turbo: совпадают с embed
            q = {}
            q['embed'] = embed; q['qdim'] = qdim
            for lbl, rdim in (('Q', embed), ('K', embed), ('V', embed)):
                W, tag = r.f32tag(qdim * rdim)
                B = r.f32raw(rdim)
                q[lbl] = (W, B)
            OW, otag = r.f32tag(embed * qdim)
            OB = r.f32raw(qdim)
            q['O'] = (OW, OB)
            plan.append((nm, 'qmha', q))
        else:
            plan.append((nm, 'copy', []))
    r.close()
    if verify:
        nbytes = sum(len(bl[1]) for _, k, bl in plan for bl in bl if bl[0] == 'fb')
        for nm, k, bl in plan:
            if k != 'copy':
                print('%-22s %-25s blocks=%s' % (nm, k, len(bl)))
        print('read total bytes:', r.read, 'copied-fb bytes:', nbytes)
        return

    # ----------------------------------------------------------------- writing
    nchunk_new = []
    for nm, kind, bl in plan:
        if kind == 'copy':
            for _, by in bl:
                nchunk_new.append(by)
            continue
        if kind == 'qgemm':
            q = bl
            K = q['K']; N = q['N']
            B = q.get('B')
            if B is None:
                continue
            Bf = B[0]
            i8, sc = quant_scales(Bf, K, N, inv)
            nchunk_new.append(i8)                       # B type8 raw
            if 'C' in q:
                C, ctag = q['C']
                nchunk_new.append(struct.pack('<I', ctag))
                nchunk_new.append(pack_fp16(C) if ctag == FP16_TAG else C.tobytes())
            nchunk_new.append(sc)                       # scales type1 raw
            continue
        if kind == 'qmha':
            q = bl
            embed = q['embed']; qdim = q['qdim']
            out = []
            for lbl, rdim in (('Q', embed), ('K', embed), ('V', embed)):
                W, B = q[lbl]
                i8, sc = quant_scales(W, qdim, rdim, inv)
                nchunk_new.append(i8)
                nchunk_new.append(B.tobytes())
                out.append(sc)
            OW, OB = q['O']
            i8out, scout = quant_scales(OW, embed, qdim, inv)
            nchunk_new.append(i8out); nchunk_new.append(OB.tobytes())
            for sc in (out + [scout]):
                nchunk_new.append(sc)

    with open(out_pfx + '.ncnn.bin', 'wb') as f:
        for by in nchunk_new:
            f.write(by)
    print('wrote', out_pfx + '.ncnn.bin', 'total', sum(len(b) for b in nchunk_new), 'bytes')

if __name__ == '__main__':
    main()