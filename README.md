<div align="center">

# NekoPort

**把一部 Android 手机变成一台真正的 Web 服务器**

内置 **Lighttpd · Nginx · Apache · PHP-FPM · MariaDB (MySQL) · Pure-FTPd** 官方二进制，
基于 [miuix](https://github.com/compose-miuix-ui/miuix) 液态玻璃设计，Kotlin + Jetpack Compose 打造。

[![CI](https://img.shields.io/github/actions/workflow/status/masgzy/NekoPort/ci.yml?branch=main&style=flat-square&logo=githubactions&logoColor=white&label=CI)](https://github.com/masgzy/NekoPort/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/masgzy/NekoPort?style=flat-square&logo=github&label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/masgzy/NekoPort/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/masgzy/NekoPort/total?style=flat-square&logo=github&label=%E4%B8%8B%E8%BD%BD%E9%87%8F)](https://github.com/masgzy/NekoPort/releases)
[![License](https://img.shields.io/github/license/masgzy/NekoPort?style=flat-square&logo=gnu&label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)
[![Stars](https://img.shields.io/github/stars/masgzy/NekoPort?style=flat-square&logo=github)](https://github.com/masgzy/NekoPort/stargazers)
[![Issues](https://img.shields.io/github/issues/masgzy/NekoPort?style=flat-square&logo=github&label=%E9%97%AE%E9%A2%98)](https://github.com/masgzy/NekoPort/issues)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)](CONTRIBUTING.md)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-miuix%20%E6%B6%B2%E6%80%81%E7%8E%BB%E7%92%83-FF6900?style=flat-square)](https://github.com/compose-miuix-ui/miuix)

</div>

---

## 目录

- [简介](#简介)
- [功能特性](#功能特性)
- [内置组件](#内置组件)
- [快速上手](#快速上手)
- [安装](#安装)
- [从源码构建](#从源码构建)
- [安全设计](#安全设计)
- [架构](#架构)
- [路线图](#路线图)
- [参与贡献](#参与贡献)
- [行为准则](#行为准则)
- [许可证](#许可证)
- [致谢](#致谢)

## 简介

NekoPort 是一款开源的 Android Web 服务器应用，功能形态类似 KSWEB：在手机上托管网站、运行 PHP 应用、开启 FTP 服务。

与市面上"从零手写 HTTP 解析器"的方案不同，**NekoPort 不自己实现任何服务器协议**：
所有服务均为上游官方程序在 Termux 项目下的 Android 原生编译版本（Bionic libc），首次启动时通过 Termux 官方软件源安装二进制及其依赖闭包，随后作为独立进程运行。你得到的是与 Linux 服务器完全一致的 Nginx/Apache/Lighttpd 行为、配置语法和扩展生态。

> 仅借鉴 KSWEB 的功能形态，未对其任何代码进行逆向或引用；本项目全部代码为原创实现，以 GPL-3.0-or-later 开源。

## 功能特性

- **三引擎站点管理** — 每个站点独立端口、独立进程，可任选 Lighttpd / Nginx / Apache 作为引擎，支持启停、编辑、目录列表开关
- **MySQL 数据库** — 内置 MariaDB 服务端，一键启停、端口 / 局域网访问开关、my.cnf 编辑、日志查看，数据目录与站点目录隔离
- **计划任务** — 标准 5 位 Cron 表达式调度，支持执行控制台命令 / 重启全部服务 / 保活看门狗，带任务日志
- **PHP 全支持** — php-fpm 以 FastCGI 方式接入三引擎（Unix Socket），按扩展名白名单精确转发；PHP 8.5+ 内置 php2shell 修复
- **真实 FTP 服务器** — Pure-FTPd（puredb 虚拟用户），可配置端口、账号密码、被动端口段，登录后直接管理站点文件
- **配置文件编辑** — nginx / Apache / lighttpd 站点配置、php.ini、php-fpm.conf、my.cnf 均可在应用内直接编辑，改动持久生效并可一键恢复默认
- **日志文件** — 应用日志 + 每个服务的引擎访问 / 错误日志、PHP 日志、MariaDB 日志、FTP 日志、计划任务日志，点击即看
- **多镜像源** — Termux 组件安装支持官方源 / 官方 CF 源 / CERNET / 清华 / 北大 / 北外 / 中科大 / 南大 / 阿里云 / 腾讯云等十多个镜像，一键切换
- **文件管理器** — 浏览 / 上传 / 新建 / 重命名 / 编辑 / 分享站点文件
- **常驻服务** — 前台服务保活，支持开机自启、通知栏一键停止
- **液态玻璃 UI** — miuix 组件库 + 液态玻璃底栏，亮暗主题跟随系统
- **中英双语** — 界面文案完整支持简体中文与英文

## 内置组件

所有组件均为 **[Termux](https://github.com/termux/termux-packages) 官方软件源**提供的 Android 原生编译版本，首次启动时在应用内一键安装（约 50-100 MB，含依赖闭包 openssl / pcre2 / zlib 等）。

| 组件 | 说明 | 许可证 |
| --- | --- | --- |
| [Lighttpd](https://www.lighttpd.net) | 轻量高性能 Web 服务器（默认引擎） | BSD-3-Clause |
| [Nginx](https://nginx.org) | 高性能 HTTP / 反向代理服务器 | BSD-2-Clause |
| [Apache HTTP Server](https://httpd.apache.org) | 最流行的 Web 服务器，模块生态丰富 | Apache-2.0 |
| [PHP-FPM](https://www.php.net) | PHP FastCGI 进程管理器（PHP 8.5+） | PHP-3.01 |
| [MariaDB](https://mariadb.org) | MySQL 兼容的关系型数据库（root 初始空密码、仅限本机） | GPL-2.0 |
| [Pure-FTPd](https://www.pureftpd.org) | 安全、标准的 FTP 服务器 | ISC |

组件安装于应用私有目录，配置文件由 NekoPort 生成并内置安全加固；下载镜像可在设置中切换（国内网络推荐 CERNET / 清华 / 阿里云等）。

## 快速上手

1. **安装组件** — 首次打开应用，在「站点」页点击 *安装服务器组件*（需联网，一次性；国内网络可在设置中先选一个镜像源）
2. **创建站点** — 点击右下角 **+** 按钮：填站点名、端口、选引擎，需要动态网页就勾上 *启用 PHP*
3. **启动服务** — 打开 *运行全部服务* 开关，通知栏出现常驻通知
4. **访问** — 同一局域网内浏览器打开 `http://手机IP:端口/`
5. **（可选）MySQL** — 在「MySQL」页开启服务，本机应用用 `127.0.0.1:3306` + root（初始空密码）连接
6. **（可选）FTP** — 在「FTP」页设置账号密码并启用，用任意 FTP 客户端连接 `ftp://手机IP:2121/`
7. **（可选）计划任务** — 在「计划任务」页添加 Cron 任务（如定时备份数据库、保活）

站点根目录位于 `sites/<站点名>/`，可用内置文件管理器或 FTP 直接维护。

## 安装

前往 [Releases](https://github.com/masgzy/NekoPort/releases/latest) 下载 APK：

| 文件 | 说明 |
| --- | --- |
| `NekoPort-vX.Y.Z-release.apk` | 正式签名的发布版（推荐） |

- 系统要求：**Android 8.0（API 26）或更高**
- 支持架构：**arm64-v8a** / armeabi-v7a / x86_64
- CI 全部构建产物均使用发布密钥签名，可在仓库 Actions 页面下载各次构建的 APK

## 从源码构建

```bash
git clone https://github.com/masgzy/NekoPort.git
cd NekoPort
./gradlew assembleRelease
```

- 需要 JDK 17+ 与 Android SDK（SDK 37 / Build Tools 36）
- 发布签名：设置环境变量 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`（本地构建未设置时自动回退 debug 签名）
- 推送到 GitHub 后由 Actions 自动构建并校验签名，打 `v*` 标签自动发布 Release

## 安全设计

NekoPort 把安全当作一等公民，重点防护 [php2shell](https://github.com/kuwoo/php2shell-Lab)（session.upload_progress + LFI 的组合利用）与常见 Web 攻击面：

| 层面 | 措施 |
| --- | --- |
| **PHP 版本** | 组件源自 Termux 官方源，PHP 8.5+ 已包含 php2shell 修复（CVE-2026-109618 / CVE-2026-109619）；设置页可校验版本是否低于安全线 |
| **执行白名单** | 仅白名单扩展名（默认 `.php`）转发至 php-fpm；其余文件一律按静态资源输出源码而非执行 |
| **FastCGI 层** | php-fpm 池级 `security.limit_extensions` 与应用层白名单双重校验；`cgi.fix_pathinfo=0` 阻断路径解析攻击 |
| **越权执行** | 每站点池独立 `open_basedir`，PHP 只能读写本站点目录与运行时临时目录 |
| **敏感文件** | 点文件（`.git`、`.env`、`.htaccess`…）在三引擎配置中统一拦截，禁止外泄 |
| **上传目录** | 默认 `uploads/` 等目录禁止执行 PHP（三引擎均落地为对应配置），防止 webshell 落地即用 |
| **数据库隔离** | MariaDB 默认仅监听 `127.0.0.1`，数据目录位于应用私有空间（非站点目录，HTTP 无法触达）；开启局域网访问需用户显式确认 |
| **运行时隔离** | 服务器二进制、配置、日志全部位于应用私有目录，HTTP 请求永远无法触达；组件安装走 HTTPS + 路径穿越校验 |
| **进程权限** | 所有服务以应用沙箱 uid 运行，FTP 虚拟用户同样映射至沙箱 uid，无提权路径 |
| **PHP 加固** | `expose_php=Off`、`allow_url_include=Off`、`display_errors=Off`、严格会话模式等内置 php.ini 加固 |

> 安全问题请勿公开提 Issue，见 [SECURITY.md](SECURITY.md)。

## 架构

```
┌──────────────────────────────────────────────┐
│  NekoPort (Kotlin + Compose + miuix)         │
│                                              │
│  UI 层   站点 / MySQL / FTP / 计划任务 /      │
│          文件 / 设置 + 全屏配置编辑器        │
│  管理层  ServerManager（进程编排 + 状态机）    │
│  调度层  CronScheduler（Cron 表达式调度器）   │
│  生成层  ConfigWriter（nginx/httpd/lighttpd   │
│          /php-fpm/my.cnf/pure-ftpd 配置生成） │
│  安装层  TermuxRuntime（deb 解析 + 依赖闭包）  │
└───────────────┬──────────────────────────────┘
                │ 独立进程（LD_LIBRARY_PATH 隔离）
    ┌───────────┼───────────┬──────────┬─────────┐
 nginx    apache httpd   lighttpd   mariadbd  pure-ftpd
 (每站点)    (每站点)      (每站点)    (全局)    (全局)
    └───── FastCGI Unix Socket ─────┘
                php-fpm（多池，每 PHP 站点一池）
```

- **运行时安装**：解析 Termux 软件源 `Packages` 索引 → 以 lighttpd / nginx / apache2 / php-fpm / mariadb / pure-ftpd 为根计算依赖闭包 → 下载 .deb（镜像可切换）→ 校验并解包至私有目录 → 重写脚本内硬编码路径
- **进程管理**：每站点一个引擎进程（前台模式），php-fpm 单主进程多池，MariaDB 首次启动用上游 `mariadb-install-db` 引导数据目录，Pure-FTPd 常驻；统一由 `ProcessController` 启停与日志采集
- **配置即代码**：所有服务器配置由 `ConfigWriter` 按站点生成（`.auto` 文件）；用户编辑的自定义配置持久生效，可随时恢复默认

## 路线图

- [ ] 辅助工具一键安装（phpMyAdmin / Adminer）
- [ ] Composer 控制台
- [ ] 站点域名绑定（ServerName / server_name 自定义）
- [ ] HTTPS（自签证书 / Let's Encrypt）
- [ ] 每站点独立 php.ini 覆盖
- [ ] 更多 FTP 虚拟用户

> 当前为 **alpha** 阶段：核心功能（站点 / MySQL / FTP / 计划任务 / 配置与日志）已可用，欢迎试用并反馈问题。

## 参与贡献

欢迎任何形式的贡献！请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)：

- 提交 Issue 请使用 [Issue 模板](.github/ISSUE_TEMPLATE)，并注明设备型号、Android 版本与复现步骤
- 提交 PR 前请确保 `./gradlew build` 通过、代码风格一致、新文案补充中英双语
- 仓库启用 [GitHub Actions](.github/workflows/ci.yml) 自动构建校验

## 行为准则

本项目遵循 [Contributor Covenant](CODE_OF_CONDUCT.md) 行为准则，参与社区即表示同意该准则。

## 许可证

本项目以 [GPL-3.0-or-later](LICENSE) 许可证开源：

```
NekoPort - Android web server bundling real nginx/Apache/lighttpd/PHP/Pure-FTPd
Copyright (C) 2026 masgzy

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

第三方组件归其原作者所有，各组件许可证见[内置组件](#内置组件)表格。

## 致谢

- [miuix](https://github.com/compose-miuix-ui/miuix) — 优雅的 Compose UI 组件库，液态玻璃底栏
- [Termux](https://github.com/termux/termux-packages) — 高质量的 Android 原生软件源，本项目所有服务器二进制均来自其官方构建
- [KSWEB](https://ksweb.mobildev.com) — 功能形态参考
- [Acode](https://github.com/Acode-Foundation/Acode) — 应用内配置 / 日志编辑器的交互设计参考
- [CERNET 镜像站联盟 / 各高校与云厂商镜像站](https://help.mirrors.cernet.edu.cn/termux/) — Termux 组件下载加速
- 所有 [贡献者](https://github.com/masgzy/NekoPort/graphs/contributors)
