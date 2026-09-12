import {
  doc, setDoc, updateDoc, getDoc, deleteDoc,
  collection, addDoc, onSnapshot
} from 'firebase/firestore';
import { db } from './firebase';
import { mediaStorageWeb } from './MediaStorageWeb';

const P2P_COLLECTION = 'p2p_transfers';
const CHUNK_SIZE = 16 * 1024;          // 16 KB — safe WebRTC DataChannel chunk
const BUFFER_LOW_THRESHOLD = 65536;   // 64 KB backpressure
const ICE_TIMEOUT_MS = 8000;          // 8s to establish DataChannel before fallback

const ICE_SERVERS = {
  iceServers: [
    { urls: [
        'stun:stun.l.google.com:19302',
        'stun:stun1.l.google.com:19302',
        'stun:stun2.l.google.com:19302',
        'stun:stun3.l.google.com:19302',
        'stun:stun4.l.google.com:19302'
    ]},
    {
      urls: [
        'turn:a.relay.metered.ca:80',
        'turn:a.relay.metered.ca:80?transport=tcp',
        'turn:a.relay.metered.ca:443',
        'turn:a.relay.metered.ca:443?transport=tcp',
        'turns:a.relay.metered.ca:443?transport=tcp'
      ],
      username: 'openrelayproject',
      credential: 'openrelayproject'
    }
  ],
  iceCandidatePoolSize: 10,
  bundlePolicy: 'max-bundle',
  rtcpMuxPolicy: 'require'
};

/**
 * P2P file transfer engine using WebRTC DataChannel.
 *
 * Signaling uses dedicated Firestore collection `p2p_transfers/{sessionId}`.
 *
 * Protocol:
 *  - Text frame 1: JSON header {type, messageId, fileName, totalBytes, totalChunks}
 *  - Binary frames 2..N: raw file ArrayBuffer chunks (16KB each, ordered)
 *
 * Offline fallback is handled by the caller (ChatRepositoryWeb).
 */
export class P2pFileTransferWeb {
  constructor() {
    this.pc = null;
    this.dc = null;
    this.unsubscribers = [];
    this._cancelled = false;
  }

  // ─── SENDER: Initiate P2P transfer ────────────────────────────────────────────
  /**
   * Returns true if P2P transfer succeeded, false if caller should use relay fallback.
   * @param {string} sessionId - Unique transfer session ID
   * @param {string} myPhone - Sender's phone number
   * @param {string} recipientPhone - Recipient's phone number
   * @param {File|Blob} file - File to send
   * @param {function} onProgress - Progress callback ({percent, mbTransferred, totalMb, speedMbps, status, mode, isIncoming})
   */
  async sendFile(sessionId, myPhone, recipientPhone, file, onProgress) {
    return new Promise(async (resolve) => {
      let resolved = false;
      const resolveOnce = (val) => { if (!resolved) { resolved = true; resolve(val); } };

      try {
        const fileName = file.name || 'file';
        const totalBytes = file.size;
        const totalChunks = Math.ceil(totalBytes / CHUNK_SIZE);

        onProgress && onProgress({ percent: 0, mbTransferred: 0, totalMb: (totalBytes / 1048576).toFixed(1), speedMbps: '0', status: 'CONNECTING', mode: 'P2P', isIncoming: false, messageId: sessionId, fileName });

        this.pc = new RTCPeerConnection(ICE_SERVERS);

        this.pc.onicecandidate = (event) => {
          if (!event.candidate) return;
          addDoc(collection(db, P2P_COLLECTION, sessionId, 'sender_ice'), {
            sdpMid: event.candidate.sdpMid || '',
            sdpMLineIndex: event.candidate.sdpMLineIndex || 0,
            candidate: event.candidate.candidate,
            ts: Date.now()
          }).catch(() => {});
        };

        this.pc.onconnectionstatechange = () => {
          const s = this.pc?.connectionState;
          console.log('[P2pWeb] Sender PC:', s);
          if (s === 'failed' || s === 'closed') resolveOnce(false);
        };

        this.pc.oniceconnectionstatechange = () => {
          if (this.pc?.iceConnectionState === 'failed') resolveOnce(false);
        };

        // Create DataChannel BEFORE creating offer
        this.dc = this.pc.createDataChannel('lks-file', { ordered: true, maxRetransmits: null });
        this.dc.binaryType = 'arraybuffer';

        this.dc.onopen = async () => {
          console.log('[P2pWeb] DataChannel OPEN — starting file stream');
          try {
            const success = await this._streamFile(sessionId, file, fileName, totalBytes, totalChunks, onProgress);
            resolveOnce(success);
          } catch (e) {
            console.error('[P2pWeb] streamFile error:', e);
            resolveOnce(false);
          }
        };

        this.dc.onclose = () => {
          console.log('[P2pWeb] Sender DC closed');
          resolveOnce(false);
        };

        // Create offer
        const offer = await this.pc.createOffer();
        await this.pc.setLocalDescription(offer);

        // Write session to Firestore p2p_transfers/{sessionId}
        await setDoc(doc(db, P2P_COLLECTION, sessionId), {
          sessionId,
          senderPhone: myPhone,
          recipientPhone,
          fileName,
          fileSize: totalBytes,
          offerSdp: offer.sdp,
          status: 'PENDING',
          timestamp: Date.now()
        });

        // Listen for answer SDP
        let answerApplied = false;
        const unsub1 = onSnapshot(doc(db, P2P_COLLECTION, sessionId), async (snap) => {
          if (!snap.exists() || this._cancelled) return;
          const data = snap.data();
          const answerSdp = data?.answerSdp;
          if (!answerSdp || answerApplied) return;
          answerApplied = true;
          try {
            await this.pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });
            console.log('[P2pWeb] Sender: remote answer applied');
          } catch (e) {
            console.warn('[P2pWeb] Sender set remote error:', e);
          }
        });
        this.unsubscribers.push(unsub1);

        // Listen for receiver ICE candidates
        const unsub2 = onSnapshot(collection(db, P2P_COLLECTION, sessionId, 'receiver_ice'), (snap) => {
          snap.docChanges().forEach((ch) => {
            if (ch.type === 'added') {
              const d = ch.doc.data();
              this.pc?.addIceCandidate({ sdpMid: d.sdpMid, sdpMLineIndex: d.sdpMLineIndex, candidate: d.candidate })
                .catch(() => {});
            }
          });
        });
        this.unsubscribers.push(unsub2);

        // ICE connection timeout
        setTimeout(() => {
          if (!resolved && this.dc?.readyState !== 'open') {
            console.warn('[P2pWeb] ICE timeout — falling back to relay');
            resolveOnce(false);
          }
        }, ICE_TIMEOUT_MS);

      } catch (e) {
        console.error('[P2pWeb] sendFile error:', e);
        resolveOnce(false);
      }
    }).finally(() => {
      this._cleanup(sessionId);
    });
  }

  // ─── Stream file over open DataChannel ────────────────────────────────────────
  async _streamFile(sessionId, file, fileName, totalBytes, totalChunks, onProgress) {
    try {
      // 1. Send JSON header (text frame)
      this.dc.send(JSON.stringify({
        type: 'FILE_HEADER',
        messageId: sessionId,
        fileName,
        totalBytes,
        totalChunks
      }));

      let sentBytes = 0;
      let lastSpeedTs = Date.now();
      let lastSpeedBytes = 0;
      let currentSpeed = 0;

      // 2. Stream binary chunks from File using FileReader slice
      for (let i = 0; i < totalChunks; i++) {
        if (this._cancelled || this.dc?.readyState !== 'open') return false;

        const start = i * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, totalBytes);
        const slice = file.slice(start, end);
        const arrayBuffer = await slice.arrayBuffer();

        // Backpressure: wait until buffer drains
        let waited = 0;
        while (this.dc.bufferedAmount > BUFFER_LOW_THRESHOLD) {
          await new Promise(r => setTimeout(r, 5));
          waited += 5;
          if (waited > 30000 || this._cancelled) return false;
        }

        this.dc.send(arrayBuffer);
        sentBytes += arrayBuffer.byteLength;

        // Speed calculation
        const now = Date.now();
        if (now - lastSpeedTs >= 200) {
          currentSpeed = ((sentBytes - lastSpeedBytes) / ((now - lastSpeedTs) / 1000));
          lastSpeedTs = now;
          lastSpeedBytes = sentBytes;
        }

        const percent = Math.round((sentBytes / totalBytes) * 100);
        const etaSec = currentSpeed > 0 ? Math.round((totalBytes - sentBytes) / currentSpeed) : 0;
        onProgress && onProgress({
          percent,
          mbTransferred: (sentBytes / 1048576).toFixed(1),
          totalMb: (totalBytes / 1048576).toFixed(1),
          speedMbps: (currentSpeed / 1048576).toFixed(1),
          etaSec,
          status: 'TRANSFERRING',
          mode: 'P2P',
          isIncoming: false,
          messageId: sessionId,
          fileName
        });
      }

      // Mark done
      try { await updateDoc(doc(db, P2P_COLLECTION, sessionId), { status: 'DONE' }); } catch (_) {}

      onProgress && onProgress({
        percent: 100,
        mbTransferred: (totalBytes / 1048576).toFixed(1),
        totalMb: (totalBytes / 1048576).toFixed(1),
        speedMbps: currentSpeed > 0 ? (currentSpeed / 1048576).toFixed(1) : '0',
        status: 'DONE',
        mode: 'P2P',
        isIncoming: false,
        messageId: sessionId,
        fileName
      });

      return true;
    } catch (e) {
      console.error('[P2pWeb] _streamFile error:', e);
      return false;
    }
  }

  // ─── RECEIVER: Accept incoming P2P file transfer ──────────────────────────────
  /**
   * Called when a P2P_OFFER inbox message is received.
   * Fetches session from Firestore, creates answer PeerConnection,
   * receives file via DataChannel, saves to MediaStorageWeb (IndexedDB).
   *
   * @param {string} sessionId - The transfer session ID
   * @param {function} onProgress - Progress callback
   * @param {function} onComplete - Called with (messageId, fileName, blob|null) when done
   */
  async receiveFile(sessionId, onProgress, onComplete) {
    try {
      // Fetch session from Firestore
      const sessionSnap = await getDoc(doc(db, P2P_COLLECTION, sessionId));
      if (!sessionSnap.exists()) { onComplete(sessionId, null, null); return; }

      const sessionData = sessionSnap.data();
      const offerSdp = sessionData.offerSdp;
      const fileName = sessionData.fileName || 'received_file';
      const totalBytes = sessionData.fileSize || 0;

      onProgress && onProgress({ percent: 0, mbTransferred: 0, totalMb: (totalBytes / 1048576).toFixed(1), speedMbps: '0', status: 'CONNECTING', mode: 'P2P', isIncoming: true, messageId: sessionId, fileName });

      this.pc = new RTCPeerConnection(ICE_SERVERS);

      this.pc.onicecandidate = (event) => {
        if (!event.candidate) return;
        addDoc(collection(db, P2P_COLLECTION, sessionId, 'receiver_ice'), {
          sdpMid: event.candidate.sdpMid || '',
          sdpMLineIndex: event.candidate.sdpMLineIndex || 0,
          candidate: event.candidate.candidate,
          ts: Date.now()
        }).catch(() => {});
      };

      this.pc.onconnectionstatechange = () => {
        const s = this.pc?.connectionState;
        console.log('[P2pWeb] Receiver PC:', s);
        if (s === 'failed' || s === 'closed') onComplete(sessionId, fileName, null);
      };

      // Chunk tracking — ordered=true so implicit index is safe
      const chunks = [];
      let totalChunks = -1;
      let receivedCount = 0;
      let receivedBytes = 0;
      let lastSpeedTs = Date.now();
      let lastSpeedBytes = 0;
      let currentSpeed = 0;

      this.pc.ondatachannel = (event) => {
        this.dc = event.channel;
        this.dc.binaryType = 'arraybuffer';

        this.dc.onopen = () => {
          console.log('[P2pWeb] Receiver DC open');
          onProgress && onProgress({ percent: 0, mbTransferred: 0, totalMb: (totalBytes / 1048576).toFixed(1), speedMbps: '0', status: 'TRANSFERRING', mode: 'P2P', isIncoming: true, messageId: sessionId, fileName });
        };

        this.dc.onmessage = async (event) => {
          if (typeof event.data === 'string') {
            // Text = JSON header
            try {
              const header = JSON.parse(event.data);
              if (header.type === 'FILE_HEADER') {
                totalChunks = header.totalChunks;
                console.log(`[P2pWeb] Receiver: expecting ${totalChunks} chunks for '${fileName}'`);
              }
            } catch (_) {}
          } else {
            // Binary = file chunk (ArrayBuffer)
            chunks.push(event.data);
            receivedCount++;
            receivedBytes += event.data.byteLength;

            const now = Date.now();
            if (now - lastSpeedTs >= 200) {
              currentSpeed = ((receivedBytes - lastSpeedBytes) / ((now - lastSpeedTs) / 1000));
              lastSpeedTs = now;
              lastSpeedBytes = receivedBytes;
            }

            const percent = totalBytes > 0 ? Math.round((receivedBytes / totalBytes) * 100) : 0;
            onProgress && onProgress({
              percent,
              mbTransferred: (receivedBytes / 1048576).toFixed(1),
              totalMb: (totalBytes / 1048576).toFixed(1),
              speedMbps: (currentSpeed / 1048576).toFixed(1),
              status: 'TRANSFERRING',
              mode: 'P2P',
              isIncoming: true,
              messageId: sessionId,
              fileName
            });

            // Assemble when all chunks received
            if (totalChunks > 0 && receivedCount >= totalChunks) {
              try {
                // Concatenate all ArrayBuffers into one Blob
                const blob = new Blob(chunks);
                const mimeType = this._guessMime(fileName);
                const namedBlob = new Blob([blob], { type: mimeType });

                // Save to IndexedDB via MediaStorageWeb
                await mediaStorageWeb.saveMedia(sessionId, namedBlob, fileName, mimeType);

                onProgress && onProgress({
                  percent: 100,
                  mbTransferred: (totalBytes / 1048576).toFixed(1),
                  totalMb: (totalBytes / 1048576).toFixed(1),
                  speedMbps: currentSpeed > 0 ? (currentSpeed / 1048576).toFixed(1) : '0',
                  status: 'DONE',
                  mode: 'P2P',
                  isIncoming: true,
                  messageId: sessionId,
                  fileName
                });

                onComplete(sessionId, fileName, namedBlob);
                this._cleanup(sessionId);
              } catch (e) {
                console.error('[P2pWeb] Assembly error:', e);
                onComplete(sessionId, fileName, null);
              }
            }
          }
        };

        this.dc.onclose = () => console.log('[P2pWeb] Receiver DC closed');
      };

      // Set remote offer
      await this.pc.setRemoteDescription({ type: 'offer', sdp: offerSdp });

      // Create and set local answer
      const answer = await this.pc.createAnswer();
      await this.pc.setLocalDescription(answer);

      // Write answer back to Firestore
      await updateDoc(doc(db, P2P_COLLECTION, sessionId), {
        answerSdp: answer.sdp,
        status: 'ANSWERING'
      });

      // Listen for sender ICE candidates
      const unsub = onSnapshot(collection(db, P2P_COLLECTION, sessionId, 'sender_ice'), (snap) => {
        snap.docChanges().forEach((ch) => {
          if (ch.type === 'added') {
            const d = ch.doc.data();
            this.pc?.addIceCandidate({ sdpMid: d.sdpMid, sdpMLineIndex: d.sdpMLineIndex, candidate: d.candidate })
              .catch(() => {});
          }
        });
      });
      this.unsubscribers.push(unsub);

    } catch (e) {
      console.error('[P2pWeb] receiveFile error:', e);
      onComplete(sessionId, null, null);
      this._cleanup(sessionId);
    }
  }

  // ─── Guess MIME type from file extension ─────────────────────────────────────
  _guessMime(fileName) {
    const ext = fileName.split('.').pop()?.toLowerCase();
    const map = {
      mp3: 'audio/mpeg', m4a: 'audio/mp4', wav: 'audio/wav', aac: 'audio/aac',
      ogg: 'audio/ogg', flac: 'audio/flac', opus: 'audio/opus',
      pdf: 'application/pdf', doc: 'application/msword',
      docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      xls: 'application/vnd.ms-excel',
      xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      zip: 'application/zip', rar: 'application/x-rar-compressed',
      mp4: 'video/mp4', mkv: 'video/x-matroska', avi: 'video/x-msvideo',
      jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif',
      apk: 'application/vnd.android.package-archive',
      txt: 'text/plain'
    };
    return map[ext] || 'application/octet-stream';
  }

  /** Cancel ongoing transfer */
  cancel(sessionId) {
    this._cancelled = true;
    this._cleanup(sessionId);
  }

  _cleanup(sessionId) {
    this.unsubscribers.forEach(u => { try { u(); } catch (_) {} });
    this.unsubscribers = [];
    try { this.dc?.close(); } catch (_) {}
    try { this.pc?.close(); } catch (_) {}
    this.dc = null;
    this.pc = null;
    // Delete Firestore session after delay
    setTimeout(async () => {
      try {
        const sessionRef = doc(db, P2P_COLLECTION, sessionId);
        // Delete ICE subcollection docs
        const [senderIce, receiverIce] = await Promise.allSettled([
          import('firebase/firestore').then(({ getDocs }) =>
            getDocs(collection(db, P2P_COLLECTION, sessionId, 'sender_ice'))),
          import('firebase/firestore').then(({ getDocs }) =>
            getDocs(collection(db, P2P_COLLECTION, sessionId, 'receiver_ice')))
        ]);
        if (senderIce.status === 'fulfilled') {
          senderIce.value.docs.forEach(d => deleteDoc(d.ref).catch(() => {}));
        }
        if (receiverIce.status === 'fulfilled') {
          receiverIce.value.docs.forEach(d => deleteDoc(d.ref).catch(() => {}));
        }
        await deleteDoc(sessionRef);
      } catch (_) {}
    }, 8000);
  }
}

// Singleton instance for convenience
export const p2pFileTransferWeb = new P2pFileTransferWeb();
