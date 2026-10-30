# 安全策略

## 支持版本

只维护最新发布版本。旧版本的问题请先升级再复现。

## 报告安全问题

请使用 GitHub 仓库的 **Security → Report a vulnerability**（私密安全公告），
不要开公开 issue。

报告里请包含：

- 受影响版本与 Android / WebView 版本
- 复现步骤与影响范围
- 是否涉及真实设备、账号或数据

请先脱敏：不要附带真实 NAS 域名、账号、token、Cookie 或完整的诊断日志。
维护者会在确认后给出修复计划；这是个人项目，响应时间为尽力而为。

## 安全模型

UgPort 把 NAS 的网页放进系统 WebView 里运行，因此信任边界如下：

- JS 桥、登录自动填充、相机/麦克风权限只对受信任主机开放：
  `*.ug.link`、`*.ugdocker.link`、`*.ugnas.com`、`*.ugreentest.com`，
  当前 NAS 的 base host，以及 localhost 与私有网段
- 文件访问与内容访问关闭，混合内容只允许兼容模式
- 账号密码与 token 使用 Android Keystore 的 AES-256-GCM 加密，
  AAD 绑定 NAS ID
- 诊断日志只在内存中保留，不主动上传；复制时不含账号与设备型号
- 允许明文 HTTP，用于没有证书的局域网 NAS；公网访问建议使用 HTTPS

## 已知取舍

- 网页由第三方 WebUI 提供，其自身漏洞不在本项目控制范围内
- `usesCleartextTraffic` 会放行 HTTP 页面，这是访问局域网 NAS 的必要取舍
- 页面脚本可以读取页面自身的数据，这是 WebView 架构的固有限制

## 禁止行为

请只在你拥有或获得明确授权的设备上测试。不要扫描、探测或控制他人的 NAS。
