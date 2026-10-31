# LKS Dialer v2.9.44 Release Notes

### 🛡️ Global OEM Device Hardening & Crash Prevention

1. **Audio Device Registration & Receiver Protection**:
   - Guarded `AudioRouteManager` with `Context.RECEIVER_EXPORTED` on Android 13+ (API 33+) and wrapped in `try-catch (e: Throwable)` to prevent `SecurityException` on strict Android 14/15 OEM ROMs (Transsion, Xiaomi, Vivo).
   - Wrapped `AudioDeviceCallback` registration in `try-catch (e: Throwable)` to shield against OEM audio HAL driver faults.

2. **WebRTC Video Track Defensive Boundaries**:
   - Wrapped `createVideoTrack` and `Camera2Enumerator` queries in comprehensive `try-catch (e: Throwable)` blocks, ensuring camera hardware/permission errors on any OEM device never crash the dialer.

3. **Multi-Device Stability Ecosystem**:
   - Combined with v2.9.43's `WRITE_SETTINGS` fix, LKS Dialer now defensively shields all hardware, media, telephony, audio, and camera framework boundaries against OEM fragmentation worldwide.
