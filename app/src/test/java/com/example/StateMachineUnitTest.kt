package com.example

import com.example.data.model.GroupJoinState
import com.example.data.model.GroupParticipantRole
import com.example.data.model.GroupRoomStatus
import com.example.data.model.QrSessionStatus
import com.example.data.p2p.P2pReceiverStage
import com.example.data.p2p.P2pSenderStage
import com.example.ui.screens.chat.ScheduledChatMessage
import com.example.ui.screens.chat.ScheduledMessageManager
import com.example.ui.screens.chat.ScheduledMessageStatus
import com.example.ui.screens.chat.ScheduledTransitionEvent
import com.example.util.CallerTuneInfo
import com.example.util.CallerTuneSource
import com.example.webrtc.CallHoldPhase
import com.example.webrtc.VideoUpgradePhase
import com.example.webrtc.WebRtcState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineUnitTest {

    @Test
    fun wire_enums_map_known_and_unknown_values_safely() {
        assertEquals(GroupRoomStatus.ACTIVE, GroupRoomStatus.fromWire("ACTIVE"))
        assertEquals(GroupRoomStatus.ENDED, GroupRoomStatus.fromWire("ended"))
        assertEquals(GroupRoomStatus.ACTIVE, GroupRoomStatus.fromWire(null))

        assertEquals(GroupParticipantRole.HOST, GroupParticipantRole.fromWire("HOST"))
        assertEquals(GroupParticipantRole.CO_HOST, GroupParticipantRole.fromWire("co_host"))
        assertEquals(GroupParticipantRole.PARTICIPANT, GroupParticipantRole.fromWire("unknown"))

        assertEquals(GroupJoinState.WAITING_LOBBY, GroupJoinState.fromWire("WAITING_LOBBY"))
        assertEquals(GroupJoinState.JOINED, GroupJoinState.fromWire("joined"))
        assertEquals(GroupJoinState.INVITED, GroupJoinState.fromWire(""))

        assertEquals(QrSessionStatus.SCANNED, QrSessionStatus.fromWire("SCANNED"))
        assertEquals(QrSessionStatus.APPROVED, QrSessionStatus.fromWire("approved"))
        assertEquals(QrSessionStatus.PENDING, QrSessionStatus.fromWire("bad_status"))
    }

    @Test
    fun scheduled_message_reducer_follows_deterministic_lifecycle() {
        val scheduledAt = 1_700_000_000_000L
        val initial = ScheduledChatMessage(
            id = "sched-1",
            recipientNumber = "+919876543210",
            recipientName = "Alex",
            payloadText = "Hello later",
            previewText = "Hello later",
            scheduledAtMillis = scheduledAt,
            createdAtMillis = scheduledAt - 60_000L
        )
        assertEquals(ScheduledMessageStatus.PENDING, initial.status)
        assertTrue(initial.status.isActiveInQueue)
        assertFalse(initial.status.isTerminal)

        val sending = ScheduledMessageManager.reduceTransition(
            initial,
            ScheduledTransitionEvent.BeginDispatch
        )
        assertEquals(ScheduledMessageStatus.SENDING, sending.status)
        assertNull(sending.lastError)

        val retryNow = 1_700_000_005_000L
        val retrying = ScheduledMessageManager.reduceTransition(
            sending,
            ScheduledTransitionEvent.DispatchFailed(
                error = "Offline",
                nowMs = retryNow,
                fixedBackoffMs = 5_000L
            )
        )
        assertEquals(ScheduledMessageStatus.RETRYING, retrying.status)
        assertEquals(1, retrying.retryCount)
        assertEquals("Offline", retrying.lastError)
        // Retrying preserves original scheduledAtMillis timeline position while advancing nextAttemptAtMillis
        assertEquals(scheduledAt, retrying.scheduledAtMillis)
        assertEquals(retryNow + 5_000L, retrying.nextAttemptAtMillis)

        val resending = ScheduledMessageManager.reduceTransition(
            retrying,
            ScheduledTransitionEvent.BeginDispatch
        )
        assertEquals(ScheduledMessageStatus.SENDING, resending.status)

        val sent = ScheduledMessageManager.reduceTransition(
            resending,
            ScheduledTransitionEvent.DispatchSucceeded
        )
        assertEquals(ScheduledMessageStatus.SENT, sent.status)
        assertTrue(sent.status.isTerminal)
        assertFalse(sent.status.isActiveInQueue)

        // Terminal states must never re-enter RETRYING or CANCELLED or SENDING
        val afterTerminalAttempt = ScheduledMessageManager.reduceTransition(
            sent,
            ScheduledTransitionEvent.DispatchFailed("Late timeout", nowMs = retryNow + 20_000L)
        )
        assertEquals(ScheduledMessageStatus.SENT, afterTerminalAttempt.status)
        assertEquals(sent, afterTerminalAttempt)
    }

    @Test
    fun scheduled_message_reschedule_and_cancel_transitions() {
        val base = ScheduledChatMessage(
            id = "sched-2",
            recipientNumber = "+919876543210",
            recipientName = "Sam",
            payloadText = "Old",
            previewText = "Old",
            scheduledAtMillis = 100_000L,
            createdAtMillis = 90_000L,
            retryCount = 2,
            lastError = "Network down",
            status = ScheduledMessageStatus.RETRYING
        )

        val rescheduled = ScheduledMessageManager.reduceTransition(
            base,
            ScheduledTransitionEvent.Reschedule(
                newScheduledAtMillis = 250_000L,
                payloadText = "Updated",
                previewText = "Updated"
            )
        )
        assertEquals(ScheduledMessageStatus.PENDING, rescheduled.status)
        assertEquals(250_000L, rescheduled.scheduledAtMillis)
        assertEquals(250_000L, rescheduled.nextAttemptAtMillis)
        assertEquals(0, rescheduled.retryCount)
        assertNull(rescheduled.lastError)

        val cancelled = ScheduledMessageManager.reduceTransition(
            rescheduled,
            ScheduledTransitionEvent.Cancel
        )
        assertEquals(ScheduledMessageStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.status.isTerminal)

        val ignoredBegin = ScheduledMessageManager.reduceTransition(
            cancelled,
            ScheduledTransitionEvent.BeginDispatch
        )
        assertEquals(ScheduledMessageStatus.CANCELLED, ignoredBegin.status)
    }

    @Test
    fun webrtc_state_derives_hold_and_video_upgrade_compatibility_flags() {
        val active = WebRtcState()
        assertFalse(active.isOnHold)
        assertFalse(active.isHeldLocally)
        assertFalse(active.isVideoUpgradeRequested)
        assertFalse(active.didIRequestVideoUpgrade)

        val localHold = active.copy(holdPhase = CallHoldPhase.LOCAL_HOLD)
        assertTrue(localHold.isOnHold)
        assertTrue(localHold.isHeldLocally)

        val cellularHold = active.copy(holdPhase = CallHoldPhase.CELLULAR_HOLD)
        assertTrue(cellularHold.isOnHold)
        assertTrue(cellularHold.isHeldLocally)

        val remoteHold = active.copy(holdPhase = CallHoldPhase.REMOTE_HOLD)
        assertTrue(remoteHold.isOnHold)
        assertFalse(remoteHold.isHeldLocally)

        val outgoingUpgrade = active.copy(videoUpgradePhase = VideoUpgradePhase.OUTGOING_REQUESTED)
        assertTrue(outgoingUpgrade.didIRequestVideoUpgrade)
        assertFalse(outgoingUpgrade.isVideoUpgradeRequested)

        val incomingUpgrade = active.copy(videoUpgradePhase = VideoUpgradePhase.INCOMING_REQUESTED)
        assertTrue(incomingUpgrade.isVideoUpgradeRequested)
        assertFalse(incomingUpgrade.didIRequestVideoUpgrade)
    }

    @Test
    fun caller_tune_source_maps_wire_values_and_checks_configuration() {
        val info = CallerTuneInfo(
            title = "Morning Chime",
            audioUrl = "https://example.com/tune.mp3",
            source = "preset"
        )
        assertEquals(CallerTuneSource.PRESET, info.sourceEnum)
        assertTrue(info.isConfigured)

        assertEquals(CallerTuneSource.DEFAULT, CallerTuneSource.fromWire("unknown_source"))
        assertEquals(CallerTuneSource.MY_SAVED_TUNE, CallerTuneSource.fromWire("MY_SAVED_TUNE"))
    }

    @Test
    fun p2p_sender_and_receiver_stages_cover_complete_lifecycle() {
        assertEquals(8, P2pSenderStage.entries.size)
        assertEquals(8, P2pReceiverStage.entries.size)
        assertFalse(P2pSenderStage.STREAMING.isTerminal)
        assertTrue(P2pSenderStage.COMPLETED.isTerminal)
        assertTrue(P2pSenderStage.CANCELLED.isTerminal)
        assertTrue(P2pSenderStage.FAILED.isTerminal)
        assertFalse(P2pReceiverStage.RECEIVING.isTerminal)
        assertTrue(P2pReceiverStage.FINALIZING.isTerminal)
        assertTrue(P2pReceiverStage.COMPLETED.isTerminal)
        assertEquals(ScheduledMessageStatus.SENT, ScheduledMessageStatus.fromWire("sent"))
    }

    @Test
    fun outgoing_audio_phase_derives_mutually_exclusive_flags() {
        assertFalse(com.example.util.OutgoingAudioPhase.IDLE.isConnecting)
        assertFalse(com.example.util.OutgoingAudioPhase.IDLE.isRinging)

        assertTrue(com.example.util.OutgoingAudioPhase.CONNECTING.isConnecting)
        assertFalse(com.example.util.OutgoingAudioPhase.CONNECTING.isRinging)

        assertTrue(com.example.util.OutgoingAudioPhase.RINGING_BUFFERING.isRinging)
        assertFalse(com.example.util.OutgoingAudioPhase.RINGING_BUFFERING.isRingbackActive)

        assertTrue(com.example.util.OutgoingAudioPhase.RINGING_CALLER_TUNE.isRinging)
        assertTrue(com.example.util.OutgoingAudioPhase.RINGING_CALLER_TUNE.isRingbackActive)

        assertTrue(com.example.util.OutgoingAudioPhase.RINGING_RINGBACK.isRinging)
        assertTrue(com.example.util.OutgoingAudioPhase.RINGING_RINGBACK.isRingbackActive)

        assertFalse(com.example.util.OutgoingAudioPhase.ANSWERED.isConnecting)
        assertFalse(com.example.util.OutgoingAudioPhase.ANSWERED.isRinging)
        assertFalse(com.example.util.OutgoingAudioPhase.ANSWERED.isRingbackActive)
    }
}
