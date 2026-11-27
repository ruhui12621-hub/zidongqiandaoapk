# HoYoLAB Cookie Manager

Android app for HoYoLAB and Skland login, with direct Huawei FunctionGraph deployment.

## Download

[**Download the latest APK**](https://github.com/ruhui12621-hub/zidongqiandaoapk/releases/tag/debug-latest) — this page is automatically updated after each successful build on `main`.

## Build

Open the project in Android Studio and run `:app:assembleDebug`, or use GitHub Actions (**Build Android APK**).

The APK build workflow automatically downloads the latest sanitized HoYoLAB and Skland ZIP packages from the cloud-functions repository and bundles them into the APK. The app does not download function packages at runtime.

## Deployment

- Log in to HoYoLAB in the embedded WebView. The current Cookie is inserted into `config/config.yaml` before upload.
- Open the Skland page, paste the Token JSON from the Skland token page, and let the app parse and save the Token. The Token is encrypted at rest with Android Keystore and injected into `code/INPUT_HYPERGRYPH_TOKEN.txt` at deployment time.
- Log in to Huawei Cloud and tap **一键部署 / 更新云函数**. The app creates either fixed function if it does not exist, otherwise it updates its code. Function names are internal constants and are not shown in the UI.
- The built-in FunctionGraph functions use Python 3.10. The HoYoLAB handler is `index.main_handler`; the Skland handler is `index.handler`.
- No existing cloud ZIP is downloaded or merged. Each deployment starts from the two packages bundled in the APK, then injects the latest local credentials.

## Security

- Huawei Cloud AK/SK are kept in the encrypted local credential vault.
- HoYoLAB Cookie remains in the WebView cookie store and is sent only as part of the HoYoLAB deployment package.
- Skland Token is encrypted at rest with Android Keystore.
- Never commit real Cookies, Tokens, push credentials, passwords, or Huawei Cloud AK/SK.
## Cloud-first account restore

- On app startup, when saved Huawei Cloud credentials are available, the app queries the configured FunctionGraph region and reads the existing HoYoLAB and Skland function packages before allowing account changes.
- FunctionGraph ZIP responses are read from Base64 `func_code.file` when present, or downloaded from Huawei's temporary HTTPS `func_code.link` when the file field is empty. Downloads are size-limited and ZIP-validated.
- Authentication, network, missing code payload, malformed ZIP, or missing expected config-file errors are treated as sync failures, never as proof that no previous cloud configuration exists. Account changes remain locked in that state.
- Only a successful query confirming that both configured functions do not exist is treated as a first-time empty cloud state.
- Existing-cloud multi-account add/delete write-back and read-after-write verification are not yet enabled; local-only destructive changes remain blocked.

## 云函数 Cookie/Token 有效性校验

HoYoLAB 与森空岛函数使用各自独立的 LTS 日志流记录凭据状态。APK 部署时会把当前华为云区域、项目 ID、日志组 ID 和平台专属日志流 ID 合并写入 FunctionGraph `user_data`，并回读验证；不会在云函数源码中写死区域。

云函数按平台、CSV 账号 ID 和凭据 SHA-256 指纹匹配最近 48 小时的状态报告。最新报告为失效时跳过该账号的全部外部请求，并用新时间戳续报；最新报告为有效、没有匹配报告、报告超过 48 小时或当前凭据指纹已变化时，允许正常请求。日志只保存指纹，不保存 Cookie/Token 原文。

运行时查询 LTS 需要 FunctionGraph 执行委托提供临时 IAM Token，并授予最小权限 `lts:logStream:searchLog`（限制到对应平台日志流）。若未配置该委托或查询失败，函数会打印 `QUERY_UNAVAILABLE` 并按要求允许正常请求，因此配置委托后才能启用跨运行的失效拦截。
