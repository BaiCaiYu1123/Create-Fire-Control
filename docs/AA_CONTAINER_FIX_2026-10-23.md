# 二型防空发射器容器弹药修复

面包当前 NeoForge 2.6.0b6 源码中的二型左右发射器，均通过 `AntiairmissilelauncherreloadProcedure` 向槽 0 装填，并调用 `AntiairmissilelaunchergoProcedure` 发射。火控此前仍对这两个 ID 读取旧 `missileammo`，调用遗留二型发射过程，导致容器内已有高速防空导弹时仍被判断为空弹。

## 修复

- 全部七种已支持的防空发射器统一以槽 0 的 `portableantiairmissile_2` 堆叠数为弹量。空槽或错误物品返回 0，不回退到残留 NBT。
- 有容器槽的二型左右改用当前通用防空发射过程。原生过程负责扣一发，火控只比较发射前后数量。
- 无可用容器槽的旧后端保留 NBT 弹量，以及二型专用旧发射过程；反射缓存按过程类名区分。
- 显示器与火控计算机共用这套读取和发射入口。

## 验证方法

`testMianbaoLauncherAmmo` 在修复前新增二型容器场景后复现 `Inventory count lost for portableantiairmissile_2`。修复后覆盖七种防空发射器的弹量、扣弹、耗尽、错误物品、残留旧标签，以及无容器旧后端的入口选择。

`GuidedMissileLaunchIntegration` 扩展为七种防空发射器 × 计算机/显示器制导路径 × 东/北两个方向 × 实体/数学两种模式。每次以两发真实高速防空弹和残留旧弹量 37 发开始，检查发射后真实弹量恰好为 1、旧标签不变，并检查空槽和错误物品不能发射；原有机载导弹、载具挂架、诱饵和 100 tick 制导轨迹对照继续运行。

独立输出目录由 `tools/aa-container-checks.init.gradle` 设置为 `build-aa-container-verify/`，避免共享编译目录被其他构建更新时丢失运行中的类文件。测试存档位于 `run_aa_container_verify/server/`，仅监听本机随机端口。

```powershell
.\gradlew.bat --offline -I tools/aa-container-checks.init.gradle testMianbaoLauncherAmmo testAirDefenseIntercept testSafetyRegressions assemble runServer -PmissileIntegration=true -PcbcRuntime=true -PrunRoot=run_aa_container_verify '-Pmod_version=0.9.1-aa-container-fix' '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar'
```

## 结果与成品

- 上述命令最终 `BUILD SUCCESSFUL`，弹药回归、防空拦截、安全回归、打包校验及独立测试服均通过。
- 测试服加载面包 2.6.0b6 与 Sable 2.0.6。共 132 次真实发射，其中 56 次覆盖完整七型防空矩阵，另有 2 次防空诱饵场景；全部 132 次各记录 100 tick，共 13200 条轨迹记录。实体与数学模式对照通过。
- `run_aa_container_verify/server/mathematical-missile-integration-result.txt` 为 `PASS`；轨迹为同目录的 `guided-missile-launch-trajectories.csv`。
- 成品为 `build/libs/create-fire-control-0.9.1-aa-container-fix.jar`，从隔离构建复制。两个修复类与实服使用的编译类逐字节哈希一致，测试类未进入成品。
- SHA-256：`fe0e2be13b6454ca28024688116e9bf55daa01ed681b22cf5d6973a80b74a04b`，同目录保存 `.sha256` 文件。

本次完成服务端真实发射和制导验证，未做完整整合包客户端手工画面验收。替换旧火控 JAR 并完整重启游戏/服务端后生效。
