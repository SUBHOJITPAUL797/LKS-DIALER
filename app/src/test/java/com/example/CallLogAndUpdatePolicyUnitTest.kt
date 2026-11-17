package com.example

import com.example.data.model.CallDirection
import com.example.data.model.CallDto
import com.example.data.model.CallIdentitySanitizer
import com.example.data.model.CallLogDto
import com.example.data.model.CallStatus
import com.example.data.model.CallType
import com.example.util.UpdatePromptPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying caller identity preservation, Recents call log resolution, and non-intrusive update policy.
 */
class CallLogAndUpdatePolicyUnitTest {

    @Test
    fun `CallIdentitySanitizer rejects placeholder numbers and names`() {
        assertTrue(CallIdentitySanitizer.isPlaceholderNumber(null))
        assertTrue(CallIdentitySanitizer.isPlaceholderNumber(""))
        assertTrue(CallIdentitySanitizer.isPlaceholderNumber("Unknown"))
        assertTrue(CallIdentitySanitizer.isPlaceholderNumber("Unknown Caller"))
        assertTrue(CallIdentitySanitizer.isPlaceholderNumber("LKS User"))
        assertFalse(CallIdentitySanitizer.isPlaceholderNumber("+919476288066"))

        assertTrue(CallIdentitySanitizer.isPlaceholderName(null))
        assertTrue(CallIdentitySanitizer.isPlaceholderName(""))
        assertTrue(CallIdentitySanitizer.isPlaceholderName("Unknown"))
        assertTrue(CallIdentitySanitizer.isPlaceholderName("Unknown Caller"))
        assertTrue(CallIdentitySanitizer.isPlaceholderName("LKS Caller"))
        assertTrue(CallIdentitySanitizer.isPlaceholderName("Me"))
        assertFalse(CallIdentitySanitizer.isPlaceholderName("Riu"))
    }

    @Test
    fun `mergeCallPreservingIdentity preserves callerNumber and callerName when partial Firestore doc arrives`() {
        val existing = CallDto(
            callId = "1bcf9c40-c1fe-4c6b-a42f-c1700bcd9652",
            callerNumber = "+919476288066",
            callerName = "Riu",
            calleeNumber = "+918927408840",
            callType = CallType.AUDIO,
            status = CallStatus.RINGING,
            callerFcmToken = "fcm_token_caller",
            offerSdp = "v=0..."
        )
        val partialFromCalleeMerge = CallDto(
            callId = "1bcf9c40-c1fe-4c6b-a42f-c1700bcd9652",
            callerNumber = "",
            callerName = "Unknown Caller",
            calleeNumber = "",
            callType = CallType.AUDIO,
            status = CallStatus.RINGING,
            callerTuneTitle = "Vaaroon"
        )

        val merged = CallIdentitySanitizer.mergeCallPreservingIdentity(partialFromCalleeMerge, existing)
        assertEquals("+919476288066", merged.callerNumber)
        assertEquals("Riu", merged.callerName)
        assertEquals("+918927408840", merged.calleeNumber)
        assertEquals("fcm_token_caller", merged.callerFcmToken)
        assertEquals("v=0...", merged.offerSdp)
        assertEquals("Vaaroon", merged.callerTuneTitle)
    }

    @Test
    fun `CallLogDto resolves real phone number and name instead of Unknown placeholders`() {
        val log = CallLogDto(
            id = "log_1",
            callId = "+919876543210_1731600000000",
            callerNumber = "Unknown",
            calleeNumber = "",
            otherPartyName = "Unknown Caller",
            direction = CallDirection.INCOMING,
            callType = CallType.AUDIO,
            status = CallStatus.ANSWERED,
            durationSeconds = 1969
        )

        assertEquals("+919876543210", log.resolveOtherPartyNumber())
        assertEquals("+919876543210", log.resolveOtherPartyName())
    }

    @Test
    fun `UpdatePromptPolicy suppresses auto dialog during calls, chats, modals, recent touch, or snooze`() {
        // Allowed when idle on Main hub with no calls or modals
        assertTrue(
            UpdatePromptPolicy.shouldShowAutoUpdateDialog(
                hasPendingUpdate = true,
                isVersionSnoozed = false,
                isOneToOneCallActive = false,
                isGroupCallActive = false,
                hasIncomingGroupInvite = false,
                isOnMainHubRoute = true,
                hasActiveModalOrSheet = false,
                millisSinceLastUserInteraction = 5000L
            )
        )

        // Suppressed during 1-to-1 call
        assertFalse(
            UpdatePromptPolicy.shouldShowAutoUpdateDialog(
                hasPendingUpdate = true,
                isVersionSnoozed = false,
                isOneToOneCallActive = true,
                isGroupCallActive = false,
                hasIncomingGroupInvite = false,
                isOnMainHubRoute = true,
                hasActiveModalOrSheet = false,
                millisSinceLastUserInteraction = 5000L
            )
        )

        // Suppressed during group call or invite
        assertFalse(
            UpdatePromptPolicy.shouldShowAutoUpdateDialog(
                hasPendingUpdate = true,
                isVersionSnoozed = false,
                isOneToOneCallActive = false,
                isGroupCallActive = false,
                hasIncomingGroupInvite = true,
                isOnMainHubRoute = true,
                hasActiveModalOrSheet = false,
                millisSinceLastUserInteraction = 5000L
            )
        )

        // Suppressed when user is in ChatConversation or Settings (not on Main hub)
        assertFalse(
            UpdatePromptPolicy.shouldShowAutoUpdateDialog(
                hasPendingUpdate = true,
                isVersionSnoozed = false,
                isOneToOneCallActive = false,
                isGroupCallActive = false,
                hasIncomingGroupInvite = false,
                isOnMainHubRoute = false,
                hasActiveModalOrSheet = false,
                millisSinceLastUserInteraction = 5000L
            )
        )

        // Suppressed when user just touched the screen (<2.5s ago)
        assertFalse(
            UpdatePromptPolicy.shouldShowAutoUpdateDialog(
                hasPendingUpdate = true,
                isVersionSnoozed = false,
                isOneToOneCallActive = false,
                isGroupCallActive = false,
                hasIncomingGroupInvite = false,
                isOnMainHubRoute = true,
                hasActiveModalOrSheet = false,
                millisSinceLastUserInteraction = 800L
            )
        )

        // Suppressed when snoozed via 'Update Later'
        assertFalse(
            UpdatePromptPolicy.shouldShowAutoUpdateDialog(
                hasPendingUpdate = true,
                isVersionSnoozed = true,
                isOneToOneCallActive = false,
                isGroupCallActive = false,
                hasIncomingGroupInvite = false,
                isOnMainHubRoute = true,
                hasActiveModalOrSheet = false,
                millisSinceLastUserInteraction = 10000L
            )
        )
    }

    @Test
    fun `UpdatePromptPolicy suppresses update notifications during active calls and when snoozed`() {
        assertFalse(
            UpdatePromptPolicy.shouldPostUpdateNotification(
                force = true,
                isVersionSnoozed = false,
                isOneToOneCallActive = true,
                isGroupCallActive = false,
                hasIncomingGroupInvite = false
            )
        )
        assertFalse(
            UpdatePromptPolicy.shouldPostUpdateNotification(
                force = false,
                isVersionSnoozed = true,
                isOneToOneCallActive = false,
                isGroupCallActive = false,
                hasIncomingGroupInvite = false
            )
        )
        assertTrue(
            UpdatePromptPolicy.shouldPostUpdateNotification(
                force = false,
                isVersionSnoozed = false,
                isOneToOneCallActive = false,
                isGroupCallActive = false,
                hasIncomingGroupInvite = false
            )
        )
    }
}
