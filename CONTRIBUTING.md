# 贡献指南

感谢你愿意改进 UgPort。这个项目主要面向个人 NAS 用户，改动请优先保证
「不破坏已有登录与端口打开流程」。

## 开发环境

- JDK 17 及以上
- Android SDK 36（Build Tools 36.0.0）
- 使用仓库自带的 Gradle Wrapper，不要提交本机的 `local.properties`

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## 代码风格

- 4 空格缩进、UTF-8、LF、文件末尾保留换行
- Kotlin 行宽 120，XML/TOML/JSON 2 空格
- 参考 `.editorconfig`
- 命名遵循 Kotlin 官方风格：类型 PascalCase，函数/属性 lowerCamel，常量 UPPER_SNAKE
- 注释写「为什么」和约束，不写逐行翻译；公开 API 用 KDoc

## 提交规范

使用 Conventional Commits，描述可以用中文：

```
feat(web): 支持页面内的下载任务
fix(media): 修复章节结束后通知不消失
docs: 补充签名说明
refactor(auth): 拆分令牌刷新逻辑
```

一个提交只做一件事。格式化、重命名、行为变更不要混在同一个提交里。

## 测试

- 纯逻辑（URL、迁移、序列化、脱敏、解析）请补 JVM 单元测试
- WebView、媒体、全屏等依赖真机的行为，请在 PR 描述里写清验证步骤与设备信息
- 提交前至少运行 `./gradlew testDebugUnitTest`

## 隐私要求

提交内容不得包含：

- 真实 NAS 连接标识、域名、端口、账号、密码、token、Cookie
- 抓包文件（HAR）、诊断日志、包含上述信息的截图
- 签名密钥、`keystore.properties`、`local.properties`

测试与文档统一使用 `my-nas`、`xx.ug.link`、`8080` 这类占位值。
如果日志对定位问题很重要，请先脱敏再贴到 issue。

## 不接受的方向

- 绕过绿联的登录、鉴权或设备绑定
- 批量扫描、控制他人设备
- 任何商业化功能（本项目许可证禁止商业使用）
- 引入统计、广告或非必要的数据上传

## 许可证

提交 Pull Request 即表示你同意以 [PolyForm Noncommercial License 1.0.0](LICENSE)
授权你的贡献，无需额外签署 CLA。
