# LKS DIALER v2.9.39 — Reliable Floating Pill Ringtone, Custom Contact Ringtones & Direct Mute

## 🔧 Critical Fixes & Improvements

### 🔔 1. Floating Pill Ringtone Playback (100% Fixed)
- **Eliminated Autonomous Overlay Silence**: Fixed an issue where Android Telecom emitted `onSilence()` autonomously when floating pill overlays (`TYPE_APPLICATION_OVERLAY`) were rendered on Samsung One UI and Xiaomi HyperOS, which previously killed the ringtone right as the pill appeared outside the app.
- **Sticky Broadcast False-Silence Protection**: Corrected `AudioManager.RINGER_MODE_CHANGED_ACTION` handling in `LksIncomingRingtonePlayer` to only silence when explicitly set to `RINGER_MODE_SILENT` or `RINGER_MODE_VIBRATE` after audio stabilization, preventing instant self-muting upon receiver registration.
- **Debounced Volume Key Broadcasts**: Added strict validation to `android.media.VOLUME_CHANGED_ACTION` (`streamType == STREAM_RING` and volume value delta) with a 1500ms audio-settling guard to prevent initial system audio routing events from silencing the incoming ring.

### 🎵 2. Rock-Solid Custom Contact & App Ringtones
- **Safe File & Content DataSources**: Removed premature `pfd.close()` calls before `player.prepare()`, ensuring custom trimmed songs and ringtones loaded from device storage play continuously without decoding errors.
- **Permanent Internal Storage Persistence**: Updated `LksRingtoneManager` to copy custom trimmed ringtones directly into permanent app-internal storage (`filesDir/ringtones/`), preventing cache cleaning or SAF permission expiration from revoking access.
- **Safe NotificationChannel Sound**: Guaranteed that `NotificationChannel` always uses a system-accessible URI so Android's `system_server` never drops sound due to private directory access restrictions.

### 🔇 3. Dedicated Silence/Mute Button on Incoming Floating Pill
- **In-Pill Silence Control**: Added an explicit Mute/Silence button directly to the floating incoming call pill next to Decline and Answer. Tapping it instantly silences ringtone and vibration while keeping the pill active so users can decide when to answer.
- **Dynamic Visual Feedback**: Subtext updates to *"Ringtone Silenced"* with an amber highlight when muted.

### 🌐 4. Web Client & Cloudflare Pages Deployment
- Version bumped to `2.9.39`.
- Built and deployed live to Cloudflare Pages: https://lksdialerweb.pages.dev.

---
*Release APK: LKS-Dialer-v2.9.39.apk / app-release.apk*
