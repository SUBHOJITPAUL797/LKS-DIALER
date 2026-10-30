# LKS DIALER v2.9.40 — Industry-Grade P2P File Transfer, Complete TLS TURN & Smooth Relay Fallback

## ⚡ Key Improvements & Fixes

### 🚀 1. Rock-Solid P2P Direct File Transfer Over Cellular & Wi-Fi
- **Full TLS Port 443 TURN Support**: Added `turns:a.relay.metered.ca:443`, `turns:a.relay.metered.ca:443?transport=tcp`, and `stun.cloudflare.com:3478`, paired with authenticated dynamic TURN credentials fetched from the Cloudflare Worker API. Transfers now bypass symmetric Carrier-Grade NAT (CGNAT) on Indian mobile carriers (Jio, Airtel, Vi) without failing.
- **Dynamic ICE Server Resolution**: `P2pFileTransfer` dynamically fetches fresh, short-lived HMAC TURN credentials via `WebRtcEngine.getFreshIceServers()`.
- **Extended ICE Handshake Timeout**: Extended handshake timeout from 15 seconds to 30 seconds (`ICE_TIMEOUT_MS = 30000L`) to give cellular handshakes and network routing ample time to negotiate before falling back.
- **Parallel ICE Candidate Collection**: Attached receiver ICE listeners immediately after setting local answer description so incoming candidates are queued and processed simultaneously with Firestore signaling writes.

### 🔋 2. Background Transfer WakeLock Protection
- **No More Doze / Deep Sleep Freezes**: Both `sendFile` and `receiveFile` now acquire a dedicated partial `PowerManager.WakeLock` (`LKS:P2P:tx_...` / `LKS:P2P:rx_...`) for the duration of the transfer. The CPU will not sleep or throttle while transferring in the background.
- **Extended FCM Receiver Window**: Boosted FCM wake lock duration and timeout in `CallMessagingService` when processing `P2P_OFFER` events from 15s to 45s.

### ☁️ 3. Instant, Transparent Relay Fallback (No Stuck "Connecting...")
- **Optimized 128 KB Chunk Size**: Reduced Firestore relay chunk size from 512 KB to 128 KB (`128 * 1024`). Chunk documents now fit well under Firestore's 1 MiB limit (~220 KB base64 ciphertext) and upload in ~250ms on mobile data.
- **Immediate `TRANSFERRING` Status**: When P2P falls back to Relay mode, status immediately switches to `TRANSFERRING` with `0.0 MB` instead of staying stuck on indeterminate `☁️ Relay Connecting...`.
- **Live Transfer Speed & Progress**: Relay mode now computes real-time transfer speed (`speedBytesPerSec`) and updates the progress indicator progressively after every chunk.

### 🌐 4. Web Client Sync & Cloudflare Pages Deployment
- Updated `lks-dialer-web` (`P2pFileTransferWeb.js` & `ChatRepositoryWeb.js`) with 128 KB chunking, 30s timeout, TLS 443 TURN configs, and immediate `TRANSFERRING` status.
- Built and deployed live to Cloudflare Pages: https://lksdialerweb.pages.dev.

---
*Release APK: LKS-Dialer-v2.9.40.apk / app-release.apk*
