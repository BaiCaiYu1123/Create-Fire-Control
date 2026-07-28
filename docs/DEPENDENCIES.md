# 本地开发依赖

`build.gradle` 使用本地 `libs/` JAR，避免在源码仓库重新分发其他模组的二进制文件。构建前请自行取得并放入下列位置。

## 基础环境

- `libs/create-1.21.1-6.0.10.jar`
- `libs/sable-neoforge-1.21.1-1.2.2.jar`
- `libs/create-aeronautics-bundled-1.21.1-1.2.1.jar`
- `libs/simulated-neoforge-1.21.1-1.2.1-compile.jar`

## 最新兼容环境

使用 `-PcompatLatest=true` 时需要：

- `libs/compat-latest/sable-neoforge-1.21.1-2.0.3.jar`
- `libs/compat-latest/create-aeronautics-bundled-1.21.1-1.3.0.jar`
- `libs/compat-latest/simulated-neoforge-1.21.1-1.3.0.jar`
- `libs/compat-latest/sable-rapier-1.21.1-2.0.3.jar`

其余可选集成依赖的准确文件名以 `build.gradle` 中的 `compileOnly` 与 `localRuntime` 项为准。
