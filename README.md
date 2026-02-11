# Launcher Home Fix（LSPosed 模块）

用于优化 **ColorOS + 第三方桌面** 场景下的回桌面手势体验。

目标链路：

- 原始常见链路：`App -> RecentsActivity -> 第三方桌面`
- 优化目标：尽量在不破坏跟手动画的前提下，减少 Recents 回弹和回桌面延迟

---

## 当前实现思路（已改为非固定 ms 主判定）

核心逻辑在 `HomeFixEntry.java`，采用：

1. **手势结束阶段识别 HOME 意图**
2. **按“手势 token + 预算”进行回弹拦截**（而不是依赖固定毫秒阈值作为主判定）
3. **必要时提前启动第三方 HOME**，避免“动画结束后桌面才出现”
4. 仅在 `com.android.launcher` 进程生效，尽量降低副作用

---

## 关键配置在哪（你关心的部分）

### 1) LSPosed 作用域配置

- 文件：`app/src/main/res/values/arrays.xml`
- 键：`xposed_scope`
- 当前默认仅：`com.android.launcher`

---

### 2) Hook 入口类

- 文件：`app/src/main/assets/xposed_init`
- 当前入口：`com.lhf.launcherhomefix.HomeFixEntry`

---

### 3) 核心逻辑与可调点

- 文件：`app/src/main/java/com/lhf/launcherhomefix/HomeFixEntry.java`

重点方法：

- `hookDirectHomeAtGestureEnd(...)`
  - 处理手势结束阶段 HOME 意图
  - 这里有“竖直上滑判定”（用于避免误伤应用切换）
- `hookSystemUiProxyStartRecents(...)`
  - 兜底拦截晚到的 Recents 回弹
- `maybeStartHomeForGesture(...)`
  - 按手势 token 控制本次手势是否已启动 HOME
- `clearDirectHomeArm(...)` / `consumeDirectHomeBypass(...)`
  - 管理当前手势的“armed 状态”和拦截预算

可调参数（都在 `HomeFixEntry` 里）：

- `sDirectHomeBypassBudget`：单次手势允许拦截回弹的次数预算
- `verticalUp` 判定条件：决定哪些手势被当作“回桌面”

---

### 4) 模块文案与描述

- 文件：`app/src/main/res/values/strings.xml`

---

### 5) 构建配置

- 文件：`app/build.gradle`
  - `compileSdk / targetSdk / minSdk`
  - `xposed-api-82.jar` 依赖

---

## 构建

```bash
./gradlew assembleDebug
```

输出 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

> 本地 SDK 路径通过 `local.properties` 指定；该文件默认不提交。

---

## 安装与 LSPosed

1. 安装 APK
2. 在 LSPosed 启用模块
3. Scope 勾选 `com.android.launcher`
4. 重启系统（建议）

---

## 建议测试项

1. 慢速上滑回桌面（看动画连续性）
2. 快速上滑回桌面（看是否偶发卡住）
3. 左右切应用手势（看是否被误伤）
4. 连续 20 次回桌面压力测试

---

## 日志采集建议

```bash
adb logcat | grep -E "LHF-HomeFix|SystemUiProxy|RecentsTransitionHandler|ActivityTaskManager"
```

重点关注：

- `gesture HOME armed`
- `blocked startRecentsActivity -> start HOME`
- `clear arm: ...`

---

## 免责声明

- 本模块面向特定 ROM 行为（ColorOS 手势链路）调优，不保证所有机型/版本一致。
- 若同时安装其它修改 HOME/Recents 的模块，可能互相干扰。

---

## GitHub Actions 自动构建（Debug + Release）

已添加工作流：`.github/workflows/android-build.yml`。

触发时机：

- `push`
- `pull_request`
- 手动触发 `workflow_dispatch`

工作流会执行：

```bash
./gradlew --no-daemon clean assembleDebug assembleRelease
```

并上传两个产物：

- `app-debug-apk` → `app/build/outputs/apk/debug/app-debug.apk`
- `app-release-apk` → `app/build/outputs/apk/release/app-release-unsigned.apk`

### Debug 和 Release 有什么区别？

在当前项目配置下（`minifyEnabled false`，且未自定义 release 签名）：

- **共同点**：功能代码基本一致（当前没有通过 `BuildConfig.DEBUG` 或 productFlavor 做功能分支）。
- **主要区别**：
  - `debug` 由 debug keystore 自动签名，便于直接安装调试。
  - `release` 这里产出的是 **unsigned** 包（`app-release-unsigned.apk`），通常用于后续正式签名。
  - Android 构建系统在 debug/release 的默认可调试属性、优化策略上仍有差异，但本项目目前未启用混淆压缩，因此体感差异一般不会特别大。

如果你后续需要“可直接安装的 release 包”，可以在 `build.gradle` 增加 `signingConfigs.release` 并给 `release` 绑定签名。
