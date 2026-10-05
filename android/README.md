# CineFlow Android TV

把 CineFlow 移植到 Android TV（APK），分支 `android-tv`。

## 架构

Electron 跑不了 Android，所以是「原生壳 + WebView」方案：

```
┌─────────────────────────────────────────────┐
│ MainActivity: WebView                        │
│  └─ Vite 产物 (dist/) 经本机 HTTP 服务加载      │
│  └─ bridge-shim.js 在页面脚本执行前注入        │
│        window.cineflow (34 个方法, Promise)    │
│                 │ postMessage                 │
│                 ▼                             │
│ CineflowBridge (Kotlin, @JavascriptInterface) │
│  ├─ Store: SharedPreferences（设置持久化）     │
│  ├─ TmdbClient: TMDB 接口（OkHttp，5 分钟缓存） │
│  └─ ResourceClient: 采集站搜索 / 评分 / 解析    │
│                                              │
│ PlayerActivity: ExoPlayer（原生播放）          │
└─────────────────────────────────────────────┘
```

- **播放拦截**：`getMediaProxyUrl()` 被 shim 拦截，直接拉起 ExoPlayer，
  WebView 内建播放器被静音（`#resourcePlayer` 的 `play()` 被改写成空操作），
  浮层自动关闭。`resolveMediaUrl()` 保留原生实现（网页类资源仍需解析）。
- **广告过滤**：TV 端暂不做清单级过滤（v1），直链播放。
- **未移植**：DoH / 元数据预热服务 / 播放代理探测（大陆网络优化三件套）、
  灵动岛与窗口控制（TV 常全屏）、`getMediaProxyUrl` 本机代理（恒等实现）。
- **TVBox 导入**：与桌面端一致，`importResourceSources` 支持 TVBox 配置
 （`{sites:[...]}`，仅 `type=1` JSON 接口）。

## 构建

CI（`.github/workflows/build-android.yml`）：push 到 `android-tv` 分支或手动
触发，自动打 debug APK 并上传为 artifact。

本地构建：

```bash
npm ci && npm run build
rm -rf android/app/src/main/assets/www && mkdir -p android/app/src/main/assets/www
cp -r dist/* android/app/src/main/assets/www/
cd android && ./gradlew :app:assembleDebug   # 或用 Android Studio 打开
```

APK 为 debug 签名，可直接 `adb install` 装到电视。电视桌面入口已配
`LEANBACK_LAUNCHER`，索尼 Android TV / Google TV 上会显示图标。

## 已知限制（v1）

- 未签名 / 未公证：Android 侧无影响，debug 包可直接安装。
- 遥控器方向键操作 WebView 页面基本可用，但页面是按鼠标交互设计的，
  部分悬停效果在电视上体验一般。
- ExoPlayer 不支持 FLV 软解以外的少数格式，这类资源会报错，
  可用「外部打开」或换线路。
- TMDB 在国内直连困难时，记得在设置里配代理（与桌面端相同）。
