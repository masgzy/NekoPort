# 贡献指南

感谢你对 NekoPort 的关注！请先花几分钟阅读本指南。

## 行为准则

参与本项目（Issue、PR、讨论）即表示同意 [行为准则](CODE_OF_CONDUCT.md)。

## 提交 Issue

- 使用 [Bug 报告模板](.github/ISSUE_TEMPLATE/bug_report.yml) 或 [功能建议模板](.github/ISSUE_TEMPLATE/feature_request.yml)
- Bug 报告请附上：设备型号、Android 版本、应用版本（设置 → 关于）、完整复现步骤、相关日志（「日志」页导出）
- **安全漏洞请勿公开提交**，请通过 [SECURITY.md](SECURITY.md) 中的私密渠道报告

## 提交 Pull Request

1. Fork 仓库并创建分支：`git checkout -b feat/your-feature`
2. 完成改动后确认：
   - `./gradlew build` 本地通过
   - 新增 UI 文案同时补充 `values/strings.xml`（中文）与 `values-en/strings.xml`（英文）
   - 服务器配置相关改动请同步更新 README「安全设计」表格（如适用）
3. 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：
   - `feat:` 新功能（如 `feat: add site-level HTTPS support`）
   - `fix:` 修复（如 `fix: nginx config escaping for special chars`）
   - `docs:` 文档、`refactor:` 重构、`chore:` 杂项
4. 推送并创建 PR，填写 PR 模板；CI 通过后等待 Review

## 开发环境

| 项 | 要求 |
| --- | --- |
| JDK | 17 或 21 |
| Android SDK | API 37，Build Tools 36 |
| Gradle | 使用仓库自带 wrapper |
| IDE | Android Studio（推荐） |

## 代码约定

- 语言：Kotlin，UI 层统一使用 [miuix](https://github.com/compose-miuix-ui/miuix) 组件，图标优先 miuix 内置图标、必要时用官方 Material Symbols XML
- 架构：UI（`ui/`）→ 管理（`core/`）→ 运行时（`runtime/`），服务器二进制只通过 `ProcessController` 管理
- **禁止自己实现服务器协议**：HTTP/FTP/PHP 一律使用捆绑的真实二进制
- 每个文件保留原有版权头风格；新文件不必添加文件头注释
- 提交前运行 `./gradlew lint`，不要引入新的阻断性 lint

## 许可

提交的所有内容以 [GPL-3.0-or-later](LICENSE) 许可，不要求签署 CLA。
