# Bug 修复索引

每个修复版本使用独立文档和独立 APK 文件名。已经发布的 APK 不覆盖、不改名、不删除。

| 版本 | 主要修复 | 文档 | APK |
| --- | --- | --- | --- |
| 1.2.0 | 菜单、资料库、队列统一 | `DEVELOPMENT_LOG.md` | `artifacts/build/suixinyiting-network-1.2.0.apk` |
| 1.2.1 | 音质切换与实际码率显示 | `DEVELOPMENT_LOG.md` | `artifacts/build/suixinyiting-network-1.2.1.apk` |
| 1.2.2 | seek、音量、旧包解耦、设置审计 | `analysis/SETTINGS_AUDIT_1.2.2.md` | `artifacts/build/suixinyiting-network-1.2.2.apk` |
| 1.2.3 | 歌词几何居中、音量一致性及防回归 | `docs/bugfixes/1.2.3.md` | `artifacts/build/suixinyiting-network-1.2.3.apk` |
| 1.2.4 | 低端音量曲线与 Wi-Fi 流量测量 | `docs/bugfixes/1.2.4.md` | `artifacts/build/suixinyiting-network-1.2.4.apk` |
| 1.2.5 | 端到端音质识别与蓝牙 Codec 审计 | `docs/bugfixes/1.2.5.md` | `artifacts/build/suixinyiting-network-1.2.5.apk` |
| 1.2.6 | 前台恢复时主动回填网络播放状态 | `docs/bugfixes/1.2.6.md` | `artifacts/build/suixinyiting-network-1.2.6.apk` |
| 1.2.7 | A2DP Codec 变化识别和持久化 | `docs/bugfixes/1.2.7.md` | `artifacts/build/suixinyiting-network-1.2.7.apk` |
| 1.2.8 | 378px 播放页音质信息完整展示 | `docs/bugfixes/1.2.8.md` | `artifacts/build/suixinyiting-network-1.2.8.apk` |
| 1.2.9 | 音质面板布局重构和真机验收 | `docs/bugfixes/1.2.9.md` | `artifacts/build/suixinyiting-network-1.2.9.apk` |
| 1.3.0 | 播放页/歌词页流畅度、内存与暂停功耗优化 | `docs/bugfixes/1.3.0.md` | `artifacts/build/suixinyiting-network-1.3.0.apk` |
| 1.4.0 | 持久块缓存、自适应预取、播放状态与恢复治理 | `docs/bugfixes/1.4.0.md` | `artifacts/build/suixinyiting-network-1.4.0.apk` |
| 1.5.0 | 手表 UI 重构、系统媒体音量归一与渲染开销治理 | `docs/bugfixes/1.5.0.md` | `artifacts/build/suixinyiting-network-1.5.0.apk` |
| 1.5.1 | 设置页去品牌、全局过渡动画与图标刷新 | `docs/bugfixes/1.5.1.md` | `artifacts/build/suixinyiting-network-1.5.1.apk` |

跨版本问题、根因、状态和回归锚点统一维护在 `docs/BUG_CATALOG.md`。

## 发布规则

1. 修改前创建该版本的 Bug 文档。
2. 新修复必须提升 `versionCode` 和 `versionName`，禁止覆盖上一版本 APK。
3. 每次发布回归：冷启动、登录保留、歌单、播放、封面、歌词、seek、音质、系统音量、MediaSession、设置路由、旧包状态。
4. 任何回归失败都停止发布，并记录失败现象、根因和修复结果。
5. 成品记录 SHA-256、安装版本和真机日志。
## 1.2.9 验收摘要

- 音质行与面板不重叠，源/耳机参数完整可见。
- seek、歌词居中、封面、MediaSession、音量衰减和后台恢复回归通过。
- 详细证据见 `docs/bugfixes/1.2.9.md` 和 `DEVELOPMENT_LOG.md`。

## 1.3.0 验收摘要

- Janky frames `57.30% → 38.19%`，P95 `150 → 32 ms`，missed vsync `43 → 1`。
- 稳定 PSS `127,384 → 41,487 KB`，Graphics `20,280 → 6,100 KB`；暂停 10 秒无新增渲染帧，后台播放停止 UI ticker。
- 歌词触摸滑动、三秒居中、封面、seek、资料库和 MediaSession 回归通过。

## 1.4.0 验收摘要

- `audio_cache.db` 保存 256KB 块位图；23,592,960 字节跨覆盖安装/服务重建复用。
- 清理释放 227,926,494 字节，活动项 25,427,968 字节延迟释放；进程重建后从 `cached=0` 重新创建，1242/1242 资料库保持。
- 后台返回按钮为两竖暂停图标；P95 27ms、missed vsync 0、PSS 45,399KB。

## 1.5.0 验收摘要

- 移除播放器二次线性增益，系统 `STREAM_MUSIC` 成为唯一衰减来源；应用内滑条
  实测把系统档位由 `5/16` 调到 `9/16`，日志恒为 `output gain=1.0 (unity)`。
- 播放页、歌词页、音量面板、菜单和资料库按 378x496 重构，菜单与资料库单屏
  可见项分别由 3 增至 4 和 5，音质信息独立成行不再被压到 8sp。
- 每秒时钟不再触发全窗口重新布局：稳态 `Slow UI thread 11/20 → 1/20`、
  P95 `44 → 34 ms`；资料库滚动 `8.39% → 3.92%` janky、P95 `17 → 13 ms`。
- 歌词高亮改为移动非度量 Span，换行不再重排整首歌词。
- 手表网络 ADB 由 `scripts/watch_adb_keepalive.ps1` 保活，断线自动重连，
  插上 USB 时自动重新 `adb tcpip`。

## 1.5.1 验收摘要

- 关于页移除原作者备案号，APK 二进制不再含该串；协议/隐私 assets 早已去品牌。
- 全局窗口过渡动画（活动页滑动+淡入、弹窗淡入），RenderThread 合成、约 200ms、
  结束即停，低功耗。
- 17 个圆底图标改为顶部微高光对角渐变，白色字形不变；纯字形 menu_* 图标不动。
- 环境受限：设置各子页逐项实拍功能测试因手表运动会话持续抢占前台未完成，
  待手表空闲后重跑。
