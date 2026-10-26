# LKS DIALER v2.9.28 — App-to-App Caller Tune Root Cause Fix

## 🔧 Critical Fixes

### 🎵 App-to-App Caller Tune Playback (100% Resolved)
- **Automatic Audio Compression:** Trimming a 30-second audio clip previously produced 480KB–1.2MB files, which exceeded Firestore's safe Base64 limits and was leaving `callerTuneAudioBase64` empty. `AudioTrimmerUtil` now automatically compresses any trimmed clip >450KB to high-efficiency 64kbps AAC mono (~180–240KB), guaranteeing that `callerTuneAudioBase64` is **always populated and never empty**.
- **Preserved Direct Audio URLs:** The original streaming URL from song searches (`song.directAudioUrl`) was previously lost when moving to the trimmer dialog. It is now preserved all the way through to Firestore and SharedPreferences as a high-speed fallback.
- **Fixed Curated Hit Links:** Replaced legacy Pixabay URLs (which were rejecting streams with `403 Forbidden`) with reliable Apple CDN preview streams that return `200 OK`.
- **SharedPreferences & FCM Sync:** `FirebaseManager.updateCallerTune` now synchronously persists the caller tune into local SharedPreferences so background and locked-screen FCM push notifications immediately attach the tune data upon receiving incoming calls.
- **Reliable MediaPlayer Stream Headers:** Added proper mobile User-Agent headers to streaming requests in `CallSoundEffectsManager` to prevent CDN anti-scraping/hotlinking blocks.
- **Web App Synchronized:** Updated `CallerTuneModal` on the Web with the same reliable preset audio streams.

---
*Release APK: LKS-Dialer-v2.9.28.apk (~81MB)*
