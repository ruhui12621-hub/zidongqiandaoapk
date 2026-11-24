# HoYoLAB Cookie Manager

Android standalone app using an embedded Android WebView and the native CookieManager API.

## Build

Open this repository in Android Studio, sync Gradle, then run `:app:assembleDebug`.

Or open GitHub Actions and run **Build Android APK**. The APK is uploaded as a workflow artifact.

## Notes

- The app opens `https://www.hoyolab.com/` in its own WebView.
- Cookie reading uses Android's native CookieManager for matching origins.
- Cookies remain in the app's WebView cookie store. No cloud upload is implemented.
- The version-15 YAML template is retained. Only `games.os.cookie` is populated; `account.cookie` stays empty; `games.os.zzz.checkin` stays enabled.
- This is an initial implementation and needs real-device login-flow testing.