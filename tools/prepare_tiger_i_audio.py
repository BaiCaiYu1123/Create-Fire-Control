"""Audit supplied exterior Vorbis without changing originals; generate measured metadata."""
import hashlib, json, math, pathlib, sys, zipfile
sys.path.insert(0, str(pathlib.Path('.tools/python').resolve()))
import numpy as np
import soundfile as sf

archive = pathlib.Path(sys.argv[1])
reference = pathlib.Path('reference/tiger_i')
original = reference / 'original'
original.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as z:
    for entry in z.infolist():
        if entry.filename.lower().endswith('.ogg'):
            name = pathlib.PurePosixPath(entry.filename).name
            assert '_interior' not in name
            (original / name).write_bytes(z.read(entry))
records = {}
for path in sorted(original.glob('*.ogg')):
    info = sf.info(path)
    assert info.format == 'OGG' and info.subtype == 'VORBIS', (path, info)
    data, rate = sf.read(path, always_2d=True)
    assert np.isfinite(data).all()
    mono = data.mean(axis=1)
    cues, confidence = [], []
    for fraction in [.12, .3, .5, .7, .88]:
        size = min(16384, len(mono))
        begin = max(0, min(len(mono)-size, int(len(mono)*fraction)-size//2))
        spectrum = abs(np.fft.rfft(mono[begin:begin+size]*np.hanning(size), 65536))
        frequencies = np.fft.rfftfreq(65536, 1/rate)
        candidates = np.arange(25, 151, .25)
        scores = sum(np.interp(candidates*h, frequencies, spectrum)/h for h in range(1, 5))
        best = int(np.argmax(scores))
        cues.append(float(candidates[best]))
        confidence.append(float(np.clip((scores[best]/max(1e-9, np.median(scores))-1)/3, 0, 1)))
    records[path.stem] = dict(seconds=len(data)/rate, rate=rate, channels=info.channels,
        bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        rms=float(np.sqrt(np.mean(data**2))), peak=float(np.max(np.abs(data))),
        seam_delta=float(np.max(np.abs(data[-1]-data[0]))),
        tonalCueHz=cues, confidence=confidence,
        cueMethod='four-harmonic comb 25-151 Hz; matching cue, not verified engine F0')
assert len(records) == 31
(reference / 'audio_analysis.json').write_text(json.dumps(records, indent=2)+'\n')
java = ['package com.hooya.stabilizedturret.client;',
        '/** Generated measurements of the supplied Tiger I exterior files. */',
        'public final class TigerIAudioCalibration {']
for method, key in [('duration', 'seconds'), ('peak', 'peak'), ('rms', 'rms')]:
    java += [f'    public static float {method}(String sample) {{ return switch(sample) {{']
    for name, record in records.items():
        java += [f'        case "{name}" -> {record[key]:.8f}F;']
    java += ['        default -> throw new IllegalArgumentException(sample);', '    }; }']
java += ['    public static float[] cue(String sample) { return switch(sample) {']
for name, record in records.items():
    java += ['        case "'+name+'" -> new float[]{'+','.join(f'{v:.2f}F' for v in record['tonalCueHz'])+'};']
java += ['        default -> throw new IllegalArgumentException(sample);', '    }; }',
         '    public static float confidence(String sample) { return switch(sample) {']
for name, record in records.items():
    java += [f'        case "{name}" -> {np.mean(record["confidence"]):.6f}F;']
java += ['        default -> throw new IllegalArgumentException(sample);', '    }; }',
    '    public static final String[] SAMPLES = {'+', '.join('"'+n+'"' for n in records)+'};',
    '    private TigerIAudioCalibration() {}', '}']
pathlib.Path('src/main/java/com/hooya/stabilizedturret/client/TigerIAudioCalibration.java').write_text('\n'.join(java)+'\n')
for name, record in records.items():
    print(name, record['channels'], round(record['seconds'],3), round(record['rms'],5), round(record['peak'],5))
