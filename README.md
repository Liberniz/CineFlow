# CineFlow

![CineFlow Preload](build/bg.png)

流畅、可定制的电影 / 节目推荐应用。（已在Linux Do上发布公告）

**CineFlow** 基于Electron + Vite构建，使用TMDB数据源，支持自然语言搜索、个性化推荐、灵动岛模式、外部片源导入与应用内播放。桌面版支持 **Windows / macOS**，另有 **Android TV** 版可安装在电视上使用。

## 简介

**CineFlow** 是一款电影/节目推荐应用，主打简洁UI、灵动岛模式、外部片源导入。它使用TMDB作为元数据源，支持电影、电视剧、节目、演员、关键词与类型发现。

## 功能亮点

- **每日推荐**：首页展示今日电影 / 节目、海报、评分、类型与简介。
- **电影 + 节目双检索**：同时覆盖 TMDB `movie` 与 `tv`，电视剧 / 综艺 / 节目都能被搜索与推荐。
- **自然语言搜索**：支持心情、天气、上班等场景、电影名、节目名、演员、题材等关键词，并自动扩展相关推荐。
- **风格探索**：动作、科幻、爱情、悬疑、动画、恐怖、喜剧、剧情等类型快速发现。
- **个性化推荐**：基于本机点击、收藏与搜索偏好调整推荐内容。
- **介绍页**：海报、简介、评分、上映 / 首播信息、季数、集数、主演、主创、相似推荐一屏展示。
- **收藏面板**：顶栏"收藏"按钮，收藏详情页从左侧拉出，一键直达介绍页播放。
- **灵动岛模式**（桌面版）：最小化后切换为灵动岛，支持拖动、展开搜索、贴边吸附与一键恢复主窗口。
- **应用内播放**：支持 HLS / DASH / FLV，电视剧资源可在播放器内显示本季剧集并切集。
- **网络加速**：元数据经加密解析并在启动时预热建立本机索引，无代理也能完整展示；播放流量可选跟随代理/强制代理/直连，代理断开时播放不断开。
- **外部资源导入**：支持自定义导入外部影视源并即时刷新配置，兼容 **TVBox 格式**（`{"sites":[...]}`、站点数组、单个站点 JSON、远程订阅地址，仅识别 `type=1` 的 JSON 接口）。
- **广告分片过滤**：三级过滤播放资源广告，任何异常自动回退原始清单，不影响播放。
- **播放优化**：窗口态拖动进度条抑制加载圈，全屏播放优先尝试 4K 并保持自适应回退。
- **本机安全配置**：TMDB Key、代理与偏好设置保存在本机，不写入源码.

## 下载与安装

前往 [GitHub Releases](https://github.com/Liberniz/CineFlow/releases) 下载最新版（当前 v4.0.0）：

| 平台 | 安装包 | 说明 |
|---|---|---|
| Windows 10 / 11（x64） | `CineFlow-4.0.0-Setup.exe` | nsis 安装包 |
| macOS（Apple Silicon） | `CineFlow-4.0.0-arm64.dmg` / `.zip` | 右键 → 打开 可绕过 Gatekeeper |
| macOS（Intel） | `CineFlow-4.0.0-x64.dmg` / `.zip` | 同上 |
| Android TV | `CineFlow-tv-4.0.0.apk` | 见下文 |

> 所有安装包均为未签名构建。macOS 首次打开被拦截时，对应用点右键 → 打开 即可运行。

## Android TV 版

电视版把同一套 Web 前端装进 Android 原生壳：WebView 承载界面，`window.cineflow` 由原生 Kotlin 实现（TMDB / 资源站 / 设置与桌面版同构），播放时自动拉起系统级 ExoPlayer（支持 HLS / DASH / 常规视频）。

安装方式（二选一）：

```bash
adb install CineFlow-tv-4.0.0.apk
```

或把 APK 拷到 U 盘，用电视自带的文件管理器打开安装。桌面有 Leanback 入口图标。

已知限制：

- 暂不支持 DoH、元数据预热、播放代理探测（桌面版的大陆网络优化三件套）与清单级广告过滤。
- 遥控器方向键可操作，但页面按鼠标交互设计，部分悬停效果在电视上体验一般。
- 与桌面版共用资源站与 TMDB 数据，但两端设置相互独立。

## 快速开始

### 1. 配置 TMDB

CineFlow 使用 TMDB 获取电影 / 节目元数据。首次启动后：

1. 点击右上角设置按钮。
2. 填入 TMDB v3 API Key 或 Read Access Token。
3. 点击保存并测试连接。

开发模式也可以复制 `.env.example` 为 `.env`：

```ini
TMDB_API_KEY=
TMDB_READ_TOKEN=
```

两者任选其一即可。**(关于申请TMDB的API可以看目录内的"申请API.md").**

具体申请流程：

#### 一、注册/登录

首先访问 [TMDB官网](https://www.themoviedb.org/)并点击右上角的**加入TMDB**/**登录**，根据输入框依次填写信息并提交

![login](src/guide/login.png)

要验证邮箱就根据提示验证邮箱，最后进行登录

进入登录完的页面后，就申请API

#### 二、进入申请页面

依次点击右上角用户名、账户设置、API、请求API密钥。

![API_1](src/guide/API_1.png)

然后进入API申请页面

![API_2](src/guide/API_2.png)

#### 三、申请API

点击页面`This is for my own personal use only`行所对应的**Yes**按钮，依次点击按钮



![API_3](src/guide/API_3.png)

就进入了开发者计划的API申请页面，然后如下填写 (也可自行发挥)

应用概述可以写：`Meet personalized needs, enrich website interfaces and functions to give users a better experience`

![API_4](src/guide/API_4.png)

申请完后在跳转的页面点击按钮`Access your API key details here.`即可查看自己的API

![API_5](src/guide/API_5.png)

经过以上的操作流程，您就可以填写申请的API至**CineFlow**的设置，然后正常使用该应用了。

### 2. 代理设置

如果本机开启代理后可以在浏览器内访问 TMDB，但 CineFlow 提示网络失败，可在设置页填写：

```text
system
http://127.0.0.1:7890
socks5://127.0.0.1:7890
direct
```

- `system`：使用系统代理。
- `direct`：强制直连。
- `http://` / `socks5://`：使用指定本地代理。

### 3. 自定义外部源

设置 → 资源源 → 导入，支持以下格式：

- **TVBox 配置**：`{"sites":[...]}` 完整配置、站点数组、单个站点 JSON、远程订阅地址。
  仅识别 `type=1` 的 JSON 接口（AppleCMS）；`csp_*`、spider / jar、xml（`type=0`）会被跳过。
  若配置中没有任何可用接口，会报错提示而不是静默导入空配置。
- **传统格式**：`name|api|modes|note` 分行文本、`{"sources":[...]}` 数组。
- **远程地址**：以 `http(s)://` 开头的单行 URL 会自动拉取后解析。

## 自行构建

```bash
npm ci
npm run build

# 桌面版
npm run build:win   # release/CineFlow-*-Setup.exe
npm run build:mac   # release/*.dmg + *.zip（dmg 只能在 macOS 上打）

# Android TV 版，详见 android/README.md
```

CI：向 `v*` tag 推送会自动构建 Windows / macOS / Android 三平台安装包并发布到 GitHub Release（`.github/workflows/`）。

## 项目结构

```text
CineFlow/
├─ android/              # Android TV 原生壳（WebView + ExoPlayer + Kotlin 桥接）
├─ build/                # 应用图标等构建资源
├─ electron/             # Electron 主进程与 preload
├─ scripts/
├─ src/                  # Vite 前端（含 TMDB 申请图文指南 src/guide/）
├─ index.html
├─ package.json
├─ vite.config.mjs
|--申请API.md
└─ README.md
```

## License

本项目基于 [MIT License](LICENSE) 开源。
