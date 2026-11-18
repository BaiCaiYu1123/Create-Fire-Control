"""Decode every supplied Vorbis file without rewriting it; derive gain/duration metadata."""
import json, math, pathlib, sys
sys.path.insert(0, str(pathlib.Path('.tools/python').resolve()))
import soundfile as sf
import numpy as np

root = pathlib.Path('src/main/resources/assets/create_fire_control/sounds/engine/ztz99a')
records = {}
for p in sorted(root.glob('*.ogg')):
    info = sf.info(p)
    assert info.format == 'OGG' and info.subtype == 'VORBIS'
    data, rate = sf.read(p, always_2d=True)
    assert np.isfinite(data).all()
    rms = float(np.sqrt(np.mean(data ** 2)))
    records[p.stem] = dict(seconds=len(data)/rate, rate=rate, channels=info.channels,
                          rms=rms, peak=float(np.max(np.abs(data))),
                          seam_delta=float(np.max(np.abs(data[-1]-data[0]))))
assert len(records) == 26
pathlib.Path('reference/ztz99a/audio_analysis.json').write_text(json.dumps(records, indent=2)+'\n')
java = ['package com.hooya.stabilizedturret.client;',
        '/** Measured from the original Vorbis files; filenames do not imply an RPM band. */',
        'public final class Ztz99aAudioCalibration {',
        '    public static float duration(String sample) {', '        return switch (sample) {']
for name, r in records.items():
    java.append(f'            case "{name}" -> {r["seconds"]:.7f}F;')
java += ['            default -> throw new IllegalArgumentException(sample);', '        };', '    }',
         '    public static float gainDb(String sample) {', '        return switch (sample) {']
for name, r in records.items():
    # Independent source calibration: -18.42 dBFS RMS, with a -4.44 dBFS peak cap.
    # No 0.5 amplitude floor: the loaded recordings need more than 6 dB attenuation.
    gain = min(2., .12/max(r['rms'], .001), .6/max(r['peak'], .001))
    r['calibration_db'] = 20*math.log10(gain)
    java.append(f'            case "{name}" -> {r["calibration_db"]:.7f}F;')
java += ['            default -> throw new IllegalArgumentException(sample);', '        };', '    }',
         '    public static float peak(String sample) {', '        return switch (sample) {']
for name, r in records.items():
    java.append(f'            case "{name}" -> {r["peak"]:.7f}F;')
java += ['            default -> throw new IllegalArgumentException(sample);', '        };', '    }',
         '    public static float rms(String sample) {', '        return switch (sample) {']
for name, r in records.items():
    java.append(f'            case "{name}" -> {r["rms"]:.7f}F;')
java += ['            default -> throw new IllegalArgumentException(sample);', '        };', '    }',
         '    public static final String[] SAMPLES = {' + ', '.join('"'+n+'"' for n in records) + '};',
         '    private Ztz99aAudioCalibration() {}', '}']
pathlib.Path('src/main/java/com/hooya/stabilizedturret/client/Ztz99aAudioCalibration.java').write_text('\n'.join(java)+'\n')
pathlib.Path('reference/ztz99a/audio_analysis.json').write_text(json.dumps(records, indent=2)+'\n')
print('Decoded all 26 original samples; start duration:', records['engine_start']['seconds'])
