# 激光测距无提示：遥控触发补全

交付包：`build/libs/create-fire-control-0.8.1-laser-rangefinder-voxy-input-fix.jar`，包含此前 LOD 地形瞄准及准星逐帧刷新修复。

读取本次游戏日志 `D:/Game/PCLII/.minecraft/versions/Create航空学/logs/latest.log`，并保存到 `.diagnostics/laser-input-2026-10-08/latest-before-fix.log`。游戏安装包 SHA256 为 `C9D6F5EBCF63844A1C181208E0184A2C84585566F7AAD67C244F9429964B0A89`，与上一轮 smooth 包相同。

23:29:27 日志确认可选 Voxy 查询桥已启用，随后记录 253.4、230.0、226.5、223.9、222.3、221.4、176.2 格的 LOD 命中。现有日志仅记录前几次 LOD 查询，没有逐次测距输入及结果信息，因此不能据此判断用户每次按键的具体结果。用户反馈按键后没有任何屏幕提示。

发现原生遥控输入的激光分支仅在 `operator == null` 时调用 `requestLaserRange`。存在已授权的操作者时，代码假定客户端补充输入已完成测距，直接跳过原生按键。如果另一个按键处理器消费了客户端补充输入，该按键会被漏掉。

本次改为原生遥控按键也请求测距，使用最新有效视线作为回退；已经排队的客户端按键瞬间快照仍优先。按住不重新触发，下坠角仍保存到下次测距。网络包格式和距离限制未变。

每次实际测距请求增加 `Laser rangefinder` 日志，分别记录拒绝原因、使用按键快照或原生回退、命中和距离、每个武器的抬炮角，以及无弹药数据或弹道无解。没有改变日常瞄准查询的日志频率。

`testLaserRangeCompensation`、`testOpenControllerPayload`、`testAimGroupPayload`、`testVoxyLodRaycast`、`testCannonRenderFrame` 和 `shadowJar`、`verifyShadedJar` 通过。日志：`build/laser-input-fix-validation.log`。

尚未用新版在用户游戏中验证本次按键触发；仍需重启游戏后实测。这次修复解决已发现的漏触发路径，新日志用于确认用户实际按键是否走到该路径，以及后续弹道计算的结果。
