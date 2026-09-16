# 实施方案（已批准并执行中）

> 本文件是开工前制定的方案原文，加上执行过程中被实测推翻/修正的部分。
> 后续章节用「执行记录」标注实际结果。

## 0. 环境勘察结论（实测）

| 项 | 实测结果 | 影响 |
|---|---|---|
| WSL | WSL2 / CentOS Stream 8 / kernel 5.15.167.4 | 非主流发行版，dnf 仓库已 EOL，**不依赖包管理器** |
| 嵌套虚拟化 | `/dev/kvm` 存在且 `crw-rw-rw-`，CPU 有 vmx，16 核 | 模拟器可在 WSL 内跑 KVM 加速（待 Gate 0 实测） |
| 内存 | 总计 7.6GB，可用 ~4.0GB | 偏紧：模拟器 + Gradle 需限流 |
| 磁盘 | `/` 892GB 可用 | 充裕 |
| JDK | 仅 JRE 1.8，无 `javac` | 必须自装 JDK 17 |
| Android SDK | 完全没有；Windows 侧也没有 | 全量自装 |
| 权限 | 无 root、`sudo` 不可用、`$HOME` **不可写**、`/tmp` 与工作区可写 | 全用户态安装，且必须装在工作区内 |
| 网络 | 带宽上限约 300–570 KB/s；`maven.google.com` 直连超时；`dl.google.com` DNS 抖动 0.3s↔10s | 必须用国内镜像 + `curl --resolve` |
| 系统时钟 | **2026-09-15** | 版本选型需按 2026 年现状校准 |

## 1. 目标与验收标准

1. `minSdk 35 / targetSdk 35 / compileSdk 35`（Android 15+）
2. 可配置 SSH 连接（主机/端口/用户名/密码或私钥+passphrase）
3. 可配置 ≥1 条本地转发规则
4. 启动后设备上 `127.0.0.1:<本地端口>` 监听，**手机浏览器访问即可打开远端服务**
5. 前台服务常驻、通知栏可停止、断线自动重连
6. 在 WSL 模拟器（API 35）上端到端验证并留存截图证据，产出可安装 debug APK

## 2. 技术选型（含理由）

| 层面 | 选择 | 理由 |
|---|---|---|
| 语言/UI | Kotlin 2.0.21 + Jetpack Compose (Material 3) | targetSdk 35 强制边到边，Compose 处理 insets 最省事 |
| 构建 | Gradle 8.9 + AGP 8.7.3 + JDK 17 | 互相兼容的稳定组合；2026 年的 AGP 9.x/BOM 2026.x 要求 compileSdk 36+，与「Android 15+」目标无益且增加风险 |
| SSH 传输 | `com.github.mwiede:jsch:0.2.21` | 纯 Java、零传递依赖、**无 `.so`** → 天然规避 Android 15 的 16KB page size 强制要求；支持 ed25519/ecdsa/rsa-sha2/curve25519/aes-gcm，兼容 OpenSSH 9.x 默认算法 |
| 端口转发 | 自研 `LocalForwardServer` | 不用 JSch 内建 `setPortForwardingL`：自研才能精确控制绑定地址（只绑 127.0.0.1）、端口冲突处理、连接计数、空闲回收、多规则并行 |
| 并发 | kotlinx-coroutines | 与 Compose/ViewModel 生命周期衔接 |
| 配置存储 | DataStore(Preferences) + org.json | 手写 JSON，避免引入 kotlinx-serialization 插件 |
| 凭据存储 | Android Keystore + AES-GCM 自封装 | 不用长期 alpha 且已弃用的 `androidx.security:security-crypto` |
| DI | 手写 `AppGraph` | 少一个注解处理器（KSP）就少一处首次构建失败点 |
| 测试 SSH 服务端 | Apache MINA SSHD（仅测试依赖） | 纯用户态、零 root，可内嵌进 JVM 测试，也可作为宿主侧「远端」 |

## 3. 工程结构

见 `README.md` 第 2 节。核心是把转发引擎与 SSH 传输层放进**纯 JVM 模块**
（`core-forward` / `core-ssh`），使大部分验证不需要模拟器。

## 4. Android 15 关键约束

见 `README.md` 第 5 节。最重要的一条：前台服务用 **`specialUse`**，
绝不能用 `dataSync`（Android 15 对 dataSync 有 6 小时/天累计上限，会掐断长连接隧道）。

## 5. 环境搭建（全用户态、全在工作区内）

1. 下载 Temurin JDK 17、Gradle 8.9、Android cmdline-tools
2. 按官方仓库 XML 安装 `platform-tools` / `platforms;android-35` /
   `build-tools;35.0.0` / `emulator` / `system-images;android-35;default;x86_64`
3. AVD、Gradle 缓存、SDK 全部落在 `.toolchain/`，**全程不写 `$HOME`**
4. Gate 0：先 `emulator -accel-check` 实测 KVM 可用

**执行中的关键修正**：不下载 `google_apis_playstore` 镜像（1680MB），
改用 `default;x86_64`（746MB）——省下约 1 小时下载，且浏览器能力由
「内置 WebView + `adb shell` 跨 UID 访问」两条证据覆盖；
若后续确需真 Chrome 再补装。

## 6. 验证策略（四层）

| 层 | 手段 | 是否需要模拟器 |
|---|---|---|
| L1 | JVM 单测（端口策略、转发引擎并发/完整性/半关闭/回环限定/空闲回收） | 否 |
| L1.5 | **JVM 内嵌真实 MINA SSHD 的端到端测试**（握手→direct-tcpip→回环监听→HTTP 取数、TOFU 三种裁决、认证失败不可重试） | 否 |
| L2 | 模拟器仪器化测试（对着宿主侧 sshd 跑真实隧道，含 6 路并发、LAN 地址不可达断言） | 是 |
| L3 | 浏览器验收取证（跨 UID 访问 + App 界面/系统浏览器截图） | 是 |

## 7. 里程碑

| 里程碑 | 验收物 | 状态 |
|---|---|---|
| M0 环境就绪 | 工具链 + `adb devices` + KVM 可用 + 模拟器 `sys.boot_completed=1` | 工具链与 KVM 已完成；模拟器待系统镜像下载完成 |
| M1 骨架 | 三模块工程 + debug APK | ✅ 已完成（APK 26.5MB，`specialUse` FGS 与 16KB 对齐已验证） |
| M2 core-forward | L1 单测全绿 | ✅ 17/17 |
| M3 core-ssh | L1.5 端到端绿 | ✅ 15/15（含内嵌真实 SSH 服务端） |
| M4 前台服务与持久化 | specialUse FGS + 通知 + Keystore + DataStore | 代码完成、编译通过，待在设备上验证 |
| M5 UI 完整化 | 表单/多规则/日志/WebView 预览 | 代码完成、编译通过 |
| M6 验收与文档 | L2/L3 证据 + APK + README | 进行中 |

### 执行过程中的额外发现（原始方案未覆盖）

| 发现 | 处理 |
|---|---|
| 本机 curl 为 7.61，不支持 `--retry-all-errors` / `--no-progress-meter` | 改用兼容参数组合 |
| 手工解包的 SDK 缺少 `package.xml`，AGP 会退化去拉远程清单并**卡死**（本网络会 reset 连接） | `provision.py` 依官方仓库 XML 生成 `package.xml`；同时 `android.builder.sdkDownload=false` 使其快速失败而非挂起 |
| AGP 8.7 默认要 build-tools **34.0.0** | 显式 `buildToolsVersion = "35.0.0"`，省掉 60MB 下载 |
| Google CDN 多个边缘 IP 速度差异达 40 倍，且会在下载中途僵死 | 改为**按实测吞吐排序**选边缘；并支持 `SDK_MIRROR` 整体切换镜像 |
| 系统镜像归档 URL 相对于 `sys-img/<tag>/` 而非 `repository/` | 每个仓库 XML 记录自己的 base URL |
| 模拟器自带 Qt6 需要 glibc 2.29/2.30，本机只有 2.28 | 模拟器改在 Ubuntu 22.04 容器内运行（`--device /dev/kvm` + `--network host`），adb/Gradle 仍留在 WSL |
| 宿主机缺 `libpulse` 等库且无 root | `scripts/fetch-system-libs.sh` 从 EL8 RPM 提取到本地 sysroot（本机原生运行时用；容器内直接 apt 安装） |
| MINA SSHD 的 ed25519 支持在可选依赖 `net.i2p.crypto:eddsa` 里，缺失时无法校验 ed25519 客户端公钥 | 测试依赖补上该库，从而可以验证 ed25519 私钥认证 |
| 私钥口令错误会被 JSch 归为通用「Auth fail」，误导用户 | 连接前先用 `KeyPair.load/decrypt` 预校验，给出精确报错并归为 PROTOCOL |
| `TunnelManager` 统计口径：上游连不上时 `totalConnections` 不增长，界面看起来像"完全没流量" | 改为按 accept 计数 |

## 8. 风险与回退

| 风险 |
|---|
|---|---|
| KVM 不可用 | Windows 侧跑模拟器，WSL 侧 `adb connect <winIP>:5555`；或只保留 L1/L1.5 证据 |
| 内存不足 | 模拟器降到 1536MB、串行测试、关 Gradle daemon；必要时由用户在 Windows 侧 `.wslconfig` 调大 memory |
| JSch 算法协商失败 | `SshTransport` 已抽象，可切 sshj / Apache MINA |
| 镜像下载失败/过慢 | 退到更小的 AOSP 镜像（已采用），浏览器证据退到内置 WebView |
| 模拟器与真机差异 | 回环语义一致，核心链路可验证；真机差异主要在省电策略，README 已说明 |
