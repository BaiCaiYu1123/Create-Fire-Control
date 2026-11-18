# 火控显示屏雷达扫描崩溃修复

## 报告与原因

输入：`错误报告-2026-10-25_17.33.24.zip`，其中服务端崩溃时间为 2026-10-25 17:33:13。

崩溃方块：`create_fire_control:connected_display`，存储坐标 `(20481034, 985, 20511754)`。
堆栈指向 `ConnectedDisplayBlockEntity.scan:1379`，异常为：

```text
IllegalArgumentException: Start element (9223367638808264704) is larger than end element (-9223372036854775808)
```

Sable 2.0.6 的子世界共用根世界的实体存储。原扫描遍历全部子世界，将雷达原点逆变换到各子世界，再对共用存储执行大范围 `getEntitiesOfClass` 查询。远处子世界产生的查询范围可能跨过 `SectionPos` 的有符号 X 坐标边界。原版实体分区遍历计算排他上界时，打包后的 long 键从正数翻转到负数，导致 fastutil 的 `subSet` 抛出异常。生物与导弹扫描都有这一问题。

## 修改

- 从根世界的 `getAllEntities()` 遍历已加载实体，按实体实际所属子世界投影包围盒，再以世界坐标执行雷达距离筛选。
- 导弹扫描合并数学模拟导弹管理器中的适配实体，并按 UUID 去重；保留己方结构、玩家敌我识别、己方巡航导弹和导弹敌我识别过滤。
- 生物接触点使用投影后的世界包围盒，避免把子世界存储坐标当成世界坐标。
- 接触点和包围盒含非有限数值时跳过该接触。

模组内部版本保持 `0.9.3`。交付文件：`build/libs/create-fire-control-0.9.3-radar-scan-fix.jar`。

## 验证

隔离服务端使用 Minecraft 1.21.1、NeoForge 21.1.230、Sable 2.0.6、航空学 1.3.2、Lithium 0.15.4、面包军火库 2.6.0b6。

`tools/radar-scan-checks.init.gradle` 加载独立的 `radarScanIntegration` source set；测试代码不进入发行包。测试在首个世界 tick 前同步执行，显式将测试实体区块发布为可访问状态，并恢复 Sable 加入实体时自动投影前的子世界坐标，以覆盖两种实际坐标形式。

验证结果：

- 真实实体存储上的旧查询复现了上述两个 long 数值完全一致的异常。
- 生产 `scan` 方法正常完成，根世界和子世界的生物、实体导弹、数学模拟导弹均生成唯一且坐标正确的接触。
- 射程外生物、己方巡航导弹被过滤。
- 子世界移到远处、以及远处子世界旋转后扫描仍正常；超过接触保持时间后，远处子世界实体接触被移除。
- `testSafetyRegressions`、`testAirDefenseIntercept`（282 个场景）、`testOpenControllerPayload`、`assemble` 与 `verifyShadedJar` 均通过。
- 最终包中的显示屏 class 与已验证的编译产物 SHA-256 一致，内部版本为 `0.9.3`，未包含雷达测试夹具。

交付包 SHA-256：`0cc07acc617dbd3e83aebabcb1e7157e07f955b5075634585dd815d55e6b23a7`。

服务端执行命令（第三方 JAR 路径按本地环境调整）：

```powershell
.\gradlew.bat -I tools/radar-scan-checks.init.gradle runServer --offline --console=plain --no-configuration-cache -PrunRoot=run_display_scan_verify -PcbcRuntime=true '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar' '-PradarLithiumJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/[锂] lithium-neoforge-0.15.4+mc1.21.1.jar'
```

隔离世界位于 `run_display_scan_verify/server`；结果文件 `radar-scan-result.txt` 为 `PASS:`。构建日志和服务端日志保存在 `.diagnostics/crash-2026-10-25_17.33.24/`。附件中没有游戏存档，本次验证使用新建的隔离世界。

## 使用

关闭游戏，将整合包 `mods` 目录中的旧 `create-fire-control-*.jar` 移出，再放入 `create-fire-control-0.9.3-radar-scan-fix.jar`，保留一份火控模组后重新启动游戏。
