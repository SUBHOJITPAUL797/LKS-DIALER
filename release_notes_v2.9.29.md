# LKS DIALER v2.9.29 — Caller Tune Trimmer Cropped Preview Fix

## 🔧 Critical Fixes & Improvements

### 🎵 Caller Tune Trimmer Audio Preview (100% Fixed)
- **Resolved Crop Preview Bug:** Fixed the issue where dragging the trimmer handles to crop an audio track (e.g. 0:27 to 0:57) and clicking "▶ Preview 30s Caller Tune" produced no sound.
- **Fixed Compose Lifecycle Disposal Bug:** Replaced `DisposableEffect(previewPlayer)` with `DisposableEffect(Unit)` so that assigning a new `MediaPlayer` instance upon tapping Preview never triggers premature `onDispose` release calls.
- **Asynchronous Seek-To Coordination:** Added `setOnSeekCompleteListener` with `MediaPlayer.SEEK_CLOSEST` to guarantee playback starts only after seeking to `startMs` lands accurately, eliminating the race condition where `LaunchedEffect` paused playback immediately.
- **SELinux Safe File Descriptors:** `MediaPlayer` and `MediaMetadataRetriever` now access internal cache files through opened `FileInputStream.fd` descriptors, completely bypassing Android's private directory SELinux IPC restrictions.
- **Explicit Media Audio Attributes:** Configured `AudioAttributes.USAGE_MEDIA` and `AudioAttributes.CONTENT_TYPE_MUSIC` with volume `1.0f` and restored `AudioManager.MODE_NORMAL` to ensure preview streams are always directed to loud speakers rather than silenced or routed to the call earpiece.
- **Instant Visual Loading Feedback:** Added "Preparing Preview..." indicator while audio initializes, preventing double-taps and providing immediate response.
- **Applied Identical Fix to Ringtone Trimmer:** Updated `SongTrimmerDialog` with the same robust media pipeline for ringtone cropping.

---
*Release APK: LKS-Dialer-v2.9.29.apk*
