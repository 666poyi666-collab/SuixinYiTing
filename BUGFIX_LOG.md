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

## 发布规则

1. 修改前创建该版本的 Bug 文档。
2. 新修复必须提升 `versionCode` 和 `versionName`，禁止覆盖上一版本 APK。
3. 每次发布回归：冷启动、登录保留、歌单、播放、封面、歌词、seek、音质、系统音量、MediaSession、设置路由、旧包状态。
4. 任何回归失败都停止发布，并记录失败现象、根因和修复结果。
5. 成品记录 SHA-256、安装版本和真机日志。
- 1.2.6：修复网络音乐后台正常播放但母版播放页仍显示“未播放”，并确保当前源音质和耳机实际 Codec 随状态一起恢复。
- 1.2.7：修复耳机已连接后应用冷启动无法显示实际 A2DP Codec；无需手动重连即可恢复音质标签。
- 1.2.8：修复 378px 屏幕只显示到“耳机 AAC”、精确码率/采样率/位深被裁切的问题。
- 1.2.9：修复音质详情标题与五个音质选项重叠。
- 1.2.9 验收：音质行与面板不重叠，源/耳机参数完整可见；seek、歌词居中、封面、MediaSession、音量衰减和后台恢复回归通过。
