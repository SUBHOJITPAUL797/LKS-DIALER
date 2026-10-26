# LKS DIALER v2.9.30 — Caller Tune Cropped Section & Settings Preview Fix

## 🔧 Critical Fixes & Improvements

### 🎵 1. Cropped Caller Tune Playback on Incoming/Outgoing Calls (100% Fixed)
- **Resolved Beginning-of-Song Playback:** Previously, when a user cropped a song (e.g. from 0:27 to 0:57), callers still heard the song from 0:00. This was caused by `AudioTrimmerUtil.tryTrimMediaMuxerFast` writing samples before `startUs` due to `MediaExtractor.seekTo` landing at timestamp 0 on AAC/MP4 audio. Added strict sample filtering `if (sampleTimeUs < startUs) continue` so the generated clip begins **precisely** at the cropped timestamp.
- **SELinux Safe Descriptors for Caller Playback:** In `CallSoundEffectsManager`, decoded Base64 tune files are now opened via `FileInputStream.fd` descriptors rather than private internal storage path strings, eliminating the `Permission Denied` error that forced fallback to the uncropped remote URL.
- **Syncing Crop Timestamp Coordinates:** Added `callerTuneStartMs` and `callerTuneEndMs` to `CallDto` and `ringUpdates` in `CallMessagingService` and `WebRtcEngine`. If the caller ever plays from direct stream URL as a fallback, `CallSoundEffectsManager` automatically seeks to `startMs` on prepared so the cropped part is guaranteed to play.

### 🔊 2. In-App Settings Preview Play Button (100% Fixed)
- **Resolved Play Button in Settings:** Fixed the issue where clicking the green Play button `(▶)` next to "Hello Tune for Callers" in Settings would not play any sound.
- **Robust Multi-Source Playback Pipeline:** `LksCallerTuneManager.playPreview` now checks `my_caller_tune.mp3`, `info.localUri`, `info.audioBase64`, and fallback `info.audioUrl` using `FileInputStream.fd` and proper User-Agent headers.
- **Forced Loud Speaker Routing:** Explicitly sets `AudioAttributes.USAGE_MEDIA` and `AudioAttributes.CONTENT_TYPE_MUSIC` with volume `1.0f` and resets `AudioManager.MODE_NORMAL` to prevent playback routing to the call earpiece.

---
*Release APK: LKS-Dialer-v2.9.30.apk*
