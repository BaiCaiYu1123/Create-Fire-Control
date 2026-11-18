# Abrams 七种工况数值对比

来自真实 Java 控制器/混音核与声音实例乘法路径的导出；不是客户端声卡录音。
条件：距离 0、GUI 100%、Minecraft Master/BLOCKS 100%、包络 1、climb 0、捆绑 JSON 音量 1。
总 RMS = sqrt(sum((每轨最终线性 Gain × 实测该素材 RMS)^2))；假设音轨不相关，未把 Gain 相加当响度。
包含 Legacy 未选轨的 0.0001 floor；Reference 未选轨为 0（−∞ dB）。
Reference 的 RPM/load 交叉补偿均为 0 dB。静态 ×4、0.15、Master −8 dB 与 calibration=0 在两模式中相同。

| RPM%/SU% | Legacy RMS dBFS | Reference RMS dBFS | 差值 dB | Legacy RPM/load 交叉补偿 dB | Reference load Bus+Gain dB |
|---|---:|---:|---:|---:|---:|
| 0/0 | -27.520 | -36.175 | -8.655 | 0.000/0.000 | -16.020 |
| 10/0 | -26.022 | -30.131 | -4.108 | 0.000/0.000 | -16.020 |
| 33/25 | -25.930 | -30.485 | -4.555 | 0.653/0.000 | -14.539 |
| 50/50 | -27.840 | -31.334 | -3.495 | 0.000/0.000 | -13.033 |
| 66/75 | -27.028 | -31.348 | -4.320 | 0.088/2.414 | -11.526 |
| 80/100 | -27.061 | -30.526 | -3.465 | 0.000/0.000 | -10.020 |
| 100/100 | -27.061 | -30.526 | -3.465 | 0.000/0.000 | -10.020 |

## RPM 0% / SU proxy 0%

| 音轨 | 素材 RMS dBFS | Legacy 最终 Gain dB | Reference 最终 Gain dB | Reference−Legacy dB |
|---|---:|---:|---:|---:|
| engine_00 | -24.134 | -14.457 | -14.457 | 0.000 |
| engine_33 | -14.161 | -84.437 | −∞ | −∞ |
| engine_66 | -15.726 | -84.437 | −∞ | −∞ |
| engine_99 | -16.357 | -84.437 | −∞ | −∞ |
| engine_00_load | -11.417 | -16.457 | -28.457 | -12.000 |
| engine_33_load | -11.271 | -84.437 | −∞ | −∞ |
| engine_66_load | -13.375 | -84.437 | −∞ | −∞ |
| engine_99_load | -11.946 | -84.437 | −∞ | −∞ |

Legacy/Reference 总 RMS 估计：-27.520/-36.175 dBFS。

## RPM 10% / SU proxy 0%

| 音轨 | 素材 RMS dBFS | Legacy 最终 Gain dB | Reference 最终 Gain dB | Reference−Legacy dB |
|---|---:|---:|---:|---:|
| engine_00 | -24.134 | -84.437 | −∞ | −∞ |
| engine_33 | -14.161 | -16.457 | -16.457 | 0.000 |
| engine_66 | -15.726 | -84.437 | −∞ | −∞ |
| engine_99 | -16.357 | -84.437 | −∞ | −∞ |
| engine_00_load | -11.417 | -16.457 | -28.457 | -12.000 |
| engine_33_load | -11.271 | -84.437 | −∞ | −∞ |
| engine_66_load | -13.375 | -84.437 | −∞ | −∞ |
| engine_99_load | -11.946 | -84.437 | −∞ | −∞ |

Legacy/Reference 总 RMS 估计：-26.022/-30.131 dBFS。

## RPM 33% / SU proxy 25%

| 音轨 | 素材 RMS dBFS | Legacy 最终 Gain dB | Reference 最终 Gain dB | Reference−Legacy dB |
|---|---:|---:|---:|---:|
| engine_00 | -24.134 | -84.437 | −∞ | −∞ |
| engine_33 | -14.161 | -16.487 | -17.141 | -0.653 |
| engine_66 | -15.726 | -38.062 | -38.715 | -0.653 |
| engine_99 | -16.357 | -84.437 | −∞ | −∞ |
| engine_00_load | -11.417 | -84.437 | −∞ | −∞ |
| engine_33_load | -11.271 | -16.457 | -26.976 | -10.519 |
| engine_66_load | -13.375 | -84.437 | −∞ | −∞ |
| engine_99_load | -11.946 | -84.437 | −∞ | −∞ |

Legacy/Reference 总 RMS 估计：-25.930/-30.485 dBFS。

## RPM 50% / SU proxy 50%

| 音轨 | 素材 RMS dBFS | Legacy 最终 Gain dB | Reference 最终 Gain dB | Reference−Legacy dB |
|---|---:|---:|---:|---:|
| engine_00 | -24.134 | -84.437 | −∞ | −∞ |
| engine_33 | -14.161 | -84.437 | −∞ | −∞ |
| engine_66 | -15.726 | -16.457 | -16.457 | 0.000 |
| engine_99 | -16.357 | -84.437 | −∞ | −∞ |
| engine_00_load | -11.417 | -84.437 | −∞ | −∞ |
| engine_33_load | -11.271 | -84.437 | −∞ | −∞ |
| engine_66_load | -13.375 | -16.457 | -25.470 | -9.013 |
| engine_99_load | -11.946 | -84.437 | −∞ | −∞ |

Legacy/Reference 总 RMS 估计：-27.840/-31.334 dBFS。

## RPM 66% / SU proxy 75%

| 音轨 | 素材 RMS dBFS | Legacy 最终 Gain dB | Reference 最终 Gain dB | Reference−Legacy dB |
|---|---:|---:|---:|---:|
| engine_00 | -24.134 | -84.437 | −∞ | −∞ |
| engine_33 | -14.161 | -84.437 | −∞ | −∞ |
| engine_66 | -15.726 | -16.457 | -16.545 | -0.088 |
| engine_99 | -16.357 | -58.046 | -58.134 | -0.088 |
| engine_00_load | -11.417 | -84.437 | −∞ | −∞ |
| engine_33_load | -11.271 | -84.437 | −∞ | −∞ |
| engine_66_load | -13.375 | -24.267 | -34.187 | -9.920 |
| engine_99_load | -11.946 | -17.243 | -27.163 | -9.920 |

Legacy/Reference 总 RMS 估计：-27.028/-31.348 dBFS。

## RPM 80% / SU proxy 100%

| 音轨 | 素材 RMS dBFS | Legacy 最终 Gain dB | Reference 最终 Gain dB | Reference−Legacy dB |
|---|---:|---:|---:|---:|
| engine_00 | -24.134 | -84.437 | −∞ | −∞ |
| engine_33 | -14.161 | -84.437 | −∞ | −∞ |
| engine_66 | -15.726 | -84.437 | −∞ | −∞ |
| engine_99 | -16.357 | -16.457 | -16.457 | 0.000 |
| engine_00_load | -11.417 | -84.437 | −∞ | −∞ |
| engine_33_load | -11.271 | -84.437 | −∞ | −∞ |
| engine_66_load | -13.375 | -84.437 | −∞ | −∞ |
| engine_99_load | -11.946 | -16.457 | -22.457 | -6.000 |

Legacy/Reference 总 RMS 估计：-27.061/-30.526 dBFS。

## RPM 100% / SU proxy 100%

| 音轨 | 素材 RMS dBFS | Legacy 最终 Gain dB | Reference 最终 Gain dB | Reference−Legacy dB |
|---|---:|---:|---:|---:|
| engine_00 | -24.134 | -84.437 | −∞ | −∞ |
| engine_33 | -14.161 | -84.437 | −∞ | −∞ |
| engine_66 | -15.726 | -84.437 | −∞ | −∞ |
| engine_99 | -16.357 | -16.457 | -16.457 | 0.000 |
| engine_00_load | -11.417 | -84.437 | −∞ | −∞ |
| engine_33_load | -11.271 | -84.437 | −∞ | −∞ |
| engine_66_load | -13.375 | -84.437 | −∞ | −∞ |
| engine_99_load | -11.946 | -16.457 | -22.457 | -6.000 |

Legacy/Reference 总 RMS 估计：-27.061/-30.526 dBFS。

