# LKS Dialer v2.9.45 — Release Notes

## 🐛 Bug Fixes

### Critical: Caller No Longer Stuck in RINGING After Callee Declines
- **Root Cause Fixed**: The server-side polling watchdog now correctly detects DECLINED/ENDED/MISSED
  status even after `hasProcessedAnswer=true`. Previously, polling stopped once the answer SDP
  was received, meaning a subsequent decline could be missed if the Firestore snapshot listener
  was slow to deliver.
- **FCM Race Fix**: `forceEndCallFromPush` now handles the edge case where `activeCall` is null
  but the engine is in CALLING/RINGING state — decline pushes are no longer silently ignored.
- **Hard 90s Watchdog**: An additional 90-second failsafe timer ensures the caller's UI always
  transitions out of RINGING/CALLING, even if all other mechanisms fail.

## 🔒 Security & Stability
- All three layers of call termination (Firestore snapshot, FCM push, polling watchdog) now
  correctly deliver ENDED/DECLINED to the caller in all scenarios.

**Build**: 203 | **Date**: 2026-10-31
