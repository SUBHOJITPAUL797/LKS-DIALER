import { db } from './firebase';
import { 
  collection, doc, setDoc, getDoc, deleteDoc, onSnapshot 
} from 'firebase/firestore';
import { chatCryptoWeb } from './ChatCryptoWeb';
import { formatAvatarUrl } from './ImageUtils';

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

      let displayText = decryptedRaw;
      let mediaData = null;
      let durationMs = Number(dto.mediaDurationMs) || 0;

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
          mediaData = parsed.bytes || '';
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

      // 6. Browser Notification if not in foreground on this chat
      if (!isCurrentPeer) {
        this.showBrowserNotification(resolvedName, displayText, profilePic);
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
    if (!this.currentListeningPhone || !recipientNumber) return;
    const normRecipient = normalizePhoneNumber(recipientNumber);
    const receiptId = generateUuid();

    const receiptDto = {
      receiptId,
      messageId,
      senderNumber: this.currentListeningPhone,
      recipientNumber: normRecipient,
      status,
      timestamp: Date.now()
    };

    try {
      await setDoc(doc(db, 'receipts', normRecipient, 'acks', receiptId), receiptDto);
    } catch (e) {
      console.warn('Failed to send receipt:', e);
    }
  }

  // --- PUBLIC API: SEND MESSAGE ---
  async sendMessage(recipientNumber, recipientName, text, mediaType = 'TEXT', mediaData = null, mediaDurationMs = 0) {
    if (!this.currentListeningPhone) {
      throw new Error('Current user is not logged in');
    }

    const normRecipient = normalizePhoneNumber(recipientNumber);
    const messageId = generateUuid();
    const now = Date.now();

    // 1. Resolve Recipient's NIST P-256 Public Key
    const recipientPublicKey = await this.resolvePeerPublicKey(normRecipient);
    if (!recipientPublicKey) {
      throw new Error('Recipient does not have E2EE key registered yet. They must log in to LKS Dialer first.');
    }

    // 2. Prepare Payload
    let payloadToEncrypt = text || '';
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
    }

    // 3. Encrypt via ChatCryptoWeb
    const { ciphertext, iv } = await chatCryptoWeb.encrypt(payloadToEncrypt, recipientPublicKey);
    const myPublicKey = await chatCryptoWeb.getMyPublicKeyBase64();

    // 4. Save to local message store as SENT (✓)
    const localMsg = {
      id: messageId,
      conversationId: normRecipient,
      senderNumber: this.currentListeningPhone,
      recipientNumber: normRecipient,
      text: text || '',
      mediaType,
      mediaData,
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
      contactName: recipientName || existingConv?.contactName || normRecipient,
      profilePicUrl: existingConv?.profilePicUrl || '',
      lastMessageText: mediaType === 'IMAGE' ? '📷 Photo'
                     : mediaType === 'AUDIO' ? '🎤 Voice message'
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
      recipientNumber: normRecipient,
      senderPublicKey: myPublicKey,
      ciphertext,
      iv,
      mediaType,
      mediaDurationMs,
      timestamp: now
    };

    try {
      await setDoc(doc(db, 'inboxes', normRecipient, 'messages', messageId), chatDto);
      console.log(`[ChatRepositoryWeb] Message ${messageId} posted to ephemeral inbox for ${normRecipient}`);
    } catch (uploadErr) {
      console.error('Failed to post message to ephemeral relay:', uploadErr);
      localMsg.status = 'FAILED';
      this.saveMessages(normRecipient, messages);
      this.notifySubscribers();
      throw uploadErr;
    }

    // 6. Send FCM wakeup push notification via Cloudflare Worker
    this.sendFcmWakeup(normRecipient, this.currentListeningPhone, text || (mediaType === 'IMAGE' ? 'Photo' : 'Voice message'));

    return localMsg;
  }

  // --- PUBLIC API: EDIT MESSAGE ---
  async editMessage(originalMessageId, newText, recipientNumber) {
    if (!this.currentListeningPhone) {
      throw new Error('Current user is not logged in');
    }

    const normRecipient = normalizePhoneNumber(recipientNumber);
    const messages = this.getMessages(normRecipient);
    const targetMsg = messages.find(m => m.id === originalMessageId);
    if (!targetMsg) {
      throw new Error('Message not found');
    }

    const now = Date.now();
    if (now - (targetMsg.timestamp || 0) > 10 * 60 * 1000) {
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
    this.saveMessages(normRecipient, messages);

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

    const recipientPublicKey = await this.resolvePeerPublicKey(normRecipient);
    if (!recipientPublicKey) {
      return; // local edit done
    }

    const { ciphertext, iv } = await chatCryptoWeb.encrypt(editPayload, recipientPublicKey);
    const myPublicKey = await chatCryptoWeb.getMyPublicKeyBase64();
    const editPacketId = generateUuid();

    const editDto = {
      messageId: editPacketId,
      senderNumber: this.currentListeningPhone,
      recipientNumber: normRecipient,
      senderPublicKey: myPublicKey,
      ciphertext,
      iv,
      mediaType: 'EDIT',
      timestamp: now,
      isEncrypted: true
    };

    try {
      await setDoc(doc(db, 'inboxes', normRecipient, 'messages', editPacketId), editDto);
    } catch (e) {
      console.warn('Failed to upload edit packet:', e);
    }

    // 3. Send FCM wakeup push
    this.sendFcmWakeup(normRecipient, this.currentListeningPhone, newText);
  }

  // --- RESOLVE PEER PUBLIC KEY ---
  async resolvePeerPublicKey(phoneNumber) {
    const norm = normalizePhoneNumber(phoneNumber);
    try {
      const userSnap = await getDoc(doc(db, 'users', norm));
      if (userSnap.exists()) {
        const data = userSnap.data();
        if (data.publicKey && typeof data.publicKey === 'string' && data.publicKey.trim().length > 0) {
          return data.publicKey.trim();
        }
      }
    } catch (e) {
      console.warn(`Failed to resolve public key for ${norm}:`, e);
    }
    return null;
  }

  // --- CLOUDFLARE WORKER FCM WAKEUP ---
  async sendFcmWakeup(recipientPhone, senderPhone, previewText) {
    const url = "https://lks-dialer-call-notifier.subhojit.workers.dev/call";
    try {
      const userSnap = await getDoc(doc(db, 'users', recipientPhone));
      if (!userSnap.exists()) return;
      const callee = userSnap.data();

      // Retrieve sender name
      const mySnap = await getDoc(doc(db, 'users', senderPhone));
      const senderName = mySnap.exists() ? (mySnap.data().displayName || senderPhone) : senderPhone;

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
          type: "chat_message",
          messagePreview: previewText
        })
      });
      console.log(`[ChatRepositoryWeb] FCM chat wakeup triggered for ${recipientPhone}`);
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
}

export const chatRepositoryWeb = new ChatRepositoryWeb();
