"""Create positional mono copies without changing any supplied 99A Ogg file."""
import hashlib
import json
import math
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / '.tools/python'))
import numpy as np
import soundfile as sf

audio = root / 'src/main/resources/assets/create_fire_control/sounds/engine/ztz99a'
out = audio / 'spatial'
out.mkdir(exist_ok=True)
report = {}
for source in sorted(audio.glob('*.ogg')):
    original, rate = sf.read(source, always_2d=True)
    # Match the PCM range actually played by Minecraft. No loudness normalization.
    original_pcm = np.clip(original, -1, 1)
    mono = original_pcm.mean(axis=1)
    target = out / source.name
    sf.write(target, mono, rate, format='OGG', subtype='VORBIS', compression_level=0.0)
    decoded, decoded_rate = sf.read(target, always_2d=True)
    if decoded.shape[1] != 1 or decoded_rate != rate or len(decoded) != len(original):
        raise ValueError('Spatial copy must retain sample rate and duration: ' + source.name)
    played = np.clip(decoded, -1, 1)
    original_rms = float(np.sqrt(np.mean(original_pcm ** 2)))
    mono_rms = float(np.sqrt(np.mean(played ** 2)))
    original_peak = float(abs(original_pcm).max())
    mono_peak = float(abs(played).max())
    # Static calibration preserves source loudness up to the original peak budget.
    trim = 20 * math.log10(min(original_rms / mono_rms, original_peak / mono_peak))
    report[source.stem] = {'originalSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'channels': 1, 'frames': len(decoded), 'rate': rate, 'trimDb': trim,
        'originalRms': original_rms, 'spatialRms': mono_rms,
        'originalPeak': original_peak, 'spatialPeak': mono_peak}

sounds_path = root / 'src/main/resources/assets/create_fire_control/sounds.json'
sounds = json.loads(sounds_path.read_text(encoding='utf-8'))
for sample in report:
    definition = sounds['engine.ztz99a.' + sample]['sounds'][0]
    definition['name'] = 'create_fire_control:engine/ztz99a/spatial/' + sample
sounds_path.write_text(json.dumps(sounds, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

java = ['package com.hooya.stabilizedturret.client;',
    '/** Measured mono-copy calibration. Native OpenAL panning handles the listener left/right channels. */',
    'public final class Ztz99aDieselSpatialAudio {',
    '    public static float trimDb(String sample) {', '        return switch(sample) {']
for sample, item in report.items():
    java.append(f'            case "{sample}" -> {item["trimDb"]:.8f}F;')
java += ['            default -> 0;', '        };', '    }',
    '    private Ztz99aDieselSpatialAudio() {}', '}']
(root / 'src/main/java/com/hooya/stabilizedturret/client/Ztz99aDieselSpatialAudio.java').write_text('\n'.join(java)+'\n', encoding='utf-8')
(root / 'src/main/resources/assets/create_fire_control/ztz99a/spatial_audio_manifest.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
print(f'Created {len(report)} positional mono copies; originals preserved; static trim range '
      f'{min(x["trimDb"] for x in report.values()):.4f} .. {max(x["trimDb"] for x in report.values()):.4f} dB')
