# 反辐射搜索框闪烁周期调研

2026-10-25 检查公开的 War Thunder Datamine HUD 源码及 HUD 配置，并使用 GitHub 代码搜索交叉检查。可确认反辐射与普通空对地制导共享部分显示状态；不能从这些公开文件确认反辐射搜索框的专用闪烁频率。

- [agmaim.nut](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/agmaim.nut) 把 `IsAntiRadiation`、`GuidanceLockState` 和 `GuidanceLockStateBlinked` 交给瞄准界面。
- [agmaimstate.nut](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/agmaimstate.nut) 通过 `interopGen` 接收游戏引擎更新的状态，没有定义闪烁周期。
- [opticweaponaim.nut](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/opticweaponaim.nut) 根据状态绘制目标框和四角框，没有给出专用时间参数。
- [hud.blkx](https://github.com/gszabi99/War-Thunder-Datamine/blob/master/aces.vromfs.bin_u/config/hud.blkx) 和 `airhudelems.nut` 的其他频率、0.33秒发射区域动画有各自用途，不能当作反辐射搜索框频率。

因此本实现暂用 **1 Hz、50%占空比，即亮0.5秒/暗0.5秒**，满足慢闪要求，并明确标为暂定值，不宣称已经确认与原版一致。常量为 `AntiRadiationGeometry.BLINK_HALF_PERIOD_TICKS = 10`（20 TPS）。用游戏时间加渲染插值计相，避免帧率改变闪烁速度；进入锁定状态后完全停用闪烁。
