# 8缸汽油发动机资源缓存修复

交付：`build/libs/create-fire-control-0.8.5.jar`。方块与物品显示名称改为“8缸汽油发动机”；注册 ID 继续使用 `create_fire_control:tiger_i_engine`。

## 原因与修复

用户实例的 Lightspeed 1.21.1-2.0.2hotx2 配置为 `startup.verifyJarHash=false`。旧资源清单 `create_fire_control0.8.4-create-fire-control-0.8.4.lsc` 不含任何 `tiger_i_engine` 模型或 `sounds/engine/tiger_i` 路径，而实际安装的 JAR 中已有这些文件。Lightspeed 按模块名、内部版本和文件名复用清单，使新增资源无法查询；日志中出现十二个方块状态缺模型以及物品模型 FileNotFoundException。

内部版本从 0.8.4 更新为 0.8.5，使资源缓存使用新的身份重新扫描。无须删除用户缓存或修改 Lightspeed 配置。中文物品名称改为“8缸汽油发动机”，英文和俄文物品名称同步更新。发动机模型、贴图与声音共 41 个资源文件与原 0.8.4 JAR 的 SHA-256 完全相同。

2026-10-12 用户指定版本号固定为 `9.0`，后续不自行递增。此前递增版本规避 Lightspeed 旧资源清单的做法保留为历史记录。

## 验证

- 在工作区隔离客户端复制用户的旧 `.lsc`，保留 `verifyJarHash=false`。内部版本为 0.8.4 时复现方块和物品模型缺失，检查因无法查询发动机几何元数据而失败。证据：`build/tiger-i-cache-old-client.log`、`build/tiger-i-cache-old-client-result.txt`。
- 在同一目录保留旧缓存，用 0.8.5 运行相同检查，通过十二个方块状态、四方向完整机体（各 240 个烘焙三角形）、背包模型与贴图、中文名称及 30 个 JOrbis 音轨解码。证据：`build/tiger-i-cache-fixed-client.log`、`run_tiger_i_cache_fix_verify/client/tiger-audio-client-report.txt`，截图：`run_tiger_i_cache_fix_verify/client/screenshots/tiger_i_v8_model.png`。
- `assemble testZtz99aPackagedResources testTigerIResources -I tools/tiger-i-checks.init.gradle --offline` 通过。最终包的内部版本和中文名称已检查，导弹共享制导与测距修复类均存在，测试夹具未进入发行包。证据：`build/engine-resource-cache-build.log`。

客户端验证使用开发源集和隔离目录，没有修改用户实例的模组、配置或存档。安装时替换旧 Create Fire Control JAR，并重启游戏。
