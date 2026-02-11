# Launcher Home Fix（LSPosed 模块）

一个针对 **ColorOS + 第三方桌面** 的手势返回优化模块。

目标：减少/消除这条链路导致的卡顿与空白感：

> App → RecentsActivity → 第三方 HOME

希望尽量接近：

> App → 第三方 HOME（动画连续、不僵硬）

---

## 一、项目现状（持续迭代中）

目前版本基于 `com.android.launcher` 进程做 Hook，核心思路是：

1. 手势结束阶段提前识别 HOME 意图（不破坏原生跟手动画链）
2. 在短窗口内拦截晚到的 `startRecentsActivity` 回弹
3. 需要时补一次收尾，避免 Recents 残留导致“半截动画/卡住”

> 这是一个“在真实机器日志上持续调参”的工程，不是一次性静态 patch。

---

## 二、代码结构

```text
Launcher-Home-Fix/
├─ app/
│  ├─ libs/
│  │  └─ xposed-api-82.jar
│  ├─ src/main/
│  │  ├─ assets/xposed_init
│  │  ├─ java/com/lhf/launcherhomefix/HomeFixEntry.java
│  │  ├─ res/values/arrays.xml
│  │  └─ AndroidManifest.xml
│  └─ build.gradle
├─ gradle/wrapper/
├─ build.gradle
├─ settings.gradle
├─ gradle.properties
└─ README.md
```

---

## 三、构建

在项目根目录执行：

```bash
./gradlew assembleDebug
```

输出 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 四、LSPosed 配置

1. 安装 APK
2. 在 LSPosed 启用模块
3. Scope 仅勾选：
   - `com.android.launcher`
4. 重启系统（或至少重启对应进程）

---

## 五、日志建议（用于继续调优）

建议过滤以下关键字：

- `LHF-HomeFix`
- `SystemUiProxy`
- `RecentsTransitionHandler`
- `ActivityTaskManager`

示例：

```bash
adb logcat | grep -E "LHF-HomeFix|SystemUiProxy|RecentsTransitionHandler|ActivityTaskManager"
```

---

## 六、注意事项

- 本模块仅针对特定 ROM 行为（ColorOS 手势链路）设计，不保证通用于所有机型/版本。
- 若你安装了其它也会改 HOME/Recents 的模块，可能互相干扰。
- 该仓库会继续围绕“动画跟手 + 稳定性 + 低延迟”做迭代。

---

## 七、许可证

当前未添加许可证文件（后续可补充 MIT）。
