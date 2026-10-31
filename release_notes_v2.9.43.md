# LKS Dialer v2.9.43 Release Notes

### 🛠️ Critical Bug Fixes & Improvements

1. **Android 15 Settings Crash Resolution (`WRITE_SETTINGS` SecurityException)**:
   - **Root Cause**: On Android 15 (API 35), specifically on Infinix X6851 and Transsion/Pixel OEM ROMs, calling `RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)` triggers internal framework checks that invoke `Settings.System.putStringForUser()`. Because third-party apps are not granted `android.permission.WRITE_SETTINGS`, this threw an unhandled `SecurityException` that instantly crashed the app whenever a user opened the Settings screen.
   - **Fix**: Wrapped all `getActualDefaultRingtoneUri` and `getRingtoneTitle` queries in `LksRingtoneManager` and `LksIncomingRingtonePlayer` with strict `try-catch` blocks and safe fallback to standard `RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)`, completely eliminating crashes on Android 15 devices.

2. **P2P & Cloud Relay File Transfer Reliability**:
   - WebRTC DataChannel P2P file transfers are backed by TLS port 443 TURN relay servers with zero WAF blocking.
   - Fallback chunk uploads utilize optimized 128KB chunks for instant Firestore synchronization without UI freeze.
   - Real-time progress displays live transfer speed (MB/s) and transferred bytes (`X.X / Y.Y MB`), removing ambiguous `Connecting...` states.
   - CPU `PartialWakeLock` protects both sender and receiver during large file transfers (audio, video, docs) across screen-off states.

3. **Call Audio & Hardware Volume Button Mute**:
   - Volume rocker press on lockscreen or background immediately silences incoming ringtones and vibration without dropping the incoming call.
   - Hardened monotonic call state machine prevents audio loops or overlapping call progress tones upon answering.
