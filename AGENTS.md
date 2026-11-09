# Android builds

After changing the Android app, run `:app:assembleRelease` and refresh the workspace-root `Auralis-Release.apk` from `android/app/build/outputs/apk/release/app-release.apk`. Deliver the release APK by default.

# Windows builds

After changing the Windows app, always update the installed Windows app and runtime using `.\scripts\update-windows-app.ps1` (which builds `:windows:createDistributable`, backs up user data, updates the installed app at `%LOCALAPPDATA%\Auralis`, and relaunches it).

The root `gradle.properties` supplies the production Connect endpoint for Android and Windows. Keep Connect enabled in normal builds, including releases for UI testing. Preserve the app ID, signing key, and installed app data when updating via ADB.
