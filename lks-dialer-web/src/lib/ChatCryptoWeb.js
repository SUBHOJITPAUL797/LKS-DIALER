/**
 * ChatCryptoWeb.js
 *
 * Implements End-to-End Encryption (E2EE) on Web browsers:
 * - NIST P-256 (secp256r1) Elliptic Curve Diffie-Hellman (ECDH)
 * - Key derivation: SHA-256 hash of ECDH shared secret
 * - Symmetric cipher: AES-256-GCM with 12-byte IV and 128-bit authentication tag
 *
 * 100% cross-compatible with Android's ChatCryptoManager.kt!
 */

export function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

export function base64ToArrayBuffer(base64) {
  const clean = base64.replace(/\s/g, '');
  const binary = atob(clean);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

class ChatCryptoWeb {
  constructor() {
    this.keyPair = null;
    this.publicKeyBase64 = null;
    this.initPromise = this.init();
  }

  async init() {
    try {
      if (typeof window === 'undefined' || !window.crypto || !window.crypto.subtle) return;
      const savedPriv = localStorage.getItem('lks_ec_private_key_pkcs8');
      const savedPub = localStorage.getItem('lks_ec_public_key_spki');

      if (savedPriv && savedPub) {
        try {
          const privBuffer = base64ToArrayBuffer(savedPriv);
          const pubBuffer = base64ToArrayBuffer(savedPub);

          const privateKey = await window.crypto.subtle.importKey(
            'pkcs8',
            privBuffer,
            { name: 'ECDH', namedCurve: 'P-256' },
            true,
            ['deriveKey', 'deriveBits']
          );

          const publicKey = await window.crypto.subtle.importKey(
            'spki',
            pubBuffer,
            { name: 'ECDH', namedCurve: 'P-256' },
            true,
            []
          );

          this.keyPair = { privateKey, publicKey };
          this.publicKeyBase64 = savedPub;
          return;
        } catch (e) {
          console.warn('Failed to restore saved EC keypair, generating fresh one:', e);
        }
      }

      // Generate fresh NIST P-256 keypair
      const newPair = await window.crypto.subtle.generateKey(
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        ['deriveKey', 'deriveBits']
      );

      const privBuffer = await window.crypto.subtle.exportKey('pkcs8', newPair.privateKey);
      const pubBuffer = await window.crypto.subtle.exportKey('spki', newPair.publicKey);

      const privBase64 = arrayBufferToBase64(privBuffer);
      const pubBase64 = arrayBufferToBase64(pubBuffer);

      localStorage.setItem('lks_ec_private_key_pkcs8', privBase64);
      localStorage.setItem('lks_ec_public_key_spki', pubBase64);

      this.keyPair = newPair;
      this.publicKeyBase64 = pubBase64;
    } catch (e) {
      console.error('Critical error in ChatCryptoWeb init:', e);
    }
  }

  async getMyPublicKeyBase64() {
    await this.initPromise;
    return this.publicKeyBase64 || '';
  }

  async deriveSharedAesKey(peerPublicKeyBase64) {
    await this.initPromise;
    if (!this.keyPair?.privateKey) {
      throw new Error('Local EC private key not initialized');
    }

    const peerBuffer = base64ToArrayBuffer(peerPublicKeyBase64);
    const peerPublicKey = await window.crypto.subtle.importKey(
      'spki',
      peerBuffer,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      []
    );

    // Derive 256 bits of shared secret using ECDH
    const sharedBits = await window.crypto.subtle.deriveBits(
      { name: 'ECDH', public: peerPublicKey },
      this.keyPair.privateKey,
      256
    );

    // Compute SHA-256 digest of the shared secret (matching Android SHA-256 hash)
    const hashedKeyBytes = await window.crypto.subtle.digest('SHA-256', sharedBits);

    // Import as AES-GCM symmetric key
    return await window.crypto.subtle.importKey(
      'raw',
      hashedKeyBytes,
      { name: 'AES-GCM' },
      false,
      ['encrypt', 'decrypt']
    );
  }

  async encrypt(plaintext, recipientPublicKeyBase64) {
    const aesKey = await this.deriveSharedAesKey(recipientPublicKeyBase64);

    // Generate random 12-byte IV
    const iv = window.crypto.getRandomValues(new Uint8Array(12));
    const encoder = new TextEncoder();
    const encodedData = encoder.encode(plaintext);

    // AES-GCM encryption with 128-bit authentication tag appended
    const cipherBuffer = await window.crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, tagLength: 128 },
      aesKey,
      encodedData
    );

    return {
      ciphertext: arrayBufferToBase64(cipherBuffer),
      iv: arrayBufferToBase64(iv)
    };
  }

  async decrypt(ciphertextBase64, ivBase64, senderPublicKeyBase64) {
    const aesKey = await this.deriveSharedAesKey(senderPublicKeyBase64);

    const cipherBuffer = base64ToArrayBuffer(ciphertextBase64);
    const ivBuffer = base64ToArrayBuffer(ivBase64);

    const decryptedBuffer = await window.crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: new Uint8Array(ivBuffer), tagLength: 128 },
      aesKey,
      cipherBuffer
    );

    const decoder = new TextDecoder('utf-8');
    return decoder.decode(decryptedBuffer);
  }
}

export const chatCryptoWeb = new ChatCryptoWeb();
