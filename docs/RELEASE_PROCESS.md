# 随心一听发布与回滚流程

## 1. 版本规则

- `versionName` 使用 `MAJOR.MINOR.PATCH`。
- `versionCode` 使用数字递增，当前映射示例：`1.4.0 -> 10400`。
- 每次可安装变更都提升版本，禁止同版本覆盖不同 APK。
- 文档变化可单独提交，不必提升应用版本；若文档描述实现变化，必须对应真实版本。

## 2. 发布前检查

1. 需求条目有 ID、优先级、状态和验收条件。
2. `docs/bugfixes/<version>.md` 已创建并补全。
3. `scripts/patch_suixin.py` 与输出文件名使用相同版本。
4. 上一版 APK、签名和 SHA-256 仍可定位。
5. bridge 编译、APK 构建、zipalign、apksigner verify 通过。
6. 目标手表覆盖安装成功，登录和资料库未丢失。
7. Smoke/Core 与版本专项测试通过。
8. 无未记录的 S0/S1/S2 回归。
9. 敏感信息扫描通过，keystore、Cookie、短期 URL 未进入 Git。

## 3. 制品命名

```text
artifacts/build/suixinyiting-network-<version>.apk
docs/bugfixes/<version>.md
artifacts/build/verify-<version>-<scene>.png
```

历史 APK 不改名、不覆盖、不删除。`unsigned`、`aligned` 和临时 APK 不是发布制品。

## 4. 发布记录

每次发布记录：

- `versionName` / `versionCode`
- Git commit
- APK 绝对/仓库路径
- 文件字节数
- SHA-256
- 签名验证结果
- 真机型号、Android 版本和 ADB serial
- 数据库/登录迁移结果
- 测试结论和证据
- 已知限制
- 回滚版本

## 5. GitHub 发布

公开仓库只提交源码、脚本、文档和脱敏截图。APK 使用 GitHub Release asset：

```powershell
gh release create v<version> `
  'artifacts\build\suixinyiting-network-<version>.apk' `
  --repo 666poyi666-collab/SuixinYiTing `
  --target main --title '随心一听 v<version>' --notes-file RELEASE_NOTES.md
```

发布后通过 GitHub API 反查 asset `state=uploaded`、大小与 digest，并确认 tag 指向预期提交。

## 6. 回滚

### 6.1 应用回滚

- 首选同签名旧 APK 执行 `adb -s <serial> install -r -d <old.apk>`。
- 回滚前记录当前版本、数据库版本和登录状态。
- 若 Android 拒绝降级，先评估 `-d`；不以卸载作为默认手段，因为卸载会丢应用私有数据。

### 6.2 数据库回滚

SQLite 升级通常不可逆。涉及 schema 的版本发布前必须：

- 保留旧数据库样本。
- 验证升级迁移。
- 说明旧 APK 是否能读取新 schema。
- 必要时提供导出/恢复工具，而不是直接删除数据库。

### 6.3 功能开关回滚

高风险功能优先使用可持久化开关或保持旧路径，以便在不降级数据库的情况下关闭新行为。开关名称、默认值和清理时机必须写入版本文档。

## 7. 发布阻断条件

- 设置或菜单路由错误。
- 登录/资料库被清除。
- 当前歌曲与声音、歌词、封面或 MediaSession 不一致。
- 随机轮次重复或队列历史损坏。
- 系统音量 0 不静音或出现额外固定应用音量。
- 378x496 上出现主要文字/控件重叠。
- S0/S1 或未记录 S2 Bug。
- APK 签名校验失败、版本号错误或 Release digest 不一致。
