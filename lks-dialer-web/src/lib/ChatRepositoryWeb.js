import { db } from './firebase';
import { 
  collection, doc, setDoc, getDoc, deleteDoc, onSnapshot 
} from 'firebase/firestore';
import { chatCryptoWeb } from './ChatCryptoWeb';
import { formatAvatarUrl } from './ImageUtils';
import { mediaStorageWeb } from './MediaStorageWeb';

/**
 * Normalizes a phone number (digits and plus only).
 */
export function normalizePhoneNumber(number) {
  if (!number) return '';
  return String(number).replace(/[^0-9+]/g, '');
}

/**
 * Checks if two phone numbers match (exact or last 10 digits).
 */
export function numbersMatch(num1, num2) {
  const clean1 = String(num1 || '').replace(/[^0-9]/g, '');
  const clean2 = String(num2 || '').replace(/[^0-9]/g, '');
  if (!clean1 || !clean2) return false;
  if (clean1 === clean2) return true;
  if (clean1.length >= 10 && clean2.length >= 10) {
    return clean1.slice(-10) === clean2.slice(-10);
  }
  return false;
}

function generateUuid() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'msg-' + Math.random().toString(36).substring(2, 11) + '-' + Date.now();
}

class ChatRepositoryWeb {
  constructor() {
    this.currentListeningPhone = null;
    this.activeChatPeerNumber = null;
    this.typingStatus = {}; // { [peerPhone]: boolean }
    this.subscribers = new Set();

    this.unsubInbox = null;
    this.unsubReceipts = null;
    this.unsubTyping = null;
    this.typingTimeouts = {};

    try {
      this.reconcileConversations();
    } catch {}
  }

  // --- PUB / SUB FOR REACTIVE UI ---
  subscribe(callback) {
    this.subscribers.add(callback);
    return () => this.subscribers.delete(callback);
  }

  notifySubscribers() {
    this.subscribers.forEach(cb => {
      try { cb(); } catch (e) { console.error('Error notifying chat subscriber:', e); }
    });
  }

  // --- ACTIVE PEER MANAGEMENT ---
  setActiveChatPeer(phoneNumber) {
    const normalized = phoneNumber ? normalizePhoneNumber(phoneNumber) : null;
    this.activeChatPeerNumber = normalized;
    if (normalized) {
      this.markConversationAsRead(normalized);
    }
  }

  // --- LOCAL PERSISTENCE (localStorage) ---
  getConversations() {
    try {
      const raw = localStorage.getItem('lks_web_chat_conversations');
      const list = raw ? JSON.parse(raw) : [];
      return list.sort((a, b) => (b.lastMessageTimestamp || 0) - (a.lastMessageTimestamp || 0));
    } catch {
      return [];
    }
  }

  saveConversations(conversations) {
    try {
      localStorage.setItem('lks_web_chat_conversations', JSON.stringify(conversations));
    } catch (e) {
      console.warn('Failed to save conversations to localStorage:', e);
    }
  }

  getMessages(peerPhoneNumber) {
    const norm = normalizePhoneNumber(peerPhoneNumber);
    try {
      const raw = localStorage.getItem(`lks_web_chat_messages_${norm}`);
      const messages = raw ? JSON.parse(raw) : [];

      // Retroactive Read Heal: If peer has replied at timestamp T, all earlier outgoing messages (<= T) were read
      let lastIncomingTime = 0;
      for (let i = messages.length - 1; i >= 0; i--) {
        if (!messages[i].isOutgoing) {
          lastIncomingTime = messages[i].timestamp || 0;
          break;
        }
      }

      if (lastIncomingTime > 0) {
        let healed = false;
        messages.forEach(m => {
          if (m.isOutgoing && m.status !== 'READ' && (m.timestamp <= lastIncomingTime)) {
            m.status = 'READ';
            healed = true;
          }
        });
        if (healed) {
          localStorage.setItem(`lks_web_chat_messages_${norm}`, JSON.stringify(messages));
        }
      }

      return messages;
    } catch {
      return [];
    }
  }

  saveMessages(peerPhoneNumber, messages) {
    const norm = normalizePhoneNumber(peerPhoneNumber);
    try {
      localStorage.setItem(`lks_web_chat_messages_${norm}`, JSON.stringify(messages));
    } catch (e) {
      console.warn('Failed to save messages to localStorage:', e);
    }
  }

  // --- RECONCILE & SELF-HEAL CONVERSATIONS ---
  reconcileConversations() {
    try {
      const conversations = this.getConversations();
      let updatedConvs = false;

      conversations.forEach(c => {
        const messages = this.getMessages(c.phoneNumber);
        if (!messages || messages.length === 0) return;

        const lastMsg = messages[messages.length - 1];
        if (lastMsg && lastMsg.isOutgoing) {
          if (c.lastMessageStatus !== lastMsg.status || !c.lastMessageIsOutgoing) {
            c.lastMessageStatus = lastMsg.status;
            c.lastMessageIsOutgoing = true;
            updatedConvs = true;
          }
        }
      });

      if (updatedConvs) {
        this.saveConversations(conversations);
        this.notifySubscribers();
      }
    } catch (e) {
      console.warn('[ChatRepositoryWeb] Reconcile error:', e);
    }
  }

  getTotalUnreadCount() {
    const convs = this.getConversations();
    return convs.reduce((sum, c) => sum + (c.unreadCount || 0), 0);
  }

  // --- FIRESTORE REAL-TIME LISTENERS ---
  attachChatListeners(myPhoneNumber) {
    if (!myPhoneNumber) return;
    const normalizedMyPhone = normalizePhoneNumber(myPhoneNumber);

    // Always run reconcile on attach
    this.reconcileConversations();

    if (this.currentListeningPhone === normalizedMyPhone && this.unsubInbox) return;

    this.currentListeningPhone = normalizedMyPhone;
    this.detachChatListeners();

    console.log(`[ChatRepositoryWeb] Attaching ephemeral chat listeners for: ${normalizedMyPhone}`);

    // 1. INBOX LISTENER: receives encrypted messages for me
    const inboxCol = collection(db, 'inboxes', normalizedMyPhone, 'messages');
    this.unsubInbox = onSnapshot(inboxCol, (snapshot) => {
      snapshot.docChanges().forEach(change => {
        if (change.type === 'added') {
          const docRef = change.doc.ref;
          const data = change.doc.data();
          this.processIncomingMessage(data, docRef);
        }
      });
    }, (err) => console.error('[ChatRepositoryWeb] Inbox listener error:', err));

    // 2. RECEIPTS LISTENER: receives DELIVERED and READ receipts
    const receiptsCol = collection(db, 'receipts', normalizedMyPhone, 'acks');
    this.unsubReceipts = onSnapshot(receiptsCol, (snapshot) => {
      snapshot.docChanges().forEach(change => {
        if (change.type === 'added') {
          const docRef = change.doc.ref;
          const data = change.doc.data();
          this.processIncomingReceipt(data, docRef);
        }
      });
    }, (err) => console.error('[ChatRepositoryWeb] Receipts listener error:', err));

    // 3. TYPING STATUS LISTENER
    const typingCol = collection(db, 'typingStatus', normalizedMyPhone, 'peers');
    this.unsubTyping = onSnapshot(typingCol, (snapshot) => {
      const now = Date.now();
      const updated = { ...this.typingStatus };
      snapshot.forEach(docSnap => {
        const peer = docSnap.id;
        const d = docSnap.data();
        const isTyping = Boolean(d.isTyping);
        const timestamp = Number(d.timestamp) || 0;
        updated[peer] = isTyping && (now - timestamp < 5000);
      });
      this.typingStatus = updated;
      this.notifySubscribers();
    }, (err) => console.error('[ChatRepositoryWeb] Typing listener error:', err));
  }

  detachChatListeners() {
    if (this.unsubInbox) { this.unsubInbox(); this.unsubInbox = null; }
    if (this.unsubReceipts) { this.unsubReceipts(); this.unsubReceipts = null; }
    if (this.unsubTyping) { this.unsubTyping(); this.unsubTyping = null; }
  }

  // --- PROCESS INCOMING MESSAGE (ZERO-RETENTION STORE & FORWARD) ---
  async processIncomingMessage(dto, docRef) {
    if (!dto || !dto.messageId || !dto.ciphertext) {
      try { await deleteDoc(docRef); } catch {}
      return;
    }

    try {
      // 1. Decrypt ciphertext using NIST P-256 ECDH + AES-GCM
      const decryptedRaw = await chatCryptoWeb.decrypt(
        dto.ciphertext,
        dto.iv,
        dto.senderPublicKey
      );

      const senderNorm = normalizePhoneNumber(dto.senderNumber);
      const isCurrentPeer = (this.activeChatPeerNumber === senderNorm);

      // Handle EDIT message packet
      if (dto.mediaType === 'EDIT') {
        try {
          const parsed = JSON.parse(decryptedRaw);
          const originalId = parsed.originalMessageId;
          const newText = parsed.newText;
          if (originalId && newText) {
            const messages = this.getMessages(senderNorm);
            const targetMsg = messages.find(m => m.id === originalId);
            if (targetMsg) {
              try {
                const existingJson = JSON.parse(targetMsg.text);
                if (existingJson && existingJson.replyTo) {
                  existingJson.text = newText;
                  targetMsg.text = JSON.stringify(existingJson);
                } else {
                  targetMsg.text = newText;
                }
              } catch {
                targetMsg.text = newText;
              }
              targetMsg.isEdited = true;
              this.saveMessages(senderNorm, messages);
            }
            // Update conversation lastMessageText if it was this message
            const conversations = this.getConversations();
            const conv = conversations.find(c => numbersMatch(c.phoneNumber, senderNorm));
            if (conv) {
              conv.lastMessageText = newText;
              this.saveConversations(conversations);
            }
          }
        } catch (editErr) {
          console.warn('[ChatRepositoryWeb] Failed to parse EDIT payload:', editErr);
        }
        // Zero retention: delete from Firestore immediately
        try { await deleteDoc(docRef); } catch {}
        this.notifySubscribers();
        return;
      }

      // Handle DELETE message packet
      if (dto.mediaType === 'DELETE') {
        try {
          const parsed = JSON.parse(decryptedRaw);
          const targetMessageId = parsed.targetMessageId;
          if (targetMessageId) {
            const messages = this.getMessages(senderNorm);
            const targetMsg = messages.find(m => m.id === targetMessageId);
            if (targetMsg) {
              const tombstone = '🚫 This message was deleted';
              targetMsg.text = tombstone;
              targetMsg.mediaData = null;
              targetMsg.mediaPath = null;
              targetMsg.isEdited = false;
              this.saveMessages(senderNorm, messages);

              const conversations = this.getConversations();
              const conv = conversations.find(c => numbersMatch(c.phoneNumber, senderNorm));
              if (conv && conv.lastMessageTimestamp <= (targetMsg.timestamp || 0)) {
                conv.lastMessageText = tombstone;
                this.saveConversations(conversations);
              }
            }
          }
        } catch (delErr) {
          console.warn('[ChatRepositoryWeb] Failed to parse DELETE payload:', delErr);
        }
        try { await deleteDoc(docRef); } catch {}
        this.notifySubscribers();
        return;
      }

      // Handle CHUNK packet (large file transfer from Android / Web)
      if (dto.mediaType === 'CHUNK') {
        try {
          const parsed = JSON.parse(decryptedRaw);
          const { parentMessageId, fileName, chunkIndex, totalChunks, bytes, fileSize } = parsed;

          // 1. Save chunk into high-capacity IndexedDB + RAM buffer
          await mediaStorageWeb.saveChunk(parentMessageId, chunkIndex, totalChunks, bytes);

          // 2. Check if all chunks have arrived
          const assembledBytes = await mediaStorageWeb.checkAndAssembleChunks(parentMessageId, totalChunks);

          if (assembledBytes) {
            // Determine MIME type from file extension
            const ext = (fileName.split('.').pop() || '').toLowerCase();
            const mimeMap = {
              mp3: 'audio/mpeg',
              m4a: 'audio/mp4',
              wav: 'audio/wav',
              ogg: 'audio/ogg',
              pdf: 'application/pdf',
              doc: 'application/msword',
              docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
              zip: 'application/zip',
              apk: 'application/vnd.android.package-archive',
              jpg: 'image/jpeg',
              jpeg: 'image/jpeg',
              png: 'image/png'
            };
            const mimeType = mimeMap[ext] || 'application/octet-stream';
            const blob = new Blob([assembledBytes], { type: mimeType });
            await mediaStorageWeb.saveMedia(parentMessageId, blob, fileName, mimeType);
            await mediaStorageWeb.clearChunks(parentMessageId);
            const blobUrl = URL.createObjectURL(blob);

            const finalStatus = isCurrentPeer ? 'READ' : 'DELIVERED';
            const messageEntity = {
              id: parentMessageId,
              conversationId: senderNorm,
              senderNumber: dto.senderNumber,
              recipientNumber: dto.recipientNumber,
              text: fileName,
              mediaType: 'DOCUMENT',
              mediaData: `idb:${parentMessageId}`,
              mediaUrl: blobUrl,
              fileSize: fileSize || blob.size,
              fileName: fileName,
              mediaDurationMs: 0,
              timestamp: dto.timestamp || Date.now(),
              status: finalStatus,
              isOutgoing: false
            };

            const messages = this.getMessages(senderNorm);
            if (!messages.some(m => m.id === parentMessageId)) {
              messages.push(messageEntity);
              this.saveMessages(senderNorm, messages);
            }

            const conversations = this.getConversations();
            let conv = conversations.find(c => numbersMatch(c.phoneNumber, senderNorm));
            const unreadCount = isCurrentPeer ? 0 : ((conv ? conv.unreadCount : 0) + 1);
            if (!conv) {
              conv = {
                phoneNumber: senderNorm,
                contactName: senderNorm,
                profilePicUrl: '',
                lastMessageText: `📄 ${fileName}`,
                lastMessageType: 'DOCUMENT',
                lastMessageTimestamp: messageEntity.timestamp,
                lastMessageStatus: finalStatus,
                lastMessageIsOutgoing: false,
                unreadCount: unreadCount,
                isPinned: false
              };
              conversations.unshift(conv);
            } else {
              conv.lastMessageText = `📄 ${fileName}`;
              conv.lastMessageType = 'DOCUMENT';
              conv.lastMessageTimestamp = messageEntity.timestamp;
              conv.lastMessageStatus = finalStatus;
              conv.lastMessageIsOutgoing = false;
              conv.unreadCount = unreadCount;
            }
            this.saveConversations(conversations);

            // Send ACK receipt back to sender (DELIVERED or READ)
            this.sendReceipt(dto.senderNumber, parentMessageId, finalStatus);

            // Browser Notification if not looking at this chat
            const isWindowHidden = typeof document !== 'undefined' && document.hidden;
            if (!isCurrentPeer || isWindowHidden) {
              this.showBrowserNotification(senderNorm, `📄 ${fileName}`, '', senderNorm);
            }

            console.log(`[ChatRepositoryWeb] Reassembled and saved large document ${parentMessageId} (${fileName})`);
          }
        } catch (chunkErr) {
          console.warn('[ChatRepositoryWeb] Failed to parse CHUNK payload:', chunkErr);
        }
        try { await deleteDoc(docRef); } catch {}
        this.notifySubscribers();
        return;
      }

      let displayText = decryptedRaw;
      let mediaData = null;
      let durationMs = Number(dto.mediaDurationMs) || 0;
      let mediaUrl = null;

      if (dto.mediaType === 'IMAGE') {
        try {
          const parsed = JSON.parse(decryptedRaw);
          displayText = parsed.caption || 'Photo';
          mediaData = parsed.bytes || '';
        } catch {
          displayText = 'Photo';
        }
      } else if (dto.mediaType === 'AUDIO') {
        try {
          const parsed = JSON.parse(decryptedRaw);
          displayText = 'Voice message';
          durationMs = Number(parsed.duration) || durationMs;
          mediaData = parsed.bytes || '';
        } catch {
          displayText = 'Voice message';
        }
      } else if (dto.mediaType === 'DOCUMENT') {
        try {
          const parsed = JSON.parse(decryptedRaw);
          displayText = parsed.fileName || 'Document';
          if (parsed.bytes) {
            const ext = (displayText.split('.').pop() || '').toLowerCase();
            const mimeType = ext === 'mp3' ? 'audio/mpeg' : ext === 'pdf' ? 'application/pdf' : 'application/octet-stream';
            const blob = await mediaStorageWeb.saveMedia(dto.messageId, parsed.bytes, displayText, mimeType);
            mediaData = `idb:${dto.messageId}`;
            mediaUrl = URL.createObjectURL(blob);
          } else {
            mediaData = parsed.bytes || '';
          }
        } catch {
          displayText = 'Document';
        }
      }

      const finalStatus = isCurrentPeer ? 'READ' : 'DELIVERED';

      // 2. Persist locally to this peer's message store
      const messages = this.getMessages(senderNorm);

      // Implicit Read Sync: If peer sent a message, all earlier outgoing messages to them were seen/read!
      let msgsUpgraded = false;
      messages.forEach(m => {
        if (m.isOutgoing && m.status !== 'READ') {
          m.status = 'READ';
          msgsUpgraded = true;
        }
      });

      // Avoid duplicates
      if (!messages.some(m => m.id === dto.messageId)) {
        const newMessage = {
          id: dto.messageId,
          conversationId: senderNorm,
          senderNumber: dto.senderNumber,
          recipientNumber: dto.recipientNumber,
          text: displayText,
          mediaType: dto.mediaType || 'TEXT',
          mediaData,
          mediaUrl,
          mediaDurationMs: durationMs,
          timestamp: dto.timestamp || Date.now(),
          status: finalStatus,
          isOutgoing: false
        };
        messages.push(newMessage);
        this.saveMessages(senderNorm, messages);
      } else if (msgsUpgraded) {
        this.saveMessages(senderNorm, messages);
      }

      // 3. Resolve sender info from registered users / existing conversations
      const conversations = this.getConversations();
      const existingConv = conversations.find(c => numbersMatch(c.phoneNumber, senderNorm));
      
      let resolvedName = existingConv?.contactName || senderNorm;
      let profilePic = existingConv?.profilePicUrl || '';

      // Try fetching sender profile from users collection if unknown
      if (!existingConv || !existingConv.profilePicUrl) {
        try {
          const userSnap = await getDoc(doc(db, 'users', senderNorm));
          if (userSnap.exists()) {
            const uData = userSnap.data();
            if (uData.displayName) resolvedName = uData.displayName;
            if (uData.profilePictureUrl) profilePic = formatAvatarUrl(uData.profilePictureUrl);
          }
        } catch {}
      }

      const unreadCount = isCurrentPeer ? 0 : ((existingConv?.unreadCount || 0) + 1);

      const updatedConv = {
        phoneNumber: senderNorm,
        contactName: resolvedName,
        profilePicUrl: profilePic,
        lastMessageText: dto.mediaType === 'IMAGE' ? '📷 Photo' 
                       : dto.mediaType === 'AUDIO' ? '🎤 Voice message' 
                       : dto.mediaType === 'DOCUMENT' ? `📄 ${displayText}`
                       : displayText,
        lastMessageType: dto.mediaType || 'TEXT',
        lastMessageTimestamp: dto.timestamp || Date.now(),
        lastMessageStatus: finalStatus,
        lastMessageIsOutgoing: false,
        unreadCount,
        isPinned: existingConv?.isPinned || false
      };

      const remainingConvs = conversations.filter(c => !numbersMatch(c.phoneNumber, senderNorm));
      this.saveConversations([updatedConv, ...remainingConvs]);

      // 4. CRITICAL ZERO-RETENTION: IMMEDIATELY delete from Firestore!
      try {
        await deleteDoc(docRef);
        console.log(`[ChatRepositoryWeb] Zero-Retention: permanently deleted ephemeral message ${dto.messageId}`);
      } catch (delErr) {
        console.warn('Failed to delete message doc from relay:', delErr);
      }

      // 5. Send ACK receipt back to sender
      this.sendReceipt(dto.senderNumber, dto.messageId, finalStatus);

      // 6. Browser Notification if not looking at this chat (or tab is in background)
      const isWindowHidden = typeof document !== 'undefined' && document.hidden;
      if (!isCurrentPeer || isWindowHidden) {
        this.showBrowserNotification(resolvedName, displayText, profilePic, senderNorm);
      }

      this.notifySubscribers();

    } catch (e) {
      console.error('[ChatRepositoryWeb] Decryption or processing error:', e);
      // Clean up corrupted message so it does not loop
      try { await deleteDoc(docRef); } catch {}
    }
  }

  // --- PROCESS INCOMING RECEIPT (DELIVERY & READ TICKS) ---
  async processIncomingReceipt(receipt, docRef) {
    if (!receipt || !receipt.messageId) {
      try { await deleteDoc(docRef); } catch {}
      return;
    }

    try {
      // 1. Resolve peer:
      // Ephemeral receipts arrive in my inbox (receipt.recipientNumber === me).
      // The peer who generated the receipt is receipt.senderNumber.
      let peer = receipt.senderNumber;
      if (!peer || numbersMatch(peer, this.currentListeningPhone)) {
        peer = receipt.recipientNumber;
      }
      const peerNorm = normalizePhoneNumber(peer);

      const conversations = this.getConversations();
      let conv = conversations.find(c => numbersMatch(c.phoneNumber, peerNorm));
      let targetPhone = conv ? conv.phoneNumber : peerNorm;
      let messages = this.getMessages(targetPhone);

      let updated = false;

      if (receipt.messageId === 'all' || receipt.status === 'READ') {
        // Mark all outgoing messages with this peer as READ
        messages.forEach(m => {
          if (m.isOutgoing && m.status !== 'READ') {
            m.status = 'READ';
            updated = true;
          }
        });

        // Also if receipt is for a specific messageId, ensure that one is explicitly READ
        if (receipt.messageId !== 'all') {
          const specific = messages.find(m => m.id === receipt.messageId);
          if (specific && specific.status !== 'READ') {
            specific.status = 'READ';
            updated = true;
          }
        }
      } else {
        // DELIVERED receipt: update specific message if found and not already READ
        let msg = messages.find(m => m.id === receipt.messageId);

        // Fallback: search all conversations if not found in targetPhone
        if (!msg) {
          for (const c of conversations) {
            if (c.phoneNumber === targetPhone) continue;
            const otherMsgs = this.getMessages(c.phoneNumber);
            const found = otherMsgs.find(m => m.id === receipt.messageId);
            if (found) {
              conv = c;
              targetPhone = c.phoneNumber;
              messages = otherMsgs;
              msg = found;
              break;
            }
          }
        }

        if (msg) {
          if (msg.status === 'SENT' || !msg.status) {
            msg.status = receipt.status;
            updated = true;
          }
        }
      }

      // Fallback search across all conversations if still not updated and messageId != 'all'
      if (!updated && receipt.messageId !== 'all') {
        for (const c of conversations) {
          if (c.phoneNumber === targetPhone) continue;
          const otherMsgs = this.getMessages(c.phoneNumber);
          const found = otherMsgs.find(m => m.id === receipt.messageId);
          if (found) {
            if (receipt.status === 'READ') {
              otherMsgs.forEach(m => {
                if (m.isOutgoing && m.status !== 'READ') {
                  m.status = 'READ';
                }
              });
              found.status = 'READ';
            } else if (found.status === 'SENT' || !found.status) {
              found.status = receipt.status;
            }
            this.saveMessages(c.phoneNumber, otherMsgs);
            if (c.lastMessageIsOutgoing) {
              const last = otherMsgs[otherMsgs.length - 1];
              if (last && last.isOutgoing) {
                c.lastMessageStatus = last.status;
                this.saveConversations(conversations);
              }
            }
            updated = true;
            break;
          }
        }
      }

      if (updated) {
        this.saveMessages(targetPhone, messages);

        // Update conversation summary status
        if (conv && conv.lastMessageIsOutgoing) {
          const last = messages[messages.length - 1];
          if (last && last.isOutgoing) {
            conv.lastMessageStatus = last.status;
          } else {
            conv.lastMessageStatus = receipt.status;
          }
          this.saveConversations(conversations);
        }
      }

      // Delete receipt from Firestore immediately
      await deleteDoc(docRef);
      console.log(`[ChatRepositoryWeb] Processed and deleted receipt for message: ${receipt.messageId} (${receipt.status}) from peer ${peerNorm}`);

      this.notifySubscribers();

    } catch (e) {
      console.warn('[ChatRepositoryWeb] Error processing receipt:', e);
      try { await deleteDoc(docRef); } catch {}
    }
  }

  // --- SEND EPHEMERAL RECEIPT ---
  async sendReceipt(recipientNumber, messageId, status) {
    let myPhone = this.currentListeningPhone;
    if (!myPhone) {
      try {
        const stored = localStorage.getItem('lksDialerUser') || localStorage.getItem('lks_user');
        if (stored) {
          const u = JSON.parse(stored);
          myPhone = u.phoneNumber ? normalizePhoneNumber(u.phoneNumber) : null;
        }
      } catch {}
    }
    if (!myPhone || !recipientNumber) return;
    if (!this.currentListeningPhone) {
      this.currentListeningPhone = myPhone;
    }
    const normRecipient = normalizePhoneNumber(recipientNumber);
    let canonicalRecipient = normRecipient;
    try {
      const peer = await this.resolvePeerUser(recipientNumber);
      if (peer && peer.phoneNumber) {
        canonicalRecipient = peer.phoneNumber;
      }
    } catch {}

    const receiptId = generateUuid();

    const receiptDto = {
      receiptId,
      messageId,
      senderNumber: myPhone,
      recipientNumber: canonicalRecipient,
      status,
      timestamp: Date.now()
    };

    try {
      await setDoc(doc(db, 'receipts', canonicalRecipient, 'acks', receiptId), receiptDto);
    } catch (e) {
      console.warn('Failed to send receipt:', e);
    }
  }

  // --- PUBLIC API: SEND MESSAGE ---
  async sendMessage(recipientNumber, recipientName, text, mediaType = 'TEXT', mediaData = null, mediaDurationMs = 0) {
    if (!this.currentListeningPhone) {
      try {
        const stored = localStorage.getItem('lksDialerUser') || localStorage.getItem('lks_user');
        if (stored) {
          const u = JSON.parse(stored);
          this.currentListeningPhone = u.phoneNumber ? normalizePhoneNumber(u.phoneNumber) : null;
        }
      } catch {}
    }
    if (!this.currentListeningPhone) {
      throw new Error('Current user is not logged in');
    }

    const normRecipient = normalizePhoneNumber(recipientNumber);
    const peerUser = await this.resolvePeerUser(recipientNumber);
    const canonicalRecipient = peerUser?.phoneNumber || normRecipient;
    const recipientPublicKey = peerUser?.publicKey || await this.resolvePeerPublicKey(canonicalRecipient);

    if (!recipientPublicKey) {
      throw new Error('Recipient does not have E2EE key registered yet. They must log in to LKS Dialer first.');
    }

    const messageId = generateUuid();
    const now = Date.now();

    // Replying or sending confirms user has read all prior incoming messages from this recipient
    try {
      await this.markConversationAsRead(canonicalRecipient);
    } catch {}

    // 2. Large Document Chunking (> 400 KB)
    if (mediaType === 'DOCUMENT' && mediaData && mediaData.length > 400 * 1024) {
      const ext = (text.split('.').pop() || '').toLowerCase();
      const mimeMap = {
        mp3: 'audio/mpeg', m4a: 'audio/mp4', wav: 'audio/wav', ogg: 'audio/ogg',
        pdf: 'application/pdf', doc: 'application/msword',
        docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        zip: 'application/zip', apk: 'application/vnd.android.package-archive'
      };
      const mimeType = mimeMap[ext] || 'application/octet-stream';
      const blob = await mediaStorageWeb.saveMedia(messageId, mediaData, text, mimeType);
      const blobUrl = URL.createObjectURL(blob);

      // Save local message as SENT
      const localMsg = {
        id: messageId,
        conversationId: normRecipient,
        senderNumber: this.currentListeningPhone,
        recipientNumber: canonicalRecipient,
        text: text || 'Document',
        mediaType: 'DOCUMENT',
        mediaData: `idb:${messageId}`,
        mediaUrl: blobUrl,
        fileSize: blob.size,
        fileName: text,
        mediaDurationMs: 0,
        timestamp: now,
        status: 'SENT',
        isOutgoing: true
      };

      const messages = this.getMessages(normRecipient);
      messages.push(localMsg);
      this.saveMessages(normRecipient, messages);

      // Upsert conversation summary
      const conversations = this.getConversations();
      const existingConv = conversations.find(c => numbersMatch(c.phoneNumber, normRecipient));
      const updatedConv = {
        phoneNumber: normRecipient,
        contactName: recipientName || peerUser?.displayName || existingConv?.contactName || normRecipient,
        profilePicUrl: peerUser?.profilePictureUrl || existingConv?.profilePicUrl || '',
        lastMessageText: `📄 ${text || 'Document'}`,
        lastMessageType: 'DOCUMENT',
        lastMessageTimestamp: now,
        lastMessageStatus: 'SENT',
        lastMessageIsOutgoing: true,
        unreadCount: existingConv?.unreadCount || 0,
        isPinned: existingConv?.isPinned || false
      };
      const remainingConvs = conversations.filter(c => !numbersMatch(c.phoneNumber, normRecipient));
      this.saveConversations([updatedConv, ...remainingConvs]);
      this.notifySubscribers();

      // Chunk binary into 384 KB parts (matching Android's CHUNK protocol)
      const chunkSize = 384 * 1024;
      const cleanBase64 = mediaData.replace(/^data:.*?;base64,/, '').replace(/\s/g, '');
      const binary = atob(cleanBase64);
      const totalBytes = binary.length;
      const totalChunks = Math.ceil(totalBytes / chunkSize);
      const myPublicKey = await chatCryptoWeb.getMyPublicKeyBase64();

      try {
        for (let i = 0; i < totalChunks; i++) {
          const start = i * chunkSize;
          const end = Math.min(start + chunkSize, totalBytes);
          const sliceStr = binary.substring(start, end);
          const chunkBase64 = btoa(sliceStr);

          const chunkPayload = JSON.stringify({
            type: "FILE_CHUNK",
            parentMessageId: messageId,
            fileName: text || 'document',
            fileSize: totalBytes,
            chunkIndex: i,
            totalChunks: totalChunks,
            bytes: chunkBase64
          });

          const { ciphertext: chunkCiphertext, iv: chunkIv } = await chatCryptoWeb.encrypt(chunkPayload, recipientPublicKey);
          const chunkDocId = `${messageId}_chunk_${i}`;

          await setDoc(doc(db, 'inboxes', canonicalRecipient, 'messages', chunkDocId), {
            messageId: chunkDocId,
            senderNumber: this.currentListeningPhone,
            recipientNumber: canonicalRecipient,
            senderPublicKey: myPublicKey,
            ciphertext: chunkCiphertext,
            iv: chunkIv,
            mediaType: 'CHUNK',
            timestamp: now + i
          });
        }
        console.log(`[ChatRepositoryWeb] Uploaded ${totalChunks} chunks for document ${messageId} to ${canonicalRecipient}`);
      } catch (uploadErr) {
        console.error('Failed to upload document chunks:', uploadErr);
        localMsg.status = 'FAILED';
        this.saveMessages(normRecipient, messages);
        this.notifySubscribers();
        throw uploadErr;
      }

      this.sendFcmWakeup(
        canonicalRecipient,
        this.currentListeningPhone,
        `📄 ${text || 'Document'}`,
        'DOCUMENT',
        messageId
      );

      return localMsg;
    }

    // 2. Prepare Payload (standard messages / media <= 400 KB)
    let payloadToEncrypt = text || '';
    let savedMediaData = mediaData;
    let localMediaUrl = null;

    if (mediaType === 'IMAGE') {
      payloadToEncrypt = JSON.stringify({
        caption: text || '',
        bytes: mediaData || ''
      });
    } else if (mediaType === 'AUDIO') {
      payloadToEncrypt = JSON.stringify({
        duration: mediaDurationMs || 0,
        bytes: mediaData || ''
      });
    } else if (mediaType === 'DOCUMENT') {
      payloadToEncrypt = JSON.stringify({
        fileName: text || 'document',
        bytes: mediaData || ''
      });
      if (mediaData) {
        const ext = (text.split('.').pop() || '').toLowerCase();
        const mimeType = ext === 'mp3' ? 'audio/mpeg' : ext === 'pdf' ? 'application/pdf' : 'application/octet-stream';
        const blob = await mediaStorageWeb.saveMedia(messageId, mediaData, text, mimeType);
        savedMediaData = `idb:${messageId}`;
        localMediaUrl = URL.createObjectURL(blob);
      }
    }

    // 3. Encrypt via ChatCryptoWeb
    const { ciphertext, iv } = await chatCryptoWeb.encrypt(payloadToEncrypt, recipientPublicKey);
    const myPublicKey = await chatCryptoWeb.getMyPublicKeyBase64();

    // 4. Save to local message store as SENT (✓)
    const localMsg = {
      id: messageId,
      conversationId: normRecipient,
      senderNumber: this.currentListeningPhone,
      recipientNumber: canonicalRecipient,
      text: text || '',
      mediaType,
      mediaData: savedMediaData,
      mediaUrl: localMediaUrl,
      mediaDurationMs,
      timestamp: now,
      status: 'SENT',
      isOutgoing: true
    };

    const messages = this.getMessages(normRecipient);
    messages.push(localMsg);
    this.saveMessages(normRecipient, messages);

    // Upsert conversation summary
    const conversations = this.getConversations();
    const existingConv = conversations.find(c => numbersMatch(c.phoneNumber, normRecipient));
    const updatedConv = {
      phoneNumber: normRecipient,
      contactName: recipientName || peerUser?.displayName || existingConv?.contactName || normRecipient,
      profilePicUrl: peerUser?.profilePictureUrl || existingConv?.profilePicUrl || '',
      lastMessageText: mediaType === 'IMAGE' ? '📷 Photo'
                     : mediaType === 'AUDIO' ? '🎤 Voice message'
                     : mediaType === 'DOCUMENT' ? `📄 ${text || 'Document'}`
                     : text,
      lastMessageType: mediaType,
      lastMessageTimestamp: now,
      lastMessageStatus: 'SENT',
      lastMessageIsOutgoing: true,
      unreadCount: existingConv?.unreadCount || 0,
      isPinned: existingConv?.isPinned || false
    };

    const remainingConvs = conversations.filter(c => !numbersMatch(c.phoneNumber, normRecipient));
    this.saveConversations([updatedConv, ...remainingConvs]);
    this.notifySubscribers();

    // 5. Post to recipient's ephemeral inbox in Firestore
    const chatDto = {
      messageId,
      senderNumber: this.currentListeningPhone,
      recipientNumber: canonicalRecipient,
      senderPublicKey: myPublicKey,
      ciphertext,
      iv,
      mediaType,
      mediaDurationMs,
      timestamp: now
    };

    try {
      await setDoc(doc(db, 'inboxes', canonicalRecipient, 'messages', messageId), chatDto);
      console.log(`[ChatRepositoryWeb] Message ${messageId} posted to ephemeral inbox for ${canonicalRecipient}`);
    } catch (uploadErr) {
      console.error('Failed to post message to ephemeral relay:', uploadErr);
      localMsg.status = 'FAILED';
      this.saveMessages(normRecipient, messages);
      this.notifySubscribers();
      throw uploadErr;
    }

    // 6. Send FCM wakeup push notification via Cloudflare Worker
    const displayPreview = when => {
      if (mediaType === 'IMAGE') return text || '📷 Photo';
      if (mediaType === 'AUDIO') return '🎤 Voice message';
      if (mediaType === 'DOCUMENT') return `📄 ${text || 'Document'}`;
      return text || 'New message';
    };
    this.sendFcmWakeup(
      canonicalRecipient,
      this.currentListeningPhone,
      displayPreview(),
      mediaType,
      messageId
    );

    return localMsg;
  }

  // --- PUBLIC API: EDIT MESSAGE ---
  async editMessage(originalMessageId, newText, recipientNumber) {
    let myPhone = this.currentListeningPhone;
    if (!myPhone) {
      try {
        const stored = localStorage.getItem('lksDialerUser') || localStorage.getItem('lks_user');
        if (stored) {
          const u = JSON.parse(stored);
          myPhone = u.phoneNumber ? normalizePhoneNumber(u.phoneNumber) : null;
        }
      } catch {}
    }
    if (!myPhone) {
      throw new Error('Current user is not logged in');
    }

    const normRecipient = normalizePhoneNumber(recipientNumber);
    const peerUser = await this.resolvePeerUser(recipientNumber);
    const canonicalRecipient = peerUser?.phoneNumber || normRecipient;

    let messages = this.getMessages(normRecipient);
    let targetMsg = messages.find(m => m.id === originalMessageId);
    let targetPhone = normRecipient;
    if (!targetMsg) {
      const conversations = this.getConversations();
      const conv = conversations.find(c => numbersMatch(c.phoneNumber, normRecipient));
      if (conv) {
        messages = this.getMessages(conv.phoneNumber);
        targetMsg = messages.find(m => m.id === originalMessageId);
        targetPhone = conv.phoneNumber;
      }
    }
    if (!targetMsg) {
      throw new Error('Message not found');
    }

    const now = Date.now();
    if (targetMsg.timestamp && (now - targetMsg.timestamp > 10 * 60 * 1000)) {
      throw new Error('Editing allowed only within 10 minutes');
    }

    // 1. Update locally
    let textToStore = newText;
    try {
      const existingJson = JSON.parse(targetMsg.text);
      if (existingJson && existingJson.replyTo) {
        existingJson.text = newText;
        textToStore = JSON.stringify(existingJson);
      }
    } catch {}
    targetMsg.text = textToStore;
    targetMsg.isEdited = true;
    this.saveMessages(targetPhone, messages);

    // Update conversation if this was the last message
    const conversations = this.getConversations();
    const conv = conversations.find(c => numbersMatch(c.phoneNumber, normRecipient));
    if (conv) {
      const last = messages[messages.length - 1];
      if (last && last.id === originalMessageId) {
        conv.lastMessageText = newText;
        this.saveConversations(conversations);
      }
    }
    this.notifySubscribers();

    // 2. Transmit edit packet over ephemeral relay
    const editPayload = JSON.stringify({
      type: 'MESSAGE_EDIT',
      originalMessageId,
      newText,
      editedAt: now
    });

    const recipientPublicKey = peerUser?.publicKey || await this.resolvePeerPublicKey(canonicalRecipient);
    if (!recipientPublicKey) {
      return; // local edit done
    }

    const { ciphertext, iv } = await chatCryptoWeb.encrypt(editPayload, recipientPublicKey);
    const myPublicKey = await chatCryptoWeb.getMyPublicKeyBase64();
    const editPacketId = generateUuid();

    const editDto = {
      messageId: editPacketId,
      senderNumber: myPhone,
      recipientNumber: canonicalRecipient,
      senderPublicKey: myPublicKey,
      ciphertext,
      iv,
      mediaType: 'EDIT',
      timestamp: now,
      isEncrypted: true
    };

    try {
      await setDoc(doc(db, 'inboxes', canonicalRecipient, 'messages', editPacketId), editDto);
      console.log(`[ChatRepositoryWeb] Edit packet ${editPacketId} uploaded for ${canonicalRecipient}`);
    } catch (e) {
      console.warn('Failed to upload edit packet:', e);
    }

    // 3. Send FCM wakeup push
    this.sendFcmWakeup(canonicalRecipient, myPhone, newText, 'EDIT', editPacketId);
  }

  // --- PUBLIC API: DELETE MESSAGE FOR EVERYONE ---
  async deleteMessageForEveryone(originalMessageId, recipientNumber) {
    const normRecipient = normalizePhoneNumber(recipientNumber);
    const peerUser = await this.resolvePeerUser(recipientNumber);
    const canonicalRecipient = peerUser?.phoneNumber || normRecipient;

    const messages = this.getMessages(normRecipient);
    const targetMsg = messages.find(m => m.id === originalMessageId);
    if (!targetMsg) {
      throw new Error('Message not found');
    }
    if (!targetMsg.isOutgoing) {
      throw new Error('Cannot delete incoming messages for everyone');
    }

    const tombstone = '🚫 You deleted this message';
    targetMsg.text = tombstone;
    targetMsg.mediaData = null;
    targetMsg.mediaPath = null;
    targetMsg.isEdited = false;
    this.saveMessages(normRecipient, messages);

    // Update conversation if needed
    const conversations = this.getConversations();
    const conv = conversations.find(c => numbersMatch(c.phoneNumber, normRecipient));
    if (conv && (conv.lastMessageTimestamp <= (targetMsg.timestamp || 0) || conv.lastMessageId === originalMessageId)) {
      conv.lastMessageText = tombstone;
      this.saveConversations(conversations);
    }
    this.notifySubscribers();

    const myPhone = this.currentListeningPhone;
    if (!myPhone) return;

    try {
      const recipientPublicKey = peerUser?.publicKey || await this.resolvePeerPublicKey(canonicalRecipient);
      if (!recipientPublicKey) return;

      const deletePayload = JSON.stringify({
        type: 'MESSAGE_DELETE',
        targetMessageId: originalMessageId,
        deletedAt: Date.now()
      });

      const { ciphertext, iv } = await chatCryptoWeb.encrypt(deletePayload, recipientPublicKey);
      const myPublicKey = await chatCryptoWeb.getMyPublicKeyBase64();
      const deletePacketId = generateUuid();

      const deleteDto = {
        messageId: deletePacketId,
        senderNumber: myPhone,
        recipientNumber: canonicalRecipient,
        senderPublicKey: myPublicKey,
        ciphertext,
        iv,
        mediaType: 'DELETE',
        timestamp: Date.now(),
        isEncrypted: true
      };

      await setDoc(doc(db, 'inboxes', canonicalRecipient, 'messages', deletePacketId), deleteDto);
      console.log(`[ChatRepositoryWeb] Delete packet ${deletePacketId} uploaded for ${canonicalRecipient}`);

      this.sendFcmWakeup(canonicalRecipient, myPhone, '', 'DELETE', deletePacketId);
    } catch (e) {
      console.warn('Failed to upload delete packet:', e);
    }
  }

  // --- PUBLIC API: DELETE MESSAGE LOCALLY (Delete for me) ---
  deleteMessageLocally(messageId, peerPhoneNumber) {
    const norm = normalizePhoneNumber(peerPhoneNumber);
    const messages = this.getMessages(norm);
    const filtered = messages.filter(m => m.id !== messageId);
    this.saveMessages(norm, filtered);

    // Update conversation if needed
    const conversations = this.getConversations();
    const conv = conversations.find(c => numbersMatch(c.phoneNumber, norm));
    if (conv) {
      if (filtered.length > 0) {
        const last = filtered[filtered.length - 1];
        conv.lastMessageText = last.text;
        conv.lastMessageType = last.mediaType;
        conv.lastMessageTimestamp = last.timestamp;
        conv.lastMessageStatus = last.status;
        conv.lastMessageIsOutgoing = last.isOutgoing;
      } else {
        conv.lastMessageText = '';
        conv.lastMessageType = 'TEXT';
      }
      this.saveConversations(conversations);
    }
    this.notifySubscribers();
  }

  // --- RESOLVE PEER USER & CANONICAL NUMBER ---
  async resolvePeerUser(phoneNumber) {
    if (!phoneNumber) return null;
    const norm = normalizePhoneNumber(phoneNumber);
    const cleanDigits = String(phoneNumber).replace(/[^0-9]/g, '');
    const variations = [norm, phoneNumber];
    if (cleanDigits) {
      variations.push(cleanDigits);
      if (cleanDigits.length > 10) {
        variations.push(cleanDigits.slice(-10));
      }
      if (!String(phoneNumber).startsWith('+')) {
        variations.push('+' + cleanDigits);
      }
    }
    const distinctVariations = Array.from(new Set(variations)).slice(0, 10);

    try {
      const q = query(collection(db, 'users'), where('phoneNumber', 'in', distinctVariations));
      const snapshot = await getDocs(q);
      if (!snapshot.empty) {
        const docSnap = snapshot.docs[0];
        const d = docSnap.data();
        return {
          id: docSnap.id,
          phoneNumber: d.phoneNumber || docSnap.id,
          ...d
        };
      }
    } catch (e) {
      console.warn("resolvePeerUser variation query failed:", e);
    }

    try {
      const userSnap = await getDoc(doc(db, 'users', norm));
      if (userSnap.exists()) {
        const d = userSnap.data();
        return {
          id: userSnap.id,
          phoneNumber: d.phoneNumber || userSnap.id,
          ...d
        };
      }
    } catch (e) {
      console.warn("Direct lookup failed:", e);
    }
    return null;
  }

  // --- RESOLVE PEER PUBLIC KEY ---
  async resolvePeerPublicKey(phoneNumber) {
    try {
      const user = await this.resolvePeerUser(phoneNumber);
      if (user && user.publicKey && typeof user.publicKey === 'string' && user.publicKey.trim().length > 0) {
        return user.publicKey.trim();
      }
    } catch (e) {
      console.warn(`Failed to resolve public key for ${phoneNumber}:`, e);
    }
    return null;
  }

  // --- CLOUDFLARE WORKER FCM WAKEUP ---
  async sendFcmWakeup(recipientPhone, senderPhone, previewText, mediaType = 'TEXT', messageId = '') {
    const url = "https://lks-dialer-call-notifier.subhojit.workers.dev/call";
    try {
      const callee = await this.resolvePeerUser(recipientPhone);
      if (!callee) return;

      // Retrieve sender name and profile picture
      let senderName = senderPhone;
      let callerProfilePic = '';
      try {
        const mySnap = await getDoc(doc(db, 'users', senderPhone));
        if (mySnap.exists()) {
          const myData = mySnap.data();
          senderName = myData.displayName || senderPhone;
          callerProfilePic = myData.profilePictureUrl || '';
        }
      } catch {}

      await fetch(url, {
        method: "POST",
        headers: { 
          "Content-Type": "application/json", 
          "X-Worker-Secret": "LKS_DIALER_EsA2u7uNJMiE0ZhbtRUnzs7tkZPe4WvJ" 
        },
        body: JSON.stringify({
          token: callee.fcmToken || null,
          webToken: callee.webToken || null,
          callerName: senderName,
          callerNumber: senderPhone,
          callerProfilePic: callerProfilePic,
          type: "chat_message",
          messageText: previewText,
          messagePreview: previewText,
          mediaType: mediaType,
          messageId: messageId
        })
      });
      console.log(`[ChatRepositoryWeb] FCM chat wakeup triggered for ${callee.phoneNumber || recipientPhone}`);
    } catch (e) {
      console.warn('Failed to send FCM chat wakeup push:', e);
    }
  }

  // --- MARK CONVERSATION AS READ ---
  async markConversationAsRead(peerPhoneNumber) {
    const norm = normalizePhoneNumber(peerPhoneNumber);
    const conversations = this.getConversations();
    const conv = conversations.find(c => numbersMatch(c.phoneNumber, norm));
    if (conv && conv.unreadCount > 0) {
      conv.unreadCount = 0;
      this.saveConversations(conversations);
      this.notifySubscribers();
    }

    // Mark local incoming messages as READ
    const targetPhone = conv ? conv.phoneNumber : norm;
    const messages = this.getMessages(targetPhone);
    let msgsUpdated = false;
    messages.forEach(m => {
      if (!m.isOutgoing && m.status !== 'READ') {
        m.status = 'READ';
        msgsUpdated = true;
      }
    });
    if (msgsUpdated) {
      this.saveMessages(targetPhone, messages);
      this.notifySubscribers();
    }

    // Send read receipt with messageId = 'all'
    this.sendReceipt(targetPhone, 'all', 'READ');
  }

  // --- TYPING INDICATORS ---
  setTyping(peerPhoneNumber, isTyping) {
    if (!this.currentListeningPhone || !peerPhoneNumber) return;
    const norm = normalizePhoneNumber(peerPhoneNumber);

    // Debounce clearing typing state
    if (this.typingTimeouts[norm]) {
      clearTimeout(this.typingTimeouts[norm]);
      delete this.typingTimeouts[norm];
    }

    setDoc(doc(db, 'typingStatus', norm, 'peers', this.currentListeningPhone), {
      isTyping: Boolean(isTyping),
      timestamp: Date.now()
    }).catch(() => {});

    if (isTyping) {
      this.typingTimeouts[norm] = setTimeout(() => {
        setDoc(doc(db, 'typingStatus', norm, 'peers', this.currentListeningPhone), {
          isTyping: false,
          timestamp: Date.now()
        }).catch(() => {});
      }, 3000);
    }
  }

  // --- BROWSER NOTIFICATIONS ---
  showBrowserNotification(senderName, text, avatarUrl) {
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
    try {
      const notif = new Notification(senderName || 'LKS Chat', {
        body: text,
        icon: (avatarUrl && avatarUrl.startsWith('http')) ? avatarUrl : '/logo192.png',
        tag: `chat_${senderName}`
      });
      notif.onclick = () => {
        window.focus();
        notif.close();
      };
    } catch (e) {
      console.warn('Failed to trigger browser notification:', e);
    }
  }

  // --- DELETE & CLEAR ---
  clearChat(peerPhoneNumber) {
    const norm = normalizePhoneNumber(peerPhoneNumber);
    try {
      localStorage.removeItem(`lks_web_chat_messages_${norm}`);
      const convs = this.getConversations().filter(c => !numbersMatch(c.phoneNumber, norm));
      this.saveConversations(convs);
      this.notifySubscribers();
    } catch (e) {
      console.error('Failed to clear chat:', e);
    }
  }

  deleteMessage(peerPhoneNumber, messageId) {
    const norm = normalizePhoneNumber(peerPhoneNumber);
    const messages = this.getMessages(norm).filter(m => m.id !== messageId);
    this.saveMessages(norm, messages);

    // Update conversation last message if needed
    const convs = this.getConversations();
    const conv = convs.find(c => numbersMatch(c.phoneNumber, norm));
    if (conv) {
      const last = messages[messages.length - 1];
      if (last) {
        conv.lastMessageText = last.mediaType === 'IMAGE' ? '📷 Photo'
                             : last.mediaType === 'AUDIO' ? '🎤 Voice message'
                             : last.text;
        conv.lastMessageType = last.mediaType;
        conv.lastMessageTimestamp = last.timestamp;
        conv.lastMessageStatus = last.status;
        conv.lastMessageIsOutgoing = last.isOutgoing;
      } else {
        conv.lastMessageText = 'No messages';
      }
      this.saveConversations(convs);
    }
    this.notifySubscribers();
  }

  // --- BROWSER NOTIFICATIONS ---
  showBrowserNotification(senderName, messageText, profilePic, peerNumber = '') {
    try {
      if (typeof window === 'undefined' || !("Notification" in window)) return;
      if (Notification.permission !== "granted") return;

      const title = senderName || peerNumber || "New Message";
      const iconUrl = (profilePic && (profilePic.startsWith('http') || profilePic.startsWith('data:image'))) 
        ? profilePic 
        : '/icon-192.png';

      let cleanBody = 'New message';
      if (typeof messageText === 'string') {
        try {
          const parsed = JSON.parse(messageText);
          cleanBody = parsed.text || parsed.caption || parsed.fileName || messageText;
        } catch {
          cleanBody = messageText;
        }
      }
      if (cleanBody.length > 80) {
        cleanBody = cleanBody.substring(0, 77) + '...';
      }

      const targetPeer = peerNumber || senderName || '';
      const options = {
        body: cleanBody,
        icon: iconUrl,
        badge: '/icon-192.png',
        tag: `chat_${targetPeer}`,
        renotify: true,
        vibrate: [200, 100, 200],
        data: {
          url: `/?tab=chats&peer=${encodeURIComponent(targetPeer)}`,
          peerNumber: targetPeer,
          callerName: senderName,
          type: 'chat_message'
        }
      };

      // 1. Try ServiceWorker Registration (works on mobile PWA & desktop)
      if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
        navigator.serviceWorker.ready.then(reg => {
          reg.showNotification(title, options);
        }).catch(() => {
          this._fallbackWindowNotification(title, options);
        });
      } else {
        this._fallbackWindowNotification(title, options);
      }
    } catch (e) {
      console.warn('[ChatRepositoryWeb] showBrowserNotification error:', e);
    }
  }

  _fallbackWindowNotification(title, options) {
    try {
      const notif = new Notification(title, options);
      notif.onclick = () => {
        window.focus();
        notif.close();
      };
    } catch (e) {
      console.warn('[ChatRepositoryWeb] Fallback window notification failed:', e);
    }
  }
}

export const chatRepositoryWeb = new ChatRepositoryWeb();
