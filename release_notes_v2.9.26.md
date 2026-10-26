# LKS DIALER v2.9.26 Release Notes

### 🎵 In-App Song Preview, 30s Trimmer & Clean Caller Experience

This update resolves all Caller Tune playback issues by bringing 100% in-app music discovery, direct audio streaming, instant in-app previews, and a clean caller screen UI.

---

### 🌟 What's New & Fixed

1. **🎵 100% In-App Music Discovery & Direct Audio Streaming**:
   - Replaced external YouTube app/browser redirects (`Intent.ACTION_VIEW`) with full in-app audio discovery across 80M+ songs (Bollywood, Hollywood, Punjabi, Lo-Fi, and Global).
   - Songs resolve direct, high-fidelity 160kbps audio streams with no external dependencies or API keys.
   - In-app audio preview: Tap the preview button to listen to any song right inside the search dialog.

2. **✂️ Instant 1-Tap Audio Trimmer**:
   - Selecting a song downloads the audio into cache in ~1s with a progress indicator and immediately opens the visual 30-second chorus trimmer.
   - Full drag-and-drop handles and chorus presets to trim the exact 30-second segment you want callers to hear.
   - Trimmed audio is converted and synced directly to your profile via Base64, guaranteeing instant playback on the caller's device without CDN rate limits or playback errors.

3. **🔕 Clean Caller Screen (No Song Name Banner)**:
   - Removed the caller-side "Playing Caller Tune: [Song Title]" banner from the outgoing call screen per user request.
   - Matches standard telecom Hello Tune behavior: callers simply enjoy the music in their earpiece or speaker while waiting for you to pick up.

4. **⚡ Fixed "Song Not Playing" Error**:
   - Fixed the issue where raw YouTube URLs caused `MediaPlayer` errors (`MEDIA_ERROR_UNKNOWN` / silence).
   - All caller tunes now stream directly or load from verified Base64 audio, ensuring audio always plays smoothly through `STREAM_VOICE_CALL`.
