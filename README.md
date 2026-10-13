# Phone AK — Android dialer starter

A native Kotlin + Jetpack Compose starter for the requested iOS-inspired Android dialer: adaptive system theme, Recents / Contacts / Keypad tabs, Recents as the initial screen, and a GitHub Actions APK build.

## Build APK with GitHub Actions

1. Create a GitHub repository (for example `phone-ak`).
2. Upload the **contents** of this folder to the repository root (so `settings.gradle.kts`, `build.gradle.kts`, `app/`, and `.github/` are at the root).
3. Push to `main`/`master`, or open **Actions → Build Phone AK APK → Run workflow**.
4. Open the completed workflow run and download the `Phone-AK-debug-apk` artifact.
5. Unzip the artifact and install `app-debug.apk` on your Android phone. You may need to allow installs from the browser/file manager you used.
6. Open Phone AK and choose **Set default dialer** in the app settings. Grant contacts and call-log permissions when prompted by Android.

## Current starter scope

- Adaptive light/dark Compose UI
- Recents loaded from Android call history when permission is granted
- Contacts list and search when contacts permission is granted
- Keypad and ACTION_DIAL handoff
- Android default dialer role request
- GitHub Actions debug APK artifact

## Important limitations

This is a starter implementation, not a fully featured production dialer yet. It currently launches the system dialer for a call rather than providing a complete in-call UI. Manual call recording, dual-SIM selection, voicemail integration, spam identification/blocking, and contacts export/restore still need device-tested implementations. Android restricts call-audio capture, and a visible recording button alone cannot guarantee call recording. Do not rely on this build to record calls.

Before public distribution, implement the full `InCallService`/Telecom integration, test default-dialer behavior on target devices, implement backup/export and blocking flows, and review permissions and Play policy requirements.
