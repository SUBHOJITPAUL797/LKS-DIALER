package com.example.data.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChatCryptoManager
 *
 * Implements End-to-End Encryption (E2EE) for LKS Dialer:
 * - Asymmetric: NIST P-256 (secp256r1) Elliptic Curve Diffie-Hellman (ECDH)
 * - Symmetric: AES-256-GCM with unique 12-byte IV and 128-bit authentication tag
 * - Key Derivation: SHA-256 on ECDH shared secret
 *
 * All messages, voice notes, and media transferred across the ephemeral relay
 * are encrypted on the sender's device and can only be decrypted by the recipient.
 */
class ChatCryptoManager private constructor(context: Context) {

    companion object {
        private const val TAG = "ChatCryptoManager"
        private const val PREFS_NAME = "lks_chat_crypto_prefs"
        private const val KEY_PRIVATE = "ec_private_key_pkcs8"
        private const val KEY_PUBLIC = "ec_public_key_x509"

        private const val EC_ALGORITHM = "EC"
        private const val EC_CURVE = "secp256r1"
        private const val ECDH_ALGORITHM = "ECDH"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12

        @Volatile
        private var INSTANCE: ChatCryptoManager? = null

        fun getInstance(context: Context): ChatCryptoManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatCryptoManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var keyPair: KeyPair? = null

    init {
        loadOrGenerateKeyPair()
    }

    /**
     * Initializes or retrieves the persistent EC keypair for this device.
     */
    @Synchronized
    private fun loadOrGenerateKeyPair() {
        val savedPrivateBase64 = prefs.getString(KEY_PRIVATE, null)
        val savedPublicBase64 = prefs.getString(KEY_PUBLIC, null)

        if (!savedPrivateBase64.isNullOrBlank() && !savedPublicBase64.isNullOrBlank()) {
            try {
                val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
                val privBytes = Base64.decode(savedPrivateBase64, Base64.NO_WRAP)
                val pubBytes = Base64.decode(savedPublicBase64, Base64.NO_WRAP)

                val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))

                keyPair = KeyPair(publicKey, privateKey)
                Log.d(TAG, "Successfully loaded existing EC keypair from storage")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore existing keypair, generating a fresh one: ${e.message}")
            }
        }

        // Generate a new EC key pair
        try {
            val keyGen = KeyPairGenerator.getInstance(EC_ALGORITHM)
            val ecSpec = ECGenParameterSpec(EC_CURVE)
            keyGen.initialize(ecSpec, SecureRandom())
            val newPair = keyGen.generateKeyPair()

            val privBase64 = Base64.encodeToString(newPair.private.encoded, Base64.NO_WRAP)
            val pubBase64 = Base64.encodeToString(newPair.public.encoded, Base64.NO_WRAP)

            prefs.edit()
                .putString(KEY_PRIVATE, privBase64)
                .putString(KEY_PUBLIC, pubBase64)
                .apply()

            keyPair = newPair
            Log.i(TAG, "Generated and saved new EC P-256 keypair successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Critical error generating EC keypair: ${e.message}", e)
        }
    }

    /**
     * Returns the Base64-encoded X.509 representation of this user's public key
     * to be shared with other users on Firestore.
     */
    fun getMyPublicKeyBase64(): String {
        val pub = keyPair?.public?.encoded ?: return ""
        return Base64.encodeToString(pub, Base64.NO_WRAP)
    }

    /**
     * Computes the 256-bit AES symmetric key derived from this user's private key
     * and the peer's public key using ECDH + SHA-256.
     */
    private fun deriveSharedAesKey(peerPublicKeyBase64: String): SecretKeySpec {
        val myPrivateKey = keyPair?.private
            ?: throw IllegalStateException("Local private key not initialized")

        val peerPubBytes = Base64.decode(peerPublicKeyBase64.trim(), Base64.NO_WRAP)
        val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
        val peerPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPubBytes))

        val keyAgreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Derive 256-bit AES key via SHA-256 hash of shared secret
        val md = MessageDigest.getInstance("SHA-256")
        val derivedKeyBytes = md.digest(sharedSecret)

        return SecretKeySpec(derivedKeyBytes, "AES")
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM with a random 12-byte IV.
     * Returns a pair of:
     * - ciphertext (Base64 string)
     * - iv (Base64 string)
     */
    fun encrypt(plaintext: String, recipientPublicKeyBase64: String): Pair<String, String> {
        val aesKey = deriveSharedAesKey(recipientPublicKeyBase64)

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec)

        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

        return Pair(ciphertextBase64, ivBase64)
    }

    /**
     * Decrypts ciphertext bytes using AES-256-GCM.
     */
    fun decrypt(ciphertextBase64: String, ivBase64: String, senderPublicKeyBase64: String): String {
        val aesKey = deriveSharedAesKey(senderPublicKeyBase64)

        val cipherBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec)

        val plaintextBytes = cipher.doFinal(cipherBytes)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    /**
     * Encrypts raw binary data (e.g. image bytes or voice note bytes)
     * Returns a pair of:
     * - ciphertext (Base64 string)
     * - iv (Base64 string)
     */
    fun encryptBytes(data: ByteArray, recipientPublicKeyBase64: String): Pair<String, String> {
        val aesKey = deriveSharedAesKey(recipientPublicKeyBase64)

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec)

        val cipherBytes = cipher.doFinal(data)

        val ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

        return Pair(ciphertextBase64, ivBase64)
    }

    /**
     * Decrypts raw binary data (e.g. image bytes or voice note bytes)
     */
    fun decryptBytes(ciphertextBase64: String, ivBase64: String, senderPublicKeyBase64: String): ByteArray {
        val aesKey = deriveSharedAesKey(senderPublicKeyBase64)

        val cipherBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val ivBytes = Base64.decode(ivBase64, Base64.NO_WRAP)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec)

        return cipher.doFinal(cipherBytes)
    }
}
