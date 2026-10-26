# LKS DIALER v2.9.25 Release Notes

### 🎵 Caller Tune (Hello Tune) with YouTube Search & Visual Chorus Trimmer

We are excited to introduce **Caller Tune (Hello Tune)** in LKS DIALER! Callers hear your favorite music while waiting for you to pick up instead of the standard supervisory ringback tone (`tuuut... tuuut...`).

---

### 🌟 What's New

1. **🎵 Caller Tune (Hello Tune) System**:
   - Callers hear your custom tune while ringing before the call connects.
   - When call is answered, declined, or disconnected, the tune terminates instantly (<50ms).
   - If no tune is configured, the classic supervisory ringback tone plays as usual.
   - Full hardware audio routing support: smoothly routes through earpiece, speakerphone, or Bluetooth headsets via `STREAM_VOICE_CALL`.

2. **🔍 YouTube Music Catalog Search**:
   - Search across millions of songs, artists, and viral hits with zero API keys required.
   - Quick genre suggestion chips: Bollywood Hits, Global Top 50, Lo-Fi Chill, Acoustic Guitar, Piano, and Punjabi.
   - Interactive song preview: tap any YouTube thumbnail to listen to the song before choosing your 30-second chorus.
   - Supports direct YouTube and Shorts link pasting to resolve song details instantly.

3. **✂️ Visual 30-Second Chorus Trimmer & Portion Selector**:
   - Visual waveform track with dual draggable handles (🟢 Green Start, 🔴 Red End).
   - Smart 30-second constraint: prevents awkward cuts while ensuring ideal length.
   - Quick chorus presets for instant selection:
     - 🌟 **Chorus (0:30 - 1:00)**
     - 🎶 **Intro (0:00 - 0:30)**
     - ⚡ **Hook (1:00 - 1:30)**
     - 🎛️ **Custom Slider**
   - Live timestamp cards showing Start Time, Selected Tune Length, and End Time.
   - In-app 30-second preview player with real-time playback progress bar.

4. **📁 Device Audio & Curated Trending Tunes**:
   - **My Device**: Pick any song or recording from your phone; visually trim and save directly to your profile.
   - **Curated Tunes**: 5 high-fidelity royalty-free tunes (Acoustic Guitar, Lo-Fi Chill, Corporate Breeze, Piano Horizon, Ambient Strings) ready to activate in 1 tap.
   - Instant P2P Sync: Trimmed tunes are compressed into Base64 and synced directly to Firestore, so callers retrieve and play the audio in <100ms.

5. **🛡️ Comprehensive Bug Audit & Reliability Hardening**:
   - Fixed startup profile synchronization so saved caller tunes are never overwritten.
   - Hardened `CallSoundEffectsManager` to prevent audio lingering after call ends.
   - Guarded WebRTC engine against duplicate tune restarts when both caller and callee resolve profile data.
   - Verified 100% 200 OK stream availability for all curated tunes.
