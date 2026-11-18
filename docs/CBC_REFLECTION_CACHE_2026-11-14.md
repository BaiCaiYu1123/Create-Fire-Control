# CBC 反射缓存与相关模组性能复测（2026-11-14）

本轮用工作目录源码构建火控 1.0.4、面包 2.7.1，并搭建只含火控相关模组和必要前置的独立客户端。51 个顶层 JAR 的来源、版本、SHA256 和纳入理由见 `analysis/full-stack-performance-20261114/模组清单.md`、`runtime-manifest.json`。

基线三个场景均为约 20 TPS，但 JFR 发现 `CannonBallisticsCompat.invokeCompatible` 在有炮座时反复调用 `Class.getDeclaredMethods()`：90 秒静置与头瞄分别估算分配约 483 MiB、2107 MiB 的 `java.lang.reflect.Method`，另有方法数组分配。这个热点在火控，不能记到面包的 Sable 反射缓存修复上。

修复落在火控：新增 `content/controller/CannonReflection.java`，按实际 Class 的 ClassValue 缓存不可变方法元数据，以名字分组；参数类型和访问权限一次准备，缺失方法复用空结果。保留原有从子类到父类、同类声明顺序、严格基本类型包装匹配及异常后的候选回退。不缓存实例、调用结果、弹种数据、炮口姿态或物理状态，也不增加任何 CBC 或拓展硬依赖。`CannonBallisticsCompat` 的实例调用、静态调用和声明方法查找统一使用该缓存。

回归入口为 `testCannonReflection`，已接入 `check`；覆盖私有继承、重载与参数变化、null/基本类型、瞬时异常恢复、静态与实例候选、不同接收者、可选类型/方法缺失和并发。预热后的 10 万次命中/缺失查询与调用分配测得为 0 字节。重构后还运行炮口预测、移动目标截击、炮塔吊舱弹道、机炮寿命、瞄准方向、弹药协议及空闲同步相关检查。

完整 `check --continue` 在本轮补缓存前运行，仅 `testNativeBearing` 的 `sustained carrier rotation holds every tested load before stopping` 断言失败；该失败在 11 月 13 日的旧代码对照中也已记录。保留断言，不将它描述为全量回归通过。本轮没有修改面包源码。

正式修复前/后及开启 Photon 的三场景测量、实际输入断言、JFR 归属、偶发长 tick 和运行环境限制，以 `analysis/full-stack-performance-20261114/性能测试报告.md` 为准。原始数据和可复用启动脚本在同目录，早期失败夹具及短测不用于最终性能结论。
