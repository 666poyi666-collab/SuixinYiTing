# 随心一听系统架构

## 1. 总体形态

项目采用“母版 APK + 独立 Java bridge DEX + 少量 Smali/Manifest 补丁”结构：

```text
母版 UI / 本地播放器
        |
        | Smali hook：菜单、页面、表冠、播放入口
        v
network-bridge (classes2.dex)
  |-- 登录与网易云 API
  |-- 元数据资料库
  |-- 随机队列
  |-- 网络播放器与缓存
  |-- 播放页状态桥接
        |
        +--> Android MediaSession / AudioFocus / STREAM_MUSIC
        +--> 网易云 HTTPS API / 短期音频地址
```

这套结构的目的不是重写母版，而是把可维护业务集中在 Java 模块，Smali 只承担稳定入口和资源层衔接。

## 2. 模块职责

| 类/目录 | 职责 | 禁止承担的职责 |
| --- | --- | --- |
| `NeteaseWebApi` | 匿名设备会话、二维码登录、歌单/歌曲/歌词/播放地址请求和响应解析 | UI、播放队列和数据库事务 |
| `SessionStore` | Android Keystore 加密保存登录 Cookie | 明文日志输出会话、清缓存时删除账号 |
| `PlaylistStore` | 歌单、歌曲、专辑、歌手关系和同步状态 | 音频正文与短期播放 URL 持久化 |
| `NetworkTrack` / `LibraryGroup` | 稳定的业务数据对象 | Android View 或播放器状态 |
| `ShuffleBag` | 完整可播放 ID 池、随机顺序、游标和上一首历史 | 依赖当前可见 UI 页决定随机范围 |
| `QualityPolicy` / `StreamVariant` | 首选档位、实际档位、格式、码率、有效期和降档原因 | 猜测耳机最终 Codec |
| `RangeCacheDataSource` | HTTP Range 流式读取、临时块缓存和流量日志 | 删除资料库和登录状态 |
| `NetworkStreamService` | MediaPlayer、音频焦点、MediaSession、通知、seek、预取、播放状态广播 | 直接操作母版 Activity View |
| `NetworkPlaybackBridge` | 把服务状态映射到母版标题、歌手、封面、歌词、进度和按钮 | 自己维护第二份播放队列 |
| `NetworkMusicActivity` | 我喜欢、歌单、专辑、歌手和队列列表页面 | 直接解析音频或控制系统音量 |
| `NetworkMenuRouter` / `NetworkEntry` | 原菜单语义到网络页面的稳定路由 | 按插入后的绝对位置偏移路由 |
| `NetworkAudioSettings` | 首选音质 UI 与当前实际链路展示 | 额外的应用音量控制 |
| `scripts/patch_suixin.py` | 品牌、权限、菜单 hook、播放页 hook 和版本号补丁 | 保存用户账号或签名凭据 |

## 3. 登录与资料库数据流

```text
二维码页面
  -> NeteaseWebApi 创建 key / QR
  -> 手机扫码并确认
  -> 手表轮询登录状态
  -> SessionStore 加密保存 Cookie
  -> 获取账号歌单
  -> PlaylistStore 先返回缓存
  -> 后台同步歌曲元数据
  -> 按 track_id 去重建立 album / artist 关系
```

关键不变量：

- 会话只保存在应用私有目录并经 Keystore 加密。
- UI 分页不裁剪资料库或随机候选集。
- 同一歌曲可属于多个歌单；全局分类按歌曲 ID 去重。
- 合作歌曲与每个歌手建立独立关系，不把组合字符串当作一个歌手。
- 清除临时缓存不影响以上数据。

## 4. 播放数据流

```text
用户点击歌曲
  -> NetworkMusicActivity 发送 PLAY(track, playlist, full source_ids)
  -> ShuffleBag 加载完整可播放池并定位当前歌曲
  -> PlaylistStore 读取稳定元数据
  -> NeteaseWebApi.resolve(首选音质)
  -> StreamVariant 返回实际档位和短期 URL
  -> RangeCacheDataSource 按 Range 提供数据
  -> MediaPlayer prepare/start
  -> MediaSession + notification + ACTION_STATE
  -> NetworkPlaybackBridge 更新母版播放页
```

服务每秒发布歌曲、播放状态、位置、时长、完整歌词、当前歌词、封面、源音质和耳机 Codec。Activity 安装 bridge 后还会主动发送 `REQUEST_STATE`，用于恢复错过广播后的 UI。

## 5. 随机队列

随机播放使用完整歌曲 ID 的 shuffle bag：

1. 只把 `playable=true` 的歌曲放入本轮池。
2. Fisher-Yates 生成本轮顺序，每首只出现一次。
3. 当前 ID、游标、历史和序列化队列写入 `network_player` preferences。
4. 下一首读取 bag 游标，上一首读取历史，不重新随机。
5. 从队列跳转只移动当前位置，不把 UI 可见段当成新池。
6. 歌单变化时增量重建，保持当前曲目。

## 6. 音质与音量链路

```text
用户首选 Hi-Res/无损/极高/较高/标准
  -> 网易云根据权益、版权和曲目返回实际源
  -> MediaPlayer 解码源音频
  -> STREAM_MUSIC 系统音量 + 应用低端衰减曲线
  -> Android A2DP 编码器
  -> 耳机实际 Codec
```

- “源音质”与“耳机音质”是两个不同层次，必须同时显示。
- 当前 OWW221 + Enco Free4：源可为无损 FLAC，但最终蓝牙为 AAC 165 kbps。
- 系统 `STREAM_MUSIC` 是用户唯一音量；应用内部曲线只缓解低档过响，0 档严格静音，最大档 unity gain。

## 7. 存储边界

| 数据 | 位置/机制 | 清缓存 | 退出登录 |
| --- | --- | --- | --- |
| 登录 Cookie | 私有 SharedPreferences + Android Keystore | 保留 | 删除 |
| 歌单/歌曲/专辑/歌手 | SQLite `PlaylistStore` | 保留 | 默认保留，显式重置才删除 |
| shuffle 状态 | `network_player` preferences | 保留 | 可按产品规则保留/重置 |
| 首选音质 | `network_settings` preferences | 保留 | 保留 |
| 音频 Range 块 | `cache/network_audio` | 删除 | 可删除 |
| 封面位图缓存 | 应用 cache/内存 | 删除 | 可删除 |
| 构建签名 | 本机 keystore | 不进入 APK 数据 | 不进入仓库 |

## 8. 系统集成

- `MediaSession` 名称：`SuixinNetwork`。
- 音频用途：`AudioAttributes.USAGE_MEDIA`。
- 控制：播放、暂停、上一首、下一首、seek、停止。
- 元数据：标题、歌手、媒体 ID。
- 音量：`AudioManager.STREAM_MUSIC`。
- 蓝牙：监听 A2DP Codec 配置变化并持久化最后确认值。

母版仍存在自己的空闲 MediaSession。网络播放时必须确保系统媒体按钮指向 `SuixinNetwork`，并避免两个活动会话同时宣称正在播放。

## 9. 构建架构

1. `build_network_bridge.ps1` 下载编译依赖、javac 编译并用 D8 生成 `classes2.dex`。
2. `patch_suixin.py` 对解包母版应用品牌、版本、权限和 hook 补丁。
3. apktool 重建母版 APK。
4. Python zip 注入替换 `classes2.dex`。
5. zipalign、apksigner 签名并验证。

原始 APK、反编译树和签名密钥属于本机工作区；公开仓库只保留 bridge 源码、补丁和文档。

