# OPPO 手表音乐软件逆向与安装基线

生成日期：2026-07-24（Asia/Shanghai）

## 1. 当前结论

- 目标手表：OPPO OWW221，Android 11 / API 30，378×496，320 dpi。
- 零度音乐已全新安装：`ml.sky233.zero.music` 3.1.0(86)，安装时间 07:58:34。
- 网易云手表版已原样备份：`com.netease.cloudmusic.watch` 3.0.39(3000039)。
- GitHub 仓库本身只有介绍图与截图，没有 Gradle 工程或原始源码。可迭代输入是 Release APK、Apktool 资源/Smali 和 JADX 伪源码。
- 两个 APK 都已完成 Apktool 解码。JADX 主体完成，零度音乐 53 个、网易云 58 个方法存在反编译级错误，遇到这些方法时应以 Smali 为准。

## 2. 可验证制品

| 制品 | 字节 | SHA-256 |
|---|---:|---|
| `artifacts/apk/ZreoMusic-release-3.1.0-86.apk` | 13,065,861 | `64E0BD6FAD032E46E991AEEBB3212BB9046195C0745C3C9E56BDD26FD2F9A7AF` |
| `artifacts/apk/netease_base.apk` | 16,515,906 | `C1F59F93736FA732CADEA4BFF5A2C9921AB3E67C2C1CB585085C1C8C32950072` |
| `reverse/zero-apktool` | 零度音乐资源、清单和 Smali | Apktool 3.0.3 |
| `reverse/zero-jadx` | 零度音乐 Java/Kotlin 伪源码 | JADX 1.5.6 |
| `reverse/netease-apktool` | 网易云资源、清单和 Smali | Apktool 3.0.3 |
| `reverse/netease-jadx` | 网易云 Java/Kotlin 伪源码 | JADX 1.5.6 |
| `artifacts/metadata/apk_inventory.json` | 组件、布局和计数机器清单 | 由 `scripts/inventory_apk.py` 生成 |

## 3. 安装与时序

- ADB 设备：`192.168.1.44:5555`，型号 OWW221。
- `adb install -r -d` 返回 `Success`。
- 冷启动：582 ms；热启动：144 ms。
- 首次进入 OOBE 欢迎页稳定，无 `FATAL EXCEPTION`。
- OOBE 完成后默认进入全屏播放器；方屏实机布局与发布截图一致。
- 零度音乐 JADX：约 15 秒；Apktool：约 16 秒。
- 网易云 JADX：约 29 秒；Apktool：约 21 秒。
- 日志中发现一个非致命配置错误：Logback 文件名使用 `%d`，但 SizeAndTimeBased 策略缺少 `%i`；这会影响滚动日志，不影响启动。

## 4. 零度音乐架构

### 4.1 组件规模

- 59 个 Activity、3 个 Service、1 个 Receiver、2 个 Provider。
- 208 个布局文件、6,485 个 JADX Java 文件。
- 主播放服务：`ZeroMusicService`。
- MediaSession：`ZeroMediaSessionService`。
- Room 数据库：`AppDatabase`，当前至少存在 11→12、15→16、22→23、23→24、24→25 的迁移。
- OPPO 设备默认选择 ExoPlayer；其他设备默认 MediaPlayer。配置还包含表冠振动、表冠控制、表冠音量控制、蓝牙控制与蓝牙自动播放。

### 4.2 UI 与功能矩阵

| 模块 | 页面/能力 |
|---|---|
| 启动与主播放器 | Splash、OOBE、全屏播放器、标题/艺术家、上一首/播放暂停/下一首、音量、模式、菜单 |
| 播放队列 | 队列查看、定位当前曲目、队列持久化与顺序重建 |
| 本地曲库 | 本地音乐、搜索、排序、批量、单击播放 |
| 专辑 | 列表、显示模式、搜索结果、详情、菜单 |
| 艺术家 | 列表、搜索结果、详情，多艺术家切割 |
| 歌单 | 列表、详情、自定义/文件夹歌单、添加、重命名、排序、搜索、导入导出 |
| 歌词 | 普通/逐字歌词、字体与字重、跟随动效、歌词设置 |
| 音效 | 均衡器、预设、配置档、压限器；MediaPlayer/ExoPlayer 双内核 |
| 扫描 | 扫描音乐、文件规则、黑名单、自定义目录、系统缩略图缓存 |
| 定时与统计 | 定时关闭；总时长、次数、今日时长、按日期历史详情 |
| 主题与界面 | 全局主题色、静态流体背景、方/圆屏适配、自定义快捷菜单 |
| 文件传输 | 连接、上传队列、暂停/继续/取消、歌单管理、主题编辑、专辑/歌手浏览、烧屏保护页 |
| 设置与帮助 | 播放器、界面、扫描、关于、更新、开源许可、用户协议、致谢、帮助、开发页 |
| Pro | 功能说明、支付、购买历史、支付协议与容器页 |

实机菜单顺序已确认：播放队列 → 本地音乐 → 专辑 → 歌手 → 歌单；静态菜单还包含音乐商店、文件传输、定时、均衡器、主题、播放器/界面/扫描设置、帮助、关于和 Pro。

### 4.3 播放模式

`EnumC2982` 定义四态：

1. `RANDOM`：随机队列；
2. `RANDOM_SINGLE`：同一随机曲目按配置次数重复，再进入下一随机曲目；
3. `LOOP`：列表循环；
4. `LOOP_SINGLE`：单曲循环。

切换到随机会复制当前队列、执行 `Collections.shuffle`，再将随机顺序持久化为独立队列。当前曲目和索引也单独保存，适合后续增加“洗牌袋”、固定种子与历史回放测试。

## 5. 网易云两个问题的代码证据

### 5.1 “我喜欢”每 100 首卡顿

调用链：

`WatchPlaylistMusicFragment` → `MusicListVM` → `PlaylistRemoteDataSource`

证据：

- `MusicListVM.java:1006-1008` 创建 `PageValue` 并硬编码 `limit = 100`。
- `MusicListVM.java:1165-1170` 非首屏时调用 `loadMore`。
- `PlaylistRemoteDataSource.java:1653-1654` 用 `已有歌曲数 + offset` 计算下一段，最多再取 100 首。
- `PlaylistRemoteDataSource.java:1690-1692` 对下一段补齐数据后直接 `playList.getMusics().addAll(...)`。
- 随后把包含全部历史歌曲的 `PlayList` 和 `SuccessState` 再次发布给 UI；列表越长，重复绑定和差分成本越高。
- 每一页还读取本地曲目、补权利信息、相关视频信息，并对缺失条目逐一增加 offset。

这解释了第 101 首开始出现明显等待：它不是连续流式加载，而是固定 100 首批次，并对累积大列表重复发布。

后续实现原则：Room/MediaStore 本地曲库采用流式分页或一次性索引；RecyclerView 使用稳定 ID 和增量提交；播放队列与可见列表分离；不因 UI 分页截断随机候选集。

### 5.2 随机播放异常

网易云 `playMode == 2` 并不总是进入同一个随机实现：

- LT 配置启用时进入 `LTRandomPlayerList`；
-子模式为 1 时进入另一随机实现；
- AI 随机实验启用时动态请求 AI 随机列表；
- 其余情况才进入普通 `C3427o` 随机列表。

普通随机实现又维护 `List<List<MusicInfo>>` 形式的多段随机历史。新增歌曲时会把剩余子列表与新增项合并后原地 `Collections.shuffle`，再重新定位当前索引；跨段前进/后退还依赖两个游标。配置分流、异步 AI 列表、原列表变化与双游标共同造成“同一按钮但行为不稳定”的结构性风险。

后续实现原则：采用单一 Fisher–Yates 洗牌袋；当前曲目固定在首位或显式游标位置；一个周期内不重复；队列增删使用不可变快照重建；上一首从历史栈读取；随机算法与在线推荐彻底解耦。

## 6. 迭代与回滚基线

1. 资源/UI 修改以 `reverse/zero-apktool` 为基线，代码逻辑以 JADX 定位、Smali 落地。
2. 所有改动记录到独立补丁目录；不直接覆盖原始 APK。
3. 每次构建后执行 Apktool rebuild、zipalign、签名、`apksigner verify`。
4. Release 原包保留在 `artifacts/apk`，可通过卸载测试签名版并重装原包回滚。
5. 安装前备份 `/sdcard/Android/data/ml.sky233.zero.music`、导出歌单与 SharedPreferences 可读状态。
6. 验证矩阵至少覆盖：0/1/100/101/500/1000 首、四种模式、队列增删、重启恢复、屏幕熄灭、蓝牙断连、表冠、方屏滚动、长歌词和无封面文件。

## 7. 已知约束

- JADX 输出不是原作者源码，变量名和部分控制流已被 R8 混淆；复杂协程方法需要同时核对 Smali。
- 当前发布包使用原作者签名。重新打包后必须使用迭代签名；同包覆盖安装前要处理签名差异和数据迁移。
- 网易云备份仅用于行为和 UI 对照；零度音乐是后续功能落点。
