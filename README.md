# 绿联快连（UgPort）

用 Android 手机直接打开绿联 NAS 上的端口应用（qBittorrent、PanSou、Jellyfin 等）。
App 负责解析 NAS 地址、完成登录、换取访问凭证，再把页面交给系统 WebView 渲染。

## 功能

- **只填连接标识**：例如 `my-nas`，自动解析当前的中转域名与 Docker 域名
- **自动登录**：RSA 加密密码，keepalive token 本机持久化；过期时自动重授权一次
- **一键打开端口**：Docker 端口自动换取 `ugreen-proxy-token`；普通网关端口自动拼接 `uid`
- **桌面 / 移动页面**：每个端口可单独选择「桌面版网页」
- **链接打开方式**：外部链接可每次询问、应用内打开或交给浏览器
- **诊断日志**：一键复制，便于排查登录与页面问题

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

## 隐私与安全

- 账号密码使用 Android Keystore 的 AES-256-GCM 加密，仅保存在本机
- token 同样加密持久化，AAD 绑定 NAS ID，复制密文到其他条目无法解密
- 除绿联服务器与自己的 NAS 外，不上传任何数据；无统计、无广告 SDK
- 仓库中不包含真实 NAS 地址、账号、令牌、HAR 或签名文件

## 已知限制

- 页面渲染依赖系统 WebView，不同厂商的 WebView 版本可能存在差异
- 部分 WebUI 只有桌面布局，建议在端口配置中开启「桌面版网页」
- 页面高度异常时会自动修复一次；仍空白时可在设置中清 Cookie 后重试

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

0.2.0
