# LKS DIALER v2.9.37 — Fix Incoming Call Auto-Hangup & Telecom Ringing Guard

## 🔧 Critical Fixes & Improvements

### 📞 1. Auto-Hangup on Incoming Ringing Calls (100% Fixed)
- **Eliminated Premature Cache-Miss Teardown**: In previous versions, when `listenToActiveCall` was attached to an incoming call, Firestore's initial local cache snapshot emitted `snapshot.exists() == false` with `isFromCache == true` before the server document arrived. The engine previously treated this as a deleted document and immediately terminated the ringing call. This has been guarded with `hasSeenActiveDoc` and explicit cache-miss detection.
- **Clock-Skew Tolerant Freshness**: Expanded the incoming call freshness threshold to ±120s with absolute time difference calculations to accommodate client/server clock drift, completely eliminating accidental `MISSED` status overrides on fresh calls.
- **Removed False Query Snapshot Dropping**: Prevented query snapshot changes from terminating an ongoing ringing call if the call document temporarily cycled out of the callee's collection query.

### 🛡️ 2. Android Telecom ConnectionService Ringing Protection
- **Guarded OEM Radio & System Teardown**: On Samsung, Xiaomi, Oppo, and Vivo devices, the Android Telecom subsystem or OEM power managers sometimes invoke `ConnectionService.onDisconnect()` during `STATE_RINGING` or `STATE_INITIALIZING`.
- **Preserved Incoming Ringing UI**: `LksCallConnection.onDisconnect()` now explicitly guards against terminating the WebRTC call if the call is still ringing. The incoming ringing screen, ringtone, and notification remain fully active until the user explicitly accepts or declines.

### ⚡ 3. Targeted Active Polling Watchdog
- **Scoped Watchdog to Caller**: The rapid 350ms server-side polling watchdog is now scoped to the caller side (waiting for callee answer), preventing unnecessary network polling contention on the callee while preparing audio and WebRTC answer negotiation.

### 🌐 4. Web Client & Cloudflare Pages Deployment
- Updated `lks-dialer-web` with version 2.9.37.
- Built and deployed live to Cloudflare Pages: https://lksdialerweb.pages.dev.

---
*Release APK: LKS-Dialer-v2.9.37.apk / app-release.apk*
