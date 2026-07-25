# 随心一听

面向 OPPO OWW221 / Android API 30 的手表音乐播放器扩展。项目通过独立 `network-bridge` DEX 接入网易云账号资料库与实时音频，并复用现有手表播放器的 UI 和本地功能。

## 下载

可安装 APK 位于 [GitHub Releases](../../releases)。当前版本为 `v1.3.0`，包名 `com.poyi.suixinyiting`。

## 当前能力

- 网易云二维码登录与 Android Keystore 会话保存。
- 我喜欢、歌单、专辑、歌手和真实随机播放队列。
- 1242 首测试资料库完整加载；音频按需播放，不批量下载。
- 完整歌词、封面背景、触摸和表冠歌词浏览。
- Hi-Res、无损、极高、较高、标准首选档位及实际源音质显示。
- 显示耳机实际蓝牙 Codec、码率、采样率和位深。
- 系统 MediaSession、通知、音频焦点、媒体按键和系统音量。

![播放页音质显示](docs/screenshots/verify-1.2.9-player.png)

## 源码结构

- `network-bridge/src/`：网络源、资料库、随机队列、播放器服务和母版 UI 桥接。
- `scripts/`：DEX 编译、母版补丁、APK 构建和清单审计脚本。
- `analysis/`：行为审计、网络流量测试和设置功能报告。
- `docs/bugfixes/`：逐版本 Bug 修复与真机验证记录。
- `DEVELOPMENT_LOG.md`：开发、操作和需求汇总。

## 项目文档

长期维护从 [文档中心](docs/INDEX.md) 开始：

- [项目长期维护手册](docs/PROJECT_HANDBOOK.md)
- [用户需求基线](docs/REQUIREMENTS.md)
- [系统架构](docs/ARCHITECTURE.md)
- [开发指南](docs/DEVELOPMENT_GUIDE.md)
- [测试与回归矩阵](docs/QA_REGRESSION.md)
- [Bug 总表](docs/BUG_CATALOG.md)
- [发布与回滚](docs/RELEASE_PROCESS.md)
- [变更与日志规范](docs/CHANGE_MANAGEMENT.md)

## 构建

构建环境：Windows PowerShell、JDK 8+、Android SDK Platform 35、Build Tools 35.0.0、apktool 3.0.3。

1. 将你有权使用的母版 APK 解包到 `work/suixin-apktool/`。
2. 将 `apktool.jar` 放到 `tools/apktool.jar`。
3. 执行：

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:SUIXIN_KEYSTORE = 'C:\path\to\your.keystore'
$env:SUIXIN_KEY_ALIAS = 'your-key-alias'
$env:SUIXIN_KS_PASS = 'your-keystore-password'
$env:SUIXIN_KEY_PASS = 'your-key-password'
& scripts\build_suixin_network.ps1
```

构建脚本会编译 `network-bridge`、应用 Smali/Manifest 补丁、注入 `classes2.dex`、zipalign 并签名。签名密钥属于本地凭据，不在仓库中；首次构建请在脚本中配置自己的 keystore。使用不同签名覆盖安装会触发 Android 签名不匹配。

## 已验证设备

- OPPO OWW221 / API 30
- OPPO Enco Free4：AAC 165 kbps、48 kHz、16-bit、立体声
- 当前验证曲目源：无损 FLAC 1598.781 kbps

详细迭代历史见 [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) 和 [BUGFIX_LOG.md](BUGFIX_LOG.md)。
