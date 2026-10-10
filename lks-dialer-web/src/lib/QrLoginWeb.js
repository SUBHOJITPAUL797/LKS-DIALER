import { doc, setDoc, updateDoc, deleteDoc, onSnapshot } from 'firebase/firestore';
import { db } from './firebase';
import QRCode from 'qrcode';

/**
 * Detect a clean, human-readable browser & platform name for display on the phone.
 */
export function getFriendlyBrowserName() {
  if (typeof navigator === 'undefined') return 'Web Browser';
  const ua = navigator.userAgent;
  let browser = 'Web Browser';
  if (ua.includes('Edg/')) browser = 'Microsoft Edge';
  else if (ua.includes('Chrome/')) browser = 'Google Chrome';
  else if (ua.includes('Safari/') && !ua.includes('Chrome/')) browser = 'Safari';
  else if (ua.includes('Firefox/')) browser = 'Mozilla Firefox';
  else if (ua.includes('Opera/') || ua.includes('OPR/')) browser = 'Opera';

  let os = 'Desktop';
  if (ua.includes('Windows')) os = 'Windows';
  else if (ua.includes('Macintosh') || ua.includes('Mac OS')) os = 'macOS';
  else if (ua.includes('Linux')) os = 'Linux';
  else if (ua.includes('Android')) os = 'Android';
  else if (ua.includes('iPhone') || ua.includes('iPad')) os = 'iOS';

  return `${browser} (${os})`;
}

/**
 * Generate a cryptographically secure random 32-character hex nonce.
 */
function generateSecureNonce() {
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  }
  return Math.random().toString(36).substring(2) + Math.random().toString(36).substring(2);
}

/**
 * QR Session Manager for Web Login.
 * Creates an ephemeral session, renders the QR code, monitors approval, and handles cleanup.
 */
export class QrLoginManager {
  constructor() {
    this.currentSessionId = null;
    this.currentNonce = null;
    this.unsubscribe = null;
    this.timerId = null;
    this.isApproved = false;
  }

  /**
   * Initializes a new QR code login session.
   * @param {Object} options
   * @param {HTMLCanvasElement} [options.canvas] - Optional canvas to draw the QR code on.
   * @param {Function} options.onQrRendered - Called with dataUrl once QR code is rendered.
   * @param {Function} options.onStatusChange - Called on status updates ('PENDING', 'SCANNED', 'APPROVED', 'CONSUMED', 'EXPIRED').
   * @param {Function} options.onSuccess - Called with user payload on successful approval.
   * @param {Function} options.onError - Called on error.
   */
  async startSession({ canvas, onQrRendered, onStatusChange, onSuccess, onError }) {
    this.cleanup();

    try {
      const sessionId = typeof crypto !== 'undefined' && crypto.randomUUID 
        ? crypto.randomUUID() 
        : 'qr_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);
      const nonce = generateSecureNonce();
      const createdAt = Date.now();
      const expiresAt = createdAt + 60000; // 60 seconds strict TTL
      const deviceName = getFriendlyBrowserName();

      this.currentSessionId = sessionId;
      this.currentNonce = nonce;
      this.isApproved = false;

      // Cryptographically structured payload for the QR code
      const qrPayload = JSON.stringify({
        v: 1,
        type: 'LKS_QR_LOGIN',
        sessionId,
        nonce,
        ts: createdAt
      });

      // Render QR code to data URL and/or canvas
      const qrDataUrl = await QRCode.toDataURL(qrPayload, {
        errorCorrectionLevel: 'M',
        margin: 2,
        width: 320,
        color: {
          dark: '#0f172a',
          light: '#ffffff'
        }
      });

      if (canvas) {
        await QRCode.toCanvas(canvas, qrPayload, {
          errorCorrectionLevel: 'M',
          margin: 2,
          width: 200,
          color: {
            dark: '#0f172a',
            light: '#ffffff'
          }
        });
      }

      if (onQrRendered) {
        onQrRendered(qrDataUrl, qrPayload);
      }

      // Record ephemeral session in Firestore
      const sessionRef = doc(db, 'qr_sessions', sessionId);
      await setDoc(sessionRef, {
        sessionId,
        nonce,
        status: 'PENDING',
        createdAt,
        expiresAt,
        deviceName,
        userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : ''
      });

      if (onStatusChange) onStatusChange('PENDING');

      // Set 60-second expiration timer
      this.timerId = setTimeout(async () => {
        if (!this.isApproved && this.currentSessionId === sessionId) {
          try {
            await updateDoc(sessionRef, { status: 'EXPIRED' });
          } catch (_) {}
          if (onStatusChange) onStatusChange('EXPIRED');
          this.cleanup();
        }
      }, 60000);

      // Listen for phone scan and approval
      this.unsubscribe = onSnapshot(sessionRef, async (snap) => {
        if (!snap.exists()) return;
        const data = snap.data();

        if (data.status === 'SCANNED') {
          if (onStatusChange) onStatusChange('SCANNED');
        } else if (data.status === 'APPROVED' && !this.isApproved) {
          // Security checks: Nonce must match exactly
          if (data.nonce !== this.currentNonce) {
            console.error('Security alert: Nonce mismatch on QR login approval!');
            if (onError) onError(new Error('Security check failed: Nonce mismatch'));
            this.cleanup();
            return;
          }

          if (!data.userPhone) {
            console.error('Missing user phone on approval');
            return;
          }

          this.isApproved = true;
          if (onStatusChange) onStatusChange('APPROVED');

          // Immediately mark as CONSUMED so this session cannot be reused (single-use)
          try {
            await updateDoc(sessionRef, { 
              status: 'CONSUMED',
              consumedAt: Date.now()
            });
          } catch (e) {
            console.warn('Failed to mark session CONSUMED:', e);
          }

          // Register this device under users/{phone}/linked_devices/{sessionId}
          try {
            const linkedRef = doc(db, 'users', data.userPhone, 'linked_devices', sessionId);
            await setDoc(linkedRef, {
              sessionId,
              deviceName: data.deviceName || deviceName,
              userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : '',
              linkedAt: Date.now(),
              lastActive: Date.now(),
              revoked: false
            }, { merge: true });
          } catch (e) {
            console.warn('Failed to record linked device:', e);
          }

          // Clean up listener
          this.cleanup();

          // Return user payload
          if (onSuccess) {
            onSuccess({
              phoneNumber: data.userPhone,
              displayName: data.userName || data.userPhone,
              profilePictureUrl: data.userProfilePic || '',
              statusMessage: data.userStatus || '',
              linkedSessionId: sessionId
            });
          }
        } else if (data.status === 'EXPIRED') {
          if (onStatusChange) onStatusChange('EXPIRED');
          this.cleanup();
        }
      }, (err) => {
        console.error('QR session snapshot error:', err);
        if (onError) onError(err);
      });

      return { sessionId, nonce, expiresAt };
    } catch (e) {
      console.error('Failed to create QR session:', e);
      if (onError) onError(e);
      throw e;
    }
  }

  /**
   * Cleans up listeners and timers.
   */
  cleanup() {
    if (this.unsubscribe) {
      this.unsubscribe();
      this.unsubscribe = null;
    }
    if (this.timerId) {
      clearTimeout(this.timerId);
      this.timerId = null;
    }
  }
}

/**
 * Watch for remote logout / device revocation by the phone.
 * @param {string} phoneNumber
 * @param {string} sessionId
 * @param {Function} onRevoked - callback triggered when the phone logs out this web device
 * @returns {Function} unsubscribe
 */
export function watchDeviceRevocation(phoneNumber, sessionId, onRevoked) {
  if (!phoneNumber || !sessionId) return () => {};

  try {
    const linkedRef = doc(db, 'users', phoneNumber, 'linked_devices', sessionId);
    return onSnapshot(linkedRef, (snap) => {
      if (!snap.exists()) {
        // Device record was deleted from phone
        console.log('[LinkedDevice] Session record removed by phone. Logging out...');
        onRevoked();
      } else {
        const data = snap.data();
        if (data && data.revoked === true) {
          console.log('[LinkedDevice] Session revoked by phone. Logging out...');
          onRevoked();
        }
      }
    }, (err) => {
      console.warn('[LinkedDevice] Error listening to revocation:', err);
    });
  } catch (e) {
    console.warn('[LinkedDevice] Failed to setup revocation watcher:', e);
    return () => {};
  }
}
