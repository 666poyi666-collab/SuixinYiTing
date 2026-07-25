# 随心一听文档中心

最后更新：2026-07-25

当前应用基线：`1.2.9` / `versionCode 10209`

目标设备：OPPO OWW221 / Android 11 / API 30 / 378x496

## 必读顺序

1. [项目长期维护手册](PROJECT_HANDBOOK.md)：项目边界、事实来源、协作规则和完成标准。
2. [用户需求基线](REQUIREMENTS.md)：全部需求编号、优先级、当前状态和验收条件。
3. [系统架构](ARCHITECTURE.md)：模块、数据流、播放链路、存储和系统边界。
4. [开发指南](DEVELOPMENT_GUIDE.md)：环境、修改流程、构建、安装和调试。
5. [测试与回归矩阵](QA_REGRESSION.md)：每版必须执行的真机检查。
6. [Bug 总表](BUG_CATALOG.md)：历史 Bug、根因、修复版本和回归锚点。
7. [发布与回滚](RELEASE_PROCESS.md)：版本、制品、签名、发布和回滚规则。
8. [变更与日志规范](CHANGE_MANAGEMENT.md)：需求、Bug、开发日志和版本文档模板。

## 历史记录

- [开发日志](../DEVELOPMENT_LOG.md)：按时间追加的实际操作、结果与版本记录。
- [Bug 修复索引](../BUGFIX_LOG.md)：版本与修复文档的快速索引。
- [逐版本修复文档](bugfixes/)：`1.2.3` 至 `1.2.9` 的实现和验证证据。
- [逆向与安装基线](../analysis/BASELINE_REPORT.md)：母版、网易云手表端与初始环境调查。
- [设置审计](../analysis/SETTINGS_AUDIT_1.2.2.md)：母版设置对网络播放的适用情况。
- [Wi-Fi 流量报告](../analysis/WIFI_TRAFFIC_REPORT_1.2.4.md)：无损流量与吞吐测试。
- [蜂窝网络诊断](../analysis/CELLULAR_DIAG_1.2.3.md)：当时现场网络不可达的排查记录。
- [待开发列表](../analysis/TODO_DEVELOPMENT.md)：短期待办；完整状态以需求基线为准。

## 文档职责

| 文档 | 回答的问题 | 维护时机 |
| --- | --- | --- |
| `REQUIREMENTS.md` | 用户要什么，做到什么程度 | 收到新需求或范围变化时 |
| `ARCHITECTURE.md` | 系统怎样工作，边界在哪里 | 模块、数据或系统集成变化时 |
| `DEVELOPMENT_GUIDE.md` | 下一位开发者怎样继续开发 | 工具链、构建或调试流程变化时 |
| `QA_REGRESSION.md` | 如何证明没有引入回归 | 新增功能、修复 Bug 或发现漏测时 |
| `BUG_CATALOG.md` | 出过什么问题，怎样避免复发 | Bug 发现、定位、修复和验证时 |
| `DEVELOPMENT_LOG.md` | 实际做了什么 | 每个开发批次结束前 |
| `docs/bugfixes/<version>.md` | 本版本为何修改、怎样验证 | 开始版本时创建，发布前补全 |

## 单一事实来源

- 当前需求状态：`docs/REQUIREMENTS.md`。
- 当前代码与版本号：`network-bridge/src/`、`scripts/patch_suixin.py`。
- 已发布制品：`artifacts/build/suixinyiting-network-<version>.apk` 与 GitHub Release。
- 真机验证结果：对应版本 Bug 文档和 `DEVELOPMENT_LOG.md`。
- 历史调查只提供证据，不自动代表当前实现状态。
