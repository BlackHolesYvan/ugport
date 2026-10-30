# 绿联快连（UgPort）

用 Android 手机直接打开绿联 NAS 上的端口应用（qBittorrent、PanSou、Jellyfin 等）。
App 负责解析 NAS 地址、完成登录、换取访问凭证，再把页面交给系统 WebView 渲染。

## 功能

- **只填连接标识**：例如 `my-nas`，自动解析当前的中转域名与 Docker 域名
- **自动登录**：RSA 加密密码，keepalive token 本机持久化；过期时自动重授权一次
- **一键打开端口**：Docker 端口自动换取 `ugreen-proxy-token`；普通网关端口自动拼接 `uid`
- **桌面 / 移动页面**：每个端口可单独选择「桌面版网页」
- **按端口控制后台播放**：默认关闭，不注入媒体脚本、不常驻前台服务、不显示媒体通知；音乐/听书端口可在「编辑端口」里手动开启，开启后锁屏/切后台可继续播放
- **链接打开方式**：外部链接可每次询问、应用内打开或交给浏览器
- **网页音频控制**：自动识别页面里的音频/视频，通知栏、锁屏、蓝牙耳机可播放/暂停/拖动进度
- **网页视频全屏**：页面播放器的全屏按钮进入沉浸式全屏；横屏视频自动横屏、竖屏视频保持竖屏，返回键先退出全屏
- **沉浸式端口页**：网页从状态栏下方开始，状态栏只显示时间和电量；右上角刷新/退出按钮 5 秒无操作后收起为右侧玻璃竖条，点击展开；系统返回键行为不变（全屏 → 网页后退 → 退出）
- **WebGPU 能力提示**：检测页面是否具备 WebGPU（安全上下文、navigator.gpu、适配器、WebGL2），不可用时在顶部说明原因；HTTP 页面只提示，不改变加载地址
- **上一首 / 下一首**：优先使用页面注册的 MediaSession 切歌回调（Navidrome、Jellyfin 等）；未注册时回退到播放器自带的上一章/下一章按钮（部分听书类 WebUI 等）
- **后台续播**：切到后台或息屏后继续播放；章节播完通知不会消失，可直接点下一首（按返回键离开端口页会停止）
- **诊断日志**：只保留页面加载、播放、服务启停和错误等关键节点；锁、音频焦点、页面结构等细节可用 `adb logcat -s UgPortMedia` / `UgPort` 查看

## 兼容性

- Android 8.0（API 26）及以上
- 使用系统 WebView，不内置浏览器内核
- 不限制 NAS 型号；支持 Docker 端口与网关（browserUrl）端口

## 构建

要求：

- JDK 21
- Gradle 8.14.3
- Android SDK 36（Build Tools 36.0.0）

```bash
gradle testDebugUnitTest
gradle assembleDebug
gradle assembleRelease
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

> 若本机 Gradle 启动时报本地 socket 异常，可加
> `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true"`。

### 签名

在项目根目录新建 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=your-release.jks
storePassword=你的密码
keyAlias=your-alias
keyPassword=你的密码
```

没有该文件时 release 产物不签名，仍可正常构建。

## 使用

1. 打开「添加 NAS」：
   - 连接标识：只填 NAS 的连接前缀，例如 `my-nas`
   - 账号、密码
2. 点「测试登录并识别服务器」，确认显示服务器标识
3. 添加端口：
   - 名称、端口号、页面路径（可选）
   - 「桌面版网页」：页面按桌面布局渲染
   - 「自动获取访问凭证」：默认开启
4. 回到首页点击端口即可打开

## 工作原理

1. `POST https://api.ugnas.com/api/p2p/v2/ta/nodeInfo/byAlias`
   用连接标识换取 `relayDomain` 与 `dockerDomain`
2. `POST /ugreen/v1/verify/check` 获取 `X-Rsa-Token`，RSA/PKCS#1 加密密码后登录
3. Docker 端口：`GET /ugreen/v1/gateway/proxy/dockerToken` 获取一次性地址，
   再由原生请求取出 `ugreen-proxy-token` 写入 CookieManager
4. 普通网关端口：`GET /ugreen/v1/docker/app/GetUid`，把 `uid` 拼到 URL
5. WebView 加载页面；token 失效或落到登录页时，自动重新授权一次
6. 页面脚本上报音频播放状态，App 启动前台服务并注册 MediaSession，
   由系统媒体控件控制页面的播放/暂停/进度

## 隐私与安全

- 账号密码使用 Android Keystore 的 AES-256-GCM 加密，仅保存在本机
- token 同样加密持久化，AAD 绑定 NAS ID，复制密文到其他条目无法解密
- 除绿联服务器与自己的 NAS 外，不上传任何数据；无统计、无广告 SDK
- 仓库中不包含真实 NAS 地址、账号、令牌、HAR 或签名文件

## 已知限制

- 页面渲染依赖系统 WebView，不同厂商的 WebView 版本可能存在差异
- 部分 WebUI 只有桌面布局，建议在端口配置中开启「桌面版网页」
- 页面高度异常时会自动修复一次；仍空白时可在设置中清 Cookie 后重试
- 上一首/下一首优先走页面注册的 MediaSession `nexttrack`/`previoustrack`；未注册时回退到播放器自带的 `lucide-skip-back/forward` 按钮（部分听书类 WebUI 等）；两者都没有时自动隐藏
- MediaSession 模式下的直播流（duration 为 Infinity）不显示切歌按钮；DOM 按钮回退不受此限制
- DOM 回退依赖页面图标类名，页面改版后可能失效
- MediaSession 只在 HTTPS 等安全上下文可用；HTTP 页面仍可通过 DOM 按钮回退控制上一首/下一首
- 按返回键或右上角主页按钮离开端口页会销毁 WebView，播放随之停止；Home 键、锁屏、切换 App 不受影响
- 右上角按钮 5 秒无操作后收起为右边缘竖条，点击竖条重新展开；网页滚动不会重置这个计时
- 部分国产系统（MIUI/HyperOS 等）需要在系统设置中把「省电策略」设为「无限制」并允许自启动，建议再在最近任务界面下拉应用卡片锁定后台；否则系统可能在十几分钟后冻结后台
- 允许后台播放的端口会让 WebView 渲染进程常驻高优先级，降低切章瞬间被系统回收的概率；万一渲染进程仍被回收，回到 App 会自动重建页面并从上次位置续播
- 首次播放会提示忽略电池优化；设置页「后台播放」可随时查看状态和重新跳转
- 播放期间会持有 CPU 唤醒锁和 Wi-Fi 高性能锁，息屏后仍会有一定耗电（流媒体播放的固有成本）；暂停或章节结束后会立即释放
- 关闭端口开关后，切到后台会暂停页面音频并暂停 WebView 渲染，适合不需要后台播放的端口
- 从旧版本升级后，所有端口的「允许后台播放」会统一变为关闭；需要后台播放的端口请到「编辑端口」重新开启
- 音频能否播放取决于系统 WebView 的编解码能力，ALAC/APE/WMA/DSD 等格式需要服务端转码
- 视频能否播放同样取决于系统 WebView 的编解码能力，H.265/AV1 等格式需要服务端转码；全屏方向按视频比例自动选择，Android 16 大屏设备会忽略应用的方向请求
- WebGPU 只在 HTTPS（或 localhost）安全上下文中可用，HTTP 页面无法使用；超分入口是否出现由页面自身的能力检测决定，App 只负责检测与提示
- Android 13+ 需要允许通知权限，否则通知栏与锁屏媒体控件不可见

## 目录结构

```
app/src/main/java/com/ugport/
├── auth/      登录、会话、域名解析、端口打开
├── data/      模型、加密、DataStore 仓库
├── di/        手写依赖容器
├── network/   绿联 API、RSA、OkHttp、诊断日志
├── ui/        Compose 页面与 ViewModel
├── util/      URL 处理
└── web/       WebView 配置、登录脚本、JS 桥
```

## 版本

0.4.7
