# UgPort · NAS Port Launcher

English | [简体中文](README.md)

UgPort is an Android client that opens web apps running on your UGREEN NAS
(qBittorrent, Jellyfin, Navidrome, and similar WebUIs) directly from your phone.
It resolves the NAS address, signs in, exchanges access credentials, and hands
the page to the system WebView.

> **Unofficial project.** UgPort is not affiliated with, endorsed by, or
> sponsored by UGREEN. "UGREEN" and related marks belong to their respective
> owners. Use it only with devices you own.

## Features

- **Connection ID only**: enter something like `my-nas`; the app resolves the
  current relay and Docker domains
- **Automatic sign-in**: RSA-encrypted password, keepalive token stored
  encrypted on device, one automatic re-authorization when it expires
- **One-tap port launch**: Docker ports exchange `ugreen-proxy-token`;
  gateway ports append `uid`
- **Desktop or mobile layout**: per-port switch
- **Per-port background playback**: off by default; enable it for music or
  audiobook ports in the port editor
- **Media controls**: detects audio/video on the page; play, pause, and seek
  from the notification, lock screen, or Bluetooth headset
- **Previous / next**: reuses the page's MediaSession handlers first, then
  falls back to the player's own chapter buttons
- **Video fullscreen**: immersive fullscreen with automatic orientation
- **Immersive port page**: the page starts below the status bar; on-screen
  buttons collapse into a side handle after 5 seconds
- **WebGPU hint**: checks secure context, `navigator.gpu`, adapter, and WebGL2,
  and explains why WebGPU is unavailable
- **External link policy**: ask every time, open in-app, or open in a browser
- **Diagnostics**: key events only, copyable from settings; account names and
  device models are never written to the log

## Compatibility

- Android 8.0 (API 26) and later
- Uses the system WebView; no bundled browser engine
- No NAS model restriction; supports Docker ports and gateway (browserUrl) ports

## Download and install

Download `UgPort-<version>-release.apk` from [GitHub Releases](../../releases).

- The application ID is always `com.ugport`, and every public release is signed
  with the same certificate, so updates install over the previous version
- Allow installing apps from unknown sources on first install
- On Android 13 and later, grant notification permission or the lock-screen
  media controls will not appear

## Build

Requirements:

- JDK 17 or newer (development and verification use JDK 21)
- Android SDK 36 (Build Tools 36.0.0)
- No preinstalled Gradle; the repository ships a wrapper (Gradle 8.14.3)

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
- R8 mapping: `app/build/outputs/mapping/release/mapping.txt`

### Signing

Signing values are read from environment variables first, then from
`keystore.properties` in the repository root (ignored by `.gitignore`).
All four values must be present for the release build to be signed:

```bash
export UGPORT_KEYSTORE_FILE=/absolute/path/your-release.jks
export UGPORT_KEYSTORE_PASSWORD='your-password'
export UGPORT_KEY_ALIAS=your-alias
export UGPORT_KEY_PASSWORD='your-password'
```

Or:

```properties
storeFile=/absolute/path/your-release.jks
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

Without a signing configuration the release build still succeeds but is unsigned.

## Usage

1. Open "Add NAS":
   - Connection ID: the NAS prefix only, for example `my-nas`
   - Username and password
2. Tap "Test sign-in and identify server" and confirm the server ID
3. Add a port:
   - Name, port number, optional page path
   - "Desktop page": render with a desktop layout
   - "Allow background playback": enable for music or audiobook ports
4. Tap the tile on the home screen to open it

## How it works

1. `POST https://api.ugnas.com/api/p2p/v2/ta/nodeInfo/byAlias` resolves the
   connection ID into `relayDomain` and `dockerDomain`
2. `POST /ugreen/v1/verify/check` returns `X-Rsa-Token`; the password is
   encrypted with RSA/PKCS#1 and submitted to the sign-in endpoint
3. Docker ports: `GET /ugreen/v1/gateway/proxy/dockerToken` returns a one-time
   URL; a native request extracts `ugreen-proxy-token` into CookieManager
4. Gateway ports: `GET /ugreen/v1/docker/app/GetUid`; the `uid` is appended
   to the target URL
5. WebView loads the page; on token expiry or a login page, the app
   re-authorizes once
6. A page script reports media state; the app starts a foreground service and
   registers a MediaSession so system media controls drive the page

> These endpoints come from observing the UGOS Pro web client. They are not
> public APIs and may change without notice.

## Privacy and security

- Passwords are encrypted with AES-256-GCM using a key in the Android Keystore
  and never leave the device
- Tokens are encrypted at rest too; the AAD binds them to a NAS ID, so copying
  ciphertext to another entry will not decrypt
- No data is uploaded anywhere except UGREEN servers and your own NAS;
  no analytics or advertising SDKs
- Diagnostics keep the latest 200 lines in memory only; the copied header
  contains the OS version, never the account name or device model
- Camera and microphone permissions are granted only to trusted NAS pages,
  for in-page capture and voice features
- Cleartext HTTP is allowed so local NAS pages without certificates keep
  working; prefer HTTPS for anything exposed to the internet
- Version control contains no real NAS addresses, accounts, tokens, HAR files,
  signing keys, or APKs

If you paste logs into an issue, check them for NAS domains and ports first.

## Known limitations

- Rendering depends on the system WebView; vendor builds differ
- Some WebUIs are desktop-only; enable "Desktop page" for those ports
- Page height is repaired once automatically; clear cookies in settings if a
  page is still blank
- Previous/next prefers MediaSession `nexttrack`/`previoustrack`; otherwise it
  falls back to the player's chapter buttons, which may break when the page
  changes
- Live streams (infinite duration) do not show previous/next in MediaSession mode
- Leaving the port page with Back or the home button destroys the WebView and
  stops playback; Home, lock screen, and app switching keep it running
- Some vendor ROMs require autostart permission, an unrestricted battery
  policy, and locking the app in the recent-tasks screen
- Ports with background playback keep the WebView renderer at high priority;
  if the renderer is still reclaimed, the app rebuilds the page and resumes
  from the last position when you return
- Playback holds a CPU wake lock and a high-performance Wi-Fi lock, so standby
  power use increases
- Audio and video depend on the system WebView codecs; some formats need
  server-side transcoding
- WebGPU requires a secure context (HTTPS or localhost); HTTP pages cannot use it
- Android 13+ requires notification permission for media controls

## Project layout

```
app/src/main/java/com/ugport/
├── auth/      sign-in, session, domain resolution, port launching
├── data/      models, encryption, DataStore repository
├── di/        hand-written dependency container
├── media/     playback state machine, foreground service, notification
├── network/   UGREEN API, endpoint constants, RSA, OkHttp, diagnostics
├── ui/        Compose screens and ViewModel
│   ├── components/  glass components and shared controls
│   ├── theme/       colors, typography, shapes
│   └── web/         port screen, floating controls, overlays, platform helpers
├── util/      URL helpers
└── web/       WebView config, login scripts, media script, JS bridges
```

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. Before submitting:

```bash
./gradlew testDebugUnitTest
```

Report security issues as described in [SECURITY.md](SECURITY.md). The release
process is documented in [RELEASING.md](RELEASING.md).

## License

UgPort is licensed under the **PolyForm Noncommercial License 1.0.0**.
The full text is in [LICENSE](LICENSE).

- Allowed: personal study, research, experiments, hobby projects, and use
  inside noncommercial organizations
- Allowed: modification and distribution, provided the license and the
  `Required Notice` are kept
- Not allowed: any commercial use, including selling the software, bundling it
  into a commercial product, or offering it as a paid service
- Commercial licensing requires prior written permission from the maintainers

To be explicit: **this is source-available software, not OSI-approved open
source**. A noncommercial restriction is incompatible with the OSI definition,
and GitHub may label the repository as using a non-standard license.

Third-party dependencies remain under their own licenses; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Trademarks

UgPort is an independent project and is not affiliated with UGREEN. The project
name and icon are not covered by the source-code license.
