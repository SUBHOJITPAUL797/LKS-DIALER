# LKS DIALER v2.9.27 — Caller Tune Fix + Web Save Fixed

## 🔧 Bug Fixes

### 🎵 Caller Tune (Hello Tune) — Now Actually Works!
- **Root cause fixed:** When a call came in via FCM (background/locked screen), the FCM handler was writing `status: RINGING` to Firestore **without** the callee's caller tune data. This caused the caller to hear the standard ringback tone instead of the set hello tune.
- **Fix:** Both RINGING writes in `CallMessagingService` now read the callee's caller tune from `SharedPreferences` and include `callerTuneTitle`, `callerTuneAudioBase64`, and `callerTuneUrl` in the same Firestore update — so the caller's listener immediately receives the tune data and plays it.
- This fix applies for both **foreground** and **background/locked** incoming calls.

### 🌐 Web — Caller Tune Save Error Fixed
- Saving a caller tune on the web (`lksdialerweb.pages.dev`) was failing with _"Failed to save caller tune"_ due to two issues:
  1. `updateDoc()` was used instead of `setDoc(..., { merge: true })` — fails if Firestore fields don't exist yet
  2. Attempting to store full audio Base64 (500KB–1MB+) in Firestore exceeded the 1MB document size limit
- **Fix:** Now uses `setDoc+merge` (always safe) and stores only the **audio URL** in Firestore. The caller streams the audio directly from the CDN URL — no size limit issues.

## ✅ What Works Now
- Set a caller tune on Android or Web → caller hears it when they call you
- Works even when your phone is locked / app is in background
- Web caller tune save no longer errors out
- Standard ringback tone still plays if no caller tune is set

---
*APK size: ~81MB*
