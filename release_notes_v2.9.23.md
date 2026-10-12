### 🚀 What's New in v2.9.23

- **🎙️ Dual-Party Call Recording (Both Voices Captured in Studio Clarity)**:
  - Fixed the issue where only the recording user's microphone audio was captured in the call recording.
  - Built `RecordingAudioTrackWrapper` directly hooking WebRTC's `WebRtcAudioTrack` playout pipeline to tap remote party's audio before it reaches the speaker/earpiece.
  - Implemented real-time synchronized 16-bit PCM audio mixer (`AudioMixerBuffer`) with soft-limiting clipping protection.
  - Automatic sample-rate normalization (e.g., 48 kHz / 44.1 kHz to 16 kHz HD Voice Wideband) with integer decimation and linear interpolation.
  - Encodes both local and remote speech into high-fidelity streamable AAC-LC (`.m4a`) with ADTS framing at 64 kbps.
  - Works seamlessly across all Android devices, Wi-Fi, cellular networks, speakerphone, earpiece, and Bluetooth headsets without echo or clock drift.

- **✨ Clean, Sleek Call Screen UI**:
  - Removed the bulky rounded circular avatar badge showing the initial letter of the contact's name from all call screens (`OutgoingCallScreen`, `IncomingCallOverlay`, and `ActiveAudioCallScreen`).
  - Sleek, modern call screen emphasizing the contact name, phone number, duration timer, signal quality, and bottom controls with beautiful breathing room.
  - In video calls, replaced the placeholder letter box with an elegant `VideocamOff` camera icon when remote video is disabled or paused.
  - Updated web calling interface to cleanly show contact info without awkward initial letter circle fallback when no avatar is configured.
