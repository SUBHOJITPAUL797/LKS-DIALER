### 🚀 What's New in v2.9.22

- **⚡ High-Speed APK Updater (Full 10+ MB/s Line Rate)**:
  - Re-architected in-app update downloader with OkHttp buffered streaming (`128 KB` buffer with `64 KB` read chunks).
  - Eliminates syscall overhead and delivers full bandwidth download speeds (10+ MB/s on Wi-Fi and high-speed cellular).
  - Enhanced update dialog with real-time download speed (`MB/s`), downloaded/total byte counter (`45.2 MB / 84.8 MB`), and accurate ETA.

- **📶 Dual-Tier Mobile Data Auto-Update Detection**:
  - Implemented dual-tier update checking mechanism: Primary GitHub REST API with seamless fallback to direct GitHub 302 web redirect.
  - Completely bypasses the 60 requests/hour IP rate limit on mobile carrier CGNAT shared IP addresses.
  - Automatic network connectivity callback triggers instant update detection when switching between Wi-Fi and mobile data.

- **📹 Seamless Web-to-Android Video Call Upgrade**:
  - Fixed video call conversion hanging on *"Connecting..."* when upgrading from audio call.
  - Progressive video-only media acquisition on Web prevents desktop webcam exclusivity conflicts and camera errors.
  - Stale remote answer SDP protection eliminates WebRTC renegotiation race conditions.
  - Automatic 10-second watchdog timer on Android: if video renegotiation stalls, safely reverts to audio call without freezing or dropping the call.
  - Proper bidirectional handling of Declined video upgrade status across both Web and Android.

- **🔍 Interactive Pinch-To-Zoom Image Viewer**:
  - Full two-finger pinch-to-zoom (1.0x - 5.0x) on both Android & Web.
  - Free pan movement when zoomed in with smooth edge bounds-clamping.
  - Double-tap or double-click to smoothly toggle between 1x and 2.5x zoom.

- **👁️ View Once Media (Zero-Retention Auto-Destruction)**:
  - WhatsApp-authentic circled `(1)` View Once toggle for photos.
  - Media permanently destroyed and unlinked from device storage once viewed.
  - Authentic pill cards for unopened and viewed states with full E2EE receipt synchronization.
