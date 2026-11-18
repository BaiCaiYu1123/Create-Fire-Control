"""Compare the real Java mixer exports, using measured PCM RMS per source."""
import argparse
import csv
import json
import math
from pathlib import Path


def db(value):
    return 20 * math.log10(value) if value > 0 else -math.inf


def fmt(value):
    return f"{value:.3f}" if math.isfinite(value) else "−∞"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv-dir", type=Path, required=True)
    parser.add_argument("--audio-audit", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    names = ["engine_00", "engine_33", "engine_66", "engine_99",
             "engine_00_load", "engine_33_load", "engine_66_load", "engine_99_load"]
    audio = json.loads(args.audio_audit.read_text(encoding="utf-8-sig"))
    rms = [10 ** (audio[name]["current"]["rms_dbfs"] / 20) for name in names]
    sheets = {}
    for mode in ("legacy", "fmod_reference"):
        with (args.csv_dir / f"{mode}-mix.csv").open(encoding="utf-8-sig", newline="") as stream:
            sheets[mode] = {(float(r["rpm"]), float(r["load"]), int(r["layer"])): float(r["gain"])
                            for r in csv.DictReader(stream) if r["scenario"] == "requested"}
        assert len(sheets[mode]) == 56, "All seven scenarios and eight layers are required"
    assert (args.csv_dir / "default-mix.csv").read_bytes() == (args.csv_dir / "fmod_reference-mix.csv").read_bytes()
    args.output.mkdir(parents=True, exist_ok=True)
    rows, summary, tables = [], [], []
    for rpm, load in [(0, 0), (10, 0), (33, .25), (50, .5), (66, .75), (80, 1), (100, 1)]:
        gains = {mode: [sheet[rpm, load, i] for i in range(8)] for mode, sheet in sheets.items()}
        totals = {mode: db(math.sqrt(sum((gain * source) ** 2 for gain, source in zip(values, rms))))
                  for mode, values in gains.items()}
        x0 = .0042372881355932203
        bus_db = -6.02 - 10 + 6 * max(0, min(1, (load - x0) / (1 - x0)))
        active_rpm = next(i for i in range(4) if gains["fmod_reference"][i] > 0)
        active_load = next(i for i in range(4, 8) if gains["fmod_reference"][i] > 0)
        # Infer the old compensation from the measured final Java coefficients.
        rpm_comp = db(gains["legacy"][active_rpm]) - db(gains["fmod_reference"][active_rpm])
        load_comp = db(gains["legacy"][active_load]) - db(gains["fmod_reference"][active_load]) - (-4.02 - bus_db)
        if abs(rpm_comp) < .0005:
            rpm_comp = 0.0
        if abs(load_comp) < .0005:
            load_comp = 0.0
        delta_total = totals["fmod_reference"] - totals["legacy"]
        summary.append(f"| {rpm}/{load * 100:g} | {totals['legacy']:.3f} | {totals['fmod_reference']:.3f} | {delta_total:.3f} | {rpm_comp:.3f}/{load_comp:.3f} | {bus_db:.3f} |")
        tables += [f"## RPM {rpm}% / SU proxy {load * 100:g}%", "",
                   "| 音轨 | 素材 RMS dBFS | Legacy 最终 Gain dB | Reference 最终 Gain dB | Reference−Legacy dB |",
                   "|---|---:|---:|---:|---:|"]
        for i, name in enumerate(names):
            a, b = db(gains["legacy"][i]), db(gains["fmod_reference"][i])
            delta = b - a
            tables.append(f"| {name} | {db(rms[i]):.3f} | {fmt(a)} | {fmt(b)} | {fmt(delta)} |")
            rows.append(dict(rpm_pct=rpm, su_load_pct=load * 100, asset=name, source_rms_dbfs=db(rms[i]),
                             legacy_gain=gains["legacy"][i], reference_gain=gains["fmod_reference"][i],
                             legacy_gain_db=a, reference_gain_db=b, delta_gain_db=delta,
                             legacy_total_rms_dbfs=totals["legacy"], reference_total_rms_dbfs=totals["fmod_reference"],
                             delta_total_rms_db=delta_total, legacy_rpm_comp_db=rpm_comp,
                             legacy_load_comp_db=load_comp, reference_load_bus_db=bus_db))
        tables += ["", f"Legacy/Reference 总 RMS 估计：{totals['legacy']:.3f}/{totals['fmod_reference']:.3f} dBFS。", ""]
    with (args.output / "seven-scenarios.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    intro = ["# Abrams 七种工况数值对比", "",
             "来自真实 Java 控制器/混音核与声音实例乘法路径的导出；不是客户端声卡录音。",
             "条件：距离 0、GUI 100%、Minecraft Master/BLOCKS 100%、包络 1、climb 0、捆绑 JSON 音量 1。",
             "总 RMS = sqrt(sum((每轨最终线性 Gain × 实测该素材 RMS)^2))；假设音轨不相关，未把 Gain 相加当响度。",
             "包含 Legacy 未选轨的 0.0001 floor；Reference 未选轨为 0（−∞ dB）。",
             "Reference 的 RPM/load 交叉补偿均为 0 dB。静态 ×4、0.15、Master −8 dB 与 calibration=0 在两模式中相同。", "",
             "| RPM%/SU% | Legacy RMS dBFS | Reference RMS dBFS | 差值 dB | Legacy RPM/load 交叉补偿 dB | Reference load Bus+Gain dB |",
             "|---|---:|---:|---:|---:|---:|"]
    report = "\n".join(intro + summary + [""] + tables) + "\n"
    (args.output / "SEVEN_SCENARIOS.md").write_text(report, encoding="utf-8")
    print("\n".join(summary))


if __name__ == "__main__":
    main()
