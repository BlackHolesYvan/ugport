# 发布流程

本文档描述从改代码到 GitHub Release 的完整流程。命令默认在仓库根目录执行。

## 版本号

- `versionName`：语义化版本，功能/界面变更升次版本，修复升修订号
- `versionCode`：单调递增，每次公开构建 +1
- Git tag：`v<versionName>`，例如 `v0.5.0`

## 密钥管理（最重要）

1. `.jks` 与 `keystore.properties` 永远不要提交到仓库，只放在仓库外并离线备份。
2. 同一个 `applicationId`（`com.ugport`）的所有公开版本必须使用同一把钥匙，
   否则老用户无法覆盖升级。
3. 发布源码包用 `git archive`，不要直接压缩工作目录，避免把 `dist/`、
   `app/build/`、`.jks` 一起打进去。

签名信息通过四个环境变量注入：

```bash
export UGPORT_KEYSTORE_FILE=/absolute/path/ugport-release.jks
export UGPORT_KEYSTORE_PASSWORD='...'
export UGPORT_KEY_ALIAS=ugport
export UGPORT_KEY_PASSWORD='...'
```

## 发布步骤

1. **确认工作区干净**

   ```bash
   git status --short          # 应为空
   git pull --ff-only
   ```

2. **升级版本号**

   修改 `app/build.gradle.kts` 的 `versionCode` / `versionName`，
   并在 `CHANGELOG.md` 顶部新增对应条目。

3. **跑测试与构建**

   ```bash
   ./gradlew clean testDebugUnitTest assembleDebug assembleRelease
   ```

4. **校验产物**

   ```bash
   aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -5
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   sha256sum app/build/outputs/apk/release/app-release.apk
   ```

   确认包名、版本号、应用名正确，证书 SHA-256 与上一个公开版本一致。

5. **覆盖升级测试**

   在已安装旧版本的设备上 `adb install -r` 新包，确认 NAS 配置与加密 token 不丢。

6. **提交并打 tag**

   ```bash
   git add -A
   git commit -m "chore(release): 0.5.0"
   git tag -a v0.5.0 -m "UgPort 0.5.0 (versionCode 27)"
   ```

7. **推送**

   ```bash
   git push origin master
   git push origin v0.5.0
   ```

8. **创建 GitHub Release**

   资产：

   - `UgPort-<version>-release.apk`
   - `UgPort-<version>-release.apk.sha256`
   - `mapping.txt`（R8 混淆映射，便于排查崩溃）
   - `THIRD_PARTY_NOTICES.md`

   正文写明：安装方式、覆盖升级、SHA-256、证书指纹、许可证、已知问题。

9. **发布后复验**

   重新下载 Release 资产，校验 SHA-256，并在干净设备或模拟器上安装一次。

## 回滚

- 如果只是构建问题：删除 Release 与 tag，修复后重新发版，不要复用已发布的版本号。
- 如果新版本有严重回归：把 Release 标记为 pre-release 或撤下，发布一个修订版本；
  不要强制推送或重写已公开的 tag。
