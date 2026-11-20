# SSH Relay 0.1.1 — debug 构建

本次是**可用性修复版**，针对真机使用中暴露的两个问题：

1. 隧道跑起来后再改端口/规则，界面上没有入口让改动生效；
2. 隧道切到后台立刻断开，切回来自动重连、再切走又断。

## 1. 改完规则，点「应用」就生效

之前编辑保存在本地，但**运行中的隧道不会跟着变**——改端口等于白改。现在：

- 只要编辑器的内容与**正在运行的配置**不同，底部就常驻一条「配置已修改，尚未生效」的提示条，
  带一个「应用」按钮（滚动到哪都在，见截图）；
- 只有**端口规则**变化时：在活的 SSH 会话上直接换监听，**不断开 SSH**，
  其他规则的现有连接也不受影响；
- 服务器地址 / 端口 / 用户名 / 认证方式变化时：重启一次隧道（前台服务不退出，
  所以通知与后台保护不会中断）。

模拟器上的实测（`ss -ltn` 与服务端日志）：

| 时刻 | 本地监听 | 事件 |
|---|---|---|
| 13:40:04 | `127.0.0.1:18082` | 启动隧道，SSH 连接建立 |
| 13:41:51 | `127.0.0.1:18083` | 点「应用」→ 旧端口释放、新端口监听，日志写「已应用新的转发规则，SSH 会话保持不变」，**没有再次握手** |
| 13:43:12 | 同上 | 应用内浏览器打开 `http://127.0.0.1:18083/`，页面标记 `SSH-RELAY-E2E-OK`，服务端看到的来源仍是 `127.0.0.1` |

顺带修掉两个在这次验证中才暴露的问题：

- 「配置已修改」提示条的判定条件里有一个永远为 `false` 的开关，导致提示条**根本不出现**；
- 提示条被画到系统导航栏下面，按钮点不到——`enableEdgeToEdge()` 下必须自己补 `navigationBarsPadding`。

## 2. 电池优化豁免检测与一键跳转

真机上「切后台就断」的头号原因是**没有加入「不受电池优化限制」**：进程一进后台就被系统冻结，
socket 不再有人服务，SSH 会话随之断开；回到前台解冻、重连，再切走再冻结。

现在的行为：

- 启动时用 `PowerManager.isIgnoringBatteryOptimizations()` 检测，**未豁免**就弹一个说明框；
- 说明框的「去设置」直接进入系统页面，顺序为：
  1. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（应用级一键确认，系统自带的
     "Let app always run in background?" 弹窗，实测点一下 ALLOW 就生效）；
  2. `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（电池优化列表页，ROM 忽略第 1 条时的兜底）；
  3. `ACTION_APPLICATION_DETAILS_SETTINGS`（应用详情页；ColorOS / HyperOS 的「自启动 / 后台运行」
     也在这里）。
- 从系统设置返回时用 `LifecycleEventEffect(ON_RESUME)` 重新读取状态，卡片即时变成
  「✓ 已不受电池优化限制」，并弹一条确认；反向撤销也会立刻变回警告；
- 界面上常驻一张「后台保活」卡片，显示当前状态并提供「去允许后台运行 / 应用详情」两个入口，
  所以「暂不」不是死路；
- 启动隧道时若仍未豁免，运行日志里会留一行警告，事后排查有据可查。

> 说明：`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 是 Google Play 对部分应用类别的敏感权限，
> 本包是自签 debug 包、侧载安装，不受该限制；如果日后要上架，这里需要改成只跳列表页。

## 3. 验证

| 层 | 结果 |
|---|---|
| 纯 JVM 测试 | **34/34 通过**（含新增：热换规则不断开会话、重启不发布 Stopped 状态） |
| 模拟器仪器化测试（Android 15 / API 35 / x86_64） | **OK (10 tests)**：`TunnelEndToEndTest` 2、`BrowserAcceptanceTest` 3、`DeviceOnlyBehaviourTest` 5 |
| 新增设备测试 | 用 `dumpsys deviceidle whitelist` 真实改白名单，断言 `isIgnoringBatteryOptimizations` 随之变化；并断言三个系统入口的顺序与 `package:` 作用域 |
| UI 走查（截图在 `artifacts/battery/`） | 启动弹框 → 系统确认框 → 点 ALLOW → 卡片变绿并提示；撤销后回前台立刻变回红色警告 |

## 安装

**要求 Android 15（API 35）及以上**。

```bash
adb install -r ssh-relay-0.1.1-debug.apk
```

| 项 | 值 |
|---|---|
| 包名 / 版本 | `dev.sshrelay` / versionCode 2、versionName 0.1.1 |
| 体积 | 26,811,091 字节（约 25.6 MiB） |
| 签名 | Android 通用 debug 证书（`CN=Android Debug`），可覆盖安装 0.1.0 |
| SHA-256 | `dba18446ed72c866286a5b9c207003cf3eb0d91ad3a65e5678401214ddfbbce6` |

下载后可用同目录的 `.sha256` 校验：`sha256sum -c ssh-relay-0.1.1-debug.apk.sha256`

## ⚠️ 注意：这是 debug 构建

- 用 Android 通用 debug 证书签名，任何人都持有这把私钥，**不适合长期分发，也不能上架**；
- 开启 `debuggable`，体积与运行时开销都大于正式包；
- 包内含仅测试用的常量（`E2E_*`，指向本地测试服务器），不影响界面与正常使用。

需要正式分发的包：

```bash
scripts/make-keystore.sh          # 一次性，密钥请离线备份
./gradlew :app:assembleRelease    # 已签名 APK
```

## 已知限制

- 只实现 `-L`（本地转发）；`-R` / `-D`(SOCKS) 未实现。
- 隧道只监听 `127.0.0.1`（有意为之）。
- 电池豁免能挡住 AOSP 的 Doze/冻结，**挡不住部分 ROM 的自启动限制**：ColorOS / HyperOS 等
  还需要在应用详情里允许「自启动 / 后台运行」，卡片里的第二个按钮就是为这个准备的。
- 即使豁免 + 允许后台，个别机型在深度睡眠时仍会掐掉长连接；这类情况只能靠 SSH keepalive
  发现并在回前台后重连（已内置指数退避重连）。
- 空闲连接 10 分钟自动回收，防止半开连接长期占用 SSH 通道。

完整设计与验证记录见 `README.md`、`docs/PLAN.md`、`docs/VERIFICATION.md`。
