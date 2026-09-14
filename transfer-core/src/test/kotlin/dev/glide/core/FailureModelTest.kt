package dev.glide.core

import dev.glide.core.domain.FailureCategory
import dev.glide.core.domain.Sha256Digest
import dev.glide.core.domain.TransferFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureModelTest {

    @Test
    fun permissionDenied_isRecoverablePrerequisite() {
        val failure = TransferFailure.PermissionDenied("Camera/Nearby denied")
        assertTrue(failure.isRecoverable)
        assertEquals(FailureCategory.Prerequisite, failure.category)
        assertEquals("PERMISSION_DENIED", failure.code)
    }

    @Test
    fun connectionRejected_isNonRecoverableConsent() {
        val failure = TransferFailure.ConnectionRejected()
        assertFalse(failure.isRecoverable)
        assertEquals(FailureCategory.Consent, failure.category)
        assertEquals("CONNECTION_REJECTED", failure.code)
    }

    @Test
    fun authenticationMismatch_isNonRecoverableSecurity() {
        val failure = TransferFailure.AuthenticationMismatch()
        assertFalse(failure.isRecoverable)
        assertEquals(FailureCategory.Security, failure.category)
        assertEquals("AUTHENTICATION_FAILED", failure.code)
    }

    @Test
    fun integrityMismatch_isNonRecoverableIntegrity() {
        val expected = Sha256Digest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        val actual = Sha256Digest("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val failure = TransferFailure.IntegrityMismatch(expected, actual)
        assertFalse(failure.isRecoverable)
        assertEquals(FailureCategory.Integrity, failure.category)
        assertEquals("INTEGRITY_MISMATCH", failure.code)
    }

    @Test
    fun insufficientStorage_isRecoverableStorage() {
        val failure = TransferFailure.InsufficientStorage(requiredBytes = 5000L, availableBytes = 1000L)
        assertTrue(failure.isRecoverable)
        assertEquals(FailureCategory.Storage, failure.category)
        assertEquals("INSUFFICIENT_STORAGE", failure.code)
    }

    @Test
    fun userCancelled_isNonRecoverableUser() {
        val failure = TransferFailure.UserCancelled()
        assertFalse(failure.isRecoverable)
        assertEquals(FailureCategory.User, failure.category)
        assertEquals("USER_CANCELLED", failure.code)
    }

    @Test
    fun systemCancelled_isRecoverableSystem() {
        val failure = TransferFailure.SystemCancelled()
        assertTrue(failure.isRecoverable)
        assertEquals(FailureCategory.System, failure.category)
        assertEquals("SYSTEM_CANCELLED", failure.code)
    }

    @Test
    fun deliveryUnconfirmed_isRecoverableReceiptAmbiguity() {
        val failure = TransferFailure.DeliveryUnconfirmed()
        assertTrue(failure.isRecoverable)
        assertEquals(FailureCategory.ReceiptAmbiguity, failure.category)
        assertEquals("DELIVERY_UNCONFIRMED", failure.code)
    }
}
