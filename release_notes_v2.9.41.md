# LKS DIALER v2.9.41 — Double-Stream Race Guard, Receiver ICE Disconnect Cleanup & Group Media Support

## ⚡ Improvements & Hardened Reliability

### 🚀 1. P2P Streaming Race Condition Elimination
- **Synchronized Single-Start Guard**: Added an atomic guard (`streamStarted`) to prevent any potential race where `DataChannel.State.OPEN` observer fires simultaneously with post-registration state check, guaranteeing the file is streamed through the DataChannel exactly once.
- **Immediate Receiver Failure Teardown**: In [`P2pFileTransfer.kt`](file:///c:/CODING/coading/LKS-DIALER/app/src/main/java/com/example/data/p2p/P2pFileTransfer.kt), if the sender disconnects, closes the DataChannel prematurely, or ICE connection state enters `FAILED`, the receiver immediately closes file descriptors, deletes the temporary file, notifies `onComplete(null, sessionId)`, and releases the CPU wakeLock.

### 👥 2. Complete Group Media File Sharing Support
- **Full Media Fanout in Groups**: Fixed an issue where group chats (`group_...`) were previously intercepted by the 1-on-1 P2P transfer block with an empty recipient public key.
- **Group Media Encryption**: Group media (photos, voice notes, stickers, and documents) now correctly prepares the local copy, base64-encodes and packages the JSON payload, and securely fans out pairwise encrypted messages to every member of the group.

### 🌐 3. Web & Cloudflare Pages Sync
- Version bumped to `2.9.41`.
- Built and deployed live to Cloudflare Pages: https://lksdialerweb.pages.dev.

---
*Release APK: LKS-Dialer-v2.9.41.apk / app-release.apk*
