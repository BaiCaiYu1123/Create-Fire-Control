import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LANG_DIR = ROOT / "src" / "main" / "resources" / "assets" / "create_fire_control" / "lang"

TRANSLATIONS = {
    "zh_cn.json": {
        "message.create_fire_control.gas_turbine.binding_cancelled": "已取消燃气轮机连接",
        "message.create_fire_control.gas_turbine.select_advanced_hub": "请用扳手右键高级无线红石遥控器信号处理器",
        "message.create_fire_control.gas_turbine.invalid_hub": "这里只能连接高级无线红石遥控器信号处理器",
        "message.create_fire_control.gas_turbine.binding_success": "高级信号处理器已连接到燃气轮机",
        "screen.create_fire_control.gas_turbine.title": "M1A2 燃气轮机控制",
        "screen.create_fire_control.gas_turbine.hub_connected": "高级信号处理器：已连接",
        "screen.create_fire_control.gas_turbine.hub_missing": "高级信号处理器：未连接（请先使用扳手连接）",
        "screen.create_fire_control.gas_turbine.drive_speed": "前进/后退转速（RPM）",
        "screen.create_fire_control.gas_turbine.turn_speed": "原地转向转速（RPM）",
        "screen.create_fire_control.gas_turbine.acceleration": "转速加速度（RPM/秒）",
        "screen.create_fire_control.gas_turbine.limit": "所有数值最高 1024 RPM；行进转向时内侧传动杆停止。",
        "screen.create_fire_control.gas_turbine.input.0": "前进",
        "screen.create_fire_control.gas_turbine.input.1": "后退",
        "screen.create_fire_control.gas_turbine.input.2": "左转",
        "screen.create_fire_control.gas_turbine.input.3": "右转",
        "screen.create_fire_control.gas_turbine.invalid_number": "请输入有效的 0–1024 数值",
        "subtitles.create_fire_control.gas_turbine": "M1A2 燃气轮机运转"
    },
    "en_us.json": {
        "message.create_fire_control.gas_turbine.binding_cancelled": "Gas turbine binding cancelled",
        "message.create_fire_control.gas_turbine.select_advanced_hub": "Wrench an advanced wireless controller signal processor",
        "message.create_fire_control.gas_turbine.invalid_hub": "Only an advanced wireless controller signal processor can be connected",
        "message.create_fire_control.gas_turbine.binding_success": "Advanced signal processor connected to gas turbine",
        "screen.create_fire_control.gas_turbine.title": "M1A2 Gas Turbine Control",
        "screen.create_fire_control.gas_turbine.hub_connected": "Advanced signal processor: connected",
        "screen.create_fire_control.gas_turbine.hub_missing": "Advanced signal processor: not connected (use a wrench first)",
        "screen.create_fire_control.gas_turbine.drive_speed": "Forward/reverse speed (RPM)",
        "screen.create_fire_control.gas_turbine.turn_speed": "Pivot-turn speed (RPM)",
        "screen.create_fire_control.gas_turbine.acceleration": "RPM acceleration (RPM/s)",
        "screen.create_fire_control.gas_turbine.limit": "All values are limited to 1024 RPM; steering while moving stops the inside shaft.",
        "screen.create_fire_control.gas_turbine.input.0": "Forward",
        "screen.create_fire_control.gas_turbine.input.1": "Reverse",
        "screen.create_fire_control.gas_turbine.input.2": "Turn left",
        "screen.create_fire_control.gas_turbine.input.3": "Turn right",
        "screen.create_fire_control.gas_turbine.invalid_number": "Enter a valid value from 0 to 1024",
        "subtitles.create_fire_control.gas_turbine": "M1A2 gas turbine runs"
    }
}


def main() -> None:
    for filename, additions in TRANSLATIONS.items():
        path = LANG_DIR / filename
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        data.update(additions)
        path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8"
        )
        print(f"{filename}: {len(additions)} gas turbine translations")


if __name__ == "__main__":
    main()
