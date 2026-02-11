# Launcher Home Fix

> LSPosed 模块：优化 **ColorOS + 第三方桌面** 场景下，手势返回桌面的连贯性与响应速度。

## 项目背景

在部分 ColorOS 版本中，使用第三方桌面时，从 App 上滑返回桌面常出现链路绕行：

`App -> RecentsActivity -> 第三方桌面`

这会带来：

- 回桌面阶段出现明显回弹感
- 桌面出现偏晚（动画结束后才显示）
- 高频操作下体验割裂

本模块通过 Hook Launcher 侧关键路径，尽量把链路收敛为“更直接的 HOME 切换”，同时控制误伤风险。

---

## 功能特性

- 在手势结束阶段预判 HOME 意图
- 基于“手势 token + 拦截预算”阻断 Recents 回弹（而非单纯固定 ms 窗口）
- 必要时提前启动默认 HOME（第三方桌面）
- 仅在 `com.android.launcher` 作用域生效，降低全局副作用

---

## 适用范围

- **ROM**：面向 ColorOS 手势链路做兼容优化
- **框架**：LSPosed
- **场景**：系统 Launcher + 第三方默认桌面

> 注意：不同机型/ROM 版本内部实现差异较大，本项目不保证在所有环境下表现一致。

---

## 实现概览

核心入口：`app/src/main/java/com/lhf/launcherhomefix/HomeFixEntry.java`

主要逻辑：

1. Hook `AbsSwipeUpHandler` 的手势结束流程，识别 HOME 目标
2. 当本次手势被“armed”后，按预算拦截 `SystemUiProxy.startRecentsActivity`
3. 在合适时机主动拉起默认 HOME，减少“先回弹再回桌面”的延迟感
4. 在 settled HOME 后执行轻量清理，避免尾部状态干扰

关键方法：

- `hookDirectHomeAtGestureEnd(...)`
- `hookSystemUiProxyStartRecents(...)`
- `maybeStartHomeForGesture(...)`
- `clearDirectHomeArm(...)` / `consumeDirectHomeBypass(...)`

---

## 目录结构（核心）

```text
app/
├─ src/main/java/com/lhf/launcherhomefix/HomeFixEntry.java  # 核心 Hook 实现
├─ src/main/res/values/arrays.xml                           # LSPosed scope
├─ src/main/assets/xposed_init                              # Xposed 入口
├─ src/main/res/values/strings.xml                          # 模块文案
└─ libs/xposed-api-82.jar                                   # Xposed API
```

---

## 构建说明

### 环境要求

- JDK 11+
- Android SDK（`compileSdk 34`）
- 可用的 Gradle Wrapper（仓库已提供）

### 构建命令

```bash
./gradlew assembleDebug
```

调试包输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 安装与启用

1. 安装 APK
2. 打开 LSPosed，启用模块
3. Scope 勾选：`com.android.launcher`
4. 重启系统（建议）

---

## 调试与排查

日志过滤建议：

```bash
adb logcat | grep -E "LHF-HomeFix|SystemUiProxy|RecentsTransitionHandler|ActivityTaskManager"
```

重点关注关键词：

- `gesture HOME armed`
- `blocked startRecentsActivity -> start HOME`
- `clear arm`

推荐回归测试：

1. 慢速上滑回桌面（观察动画连续性）
2. 快速上滑回桌面（观察是否偶发卡顿）
3. 左右切应用手势（确认未误伤）
4. 连续多次（20+）回桌面压力测试

---

## GitHub Actions 自动构建（Debug + Release）

仓库已包含工作流：`.github/workflows/android-build.yml`。

触发时机：

- `push`
- `pull_request`
- 手动触发 `workflow_dispatch`

执行命令：

```bash
./gradlew --no-daemon clean assembleDebug assembleRelease
```

产物：

- `app-debug-apk` -> `app/build/outputs/apk/debug/app-debug.apk`
- `app-release-apk` -> `app/build/outputs/apk/release/app-release-unsigned.apk`

说明：

- 当前 `release` 默认产物是 **unsigned APK**（未配置自定义签名）
- 当前项目 `minifyEnabled false`，因此 debug/release 的功能差异较小
- 若需要可直接安装的 release 包，请补充 `signingConfigs.release`

---

## 兼容性与风险提示

- 本模块属于 ROM 行为定向优化，不是通用“全机型万能修复”
- 若同时启用其他修改 HOME/Recents 链路的模块，可能产生冲突
- 建议每次改动后逐步测试，避免直接用于高依赖生产环境

---

## 版本信息

当前版本来自 `app/build.gradle`：

- `versionName`: `1.0.0`
- `versionCode`: `1`

---

## 贡献

欢迎通过 Issue / PR 提交：

- 机型与系统版本适配反馈
- 可复现场景与日志
- 更稳妥的 Hook 点与降级策略

提交问题时建议附带：

- 设备型号、ROM 版本
- 默认桌面与第三方桌面信息
- LSPosed 版本
- 关键 logcat 片段

---

## 免责声明

- 本模块面向特定 ROM 行为（ColorOS 手势链路）调优，不保证所有机型/版本一致。
- 若同时安装其它修改 HOME/Recents 的模块，可能互相干扰。
- 本项目按“现状（AS IS）”提供。请在理解风险并可自行恢复的前提下使用。
