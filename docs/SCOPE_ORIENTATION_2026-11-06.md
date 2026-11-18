# 炮镜车体 / 炮塔方向指示

打开炮镜后，右下角显示线条式坦克俯视图。白线为车体与履带，黄色为炮塔与炮管；下方显示炮塔相对车体的夹角，右转为正、左转为负。图的上方对应当前炮镜的观察方向，两部分分别随实际方向旋转。

车头以方向机的装配零位为基准；配置方向机转角限位时，使用该方向机设置的中心方向。车体跟随方向机底座所在的实时 Sable 子空间，炮塔使用实际火炮方向，不使用鼠标目标、弹道落点或射击补偿。客户端逐帧读取渲染插值姿态；实体尚未同步时使用网络角度的最短路径插值，跨越 ±180° 不会绕一整圈。

图标按窗口高度等比缩放，显示在原方位 / 仰角文字的左上方，兼容默认与预设炮镜及热成像。退出炮镜、进入吊舱或武器观察视角后隐藏；缺少有效方向机与炮向数据时不显示虚构方向。此功能不增加配置项。

新增 `scope_orientation` 客户端数据包，网络协议从 57 更新为 **58**；联机时客户端和服务端需要一起更新。

## 验证

```powershell
.\gradlew.bat testScopeOrientation testAimGroupPayload testVirtualScopeGeometry testCannonRenderFrame assemble --offline
```

`testScopeOrientation` 覆盖车体转向与倾斜、倒装方向机、非零装配零位、炮管俯仰、跨 ±180°、数据包往返及不同窗口 / GUI 缩放。线条预览输出到 `build/reports/scope-orientation-preview.png`。

真实客户端检查使用独立运行目录和新测试存档，验证嵌套 Sable 车体 / 炮塔的姿态归属，并保存普通炮镜、炮塔侧转、车体侧转、后向炮塔、热成像与预设炮镜截图：

```powershell
.\gradlew.bat runClient --offline -I tools/scope-orientation-checks.init.gradle '-PrunRoot=run_scope_orientation_verify' '-PcbcRuntime=true' '-PmianbaoJar=面包军火库2.7.0或以上JAR的完整路径' '-Pgeckolib_version=4.9.3'
```

结果在运行目录的 `client/scope-orientation-client-result.txt`，截图在 `client/scope-orientation-qa/`。检查源码位于独立源码集，不进入发行 JAR。
