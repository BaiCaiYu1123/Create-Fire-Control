"""Convert to mono Vorbis, matching the two existing loaders' perceived loudness."""
import sys, json, math
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/audio-python'))
import av
sys.path.insert(0, str(ROOT / '.tools/python'))
import soundfile as sf

source = Path(sys.argv[1])
target = ROOT / 'src/main/resources/assets/create_fire_control/sounds/type_10_loader.ogg'

def decode_mono(path):
    samples = []
    resampler = av.AudioResampler(format='fltp', layout='mono', rate=48000)
    with av.open(str(path)) as container:
        for frame in container.decode(audio=0):
            samples.extend(converted.to_ndarray() for converted in resampler.resample(frame))
        samples.extend(converted.to_ndarray() for converted in resampler.resample(None))
    return np.concatenate(samples, axis=1)

def integrated_loudness(pcm):
    """BS.1770 K weighting and 400 ms / 100 ms, absolute + relative gating at 48 kHz."""
    x = pcm[0].astype(np.float64)
    for b, a in [([1.53512485958697, -2.69169618940638, 1.19839281085285],
                  [1., -1.69065929318241, .73248077421585]),
                 ([1., -2., 1.], [1., -1.99004745483398, .99007225036621])]:
        y = np.empty_like(x)
        s1 = s2 = 0.
        for i, value in enumerate(x):
            output = b[0] * value + s1
            s1 = b[1] * value - a[1] * output + s2
            s2 = b[2] * value - a[2] * output
            y[i] = output
        x = y
    summed = np.concatenate(([0.], np.cumsum(x * x)))
    starts = np.arange(0, len(x) - 19200 + 1, 4800)
    energy = (summed[starts + 19200] - summed[starts]) / 19200
    loudness = -.691 + 10 * np.log10(np.maximum(energy, 1e-30))
    relative_gate = -.691 + 10 * np.log10(np.mean(energy[loudness >= -70])) - 10
    return float(-.691 + 10 * np.log10(np.mean(energy[(loudness >= -70) & (loudness >= relative_gate)])))

reference_lufs = {name: integrated_loudness(decode_mono(target.with_name(name + '.ogg')))
                  for name in ['mz_loader', 'az_loader']}
target_lufs = sum(reference_lufs.values()) / len(reference_lufs)
pcm = decode_mono(source)
source_lufs = integrated_loudness(pcm)
gain_db = target_lufs - source_lufs
for attempt in range(3):
    gain = 10 ** (gain_db / 20)
    assert gain < 1, 'This adjustment should lower the supplied recording'
    sf.write(str(target), (pcm * gain).T, 48000, format='OGG', subtype='VORBIS')
    encoded = decode_mono(target)
    output_lufs = integrated_loudness(encoded)
    if abs(output_lufs - target_lufs) < .05:
        break
    gain_db += target_lufs - output_lufs
assert abs(output_lufs - target_lufs) < .15, 'Encoded sound must match the existing loaders'
assert np.abs(encoded).max() < 1, 'Leave headroom after Vorbis decoding'
report = dict(reference_lufs=reference_lufs, target_lufs=target_lufs, source_lufs=source_lufs,
              gain_db=gain_db, gain=gain, output_lufs=output_lufs,
              output_peak_dbfs=20 * math.log10(float(np.abs(encoded).max())))
report_path = ROOT / 'build/type-10-audio-balance.json'
report_path.parent.mkdir(parents=True, exist_ok=True)
report_path.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
with av.open(str(target)) as check:
    stream = check.streams.audio[0]
    assert stream.codec_context.channels == 1
    print(f'{target.name}: {float(stream.duration * stream.time_base):.3f}s, '
          f'{stream.codec_context.sample_rate}Hz, mono Vorbis')
