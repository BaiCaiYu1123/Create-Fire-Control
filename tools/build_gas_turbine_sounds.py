import os
from pathlib import Path

import numpy as np
import pyloudnorm as pyln
import soundfile as sf


ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "sound_preview_m1a2" / "preview"
OUTPUT = ROOT / "src" / "main" / "resources" / "assets" / "stabilized_turret" / "sounds" / "gas_turbine"
USER_START_IDLE = PREVIEW / "M1A2_user_start_idle.mp3"
TARGET_LOUDNESS = -22.0
MAX_PEAK = 0.89


def read(name: str) -> tuple[np.ndarray, int]:
    data, rate = sf.read(PREVIEW / name, always_2d=True, dtype="float32")
    return data, rate


def segment(data: np.ndarray, rate: int, start: float, end: float) -> np.ndarray:
    return data[round(start * rate) : round(end * rate)].copy()


def trim_silence(data: np.ndarray, rate: int, threshold: float = 0.001) -> np.ndarray:
    loud = np.max(np.abs(data), axis=1)
    indices = np.flatnonzero(loud > threshold)
    if indices.size == 0:
        raise ValueError("Audio segment is silent")
    padding = round(0.08 * rate)
    start = max(0, int(indices[0]) - padding)
    end = min(len(data), int(indices[-1]) + padding)
    return data[start:end].copy()


def fade(data: np.ndarray, rate: int, seconds: float) -> np.ndarray:
    frames = min(round(seconds * rate), len(data) // 2)
    if frames <= 0:
        return data
    ramp = np.sin(np.linspace(0, np.pi / 2, frames, dtype=np.float32)) ** 2
    data[:frames] *= ramp[:, None]
    data[-frames:] *= ramp[::-1, None]
    return data


def make_seamless_loop(data: np.ndarray, rate: int, seconds: float = 0.10) -> np.ndarray:
    frames = min(round(seconds * rate), len(data) // 4)
    if frames <= 0:
        return data
    blend = np.linspace(0.0, 1.0, frames, dtype=np.float32)[:, None]
    seam = data[-frames:] * (1.0 - blend) + data[:frames] * blend
    return np.concatenate((data[frames:-frames], seam), axis=0)


def normalize_loudness(data: np.ndarray, rate: int) -> np.ndarray:
    mono = np.mean(data, axis=1)
    current = pyln.Meter(rate).integrated_loudness(mono)
    if not np.isfinite(current):
        raise ValueError("Audio segment is silent")
    adjusted = data * 10.0 ** ((TARGET_LOUDNESS - current) / 20.0)
    peak = float(np.max(np.abs(adjusted)))
    if peak > MAX_PEAK:
        adjusted *= MAX_PEAK / peak
    return adjusted.astype(np.float32)


def write(name: str, data: np.ndarray, rate: int, loop: bool = False) -> None:
    data = make_seamless_loop(data, rate) if loop else fade(data, rate, 0.035)
    data = normalize_loudness(data, rate)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    sf.write(OUTPUT / f"{name}.ogg", data, rate, format="OGG", subtype="VORBIS")
    info = sf.info(OUTPUT / f"{name}.ogg")
    print(f"{name}: {info.duration:.3f}s, {info.samplerate} Hz, {info.channels} channels")


def main() -> None:
    start_idle, start_idle_rate = sf.read(
        USER_START_IDLE, always_2d=True, dtype="float32")
    run, run_rate = read("M1A2_02_idle_accel_load_decel.wav")
    stop, stop_rate = read("M1A2_03_stop.wav")
    if run_rate != stop_rate:
        raise ValueError("Source sample rates do not match")
    if len(start_idle) <= round(15.0 * start_idle_rate):
        raise ValueError("User start/idle recording is shorter than 15 seconds")

    # The supplied recording contains the complete start in its first
    # 15 seconds and sustained idle afterwards.  Fifteen seconds of the idle
    # section is enough to avoid an obvious short repeat while keeping the
    # packaged sound reasonably small.
    write("start", segment(start_idle, start_idle_rate, 0.0, 15.0),
          start_idle_rate)
    idle_end = min(30.0, len(start_idle) / start_idle_rate)
    write("idle", segment(start_idle, start_idle_rate, 15.0, idle_end),
          start_idle_rate, loop=True)

    stopped = trim_silence(stop, stop_rate)
    # Skip the isolated high-energy transient at 4.5 seconds.  It was almost
    # 12 dB louder than the sustained acceleration layer and made every key
    # press sound like a volume spike followed by a fade-out.
    write("accel", segment(run, run_rate, 5.5, 11.0), run_rate)
    write("run", segment(run, run_rate, 11.5, 14.5), run_rate, loop=True)
    write("decel", segment(run, run_rate, 15.0, 25.0), run_rate)
    write("stop", stopped, stop_rate)


if __name__ == "__main__":
    main()
