### 🚀 What's New in v2.9.24

- **🎵 Caller Tune (Hello Tune) with YouTube Music Library**:
  - Set any song as your personal Caller Tune so callers hear your chosen track while waiting for you to answer!
  - Integrated **YouTube Music Search**: Search across millions of songs and artists with live video thumbnails, channel names, and durations with zero API key requirement.
  - **Visual 30-Second Audio Portion Trimmer**:
    - High-precision visual timeline with draggable dual handles (Start in Green, End in Red).
    - Quick Chorus Presets: 1-tap jump to *Intro (0:00–0:30)*, *Chorus (0:30–1:00)*, or *Hook (1:00–1:30)*.
    - Live audio section preview before applying.
  - **Instant Outgoing Ringing Playback**:
    - Callers automatically hear your Caller Tune played over the voice call audio stream (`STREAM_VOICE_CALL`) on earpiece, speaker, or Bluetooth headset.
    - Outgoing call UI badge displays `🎵 Playing Caller Tune: [Song Title]`.
    - Automatically stops playback within 50ms as soon as the call is answered or declined.
    - Graceful fallback: If no tune is set, standard supervisory ringback tone (`tuuut... tuuut...`) plays normally.
  - **Curated Trending Tunes & Device Audio**:
    - Curated offline-ready presets across genres (Acoustic Guitar, Lo-Fi Chill, Corporate Breeze, Classical Piano, Romantic Strings).
    - Custom MP3/audio file support from local phone storage with built-in instant frame slicer.
