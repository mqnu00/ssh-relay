# SSH Relay — Android SSH 端口转发

在 Android 15+（API 35）手机上建立 SSH 连接并把远端端口转发到**本机回环地址**，
之后用手机上的任意浏览器访问 `http://127.0.0.1:<本地端口>` 即可打开远端服务。

```
浏览器 / 任意 App
      │  http://127.0.0.1:8080
      ▼
┌─────────────────── Android 设备 ───────────────────┐
│  LocalForwardServer (127.0.0.1:8080)               │
│      │  每个连接一条 direct-tcpip 通道             │
│  JschTransport ── SSH ──► sshd                     │
│  TunnelService (specialUse 前台服务, 常驻)          │
└────────────────────────────────────────────────────┘
                                │
                                ▼
                      远端 127.0.0.1:80 / db:5432 / ...
```

---

## 1. 功能

- 密码 / 私钥（含 passphrase）两种认证方式
- 多条本地转发规则（`本地端口 → 远端主机:远端端口`），可分别启用/停用
- 隧道以前台服务常驻：切后台或杀掉界面不中断，通知栏可看状态并一键停止
- 断线自动重连（指数退避 + 抖动，1s → 30s）；认证失败/主机密钥变更不盲目重试
- 主机密钥 TOFU 校验：首次连接记录指纹，之后变更即拒绝连接（防中间人）
- 凭据用 Android Keystore（AES-GCM）加密存储，日志与通知中不出现
- 内置 WebView 预览 + 一键跳转系统浏览器
- 实时日志面板与每条规则的连接数/流量统计

---

## 2. 工程结构

```
ssh-relay/
├── env.sh                      # 导出工作区内的工具链路径
├── gradlew                     # 薄封装：直接用 .toolchain 里的 Gradle
├── .toolchain/                 # JDK17 / Android SDK / Gradle / AVD（不入库，约 3GB）
├── scripts/
│   ├── provision.py            # 用户态安装整套工具链（可续传、绕开 DNS）
│   ├── emulator.sh             # 创建/启动无头 API35 模拟器
│   ├── run-test-sshd.sh        # 启动宿主侧测试 SSH 服务端
│   └── verify-browser.sh       # 浏览器验收取证
├── core-forward/               # 纯 JVM：本地转发引擎（无 Android 依赖，可单测）
├── core-ssh/                   # 纯 JVM：JSch 传输层、TOFU、TunnelManager
├── app/                        # Android：Compose UI、前台服务、Keystore、DataStore
└── tools/test-sshd/            # 宿主侧测试 SSH 服务端 + HTTP 目标
```

**为什么把转发与 SSH 放进纯 JVM 模块**：本机没有真机，这两个模块用
`./gradlew :core-forward:test :core-ssh:test` 就能跑完整测试，其中
`core-ssh` 的集成测试会在**同一个 JVM 里内嵌一个真实的 MINA SSHD 服务端**，
从而在不启动模拟器的前提下验证「SSH 握手 → direct-tcpip → 本地监听 → HTTP 取数」
整条链路。

---

## 3. 开发环境（Windows WSL）

本机条件：无 root、`sudo` 不可用、`$HOME` 不可写、`dnf` 因 CentOS Stream 8 已 EOL 不可用。
因此**全部工具链都装在工程目录内的 `.toolchain/`**，纯解压式、可复现。

```bash
# 1) 安装 JDK17 + Gradle 8.9 + Android SDK（platform-tools / platform-35 /
#    build-tools 35 / emulator / API35 系统镜像）
python3 scripts/provision.py

# 2) 之后每个终端都先加载环境
source env.sh
```

`scripts/provision.py` 的三个关键设计（都是被本机实际条件逼出来的）：

| 问题 | 处理方式 |
|---|---|
| `dl.google.com` 的 DNS 解析一会儿 0.3s、一会儿 10s+ | 先探测可用 CDN 边缘 IP，再用 `curl --resolve` 直连，完全绕开 DNS |
| 带宽上限约 300–570 KB/s，动辄 1.7GB 下载 | 全部下载可续传（`curl -C -`）、按关键路径排序、sha1 校验、下完即删 zip |
| `sdkmanager` 走 Java DNS 会被卡住 | 直接解析官方仓库 XML，按包路径精确下载并解包到正确目录 |

镜像选择：JDK 用清华 TUNA（Adoptium），Gradle 用腾讯云，Maven 依赖用阿里云
`google`/`public` 仓库（本机直连 `maven.google.com` 会超时）。

如果 Google CDN 太慢或不可达，可整体切换到完整镜像（路径结构与官方一致）：

```bash
SDK_MIRROR=https://mirrors.cloud.tencent.com/AndroidSDK/ python3 scripts/provision.py
```

### 3.1 模拟器为什么跑在容器里，以及 adb 的两个坑

本机 glibc 是 **2.28**（CentOS Stream 8），而模拟器自带的 Qt6 库要求
**GLIBC_2.29 / GLIBC_2.30**：

```
qemu-system-x86_64: /lib64/libpthread.so.0: version `GLIBC_2.30' not found
```

所以模拟器运行在 `docker/emulator.Dockerfile`（Ubuntu 22.04，glibc 2.35）容器中：

- `--device /dev/kvm` 直通 KVM（实测 `KVM (version 12) is installed and usable.`）
- 容器自带 root，缺的系统库直接 `apt-get install`，不必像宿主机那样从 RPM 里抠

`scripts/emulator.sh` 会自己判断：能本机跑就本机跑，否则自动走容器。

在这个容器里跑通 adb，踩到了三个只有实际跑起来才会暴露的坑，都已固化进脚本：

| 现象 | 原因 | 处理 |
|---|---|---|
| `qemu_thread_create: Operation not permitted` | Docker 默认 seccomp 对 `clone3` 返回 EPERM | 加 `--security-opt seccomp=unconfined`；QEMU 还需要 `--shm-size=2g` |
| 宿主 `adb devices` 永远是 `offline` | 模拟器把 adb 私钥放在 `$ANDROID_EMULATOR_HOME/adbkey`（**不看 `HOME`**），而宿主 adb 用的是 `$HOME/.android/adbkey`，两把钥匙不同 | `scripts/emulator.sh` 在启动前后同步这把密钥到宿主 adb 的 HOME |
| 宿主 `adb` 崩溃：`Cannot mkdir '/home/lzh/.android'` | 本机 `$HOME` 不可写；adb 只认 `HOME`，不认 `ANDROID_USER_HOME` | `scripts/adb` 包装器把 `HOME` 指向 `.toolchain/adb-home`；`scripts/connected-test.sh` 再为 AGP 的 adb 子进程做同样处理 |
| AGP 报 `No connected devices!` | ddmlib **不认 `ADB_SERVER_SOCKET`**，只认 `ANDROID_ADB_SERVER_PORT` | 两个变量都导出（见 `scripts/emulator.sh`） |
| `emulator-5554` 在容器内可见、宿主不可见 | 这是 Docker Desktop：容器在**独立 VM 的命名空间**里，`--network host` 并不会与 WSL 共享网络；而模拟器又把 5554/5555 绑在容器自己的 `127.0.0.1`，端口发布也穿不过去 | 最终把**测试构建直接放进容器里跑**（`scripts/connected-test.sh` 检测到容器在跑就用 `docker exec`），adb 一跳都不需要跨命名空间 |

JDK、Android SDK、Gradle 及其缓存都以**完全相同的路径**挂载进容器，因此容器内构建与宿主构建等价，
只是少了 adb 那一跳。

---

## 4. 构建与发布

```bash
# Debug 包：已用 debug 证书签名，装到设备上直接用
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Release 包：需要先有签名密钥（见下），否则产物是 unsigned、装不上
scripts/make-keystore.sh              # 一次性，会让你输入自己的口令
./gradlew :app:assembleRelease        # -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:bundleRelease          # -> app/build/outputs/bundle/release/app-release.aab（上架 Google Play 用）
```

### 4.1 签名密钥

`scripts/make-keystore.sh` 生成两样东西，**都在 `.gitignore` 里**：

| 文件 | 说明 |
|---|---|
| `.toolchain/keystore/release.jks` | PKCS12 密钥库，RSA 4096，有效期 10000 天 |
| `keystore.properties` | Gradle 读取的口令与路径（`chmod 600`） |

**请立刻把这它们备份到安全的地方。**Android 只允许用**同一把密钥**更新已安装的应用：
密钥丢了，就再也无法给已装的 App 推送更新（Google Play 上，如果启用了 Play App Signing，
丢的是*上传密钥*还能申请重置；丢了*应用签名密钥*则不可恢复）。同理，这把密钥不能提交到仓库——
`.gitignore` 已屏蔽 `keystore.properties`、`*.jks`、`*.keystore`。

没有密钥时 `assembleRelease` 不会失败，只是产出未签名的包，并打印提示告诉你该跑哪个脚本。

### 4.2 实测的产物属性

| 项 | 值 |
|---|---|
| APK 签名方案 | `apksigner verify` → `Verifies`，**v3**（v1/v2 关闭） |
| AAB 签名 | `jarsigner -verify` → `jar verified`（自签名证书的 "invalid certificate chain" 警告是正常的） |
| 16KB 页对齐 | 签名后仍然 `zipalign -c -P 16 -v 4` → `Verification succesful` |
| minSdk / targetSdk | 35 / 35（因此只能装到 **Android 15 及以上**） |
| 体积 | APK 19.9 MB、AAB 6.9 MB（debug 包 26.5 MB） |

关于签名方案：AGP 在这里是**二选一**，不是叠加——不设 `enableV3Signing` 时产出 v2-only，
设了则产出 v3-only（都实测过）。选 v3，因为它支持日后轮换签名密钥，而本应用支持的设备
全都远超引入 v3 的 Android 9。

## 5. 测试与验证

### 5.1 纯 JVM（不需要模拟器，秒级反馈）

```bash
./gradlew :core-forward:test :core-ssh:test     # 32 个测试
```

覆盖：端口校验、并发连接、4MB 载荷完整性、半关闭语义、只监听回环地址、
空闲回收、端口占用、重连退避、TOFU 首次信任/二次信任/密钥变更拒绝、
密码与 **ed25519 私钥（含 bcrypt 加密私钥的口令）** 认证、
认证失败不重试、以及**内嵌真实 SSH 服务端的端到端转发**。

### 5.2 模拟器端到端

```bash
scripts/emulator.sh accel              # Gate 0：确认 KVM 可用（必要时自动走容器）
scripts/emulator.sh create
scripts/emulator.sh start
scripts/run-test-sshd.sh container     # 容器内的「远端」：sshd:2222 + HTTP:18080
scripts/connected-test.sh              # 全部用例
scripts/connected-test.sh dev.sshrelay.TunnelEndToEndTest   # 或按类分批
```

两个运行入口，按内存余量选：

| 命令 | 说明 |
|---|---|
| `scripts/connected-test.sh` | 经 Gradle + UTP，有 HTML 报告；会先停掉 Gradle 守护进程、以单 JVM 低内存模式运行，并为 AGP 的 adb 子进程准备好可写的 `HOME` |
| `scripts/run-instrumented.sh` | 直接 `adb install` + `am instrument`（APK 已构建好），省掉约 1.2GB 构建 JVM，按类分批跑 |

> 本机 WSL 共 7.6GB，模拟器固定占用约 3GB（客机 RAM 被模拟器抬到 2560MB，且无视 `-memory`）。
> 若宿主上另有大型容器（例如 `gitlab-13` 实测占 3.1GB），叠加后必然 OOM、模拟器被杀，
> 外部表现是"设备中途消失"。内存紧张时：用 `run-instrumented.sh` 按类跑，
> 或用 `LOWRAM=1 scripts/emulator.sh start` 让客机自己少占资源。

仪器化测试（`app/src/androidTest`）：

| 测试 | 验证内容 |
|---|---|
| `TunnelEndToEndTest` | 设备内建立真实 SSH 隧道，取回 `http://127.0.0.1:18080/`；断言页面标记、断言 HTTP 服务看到的来源是 `127.0.0.1`（证明请求确实穿过隧道）、断言设备 LAN 地址连不通（证明只绑定回环）、6 路并发请求；错误密码进入 `Failed` 而非无限重连 |
| `BrowserAcceptanceTest` | **用 App 内置 WebView（真实 Chromium 引擎）打开转发地址**，读取 `document.body.innerText` 断言其中同时含页面标记与 `127.0.0.1`，并自动截图存证；另有一个"保持隧道开启"的用例，供宿主侧脚本做跨应用验收 |
| `DeviceOnlyBehaviourTest` | Android Keystore 加解密往返、前台服务通知构造与停止动作 |

### 5.3 浏览器验收取证

```bash
scripts/verify-browser.sh              # 跨应用浏览器验收（服务端访问日志判定）
scripts/verify-remote-forwarding.sh    # 用标准 OpenSSH 客户端独立佐证
scripts/pull-evidence.sh               # 取回测试内自动截取的截图
```

`verify-browser.sh` 的判定完全不依赖"看截图"：它把带唯一标记的 URL 交给系统浏览器，
再在测试 HTTP 服务端的访问日志里找那条请求——日志同时记录来源地址与 User-Agent，
因此"另一个应用确实通过隧道取到了页面"是可判定的。实测通过：

```
http request #4  uri=/?proof=1789527641  peer=/127.0.0.1:60820
  ua=Mozilla/5.0 (...) ... Chrome/124.0.6367.219 Mobile Safari/537.36
```

产物在 `artifacts/` 与 `artifacts/device/`：
- `openssh-forward.html`：标准 `ssh -L` 转发后取回的页面（不依赖本项目代码的基线）
- `app-tunnel-running.png`：主界面的隧道运行状态
- `browser-webview-forward.png`：**应用内 WebView（Chromium）渲染出的转发页面**
- `browser-system-app.png`：交给系统浏览器后的屏幕状态

跨应用判定不依赖"看截图"：测试 HTTP 服务端会记录每个请求的 URI 与 **User-Agent**，
因此一条带 `Chrome/` 的 User-Agent、且 URI 含本次探测标记的请求，就是"另一个应用（浏览器）
确实通过隧道取到了页面"的确凿证据。

---

## 6. Android 15（API 35）相关实现要点

| 约束 | 本项目的处理 |
|---|---|
| 前台服务必须声明类型 | 使用 `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="ssh_port_forwarding"`。**不能用 `dataSync`**：Android 15 对它有 6 小时/天的累计上限，长连接隧道会被系统掐断 |
| 后台不能启动前台服务 | 只从可见 UI 的按钮启动，并捕获启动失败并提示 |
| `POST_NOTIFICATIONS` 运行时权限 | 已在清单声明；未授权时前台服务照常运行 |
| 非特权应用不能绑定 <1024 端口 | UI 与 `PortPolicy` 双重校验，直接给出中文原因而不是运行时 EACCES |
| targetSdk 35 强制边到边 | `enableEdgeToEdge()` + Compose insets 处理 |
| 16KB page size 要求 | 选用纯 Java 的 JSch，**不含任何 `.so`**，天然规避 |
| 明文流量 | 系统浏览器不受影响；内置 WebView 通过 `network_security_config` 仅对 `127.0.0.1`/`localhost` 放开 |

安全默认值：

- 只绑定 `127.0.0.1`，**从不绑定 `0.0.0.0`**（设备上回环无跨应用隔离，绑 0.0.0.0 等于把隧道暴露到整个局域网）
- 主机密钥 TOFU：首见即记录，变更即拒绝
- 凭据仅以 Keystore 加密形式落盘

---

## 7. 已知限制

- 仅实现 `-L`（本地转发）。`-R` / `-D`(SOCKS) 未实现。
- 已知主机指纹的删除入口在界面「已知主机密钥」区域，属于手动操作（符合预期：防止误点绕过告警）。
- 模拟器与真机在**厂商省电策略**上仍有差异；本项目的保活手段是前台服务 + SSH keepalive，
  真机若仍被清理，需要在系统设置里关闭对该应用的电池优化。
- 空闲连接 10 分钟自动回收（防止半开连接长期占用 SSH 通道）。
