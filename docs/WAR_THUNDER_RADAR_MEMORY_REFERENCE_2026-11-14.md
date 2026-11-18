# 战争雷霆雷达失去回波后的记忆与闪烁参数

本轮仅查询参考参数，未修改火控实现。

参考数据来自非官方拆包仓库 `gszabi99/War-Thunder-Datamine`，固定提交 `ecbd610ff9aa0248bee3095db0cf28bde9ea5aee`，提交版本 `2.61.0.17`，时间 `2026-11-13T10:13:55Z`。没有通过游戏录像逐帧测量，也没有取得原生雷达/HUD 模块的内部实现。

## 屏幕目标框闪烁

来源：[radarindication.das，第 45 行起](https://github.com/gszabi99/War-Thunder-Datamine/blob/ecbd610ff9aa0248bee3095db0cf28bde9ea5aee/gui.vromfs.bin_u/reactivegui/radarindication.das#L45)。

`targets_on_screen` 在 `get_radar_hud_selected_target_blinking()` 为真时计算：

```text
opacity = 0.5 * (1 - cos((time % 0.5) / 0.5 * PI))
opacity *= 1 - get_radar_hud_selected_target_fade()
color *= lerp(0.2, 1, opacity)
if target.isGhost:
    color *= 0.25
```

所以该公共屏幕目标框代码每 0.5 秒重复，即 2 Hz；忽略额外 fade/ghost 时亮度约在原色 20% 到 100% 之间变化。形状是半余弦上升后周期重置，不能当作亮/灭各 0.25 秒的方波。ghost 目标另有 0.25 的颜色倍率，fade 也会影响实际亮度。此结论限定于该目标框绘制路径，不能扩展为所有座舱 HUD、搜索雷达点迹和警报的统一频率。闪烁标志的原生判定条件未在这些文件中公开。

## 持续跟踪失去回波后的超时配置

| 雷达配置 | 跟踪外推清除超时 |
| --- | --- |
| AN/APG-68 | 3.0 秒 |
| AN/APG-63 | 3.0 秒 |
| AN/APG-83 | 3.0 秒 |
| N019 | 3.0 秒 |
| PS-46/A | 3.0 秒 |
| 猎豹跟踪雷达 Gepard TT | 2.0 秒 |
| 通古斯卡跟踪配置 su_1rl144_track | 2.0 秒 |

机载代表来源：[us_an_apg_68.blkx，第 1752 行](https://github.com/gszabi99/War-Thunder-Datamine/blob/ecbd610ff9aa0248bee3095db0cf28bde9ea5aee/aces.vromfs.bin_u/gamedata/sensors/us_an_apg_68.blkx#L1752)：

```json
"extrapolate": {
  "extrapolateTargetsOfInterest": {},
  "clearTargetsOfInterest": { "timeOut": 3.0, "dirRateLim": 1.0 }
}
```

防空代表来源：[de_gepard_fr.blkx，第 502 行](https://github.com/gszabi99/War-Thunder-Datamine/blob/ecbd610ff9aa0248bee3095db0cf28bde9ea5aee/aces.vromfs.bin_u/gamedata/sensors/de_gepard_fr.blkx#L502)；[su_1rl144_track.blkx，第 881 行](https://github.com/gszabi99/War-Thunder-Datamine/blob/ecbd610ff9aa0248bee3095db0cf28bde9ea5aee/aces.vromfs.bin_u/gamedata/sensors/su_1rl144_track.blkx#L881)。两者 `targetNotDetected` 调用外推，并以 `timeOut: 2.0, dirRateLim: 0.78` 清理。

这些是配置超时，不能保证每种失锁情形都恰好显示满相同秒数；角速度限制等条件仍可能影响结果。搜索/TWS 另有参数，例如 APG-68 的 TWS 为 8 秒、N019 的 TWS 为 12 秒、APG-83 的 TWS 为 4 秒，不能混用为持续锁定记忆时长。

## 恢复检测的路径

APG-68 `fsms.track.transitions` 中，`targetNotDetected` 调用 `extrapolate`，最终可将模式名设为 `track memory`；`targetDetected` 进入 `trackPulse`/`trackDoppler`，相应动作更新目标并恢复正常跟踪模式。猎豹的 `targetDetected` 直接调用 `updateActiveTargetOfInterest`，不执行超时清除动作。

这支持实现为：从最后一次真实检测起计时，遮挡后显示记忆框；计时结束前重新确认同一目标则恢复正常跟踪、取消旧的消失计时。记忆位置使用最后观测及其运动外推，持续遮挡不能刷新最后观测时间；记忆框保留不等同于目标仍有有效雷达照射。

若本项目按这些代表配置实现，可采用机载 3 秒、防空 2 秒、公共屏幕框 2 Hz；连接显示器雷达没有一个直接对应的战雷型号，其时长需要作为本项目的设计选择明确指定，不能称为已经查到的战雷统一值。
