"""Render the exported Java mix timeline with bundled loop PCM (not a game recording)."""
import argparse
import csv
import sys
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--trace", type=Path, default=Path("build/reports/engine-sound-128-256-mix.csv"))
parser.add_argument("--output", type=Path, default=Path("build/reports/engine-sound-128-256-audio"))
parser.add_argument("--packages", type=Path, default=Path(".diagnostics/python-deps"))
args = parser.parse_args()
sys.path.insert(0, str(args.packages.resolve()))
import numpy as np
import soundfile as sf

root = Path(__file__).resolve().parents[1]
names = ["engine_00", "engine_33", "engine_66", "engine_99",
         "engine_00_load", "engine_33_load", "engine_66_load", "engine_99_load"]
sources = []
for name in names:
    pcm, rate = sf.read(root / "src/main/resources/assets/create_fire_control/sounds/engine/abrams" / f"{name}.ogg")
    assert pcm.ndim == 1 and rate == 44100
    sources.append(pcm)
with args.trace.open(encoding="utf-8", newline="") as stream:
    rows = list(csv.DictReader(stream))
scenarios = sorted({row["scenario"] for row in rows})
ticks = max(int(row["tick"]) for row in rows) + 1
samples_per_tick = rate // 20
args.output.mkdir(parents=True, exist_ok=True)
for scenario in scenarios:
    gains = np.zeros((ticks, 8))
    pitches = np.ones((ticks, 8))
    for row in rows:
        if row["scenario"] == scenario:
            tick, layer = int(row["tick"]), int(row["layer"])
            gains[tick, layer] = float(row["gain"])
            pitches[tick, layer] = np.clip(float(row["pitch"]), .5, 2)
    mixed = np.zeros(ticks * samples_per_tick)
    for layer, source in enumerate(sources):
        speed = np.repeat(pitches[:, layer], samples_per_tick)
        phase = np.concatenate(([0], np.cumsum(speed[:-1]))) % len(source)
        padded = np.append(source, source[0])
        pcm = np.interp(phase, np.arange(len(padded)), padded)
        # UI 150%, loop 4x, common engine volume .15; zero distance/category 100%.
        mixed += pcm * np.repeat(gains[:, layer], samples_per_tick) * .9
    assert np.max(np.abs(mixed)) < 1, "Render must not clip or normalize scenarios separately"
    output = args.output / f"{scenario}-128-256.wav"
    sf.write(output, mixed, rate, subtype="PCM_16")
    print(f"{output}: {len(mixed)/rate:g}s, peak {np.max(np.abs(mixed)):.4f}")
print("Idle 0-5s; held behavior 5-17s; released 17-22s. Same assets, same gain, continuous loop phases.")
