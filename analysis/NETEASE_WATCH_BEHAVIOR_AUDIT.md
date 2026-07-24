# 网易云手表端行为审计（随心一听网络源第一版）

更新时间：2026-07-24

## 1. 登录链路

网易云手表端的实际入口位于：

- `music/biz/login/account/WatchLoginActivity`
- `music/biz/login/account/LoginUUIDViewModel`
- `music/biz/login/account/C3887q`
- `music/biz/login/account/WatchLoginApi`

确认的协议行为：

1. 未登录设备先请求 `login/anon/device`，建立匿名设备会话。
2. 二维码密钥请求 `login/qrcode/unikey`，参数 `type=2` 和 `login_traceId`。
3. 二维码内容不是网页端 `/login?codekey=`，而是
   `/st/platform/scanlogin?codekey=...`。
4. 二维码还携带 `hdw_deviceid`、`hdw_device=watch`、`hdw_brand`、
   `hdw_model`、`hdw_token`、`hdw_ip` 和 `login_traceId`。
5. 手表端会调用 `middle/shorturl/generate` 缩短二维码内容。
6. 每 2 秒轮询 `login/qrcode/client/login`，同样使用 `type=2` 和
   `login_traceId`。
7. 状态码：800 过期、801 等待扫码、802 已扫码待确认、803 授权成功。
8. 803 后仍需用返回的登录 Cookie 请求账号接口；账号资料成功写入后，
   页面才真正结束登录流程。

此前手机版提示“请切换其他登录方式”的原因是网络桥使用了网页端
`type=1 + /login?codekey=`。当前实现已经按以上手表协议更正。

## 2. 会话行为

网易云客户端 Cookie 存储会同时维护设备字段与登录字段，关键字段为：

- `MUSIC_U`：用户登录凭据。
- `MUSIC_A`：匿名会话凭据。
- `__csrf`：部分 WEAPI 请求使用。
- `deviceId`、`appver`、`os`、`osver`、`mobilename`、`channel`：设备信息。

当前网络桥已经增加：

- 二维码全过程 Cookie 合并。
- `Set-Cookie` 大小写兼容。
- 803 响应体 Cookie 与响应头 Cookie 合并。
- 过滤 Path、Domain、Expires、Max-Age 等 Cookie 属性。
- 收到 `MUSIC_U` 后移除匿名 `MUSIC_A`。
- Android Keystore AES-GCM 加密保存会话。
- 账号接口二次验证；只有拿到有效用户 ID 才进入歌单。
- 保存用户 ID，供播放地址请求使用。

## 3. 千首歌单表现

网易云手表端 `MusicListVM` 将 `PageValue.limit` 固定为 100。
`PlaylistRemoteDataSource` 每次把下一段加入累计 `musics`，随后重新发布
完整累计列表。这解释了“我喜欢”超过 100 首后等待很久、后续页面越来越
重的表现。

当前网络桥采用：

- 首屏 50 首。
- 每次仅向 UI 增加下一段 50 首。
- 歌单只保存元数据，不保存整首音频。
- 歌曲详情按 200 个 ID 一批获取。
- SQLite 保留原始 position。
- 同步使用 `sync_token`：同步开始时保留旧缓存供首屏立即显示；新批次逐步
  覆盖；全部成功后再清理旧记录，避免网络失败导致缓存歌单被清空。
- 切换歌单时校验回调所属 playlist ID，避免前一个后台任务覆盖当前页面。

## 4. 播放地址和音质

官方播放器地址请求使用：

- `song/enhance/player/url/v1`
- `ids=["歌曲ID_用户ID"]`
- `level`
- `encodeType`
- `trialMode`
- `immerseType`
- `cliUserId`

当前网络桥已对齐这些关键参数，并按以下顺序降档：

- 默认：无损 → 极高 → 较高 → 标准。
- Hi-Res 优先：Hi-Res → 无损 → 极高 → 较高 → 标准。

播放地址有效期读取服务端 `time` 字段。播放过程中因地址过期或鉴权错误
触发媒体错误时，会清除预解析地址、重新解析一次并续播。

## 5. 随机播放

网易云手表端存在普通随机、AI 随机等多条分支及多段历史游标，状态组合
复杂。

当前网络桥使用单一持久化 shuffle bag：

- 每轮每首出现一次。
- 保存 seed、cursor 和 current。
- 上一首回退游标，下一首可回到原随机历史。
- 预解析从 shuffle bag 的真实后续两首读取，而不是从歌单原始顺序读取。

## 6. 仍需真机账号完成的验证

以下检查依赖下一次有效扫码会话：

1. 803 响应是否返回 `MUSIC_U`，以及账号接口是否立即通过。
2. 用户歌单接口是否完整返回全部歌单。
3. 1000+ 歌单的 `trackIds` 数量、歌曲详情批次和 position 是否一致。
4. 会员账号的 lossless/hires 实际返回档位。
5. 音频 CDN 对 Range 请求、地址过期刷新和断网恢复的真机表现。
6. 版权受限、数字专辑、云盘歌曲和试听资源的 privilege 字段组合。

## 7. 2026-07-24 真机验证结果

- OPPO OWW221 登录状态完整经过 801 → 802 → 803。
- 803 响应头包含登录 Cookie，账号接口验证通过。
- 成功读取 24 个歌单。
- “我喜欢的音乐”列表接口显示 1240 首；详情接口实际同步 1242 条。
  当前保留详情接口结果，后续继续检查这 2 条差异是否属于实时更新或特殊曲目。
- 1242 条详情同步完成约 11 秒；缓存首屏可直接显示。
- UI 初始只提交 50 首，滚动实测已从 50 扩展到 200，跨过第 100/101 首，
  没有整表等待、崩溃或明显卡顿。
- 歌曲 `27646693` 成功解析为 lossless FLAC，码率 1,598,781 bps。
- 歌曲 `438801672` 成功解析为 lossless FLAC，码率 942,300 bps。
- 网易云音频 CDN 返回 HTTP 地址；已在网络安全配置中仅为
  `music.126.net` 及其子域开放明文流量。
- Range 数据源准备成功，MediaPlayer 进入 started，网络 MediaSession
  成为系统媒体按钮会话。
- OPPO 的 ADB 合成媒体键没有触发应用回调；当前增加了 flags=3 的
  MediaSession 和显式 MEDIA_BUTTON PendingIntent/Receiver。实体耳机键仍需
  在真实佩戴播放场景复核一次。
