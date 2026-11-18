# 方向机限位切换稳定性修复

游戏日志来源：`D:/Game/PCLII/.minecraft/versions/Create航空学/logs/latest.log`。
日志中的地面方向机位于 `(58,138,886)`，存在明显的实际方向误差；没有对应的物理崩溃堆栈。

## 原因与修改

启用限位的方向机此前使用固定的限位中心作为 Rapier 电机和止挡的坐标基准。
炮塔已经位于限位范围外、尤其接近相对中心的 ±180° 时，原生角度约束的梯度接近奇异点。
用实际 Sable Rapier DLL 复现，从 179.999° 开启 ±45° 限位时，旧路径单个 1/120 秒物理步跳转约 9.764°。

限位方向机现在与自由方向机一样，每个物理步将原生角度基准移到当前姿态。
物理止挡与电机目标同时转换到该基准，远处止挡暂时截在 ±90°，随炮塔移动，接近真实边界时仍使用配置中的物理边界。
铰链模型预测和物理手杖让控路径使用相同坐标。

范围外回收期间保留 60°/秒的止挡收窄速度，并暂停误差积分、瞄准落点制动和操作者速度前馈。
实际带载测试发现，仅修复坐标而继续累积误差积分，会使回收速度从约 68°/秒逐步增加到超过 125°/秒；这条累积路径也已修正。
跨越背面角度接缝时保留原来的回收侧，防止来回选择相反方向。
回收期间暂停内部瞄准轨迹的前进，回到可达边界后再按配置速度继续瞄准，避免回收结束时突然追赶提前积累的目标角。

## 验证

- `testNativeYawLimits`：256 个范围外开启场景，包含 40/120 Hz、四个中心、正装/倒装、水平/倾斜载具和 ±180°。修复坐标后的箱体场景最大回收速度约 60.00249°/秒。
- 原生止挡测试覆盖非对称范围、单边开放、零范围和无限旋转，确认电机强制请求非法姿态时止挡仍生效。
- `testYawLimits`：合法长路径、四个中心、开放侧、保存读取及范围外跨接缝回收方向。
- `src/yawLimitIntegration`：独立服务器真实装配方向机，验证背向开启、修改中心、关闭/重新开启和零范围锁定；测试代码不进入发行包。

最终真实服务器测试通过，720 次回收采样峰值为 **62.74435°/秒**；恢复瞄准使用日志中的 30°/秒设置。
完整 `build testStabilizerDisabled testStabilizedHeadSpeed` 构建通过，共 102 个任务。
构建沿用 README 的 `-PcharacterizeCarrierHold=true`，原有持续稳定精度表征提示仍保留，本次限位原生测试和真实服务器测试均为严格断言。

运行真实服务器验证时使用独立、干净的 `runRoot`，并加入本地面包军火库 JAR：

```powershell
.\gradlew.bat runServer -I tools/yaw-limit-checks.init.gradle `
  -PrunRoot=run_yaw_limit_verify -PcbcRuntime=true `
  '-PmianbaoJar=D:\Game\PCLII\.minecraft\versions\Create航空学\mods\mianbaos_modernwarfare-2.6.9.jar' `
  --offline --no-configuration-cache --console=plain
```

测试结果写入 `server/yaw-limit-integration-result.txt`，逐物理步角度和速度写入 `server/yaw-limit-integration.csv`。
