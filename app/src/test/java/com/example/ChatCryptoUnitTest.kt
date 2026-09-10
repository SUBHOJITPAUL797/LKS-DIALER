package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.ChatCryptoManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.SecureRandom
import android.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatCryptoUnitTest {

    @Test
    fun test_crypto_manager_generates_public_key() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cryptoManager = ChatCryptoManager.getInstance(context)
        val publicKeyBase64 = cryptoManager.getMyPublicKeyBase64()

        assertNotNull(publicKeyBase64)
        assertTrue(publicKeyBase64.isNotBlank())
    }

    @Test
    fun test_encryption_decryption_round_trip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val aliceCrypto = ChatCryptoManager.getInstance(context)
        val alicePublicKey = aliceCrypto.getMyPublicKeyBase64()

        // Generate a simulated Bob EC keypair
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val bobKeyPair = keyGen.generateKeyPair()
        val bobPublicKeyBase64 = Base64.encodeToString(bobKeyPair.public.encoded, Base64.NO_WRAP)

        // Alice encrypts a confidential message for Bob
        val secretMessage = "Hello from LKS Dialer! This is an End-to-End Encrypted test message 🔒"
        val (ciphertext, iv) = aliceCrypto.encrypt(secretMessage, bobPublicKeyBase64)

        assertNotNull(ciphertext)
        assertNotNull(iv)
        assertNotEquals(secretMessage, ciphertext)

        // Bob computes shared secret and decrypts using AES-256-GCM
        val bobPriv = bobKeyPair.private
        val alicePubBytes = Base64.decode(alicePublicKey, Base64.NO_WRAP)
        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val alicePub = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(alicePubBytes))

        val keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        keyAgreement.init(bobPriv)
        keyAgreement.doPhase(alicePub, true)
        val sharedSecret = keyAgreement.generateSecret()

        val md = java.security.MessageDigest.getInstance("SHA-256")
        val derivedKey = javax.crypto.spec.SecretKeySpec(md.digest(sharedSecret), "AES")

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, derivedKey, javax.crypto.spec.GCMParameterSpec(128, ivBytes))

        val decryptedBytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        val decryptedMessage = String(decryptedBytes, Charsets.UTF_8)

        assertEquals(secretMessage, decryptedMessage)
    }

    @Test
    fun test_binary_encryption_decryption_for_media() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val aliceCrypto = ChatCryptoManager.getInstance(context)
        val alicePublicKey = aliceCrypto.getMyPublicKeyBase64()

        // Generate simulated peer
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val bobKeyPair = keyGen.generateKeyPair()
        val bobPublicKeyBase64 = Base64.encodeToString(bobKeyPair.public.encoded, Base64.NO_WRAP)

        // Simulated voice note audio bytes
        val dummyAudioBytes = ByteArray(256) { it.toByte() }
        val (ciphertext, iv) = aliceCrypto.encryptBytes(dummyAudioBytes, bobPublicKeyBase64)

        assertNotNull(ciphertext)
        assertNotNull(iv)

        // Bob decrypts
        val bobPriv = bobKeyPair.private
        val alicePubBytes = Base64.decode(alicePublicKey, Base64.NO_WRAP)
        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val alicePub = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(alicePubBytes))

        val keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        keyAgreement.init(bobPriv)
        keyAgreement.doPhase(alicePub, true)
        val sharedSecret = keyAgreement.generateSecret()

        val md = java.security.MessageDigest.getInstance("SHA-256")
        val derivedKey = javax.crypto.spec.SecretKeySpec(md.digest(sharedSecret), "AES")

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, derivedKey, javax.crypto.spec.GCMParameterSpec(128, ivBytes))

        val decryptedBytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        assertArrayEquals(dummyAudioBytes, decryptedBytes)
    }
}
