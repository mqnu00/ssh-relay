# 验证记录（Evidence）

本文件记录**实际跑出来的结果**。所有命令都可直接粘贴执行（先 `source env.sh`）。

结论速览：

| 层 | 内容 | 结果 |
|---|---|---|
| L1 | 纯 JVM 单测与集成测试（转发引擎、SSH、TOFU、认证） | **32/32 通过** |
| L1.5 | 内嵌真实 SSH 服务端的端到端转发 | **通过** |
| 基线 | 标准 OpenSSH 客户端 `ssh -L` + curl | **通过** |
| 构建 | debug APK + Android 15 合规性检查 | **通过** |
| Gate 0 | 容器内 KVM 可用 | **通过** |
| L2 | 模拟器（Android 15 / API 35）仪器化端到端 | **通过** |
| L3 | 浏览器渲染转发页面 | **通过**（应用内 WebView 断言 DOM；系统浏览器经服务端访问日志证实） |

---

## 1. 工具链（M0）

```bash
python3 scripts/provision.py
bash scripts/fetch-system-libs.sh      # 仅当需要在本机原生跑模拟器时
```

| 组件 | 版本 |
|---|---|
| Temurin JDK | 17.0.20.1+1 |
| Gradle | 8.9 |
| platform-tools | 37.0.1 |
| Android Platform | android-35 (rev 2) |
| Build-Tools | 35.0.0 |
| Emulator | 37.2.9 |
| 系统镜像 | `system-images;android-35;default;x86_64` |

所有 zip 均以 Google 官方仓库 XML 中的 sha1 校验通过。

---

## 2. L1 — 纯 JVM 测试（**32/32，0 失败**）

```bash
./gradlew :core-forward:test :core-ssh:test
```

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `PortPolicyTest` | 7 | 端口区间校验（<1024 被拒并给出可读原因）、主机名校验、规则描述 |
| `LocalForwardServerTest` | 10 | 字节转发、HTTP 半关闭语义、24 路并发无串扰、4MB 载荷 sha256 一致、**只监听 127.0.0.1（LAN 地址不可达）**、特权端口拒绝、端口占用报错、停止后释放端口、空闲回收、上游不可达的错误上报 |
| `JschTunnelIntegrationTest` | 10 | 内嵌真实 MINA SSHD 的完整链路（HTTP 客户端 → 本地监听 → 转发引擎 → JSch direct-tcpip → 真实 sshd → HTTP 目标）、TOFU 首次信任/二次信任/**密钥变更拒绝**、认证失败不可重试、不可达可重试、TunnelManager 全生命周期 |
| `PrivateKeyAuthTest` | 5 | **ed25519 私钥认证**（含 bcrypt 加密私钥 + 口令）、口令错误精确报错、私钥认证下转发可用 |

---

## 3. 基线 — 标准 OpenSSH 客户端

不依赖本项目任何代码，验证「SSH -L 转发 → 本地回环可被访问」这一概念与测试台架：

```bash
scripts/run-test-sshd.sh start
scripts/verify-remote-forwarding.sh
```

实测输出：

```
== 2. ssh -L 19080:127.0.0.1:18080 tester@127.0.0.1:2222
== 3. the browser's view: curl http://127.0.0.1:19080/
   PASS 127.0.0.1:19080 served the remote page (SSH-RELAY-E2E-OK)
        来源</td><td><code>/127.0.0.1:48256
== 4. the forward is loopback-only on this side too
   PASS not reachable on 172.26.44.254 (bound to loopback)
```

产物：`artifacts/openssh-forward.html`

---

## 4. APK 与 Android 15 合规性（M1）

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk (26.5 MB)
```

`aapt2 dump badging`：

```
package: name='dev.sshrelay' ... compileSdkVersion='35' compileSdkVersionCodename='15'
targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_SPECIAL_USE'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
```

前台服务类型（`aapt2 dump xmltree`）：

```
E: service
  A: android:name="dev.sshrelay.service.TunnelService"
  A: android:foregroundServiceType(0x01010599)=0x40000000   ← FOREGROUND_SERVICE_TYPE_SPECIAL_USE
  E: property
    A: android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
    A: android:value="ssh_port_forwarding"
```

> 必须是 `specialUse` 而非 `dataSync`：Android 15 对 `dataSync` 型前台服务有 **6 小时/24 小时**累计上限，
> 长连接隧道会被系统静默掐断。

16KB 页大小要求：

```
$ zipalign -c -P 16 -v 4 app-debug.apk
Verification succesful

$ readelf -lW lib/arm64-v8a/*.so | awk '/LOAD/{print $NF}'
0x4000        # libandroidx.graphics.path.so
0x4000        # libdatastore_shared_counter.so
```

本项目自身的 SSH 实现是纯 Java（mwiede/jsch，无 `.so`）；APK 中仅有的两个原生库来自 AndroidX，
其 ZIP 对齐与 ELF 段对齐均满足 16KB 要求。

---

## 5. Gate 0 — KVM 可用

宿主机跑不了模拟器（CentOS Stream 8 的 glibc 2.28 < 模拟器 Qt6 所需的 2.29/2.30），
因此模拟器运行在 `docker/emulator.Dockerfile`（Ubuntu 22.04，glibc 2.35）容器中，
通过 `--device /dev/kvm` 直通 KVM。

```bash
scripts/emulator.sh accel
```

```
== host ==
   ldd (GNU libc) 2.28
   emulator cannot run natively here -> container is used

== emulator -accel-check (inside the container) ==
accel:
0
KVM (version 12) is installed and usable.
accel
```

模拟器就绪后的自述：

```
emulator ready:
List of devices attached
emulator-5554	device

android 15  api 35  abi x86_64
```

---

## 6. L2 / L3 — 模拟器端到端与浏览器验收

```bash
scripts/emulator.sh create && scripts/emulator.sh start
scripts/run-test-sshd.sh container                            # 容器内的"远端"
scripts/connected-test.sh                                     # 全部用例
scripts/connected-test.sh dev.sshrelay.TunnelEndToEndTest      # 单类
KEEP_INSTALLED=1 scripts/connected-test.sh dev.sshrelay.BrowserAcceptanceTest
scripts/pull-evidence.sh                                      # 取回截图
```

### 6.1 仪器化测试结果（设备：`sshrelay_api35`，Android 15 / API 35 / x86_64）

最终代码状态下的实测结果（`scripts/run-instrumented.sh`，输出为 `am instrument` 的原始汇总）：

| 测试类 | 结果 | 关键断言 |
|---|---|---|
| `TunnelEndToEndTest` | **OK (2 tests)** | 设备内建立真实 SSH 隧道后取回 `http://127.0.0.1:18080/`；页面标记存在；**HTTP 服务看到的来源是 `127.0.0.1`**（证明请求确实穿过隧道）；**设备 LAN 地址上连不通**（证明只绑定回环）；6 路并发请求全部成功；错误密码进入 `Failed` 而非无限重连 |
| `DeviceOnlyBehaviourTest` | **OK (3 tests)** | Android Keystore 加解密往返（含中文与特殊字符、覆盖写入）、空值清除、前台服务通知在全部 5 种状态下均可构造且带停止动作 |
| `BrowserAcceptanceTest#aBrowserEngineRendersTheForwardedPage` | **OK (1 test)** | **应用内 WebView（Chromium 124）打开转发地址**，读取 `document.body.innerText` 断言其中同时包含页面标记与 `127.0.0.1`（避免"绕过隧道也能通过"的假阳性）；并自动截图存证 |
| `BrowserAcceptanceTest#holdsTheTunnelOpenForExternalChecks` | **OK (1 test)** | 把隧道拉起并保持，供宿主脚本做跨应用验收（见 6.4） |
| `BrowserAcceptanceTest#theSystemBrowserOpensTheForwardedPort` | `@Ignore` | 见 6.5 |

两套运行方式都可用：

```bash
scripts/connected-test.sh                    # 经 Gradle（有 UTP 报告；峰值内存更高）
scripts/run-instrumented.sh                  # 直接 am instrument（APK 已构建，省掉 ~1.2GB 构建 JVM）
scripts/run-instrumented.sh dev.sshrelay.TunnelEndToEndTest
```

### 6.2 服务端访问日志（独立于本项目代码的证据）

测试 HTTP 服务端为每个页面请求打印 URI 与 **User-Agent**：

```
http request #1  uri=/             peer=/127.0.0.1:35878  ua=Dalvik/2.1.0 (Linux; U; Android 15; ...)
http request #2  uri=/             peer=/127.0.0.1:47830  ua=Mozilla/5.0 (Linux; Android 15; ...; wv) ... Chrome/124.0.6367.219 Mobile Safari/537.36
http request #3  uri=/favicon.ico  peer=/127.0.0.1:47830  ua=Mozilla/5.0 ... Chrome/124.0.6367.219 ...
```

由此得到两点证实：

1. 请求来源是 `127.0.0.1`，说明连接**由 SSH 服务端在本机发起**，即确实穿过了隧道，而不是设备直连远端；
2. 出现 **Chrome/124 的 User-Agent**，说明是真实浏览器引擎（而非普通 HTTP 客户端）取走了页面。

### 6.3 截图取证

`artifacts/device/`（720×1280 PNG，由仪器化测试自动截取，`scripts/pull-evidence.sh` 取回）：

| 文件 | 内容 |
|---|---|
| `app-tunnel-running.png` | 主界面的隧道运行状态（监听端口、连接数、流量） |
| `browser-webview-forward.png` | 应用内 WebView 渲染转发页面 |
| `browser-system-app.png` | 交给系统浏览器后的屏幕状态 |

### 6.4 跨应用浏览器验收（宿主脚本，实测通过）

`theSystemBrowserOpensTheForwardedPort` 在 instrumentation 内部驱动第三方浏览器不可靠（见 6.5），
因此这一步改由 `scripts/verify-browser.sh` 在宿主侧完成：测试只负责把隧道拉起并保持，
脚本把 URL 交给**系统浏览器**，再用服务端访问日志判定。

实测输出：

```
== 1. device
   emulator-5554	device
   android 15

== 2. bring the tunnel up and hold it (instrumented, 180s)
   waiting for the app to report its listener.
   PASS the app is listening on 127.0.0.1:18080

== 3. hand the URL to the system browser (a different app, different uid)
   http://127.0.0.1:18080/?proof=1789527641
   resolved browser app: org.chromium.webview_shell

== 4. did that app actually fetch the page through the tunnel?
   PASS the remote service was reached for this exact URL:
   http request #4  uri=/?proof=1789527641  peer=/127.0.0.1:60820
     ua=Mozilla/5.0 (Linux; Android 15; ...; wv) AppleWebKit/537.36 (KHTML, like Gecko)
        Version/4.0 Chrome/124.0.6367.219 Mobile Safari/537.36
   PASS the request carried a Chrome User-Agent, so a real browser engine fetched it
```

这条记录同时证明了三件事：

1. 请求是**为本次探测的专属 URL**（`?proof=…`）发出的，不是缓存或历史流量；
2. 来源是 `127.0.0.1`，即**由 SSH 服务端在本机发起**，确实穿过了隧道；
3. User-Agent 是 **Chrome/124**，说明取走页面的是真实浏览器引擎，而不是普通 HTTP 客户端。

该 URL 是由**另一个应用**（`org.chromium.webview_shell`，uid 10103）处理的——这正是"手机浏览器能访问"的字面含义。

### 6.5 已知不足（如实记录）

- `theSystemBrowserOpensTheForwardedPort` 保持 `@Ignore`：在 instrumentation 内部驱动第三方浏览器不可靠
  ——同一 URL 的 `am start` 不会让已在运行的 WebView shell 重新加载；`am force-stop` 之后它不再起来；
  且 `uiautomator dump` 在 instrumentation 环境下不产出内容，无法读取该应用显示的文本。
  该场景已由 6.4 的宿主脚本覆盖。
- 系统镜像是 AOSP `default`，自带 `com.android.webview` 124 与 `org.chromium.webview_shell` 浏览器，
  但没有 Play 版 Chrome；真机上换成 Chrome 不改变结论。
- **运行环境对内存极敏感**：宿主 WSL 共 7.6GB；模拟器固定占用约 2.5GB 客机 RAM（模拟器会无视
  `-memory` 把客机 RAM 抬到 2560MB）+ QEMU 自身开销，合计约 3GB。若宿主上另有大型容器
  （实测用户的 `gitlab-13` 占 3.1GB），叠加后必然触发 OOM，模拟器容器以 exit 137/139 被杀，
  外部表现是"设备中途消失"。
  应对手段（均已固化）：`scripts/connected-test.sh` 先停 Gradle 守护进程并以单 JVM 低内存模式运行；
  `scripts/run-instrumented.sh` 完全绕开 Gradle 构建 JVM；`LOWRAM=1 scripts/emulator.sh start`
  用 `-lowram` 让客机自己少占资源；并建议按类分批执行。
## 7. 复现清单

```bash
cd <repo>
python3 scripts/provision.py                  # 装工具链（约 1.7GB）
source env.sh

./gradlew :core-forward:test :core-ssh:test    # L1 + L1.5，无需模拟器
./gradlew :app:assembleDebug                   # 产出 APK

scripts/run-test-sshd.sh start
scripts/verify-remote-forwarding.sh            # OpenSSH 基线

scripts/emulator.sh accel                      # Gate 0
scripts/emulator.sh create && scripts/emulator.sh start
scripts/run-test-sshd.sh container             # 容器内的"远端"
scripts/connected-test.sh                      # L2（经 Gradle）
scripts/run-instrumented.sh                    # L2（直接 am instrument，更省内存）
scripts/verify-browser.sh                      # L3（跨应用浏览器）
scripts/pull-evidence.sh                       # 取回截图
```

## 8. 0.1.1 增量验证（「应用」按钮 + 电池优化豁免）

本次改动针对真机反馈的两个问题：改端口后没有生效入口、切后台立刻断线。环境与 0.1.0 相同
（`sshrelay_api35` / Android 15 / API 35 / x86_64，模拟器跑在容器里，远端是容器内的测试 sshd）。

```bash
scripts/emulator.sh start
scripts/run-test-sshd.sh container      # 远端必须与模拟器同容器，10.0.2.2 才是它
./gradlew test                          # L1：34/34
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
scripts/run-instrumented.sh             # L2 + L3：OK (10 tests)
```

| 测试类 | 结果 | 本次相关断言 |
|---|---|---|
| `TunnelEndToEndTest` | OK (2) | 端到端取回页面；LAN 地址不可达；错误密码快速失败 |
| `BrowserAcceptanceTest` | OK (3) | 应用内 WebView 渲染转发页面并断言页面标记 |
| `DeviceOnlyBehaviourTest` | **OK (5)** | 新增两条：① `dumpsys deviceidle whitelist +dev.sshrelay` 之后 `isIgnoringBatteryOptimizations` 必须变为 `true`，移除后变回 `false`；② 三个系统设置入口的顺序与 `package:` 作用域 |
| `:core-ssh:test` / `:core-forward:test` | OK (34) | 新增「热换规则不断开会话」「重启不发布 Stopped」两条 |

界面走查（截图在 `artifacts/battery/`，720×1280）：

| 文件 | 内容 |
|---|---|
| `01-prompt.png` | 未豁免时的启动提示框（暂不 / 去设置） |
| `02-after-go-settings.png` | 点「去设置」后系统弹出的 "Let app always run in background?"（`com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`） |
| `03-allow-return.png` | 点 ALLOW 返回：卡片变「✓ 已不受电池优化限制」，弹出确认提示；`dumpsys deviceidle whitelist` 出现 `user,dev.sshrelay,10154` |
| `04-revoked.png` | 撤销白名单后回到前台，卡片立刻变回「⚠ 仍在电池优化范围内」（验证 ON_RESUME 重读） |
| `07-apply-bar.png` | 运行中把本地端口改为 18083，「配置已修改，尚未生效」条出现在导航栏之上 |
| `08-applied.png` | 点「应用」后的界面 |
| `09-forwarded-page.png` | 换端口后应用内浏览器仍能打开 `127.0.0.1:18083`，页面标记 `SSH-RELAY-E2E-OK`，服务端看到的来源是 `127.0.0.1` |

热换规则的直接证据（`adb shell ss -ltn` + 应用内日志）：

```
13:40:04 [tun] 开始连接 tester@10.0.2.2:2222
13:40:04 [ssh] SSH 已连接：tester@10.0.2.2:2222（服务端 SSH-2.0-APACHE-SSHD-2.14.0）
13:40:04 [tun] 已监听 127.0.0.1:18082 → 127.0.0.1:18080
13:41:51 [tun] 已监听 127.0.0.1:18083 → 127.0.0.1:18080
13:41:51 [tun] 已应用新的转发规则，SSH 会话保持不变
```

13:40:04 之后没有第二次「开始连接」，即 SSH 会话没有重建；`ss -ltn` 同时只出现新端口。

走查中发现并修掉的两个缺陷（都属于 0.1.0 遗留的 UI 接线问题）：

1. `pendingChanges` 里的 `settingsLoaded` 从未被置为 `true`，导致「应用」提示条永远不显示；
2. 提示条没有 `navigationBarsPadding()`，在 `enableEdgeToEdge()` 下被画到导航栏底下，按钮不可点。

