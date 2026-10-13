# Phone AK — native Android dialer

Phone AK is a Kotlin + Jetpack Compose Android dialer with an iOS-inspired adaptive light/dark interface, Recents / Contacts / Keypad tabs, a default-dialer role request, Android Telecom call placement, and a custom `InCallService` with its own incoming and active-call UI.

## Build the APK with GitHub Actions

1. Upload the contents of this folder to the root of your GitHub repository (including `app/`, `build.gradle.kts`, `settings.gradle.kts`, and `.github/`).
2. Open **Actions → Build Phone AK APK → Run workflow** (or push a commit to `main`/`master`).
3. When the run succeeds, open it and download the `Phone-AK-debug-apk` artifact.
4. Install `app-debug.apk` on an Android phone.
5. Open Phone AK, grant phone/notification/contacts permissions, and choose **Set default dialer** in the app settings. Android must designate Phone AK as the default phone app before it can own the in-call UI.

## Implemented in this version

- Adaptive light/dark UI and Recents / Contacts / Keypad tabs
- Recents from Android call history and contacts search
- Default phone app request using Android's dialer role
- Calls placed with `TelecomManager.placeCall()` instead of handing off to Google's dialer
- `PhoneAKInCallService` registration and call-state tracking
- Phone AK incoming-call screen notification/full-screen intent with Answer and Decline actions
- Built-in active-call screen with answer/reject, hang up, mute, speaker, hold/resume, DTMF keypad, and add-call entry point
- Ongoing-call notification to return to the Phone AK call screen
- GitHub Actions APK artifact build

## Important platform limits

- Android's preloaded dialer is always used for emergency calls, even when Phone AK is the default dialer. Android, carrier, and manufacturer behavior can also affect call UI, SIM routing, Bluetooth, and call waiting.
- For dual-SIM devices, this version delegates account/SIM selection to Android Telecom; a dedicated in-app SIM picker is not yet implemented.
- Call recording is not implemented. A visible button alone cannot capture both sides of a cellular call; support is restricted by Android version, device firmware, carrier policy, and local law.
- Spam identification/blocking, voicemail integration, and contacts backup/restore are not implemented in this build.
- This source update has not been compiled or tested on a physical phone in this environment. Run the included GitHub Actions workflow and test incoming/outgoing calls on your device before relying on it.
