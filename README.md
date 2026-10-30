# UgPort · NAS 端口快连

[English](README.en.md) | 简体中文

UgPort 是一个 Android 客户端，用来在手机上直接打开绿联云 NAS 上的端口应用
（qBittorrent、Jellyfin、Navidrome 等 WebUI）。App 负责解析 NAS 地址、完成登录、
换取访问凭证，再把页面交给系统 WebView 渲染。

> **非官方项目**：本项目与绿联（UGREEN）无隶属关系，未获得官方授权或背书。
> 「绿联」「UGREEN」等名称与商标归其各自所有者。请仅用于访问你自己拥有的设备。

## 功能

- **只填连接标识**：例如 `my-nas`，自动解析当前的中转域名与 Docker 域名
- **自动登录**：RSA 加密密码，keepalive token 本机加密保存；过期时自动重授权一次
- **一键打开端口**：Docker 端口自动换取 `ugreen-proxy-token`；网关端口自动拼接 `uid`
- **桌面 / 移动页面**：每个端口可单独选择「桌面版网页」
- **按端口控制后台播放**：默认关闭；需要后台播放的音乐/听书端口可在「编辑端口」里开启
- **网页音频控制**：识别页面里的 audio/video，通知栏、锁屏、蓝牙耳机可播放/暂停/拖动进度
- **上一首 / 下一首**：优先复用页面注册的 MediaSession 回调，其次回退到播放器自带的切章按钮
- **网页视频全屏**：播放器全屏按钮进入沉浸式全屏，横屏视频自动横屏
- **沉浸式端口页**：网页从状态栏下方开始，右上角按钮 5 秒无操作后收起为侧边竖条
- **WebGPU 能力提示**：检测安全上下文、`navigator.gpu`、适配器与 WebGL2，不可用时说明原因
- **外部链接策略**：每次询问、应用内打开或交给浏览器
- **诊断日志**：只保留关键节点，可在设置页复制；账号与设备型号不会写入日志

## 兼容性

- Android 8.0（API 26）及以上
- 使用系统 WebView，不内置浏览器内核
- 不限制 NAS 型号；支持 Docker 端口与网关（browserUrl）端口
- 界面文案目前只有中文（README 提供中英双语）

## 下载与安装

从 [GitHub Releases](../../releases) 下载 `UgPort-<version>-release.apk` 安装。

- 包名固定为 `com.ugport`，所有公开版本使用同一签名证书，可直接覆盖升级
- 首次安装需要允许「安装未知来源应用」
- Android 13 及以上建议允许通知权限，否则锁屏媒体控件不可见

## 构建

要求：

- JDK 17 及以上（开发与验证使用 JDK 21）
- Android SDK 36（Build Tools 36.0.0）
- 无需预装 Gradle，仓库自带 Wrapper（Gradle 8.14.3）

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
- 混淆映射：`app/build/outputs/mapping/release/mapping.txt`

### 签名

签名信息优先读环境变量，其次读仓库根目录的 `keystore.properties`
（已被 `.gitignore` 忽略）。四个值齐全时才会签名：

```bash
export UGPORT_KEYSTORE_FILE=/absolute/path/your-release.jks
export UGPORT_KEYSTORE_PASSWORD='你的密码'
export UGPORT_KEY_ALIAS=your-alias
export UGPORT_KEY_PASSWORD='你的密码'
```

或：

```properties
storeFile=/absolute/path/your-release.jks
storePassword=你的密码
keyAlias=your-alias
keyPassword=你的密码
```

没有签名配置时 release 产物不会被签名，构建仍然成功。

## 使用

1. 打开「添加 NAS」：
   - 连接标识：只填 NAS 的连接前缀，例如 `my-nas`
   - 账号、密码
2. 点「测试登录并识别服务器」，确认显示服务器标识
3. 添加端口：
   - 名称、端口号、页面路径（可选）
   - 「桌面版网页」：页面按桌面布局渲染
   - 「允许后台播放」：音乐/听书端口再开启
4. 回到首页点击端口即可打开

## 工作原理

1. `POST https://api.ugnas.com/api/p2p/v2/ta/nodeInfo/byAlias`
   用连接标识换取 `relayDomain` 与 `dockerDomain`
2. `POST /ugreen/v1/verify/check` 获取 `X-Rsa-Token`，RSA/PKCS#1 加密密码后登录
3. Docker 端口：`GET /ugreen/v1/gateway/proxy/dockerToken` 获取一次性地址，
   再由原生请求取出 `ugreen-proxy-token` 写入 CookieManager
4. 网关端口：`GET /ugreen/v1/docker/app/GetUid`，把 `uid` 拼到 URL
5. WebView 加载页面；token 失效或落到登录页时，自动重新授权一次
6. 页面脚本上报音频状态，App 启动前台服务并注册 MediaSession，
   由系统媒体控件控制页面的播放/暂停/进度

> 这些接口来自 UGOS Pro Web 端的实际请求，属于非公开接口，可能随官方升级而变化。

## 隐私与安全

- 账号密码使用 Android Keystore 的 AES-256-GCM 加密，仅保存在本机
- token 同样加密持久化，AAD 绑定 NAS ID，复制密文到其他条目无法解密
- 除绿联服务器与你自己的 NAS 外，不上传任何数据；无统计、无广告 SDK
- 诊断日志只在内存中保留最近 200 条，不落盘；复制时只包含系统版本，不含账号与设备型号
- 相机、麦克风权限只在受信任的 NAS 页面被请求时才授予，用于网页内的拍照与语音功能
- 允许明文 HTTP 是为了访问局域网内没有证书的 NAS 页面；公网地址仍建议使用 HTTPS
- 版本控制中不包含真实 NAS 地址、账号、令牌、抓包文件、签名密钥或 APK

如果你在 issue 里粘贴日志，请先确认其中没有 NAS 域名、端口等个人信息。

## 已知限制

- 页面渲染依赖系统 WebView，不同厂商的 WebView 版本可能存在差异
- 部分 WebUI 只有桌面布局，建议在端口配置中开启「桌面版网页」
- 页面高度异常时会自动修复一次；仍空白时可在设置中清 Cookie 后重试
- 上一首/下一首优先走页面注册的 MediaSession `nexttrack`/`previoustrack`；
  未注册时回退到播放器自带的切章按钮，页面改版后可能失效
- MediaSession 模式下的直播流（duration 为 Infinity）不显示切歌按钮
- 按返回键或右上角主页按钮离开端口页会销毁 WebView，播放随之停止；
  Home 键、锁屏、切换 App 不受影响
- 部分国产系统需要在系统设置中允许自启动、把省电策略设为「无限制」，
  建议再在最近任务界面下拉应用卡片锁定后台
- 允许后台播放的端口会让 WebView 渲染进程常驻高优先级；
  万一渲染进程仍被回收，回到 App 会自动重建页面并从上次位置续播
- 播放期间会持有 CPU 唤醒锁与 Wi-Fi 高性能锁，息屏后仍有一定耗电
- 音频/视频能否播放取决于系统 WebView 的编解码能力，部分格式需要服务端转码
- WebGPU 只在 HTTPS（或 localhost）安全上下文中可用，HTTP 页面无法使用
- Android 13+ 需要允许通知权限，否则通知栏与锁屏媒体控件不可见

## 目录结构

```
app/src/main/java/com/ugport/
├── auth/      登录、会话、域名解析、端口打开
├── data/      模型、加密、DataStore 仓库
├── di/        手写依赖容器
├── media/     播放状态机、前台服务、媒体通知
├── network/   绿联 API、端点常量、RSA、OkHttp、诊断日志
├── ui/        Compose 页面与 ViewModel
│   ├── components/  玻璃组件与通用控件
│   ├── theme/       色板、字体、形状
│   └── web/         端口页、悬浮控件、覆盖层、平台辅助
├── util/      URL 处理
└── web/       WebView 配置、登录脚本、媒体脚本、JS 桥
```

## 参与贡献

请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。提交前请确保：

```bash
./gradlew testDebugUnitTest
```

安全问题的报告方式见 [SECURITY.md](SECURITY.md)，发布流程见 [RELEASING.md](RELEASING.md)。

## 许可证

本项目采用 **PolyForm Noncommercial License 1.0.0**，完整文本见 [LICENSE](LICENSE)。

- 允许：个人学习、研究、实验、业余项目，以及非商业组织内部使用
- 允许：修改、分发，但必须保留许可证与 `Required Notice`
- 禁止：任何商业用途，包括销售、商业产品集成、以商业目的提供服务
- 如需商用，请先联系项目维护者另行取得书面授权

需要说明的是：**这是「源码公开」（source-available），不是 OSI 定义的开源**。
「禁止商用」与 OSI 的开源定义不兼容，GitHub 也可能把本仓库标记为非标准许可证。

第三方依赖仍按各自许可证分发，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 商标

UgPort 是独立项目，与绿联（UGREEN）无隶属关系。项目名称与图标不包含在源代码许可证内。
