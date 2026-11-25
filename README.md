# HoYoLAB Cookie Manager

Android app for HoYoLAB and Skland login, with direct Huawei FunctionGraph deployment.

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
