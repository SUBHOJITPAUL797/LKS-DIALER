# LKS Dialer - Bug Audit & Remediation Tracker

This document tracks all identified bugs and stability enhancements across the LKS Dialer codebase, their root causes, severity, affected files, and verification criteria.

---

## 📋 Bug Summary Table

| ID | Bug Description | Severity | File(s) | Status |
|---|---|---|---|---|
| **BUG-01** | Outgoing VoIP calls never reported to Android Telecom (`telecomManager.placeCall` missing) | 🔴 CRITICAL | `services/LksTelecomManager.kt` | ✅ Fixed |
| **BUG-02** | Double ringtone playback on lockscreen (Notification Channel sound + `LksIncomingRingtonePlayer` conflict) | 🔴 CRITICAL | `services/CallMessagingService.kt` | ✅ Fixed |
| **BUG-03** | Callee ignores SDP renegotiation for mid-call video upgrade and ICE restarts | 🟠 HIGH | `webrtc/WebRtcEngine.kt` | ✅ Fixed |
| **BUG-04** | Proximity sensor 10-minute timeout drops screen blackout during long calls & missing on dialing | 🟠 HIGH | `ui/screens/call/CallScreens.kt` | ✅ Fixed |
| **BUG-05** | AudioFocus listener empty & native cellular phone call interruption handling | 🟡 MEDIUM | `webrtc/WebRtcEngine.kt` | ✅ Fixed |
| **BUG-06** | Samsung Voice Focus `AudioEffect` instance garbage-collected and leaked in native audio HAL | 🟡 MEDIUM | `webrtc/WebRtcEngine.kt` | ✅ Fixed |
| **BUG-07** | `LksIncomingRingtonePlayer.silence()` does not reset `isRinging`, risking monitor restart | 🟡 MEDIUM | `util/LksIncomingRingtonePlayer.kt` | ✅ Fixed |
| **BUG-08** | Fresh install / login spams user with all historical missed call notifications | 🟡 MEDIUM | `data/repository/FirebaseManager.kt` | ✅ Fixed |
| **BUG-09** | Screen stay-awake flag (`FLAG_KEEP_SCREEN_ON`) not cleared when call is DECLINED | 🟡 MEDIUM | `MainActivity.kt` | ✅ Fixed |
| **BUG-10** | `clearCallLogs()` crashes if total logs exceed 500 documents (Firestore batch limit) | 🟡 MEDIUM | `data/repository/FirebaseManager.kt` | ✅ Fixed |
| **BUG-11** | Professional Call Hold: Auto-hold on cellular call pickup + sync "On Hold" status to other party | 🟠 HIGH | `data/model/Models.kt`, `webrtc/WebRtcEngine.kt`, `ui/screens/call/CallScreens.kt` | ✅ Fixed |
| **BUG-12** | AudioFocusChangeListener dropped on audio route switch, disabling cellular auto-hold | 🟠 HIGH | `webrtc/WebRtcEngine.kt` | ✅ Fixed |
| **BUG-13** | PiP called in `onStop()` crashes / conflicts with floating call bubble on video call backgrounding | 🟠 HIGH | `MainActivity.kt` | ✅ Fixed |
| **BUG-14** | System back gesture closes `MainActivity` during active call instead of sending task to back | 🟡 MEDIUM | `MainActivity.kt` | ✅ Fixed |
| **BUG-15** | `endCallInternalLocal` immediately wipes `activeCall`, dropping "Call Ended" UI feedback | 🟡 MEDIUM | `webrtc/WebRtcEngine.kt` | ✅ Fixed |
| **BUG-16** | Video call screen lacks call hold status, manual hold control, and earpiece proximity sensor | 🟠 HIGH | `ui/screens/call/CallScreens.kt` | ✅ Fixed |
| **BUG-17** | Telecom connection lacks `onHold()` / `onUnhold()` overrides for Bluetooth headsets & car kits | 🟡 MEDIUM | `services/LksConnectionService.kt` | ✅ Fixed |
| **BUG-18** | `lookupUserByNumber` fails on formatted phone numbers (dashes, parentheses) | 🟡 MEDIUM | `data/repository/FirebaseManager.kt` | ✅ Fixed |
| **BUG-19** | `SongTrimmerDialog` `DisposableEffect(Unit)` captures null `previewPlayer`, leaking MediaPlayer | 🟡 MEDIUM | `ui/components/SongTrimmerDialog.kt` | ✅ Fixed |
| **BUG-20** | Loudspeaker reset to Earpiece when outgoing call connects (Telecom active transition override) | 🔴 CRITICAL | `services/LksConnectionService.kt`, `webrtc/WebRtcEngine.kt` | ✅ Fixed |
| **BUG-21** | Remote party not receiving Call Hold status (Firestore JavaBean mapping & phone format mismatch) | 🔴 CRITICAL | `data/model/Models.kt`, `webrtc/WebRtcEngine.kt` | ✅ Fixed |
| **BUG-22** | Switching from Bluetooth to Speaker/Earpiece on Samsung fails (Telecom echo & audio policy override) | 🔴 CRITICAL | `webrtc/WebRtcEngine.kt` | ✅ Fixed |

---

## 🔍 Detailed Bug Reports

### BUG-01: Outgoing Calls Missing Android Telecom Registration
- **Severity**: 🔴 Critical
- **Affected File**: `app/src/main/java/com/example/services/LksTelecomManager.kt`
- **Root Cause**: `reportOutgoingCall()` only called `registerPhoneAccount(context)` and logged a message. It never called `telecomManager.placeCall(uri, extras)`.
- **Impact**:
  - `LksConnectionService.activeConnection` remained `null` for outgoing calls.
  - Caller could not control audio routing (Speakerphone/Earpiece) via Telecom.
  - Bluetooth car kits and wireless earbuds did not receive call state; hardware answer/hangup buttons failed on outgoing calls.
- **Fix**: Implement `telecomManager.placeCall(uri, extras)` with `PhoneAccountHandle` and `EXTRA_OUTGOING_CALL_EXTRAS`.

---

### BUG-02: Double Ringtone Playback on Lockscreen
- **Severity**: 🔴 Critical
- **Affected File**: `app/src/main/java/com/example/services/CallMessagingService.kt`
- **Root Cause**: When phone is locked, notification channel `lks_incoming_call_ringing_channel_v3` was configured with `setSound(ringtoneUri)` AND `LksIncomingRingtonePlayer.start()` was called simultaneously.
- **Impact**: Two media players played the ringtone simultaneously on `STREAM_RING`, causing audio stutter, volume ducking, and echo.
- **Fix**: Configure notification channel with `setSound(null, null)` and `enableVibration(false)` while maintaining `IMPORTANCE_HIGH` for lockscreen display, leaving `LksIncomingRingtonePlayer` as the sole, reliable audio/vibration manager.

---

### BUG-03: Callee Ignores SDP Renegotiation for Video Upgrades & ICE Restarts
- **Severity**: 🟠 High
- **Affected File**: `app/src/main/java/com/example/webrtc/WebRtcEngine.kt`
- **Root Cause**: In `processOfferSdpIfAvailable()`, execution returned immediately if `hasProcessedOffer == true` or `pc.remoteDescription != null`.
- **Impact**: When upgrading from audio to video mid-call or when the caller restarted ICE, the callee rejected the new `offerSdp`, leaving video upgrade stuck at "Connecting video...".
- **Fix**: Allow renegotiated offers when `offerSdp != pc.remoteDescription?.description`, set the new remote description, generate a new answer, and update `answerSdp` in Firestore.

---

### BUG-04: Proximity Sensor 10-Minute Timeout & Missing on Dialing
- **Severity**: 🟠 High
- **Affected File**: `app/src/main/java/com/example/ui/screens/call/CallScreens.kt`
- **Root Cause**: `wakeLock.acquire(10 * 60 * 1000L)` had a hardcoded 10-minute timeout, and only checked `CallStatus.ANSWERED`.
- **Impact**: Calls longer than 10 minutes lost proximity blackout (screen lit up against face, triggering accidental touches). Dialing outgoing calls with phone to ear didn't black out until answer.
- **Fix**: Remove 10-minute limit, use `wakeLock.setReferenceCounted(false)`, allow proximity during `CALLING` and `RINGING` when on Earpiece, and release cleanly via `onDispose`.

---

### BUG-05 & BUG-11: Professional Call Hold & Cellular Interruption Management
- **Severity**: 🟠 High
- **Affected Files**: `app/src/main/java/com/example/data/model/Models.kt`, `app/src/main/java/com/example/webrtc/WebRtcEngine.kt`, `app/src/main/java/com/example/ui/screens/call/CallScreens.kt`
- **Requirement**: When user receives or picks up a native cellular call during an LKS call, the VoIP call must immediately silence mic, mute audio, and transition to "On Hold". The remote caller must see "Call on Hold" status in real-time. When cellular call concludes, LKS call resumes smoothly. Also adds explicit manual "Hold / Resume" button in call UI.
- **Fix**:
  1. Add `isOnHold: Boolean = false` and `heldBy: String = ""` to `CallDto` and `WebRtcState`.
  2. Implement `OnAudioFocusChangeListener`: on `AUDIOFOCUS_LOSS_TRANSIENT` (cellular call or third-party VoIP), automatically set `putOnHold(true)`. On `AUDIOFOCUS_GAIN`, automatically set `putOnHold(false)`.
  3. Mute local audio track and suppress remote audio during hold.
  4. Show clear "Call On Hold" banner to both local and remote users.

---

### BUG-06: Samsung Voice Focus `AudioEffect` Resource Leak
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/webrtc/WebRtcEngine.kt`
- **Root Cause**: `applySamsungVoiceFocusIfAvailable()` instantiated an `AudioEffect` as a local variable without retaining a reference or calling `release()`.
- **Impact**: Premature garbage collection and native audio HAL resource leak.
- **Fix**: Hold `samsungVoiceFocusEffect` reference in engine and call `release()` in `endCallInternalLocal`.

---

### BUG-07: `silence()` Does Not Reset `isRinging`
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/util/LksIncomingRingtonePlayer.kt`
- **Root Cause**: `silence()` stopped playback but didn't set `isRinging = false`.
- **Impact**: `loopMonitorRunnable` could potentially restart playback if a tick executed after silencing.
- **Fix**: Explicitly set `isRinging = false` in `silence()`.

---

### BUG-08: Historical Missed Call Notification Spam on First Login
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/data/repository/FirebaseManager.kt`
- **Root Cause**: `lastSeenMissedCallAt` defaulted to `0L`.
- **Impact**: On a new device or re-install, up to 100 past missed calls from history triggered notifications simultaneously.
- **Fix**: Initialize `lastSeenMissedCallAt = System.currentTimeMillis()` if `0L`.

---

### BUG-09: Screen Stay-Awake Flag Not Cleared on Call DECLINED
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/MainActivity.kt`
- **Root Cause**: `FLAG_KEEP_SCREEN_ON` logic omitted `CallStatus.DECLINED` in the terminal state check.
- **Impact**: Screen stayed awake indefinitely after a call was declined, draining battery.
- **Fix**: Ensure `clearFlags(FLAG_KEEP_SCREEN_ON)` runs when call status is `DECLINED`.

---

### BUG-10: `clearCallLogs()` Batch Exceeds 500 Limit
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/data/repository/FirebaseManager.kt`
- **Root Cause**: Single `db.batch()` used for all documents without chunking.
- **Impact**: Throws `IllegalArgumentException` if user has > 500 call logs.
- **Fix**: Chunk deletion operations into batches of 450.

---

### BUG-12: AudioFocus Listener Dropped on Dynamic Audio Route Switching
- **Severity**: 🟠 High
- **Affected File**: `app/src/main/java/com/example/webrtc/WebRtcEngine.kt`
- **Root Cause**: `selectAudioDevice()` created a new `AudioFocusRequest` without attaching `setOnAudioFocusChangeListener`.
- **Impact**: Switching from Earpiece to Speakerphone or Bluetooth caused subsequent incoming cellular calls to not trigger auto-hold, causing audio overlap and broken communication.
- **Fix**: Elevate `audioFocusChangeListener` to a class property and attach it to every `AudioFocusRequest` builder invocation.

---

### BUG-13: PiP Initiation in `onStop()` & Conflict with Floating Call Pill
- **Severity**: 🟠 High
- **Affected File**: `app/src/main/java/com/example/MainActivity.kt`
- **Root Cause**: Calling `enterPictureInPictureMode()` in `onStop()` throws `IllegalStateException` on Android because the Activity has already stopped. Furthermore, returning to home screen during video call triggered BOTH Android PiP and the floating overlay pill.
- **Fix**: Move `enterPictureInPictureMode()` to `onUserLeaveHint()`, and guard `triggerFloatingCallBubbleIfActive()` to check `isInPictureInPictureMode`.

---

### BUG-14: Back Gesture Kills Activity During Active Call
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/MainActivity.kt`
- **Root Cause**: No `BackHandler` was registered in the calling screen overlay. System back navigation would pop or finish `MainActivity`.
- **Fix**: Add Compose `BackHandler { (context as? Activity)?.moveTaskToBack(true) }` to keep the call alive in background.

---

### BUG-15: Call End Transition Discards `activeCall` Metadata Prematurely
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/webrtc/WebRtcEngine.kt`
- **Root Cause**: `endCallInternalLocal()` assigned `_state.value = WebRtcState(callStatus = status)`, setting `activeCall = null` immediately.
- **Fix**: Retain `activeCall = prevCall` during the 1.5s post-call delay and set appropriate `connectionStatusText` so the caller sees "Call Ended" / "Call Declined" before returning to the dialer.

---

### BUG-16: Video Call Screen Missing Call Hold Indicators & Proximity Sensor
- **Severity**: 🟠 High
- **Affected File**: `app/src/main/java/com/example/ui/screens/call/CallScreens.kt`
- **Root Cause**: `ActiveVideoCallScreen` lacked Hold/Resume button, Hold animated banner, and proximity sensor management when routed to Earpiece.
- **Fix**: Add animated Hold banner, Hold/Resume button, and proximity sensor `DisposableEffect` for earpiece routing.

---

### BUG-17: Android Telecom Connection Missing Hold / Unhold Sync
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/services/LksConnectionService.kt`
- **Root Cause**: `LksCallConnection` did not override `onHold()` and `onUnhold()`.
- **Fix**: Implement `onHold()` and `onUnhold()` in `LksCallConnection` delegating to `WebRtcEngine.putCallOnHold()`, and add `LksConnectionService.setCallOnHold(onHold)`.

---

### BUG-18: Phone Number Lookup Fails on Formatted Contact Numbers
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/data/repository/FirebaseManager.kt`
- **Root Cause**: `lookupUserByNumber()` only stripped whitespace with `replace(" ", "")`, failing on numbers formatted with dashes or parentheses (e.g. `+91-98765-43210`, `(555) 123-4567`).
- **Fix**: Use `ContactsHelper.normalizePhoneNumber(phoneNumber)` and `ContactsHelper.numbersMatch(it.phoneNumber, phoneNumber)` for robust matching.

---

### BUG-19: `SongTrimmerDialog` Leaks MediaPlayer on Dialog Dismiss
- **Severity**: 🟡 Medium
- **Affected File**: `app/src/main/java/com/example/ui/components/SongTrimmerDialog.kt`
- **Root Cause**: `DisposableEffect(Unit)` captured initial `previewPlayer` (null), so when the dialog closed, `previewPlayer?.release()` did not execute.
- **Fix**: Key effect to `DisposableEffect(previewPlayer)` and release player in `onDismissRequest`.

---

### BUG-20: Loudspeaker Automatically Reset to Earpiece on Outgoing Call Connect
- **Severity**: 🔴 Critical
- **Affected Files**: `app/src/main/java/com/example/services/LksConnectionService.kt`, `app/src/main/java/com/example/webrtc/WebRtcEngine.kt`
- **Root Cause**: When the caller toggled Speakerphone while the call was DIALING, the audio route was set. But when the callee answered, Telecom transitioned the connection to `STATE_ACTIVE`. This system transition triggered `onCallAudioStateChanged` with system default `ROUTE_EARPIECE`, snapping the audio back to Earpiece.
- **Fix**: 
  1. Add explicit guard in `WebRtcEngine.onTelecomAudioRouteChanged()` to prevent Telecom from reverting `SPEAKERPHONE` to `EARPIECE`.
  2. Increase user selection cooldown to 3500ms.
  3. Re-assert user's selected audio route in `WebRtcEngine.listenToActiveCall()` when outgoing call becomes `ANSWERED`.
  4. Remove stale route guard in `LksConnectionService.setAudioRoute()`.

---

### BUG-21: Remote Party Not Receiving Call Hold Status
- **Severity**: 🔴 Critical
- **Affected Files**: `app/src/main/java/com/example/data/model/Models.kt`, `app/src/main/java/com/example/webrtc/WebRtcEngine.kt`
- **Root Cause**:
  1. Firestore Java SDK uses JavaBean naming introspection. Without `@get:PropertyName("isOnHold") @set:PropertyName("isOnHold")`, `val isOnHold: Boolean` is serialized as `onHold` and fails to deserialize onto `isOnHold`. The remote party's snapshot listener evaluated `false != false` and failed to trigger the hold state change.
  2. `call.heldBy == myPhoneNumber` failed when phone number formatting differed (e.g. `+91` prefix vs national format).
  3. `myPhoneNumber` was not initialized during outgoing calls in `initiateCall()`.
- **Fix**:
  1. Annotate `isOnHold` and `heldBy` in `CallDto` with `@get:PropertyName` and `@set:PropertyName`.
  2. Read hold state directly from snapshot (`snapshot.getBoolean("isOnHold") == true || snapshot.getBoolean("onHold") == true || call.isOnHold`).
  3. Match phone numbers using `ContactsHelper.numbersMatch(heldBy, myPhone)`.
  4. Ensure `myPhoneNumber = callerNumber` is set in `initiateCall()`, and `putCallOnHold()` falls back to `FirebaseManager.currentUser`.

---

### BUG-22: Audio Route Locked to Bluetooth on Samsung Devices
- **Severity**: 🔴 Critical
- **Affected File**: `app/src/main/java/com/example/webrtc/WebRtcEngine.kt`
- **Root Cause**:
  1. When taking a call on a connected Bluetooth headset, Telecom route is `ROUTE_BLUETOOTH`. When user tapped Speaker or Earpiece, Telecom dispatched `onCallAudioStateChanged` with `ROUTE_BLUETOOTH`. Because `onTelecomAudioRouteChanged` only guarded against `ROUTE_EARPIECE`, Telecom's event for `BLUETOOTH` was unguarded and immediately snapped audio back to Bluetooth.
  2. On Samsung One UI (Android 12+), calling `am.clearCommunicationDevice()` immediately prior to `am.setCommunicationDevice(speaker)` caused Samsung's audio policy to revert back to the connected Bluetooth device during the switch.
- **Fix**:
  1. Add strict 5000ms cooldown in `onTelecomAudioRouteChanged()` against any Telecom transition echo that contradicts explicit user selection.
  2. Add permanent guards: when user explicitly chooses `SPEAKERPHONE` or `EARPIECE`, Telecom is never permitted to force audio back to `BLUETOOTH`.
  3. Disconnect Bluetooth SCO cleanly (`if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() }`) and call `setCommunicationDevice` directly using resolved `rawDevice` before falling back to `clearCommunicationDevice()`.


