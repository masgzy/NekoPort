# 安全策略

NekoPort 致力于提供一个安全可靠的 Android Web 服务器。感谢你负责任地披露漏洞。

## 支持版本

| 版本 | 是否支持 |
| --- | --- |
| 0.2.x | ✅ 支持 |
| < 0.2 | ❌ 请升级 |

## 报告漏洞

**请勿通过公开 Issue 报告安全漏洞。**

请使用以下方式私密报告：

1. **GitHub Security Advisories（推荐）** — 仓库页 → Security → [Report a vulnerability](https://github.com/masgzy/NekoPort/security/advisories/new)
2. 通过仓库主页公开的邮箱联系维护者

报告请包含：

- 漏洞类型与影响（含 CVSS 评分将更好）
- 受影响的版本 / 组件（应用版本、Nginx/Apache/Lighttpd/PHP 版本）
- 完整复现步骤或 PoC
- 修复建议（如有）

## 响应流程

1. **48 小时内**确认收到
2. **7 天内**给出初步评估（是否成立、严重级别）
3. **30 天内**发布修复或给出修复计划；期间可与报告者协商披露时间
4. 修复后发布新版本，并在 Release Notes 中致谢报告者（可匿名）

## 内置安全机制概览

- PHP 8.5+（含 php2shell 修复 CVE-2026-109618 / CVE-2026-109619）
- PHP 扩展名白名单 + php-fpm `security.limit_extensions` 双重校验
- 每站点 `open_basedir` 隔离、`cgi.fix_pathinfo=0`
- 点文件拦截、上传目录 PHP 执行禁止
- 组件 HTTPS 下载 + 解包路径穿越校验
- 全部服务运行于应用沙箱 uid，无提权路径

详见 README「[安全设计](README.md#安全设计)」章节。
