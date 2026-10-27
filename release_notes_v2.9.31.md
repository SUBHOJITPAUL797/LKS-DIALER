## v2.9.31 – Fast RINGING Status & Instant Call Termination

### 🚀 What's New & Fixed
- **Instant RINGING Propagation**:
  - Eliminated the long 10–30s delay before showing "Ringing..." on the caller screen.
  - Implemented 1.2s optimistic ringing fallback on both Android and Web, providing immediate feedback without waiting for multiple FCM / network roundtrips.
  - Callee updates real-time caller tune details seamlessly upon pickup.

- **Instant Call Cut / Termination Propagation**:
  - Fixed an issue where cutting a call on Phone 1 left Phone 2 still ringing.
  - Corrected `missed_call` push notification mapping in `CallMessagingService` so that cancelling an unanswered call forces Phone 2 to immediately stop ringing and clear the incoming call UI.
  - Attached real-time `calls/{callId}` snapshot listeners immediately upon call attach without waiting for `.get()` roundtrips.
  - Added duplicate listener prevention (`currentListeningCallId`) to avoid listener teardown races.
  - Suppressed zombie incoming call alerts for calls that were already terminated before push delivery.
  - Fixed Web client handling for `REMOVED`, `ENDED`, `MISSED`, and `DECLINED` states to immediately silence ringtones and dismiss modals without lingering screens.
