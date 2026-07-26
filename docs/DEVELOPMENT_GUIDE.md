# 随心一听开发指南

## 1. 环境

当前本机基线：

- Windows PowerShell
- JDK 8+（源码目标 Java 8）
- Python 3
- Android SDK Platform 35
- Android Build Tools 35.0.0
- apktool 3.0.3
- ADB
- OPPO OWW221 / API 30 真机

公开仓库构建使用环境变量：

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:SUIXIN_KEYSTORE = 'C:\path\to\your.keystore'
$env:SUIXIN_KEY_ALIAS = 'your-key-alias'
$env:SUIXIN_KS_PASS = 'your-keystore-password'
$env:SUIXIN_KEY_PASS = 'your-key-password'
```

签名值不得写入 Git、Markdown、脚本默认值或终端截图。

## 2. 工作区

| 路径 | 用途 | 是否提交公开仓库 |
| --- | --- | --- |
| `network-bridge/src/` | 可维护 Java 业务源码 | 是 |
| `scripts/` | 编译、补丁、构建和清单脚本 | 是 |
| `docs/`、`analysis/` | 规范、需求、验证和调查 | 是 |
| `work/suixin-apktool/` | 当前补丁化母版 | 否 |
| `reverse/` | Apktool/JADX 分析输出 | 否 |
| `artifacts/build/` | APK、签名密钥、截图和中间产物 | APK 走 Release；密钥永不提交 |
| `network-bridge/build/`、`deps/` | 编译输出和下载依赖 | 否 |
| `publish/SuixinYiTing/` | 干净公开 Git 仓库工作树 | 是，独立 Git 仓库 |

## 3. 开始一个版本

1. 阅读 `docs/INDEX.md`、需求条目和相关 Bug 历史。
2. 检查工作区：`git status --short`，不得清理或覆盖未知用户修改。
3. 确认上一版 APK、SHA-256 和真机安装版本。
4. 提升 `versionName` 和 `versionCode`。
5. 创建 `docs/bugfixes/<version>.md`，先写需求、根因假设和测试计划。
6. 在 `DEVELOPMENT_LOG.md` 追加开始记录。

## 4. 修改原则

- 优先在 `network-bridge` 实现业务；Smali 只做入口、事件转发和母版资源桥接。
- 数据解析使用 JSON/SQLite API，不用脆弱字符串截取。
- 菜单路由绑定语义，不绑定插入后的绝对位置。
- 播放服务是播放状态唯一所有者，Activity 不复制队列或播放器状态。
- 所有异步 UI 回调先确认 Activity、歌曲 ID 或请求 token 仍有效。
- 不在日志输出 Cookie、二维码登录凭证、完整短期 URL 或签名信息。
- 不修改与当前需求无关的母版功能。

## 5. 构建

本机完整构建：

```powershell
Set-Location 'C:\开发\手表音乐软件'
powershell -ExecutionPolicy Bypass -File scripts\build_suixin_network.ps1
```

仅编译 bridge：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build_network_bridge.ps1
```

成功条件：

- javac 和 D8 返回 0。
- apktool build 成功。
- zipalign 成功。
- `apksigner verify` 的 v1/v2/v3 至少符合当前设备安装要求。
- 输出 APK 名含新版本号，上一版文件仍存在。
- 记录 SHA-256。

## 6. ADB 安装

多设备在线时始终显式指定设备：

```powershell
$device = '192.168.1.44:5555'
adb -s $device shell getprop ro.product.model
adb -s $device install -r artifacts\build\suixinyiting-network-<version>.apk
adb -s $device shell dumpsys package com.poyi.suixinyiting |
  Select-String 'versionName|versionCode'
```

禁止在存在多台设备时使用无 `-s` 的安装、卸载、清数据或设置命令。

覆盖安装只用 `install -r`。测试登录/资料库保留时禁止 `pm clear` 和卸载重装。

### 6.1 网络 ADB 常驻

手表是 production `user` 构建，无法把网络调试写进设备本身：

- `adb shell setprop persist.adb.tcp.port 5555` → `Failed to set property`。
- `adb shell settings put global adb_wifi_enabled 1` → 框架立即回滚为 `0`，
  Android 11 无线调试不可用。

因此 `adb tcpip 5555` 只在本次开机内有效，常驻由 PC 侧保活：

```powershell
# 前台运行（当前会话保活）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\watch_adb_keepalive.ps1

# 单次对账，用于排查
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\watch_adb_keepalive.ps1 -Once

# 注册为登录自启的计划任务（长期常驻，需本人执行一次）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install_watch_adb_task.ps1

# 卸载计划任务
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install_watch_adb_task.ps1 -Remove
```

保活逻辑：

1. 端点健康时只做 `shell echo ok` 探活，不做多余操作。
2. 断线时先用缓存端点 `adb connect` 重连。
3. 重连失败且 USB 在位时，重新 `adb tcpip 5555`，回读 `wlan0` 地址并重连
   （手表重启后就靠这一步恢复）。
4. DHCP 换租约时按同网段 ARP 表逐个尝试，命中后更新缓存端点。

状态与日志：`artifacts/adb/watch-endpoint.json`、
`artifacts/adb/watch_adb_keepalive.log`。


## 7. 调试命令

播放与崩溃：

```powershell
adb -s $device shell logcat -c
adb -s $device shell logcat -v time `
  SuixinNetease:V SuixinPlaybackBridge:V AndroidRuntime:E '*:S'
```

MediaSession：

```powershell
adb -s $device shell dumpsys media_session |
  Select-String 'SuixinNetwork|description=|state=PlaybackState'
```

系统音量：

```powershell
adb -s $device shell dumpsys audio |
  Select-String 'STREAM_MUSIC|bt_a2dp'
```

蓝牙 Codec：

```powershell
adb -s $device shell dumpsys bluetooth_manager |
  Select-String 'mCodecConfig|Current Codec|A2dpStateMachine'
```

截图与 UI 层级：

```powershell
adb -s $device shell screencap -p /sdcard/verify.png
adb -s $device pull /sdcard/verify.png artifacts\build\verify.png
adb -s $device shell uiautomator dump /sdcard/window.xml
adb -s $device pull /sdcard/window.xml artifacts\build\window.xml
```

## 8. 数据迁移

- SQLite schema 变化必须提升数据库版本并提供向前迁移。
- 不删除未知列或整库重建来解决迁移错误。
- 新增字段提供默认值，升级前后验证歌单数、歌曲数、专辑数和歌手数。
- 会话存储格式变化要兼容旧值或提供明确重新登录流程。
- 清缓存、退出登录、重置资料库必须是三个不同操作。

## 9. 代码检查

发布前至少执行：

```powershell
rg -n --hidden -S 'MUSIC_U=|MUSIC_A=|github_pat_|gho_|PRIVATE KEY' .
git status --short
Get-FileHash artifacts\build\suixinyiting-network-<version>.apk -Algorithm SHA256
```

公开仓库还要确认 `.gitignore` 排除 keystore、APK、DEX、反编译树、缓存和本机会话。

## 10. 故障处理

- 构建失败：保留首个有效错误，修正后从失败阶段重新执行，不安装旧/半成品。
- 安装签名不匹配：核对 keystore，不通过卸载规避数据迁移验证。
- UI 状态陈旧：先核对服务 ACTION_STATE、`REQUEST_STATE` 和 Activity 生命周期。
- 能播放但系统不识别：核对 MediaSession active state、AudioFocus 和 playback state。
- 播放无声：按“地址解析 → Range 数据 → prepared → STREAM_MUSIC → 输出路由”顺序定位。
- 网络失败：分别验证账号接口、播放域名、系统公网连通性、Wi-Fi/蜂窝路由和计费状态。
