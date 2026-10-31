# LKS DIALER v2.9.42 — Monotonic Call State Protection, Ringback Drowning Fix & Cloudflare Worker TURN Auth

## ⚡ Critical Bug Fixes & Reliability Enhancements

### 📞 1. Strict Monotonic Call State Machine & Ringback Drowning Bug Fix
- **Log Investigation Findings**: Diagnosed the exact issue from the 30/10/2026 ~23:46:37 call session between Babai (`SM-M346B`) and Riu (`22120RN86I`). When Riu answered the call, Babai's phone received the high-priority `call_answered` FCM push and unmuted the audio track to 1.0. However, 150ms later, a delayed Firestore snapshot/polling event delivered a document state where `status` was still `RINGING`.
- **Root Cause Eliminated**: `WebRtcEngine.kt`'s document handler lacked an `ANSWERED` check and was calling `CallSoundEffectsManager.onCallRinging(context)` repeatedly on every poll (4 times/sec), which restarted the `ToneGenerator` supervisory ringback tone (`tuuut... tuuut...`) in Babai's earpiece while Riu was actively speaking, drowning out Riu's voice.
- **Strict Monotonic Transition**: Added strict state guards preventing the engine from ever transitioning backwards from `ANSWERED` to `RINGING` or `CALLING`.
- **Sound Effects Lockout**: Added `@Volatile private var isCallAnswered = false` in `CallSoundEffectsManager.kt`. Once a call is answered, `stopRingbackTone()` permanently locks out supervisory tones until a new call is placed.

### 🌐 2. Cloudflare Worker TURN Credential Authentication Fix
- **Resolved 403 Forbidden on TURN Endpoint**: Added standard `User-Agent: LKS-Dialer-Android` header to `WebRtcEngine.getFreshIceServers()`. Cloudflare's WAF was blocking the default Android HttpURLConnection User-Agent with HTTP 403 Forbidden, which previously forced devices onto throttled public TURN fallback.
- **Reliable Dynamic Relay**: All WebRTC calls and P2P transfers now reliably receive fresh, authenticated TLS 443 TURN credentials directly from the Cloudflare Worker.

### 🚀 3. Web & Cloudflare Pages Sync
- Bumped web version to `2.9.42`.
- Deployed live to Cloudflare Pages: https://lksdialerweb.pages.dev.

---
*Release APK: LKS-Dialer-v2.9.42.apk / app-release.apk*
