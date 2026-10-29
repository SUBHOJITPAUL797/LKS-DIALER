# LKS DIALER v2.9.38 — Native Volume Button Silence & Ringtone Mute

## 🔧 Critical Fixes & Improvements

### 🔇 1. Native Volume Button Silence (100% Fixed)
- **Instant Mute on Volume Button**: When an incoming call is ringing, pressing either Volume Down, Volume Up, or Volume Mute immediately silences the ringtone and stops vibration without declining or dropping the call—exactly like native Android incoming phone calls!
- **Meeting Mode Friendly**: Allows users to quickly silence unwanted rings in meetings, classes, or public spaces while keeping the incoming call screen active to decide whether to accept or decline.
- **Background & Lockscreen Support**:
  - **Android Telecom `Connection.onSilence()`**: Connected Android Telecom's native `onSilence()` callback with an 800ms initial debounce to handle hardware volume button presses on lockscreen or background.
  - **System Volume Change Broadcast Listener**: Dynamic broadcast receiver listening for `android.media.VOLUME_CHANGED_ACTION` and `AudioManager.RINGER_MODE_CHANGED_ACTION` to catch volume key adjustments from any context.
  - **Foreground Window Key Dispatch**: Intercepts `KEYCODE_VOLUME_DOWN`, `KEYCODE_VOLUME_UP`, and `KEYCODE_VOLUME_MUTE` in `MainActivity` with both `ACTION_DOWN` and `ACTION_UP` consumed so no volume HUD slider disrupts the call UI.

### 🛡️ 2. Permanent Silencing Guard
- **Prevented Unwanted Ring Restarts**: Added `isSilenced` state tracking in `LksIncomingRingtonePlayer` linked to `activeCallId` so Compose state changes and loop monitors never re-trigger audio playback once silenced by the user.
- **Fixed False Unsilence in Floating Bubble**: Replaced premature `stopRinging()` with `silenceRingingInternal()` in `FloatingCallBubbleService` to avoid clearing silence flags.

### 🌐 3. Web Client & Cloudflare Pages Deployment
- Version bumped to 2.9.38.
- Built and deployed live to Cloudflare Pages: https://lksdialerweb.pages.dev.

---
*Release APK: LKS-Dialer-v2.9.38.apk / app-release.apk*
