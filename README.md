# 随心一听

面向 OPPO OWW221/API 30 的手表音乐播放器。项目以现有手表播放器母版为 UI 和本地功能基础，通过独立 `network-bridge` dex 接入网易云账号、资料库和实时音频。

## 当前能力

- 网易云二维码登录与会话保存。
- 我喜欢、歌单、专辑、歌手和真实随机播放队列。
- 千首列表完整加载，资料库元数据本地缓存，音频按需播放。
- 完整歌词、封面背景、触摸/表冠歌词浏览，换行只移动颜色 Span 不重排整块。
- Hi-Res、无损、极高、较高、标准首选档位及实际音质显示。
- 系统媒体会话、通知、音频焦点、媒体按键；系统 `STREAM_MUSIC` 是唯一音量控制源，应用不再叠加增益。
- 默认 512MB 持久分块缓存、自适应预缓存、严格 LRU 和安全播放恢复。
- 播放页、歌词页、音量面板、菜单和资料库按 378x496 手表屏重构。
- 原母版本地扫描、文件传输、主题、背景和本地播放器设置。

## 目录

- `network-bridge/`：网络源、资料库、随机队列、网络播放器与母版播放页桥接。
- `res-overlay/`：手表屏 UI 资源，由 `patch_suixin.py` 幂等覆盖到母版树。
- `work/suixin-apktool/`：已反编译和补丁化的 APK 母版。
- `scripts/`：补丁、bridge 编译、APK 构建、网络 ADB 保活和发布同步脚本。
- `analysis/`：行为审计、基线报告和待开发事项。
- `artifacts/build/`：构建产物。
- `DEVELOPMENT_LOG.md`：版本、操作、Bug 修复和需求总记录。

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

```powershell
# 补丁 + 编译 bridge + 打包 + 对齐 + 签名，一步到位
& scripts\build_suixin_network.ps1
```

该脚本依次执行 `patch_suixin.py`（母版 smali、品牌与 `res-overlay/` 资源覆盖）、`build_network_bridge.ps1`（javac + d8 生成 `classes2.dex`）、apktool 打包、dex 注入、zipalign 和 apksigner。

现有真机升级必须沿用 `artifacts/build/suixin-debug.keystore`，否则会丢失登录和资料库数据。

调试期间保持网络 ADB 常驻：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\watch_adb_keepalive.ps1
```

细节见[开发指南 6.1 网络 ADB 常驻](docs/DEVELOPMENT_GUIDE.md)。

## 目标设备

- OPPO OWW221
- Android API 30
- 应用包名：`com.poyi.suixinyiting`

具体迭代历史见 [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md)，当前需求状态以 [REQUIREMENTS.md](docs/REQUIREMENTS.md) 为准。
