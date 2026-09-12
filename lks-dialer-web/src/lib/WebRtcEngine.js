import { db, messaging } from './firebase';
import { getToken } from 'firebase/messaging';
import { 
  collection, doc, setDoc, getDoc, updateDoc, onSnapshot, 
  query, where, getDocs, addDoc, serverTimestamp 
} from 'firebase/firestore';
import { callSounds } from './CallSounds';
import { formatAvatarUrl } from './ImageUtils';
import { chatCryptoWeb } from './ChatCryptoWeb';
import { numbersMatch } from './ChatRepositoryWeb';

const servers = {
  iceServers: [
    { urls: ['stun:stun1.l.google.com:19302', 'stun:stun2.l.google.com:19302'] },
    {
      urls: [
        'turn:a.relay.metered.ca:80',
        'turn:a.relay.metered.ca:80?transport=tcp',
        'turn:a.relay.metered.ca:443',
        'turn:a.relay.metered.ca:443?transport=tcp',
        'turns:a.relay.metered.ca:443',
        'turns:a.relay.metered.ca:443?transport=tcp'
      ],
      username: 'openrelayproject',
      credential: 'openrelayproject'
    }
  ],
  iceCandidatePoolSize: 10,
};

export const PRESENCE_TIMEOUT_MS = 40000; // 40s timeout for presence staleness (matches Android)

/**
 * Checks if a user is truly online:
 * Must have isOnline == true AND lastSeen within the last 40 seconds.
 */
export function isUserOnline(user, currentMs = Date.now()) {
  if (!user) return false;
  const isOnline = user.isOnline === true || user.online === true;
  if (!isOnline) return false;
  const lastSeen = user.lastSeen?.toMillis 
    ? user.lastSeen.toMillis() 
    : (user.lastSeen?.seconds ? user.lastSeen.seconds * 1000 : Number(user.lastSeen));
  if (!lastSeen || isNaN(lastSeen) || lastSeen <= 0) return false;
  return (currentMs - lastSeen) < PRESENCE_TIMEOUT_MS;
}

/**
 * Formats lastSeen timestamp into human-readable WhatsApp-style label:
 * e.g. "last seen just now", "last seen 1m ago", "last seen today at 11:42 AM", "last seen yesterday at 3:15 PM"
 */
export function formatLastSeen(lastSeenMs, currentMs = Date.now()) {
  if (!lastSeenMs) return '';
  const ms = lastSeenMs?.toMillis 
    ? lastSeenMs.toMillis() 
    : (lastSeenMs?.seconds ? lastSeenMs.seconds * 1000 : Number(lastSeenMs));
  if (isNaN(ms) || ms <= 0) return '';
  const diff = currentMs - ms;
  if (diff < 0 || diff < 60000) return 'last seen just now';
  if (diff < 120000) return 'last seen 1m ago';
  if (diff < 3600000) return `last seen ${Math.floor(diff / 60000)}m ago`;

  const seenDate = new Date(ms);
  const nowDate = new Date(currentMs);
  
  const timeStr = seenDate.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', hour12: true });

  const isToday = seenDate.toDateString() === nowDate.toDateString();
  if (isToday) {
    return `last seen today at ${timeStr}`;
  }

  const yesterday = new Date(nowDate);
  yesterday.setDate(nowDate.getDate() - 1);
  const isYesterday = seenDate.toDateString() === yesterday.toDateString();
  if (isYesterday) {
    return `last seen yesterday at ${timeStr}`;
  }

  const dateStr = seenDate.toLocaleDateString([], { month: 'short', day: 'numeric' });
  return `last seen ${dateStr} at ${timeStr}`;
}

class WebRtcEngine {
  constructor() {
    this.peerConnection = null;
    this.localStream = null;
    this.remoteStream = new MediaStream();
    this.activeCallId = null;
    this.currentUser = null;
    this.isFrontCamera = true;
    this.iceServers = servers;
    
    // Presence & heartbeat properties
    this.presenceInterval = null;
    this.presenceGraceTimer = null;
    this.boundVisibilityHandler = null;
    this.boundUnloadHandler = null;
    
    // Callbacks for UI updates
    this.onCallStateChange = null;
    this.onLocalStream = null;
    this.onRemoteStream = null;
    
    this.callUnsubscribers = [];
    this.fetchIceServers();
  }

  async fetchIceServers() {
    try {
      const res = await fetch("https://lks-dialer-call-notifier.subhojit.workers.dev/turn-credentials", {
        headers: { "X-Worker-Secret": "LKS_DIALER_EsA2u7uNJMiE0ZhbtRUnzs7tkZPe4WvJ" }
      });
      if (res.ok) {
        const data = await res.json();
        if (data.iceServers && data.iceServers.length > 0) {
          this.iceServers = { iceServers: data.iceServers, iceCandidatePoolSize: 10 };
          console.log("✅ Web dynamic TURN servers loaded from Worker:", data.iceServers.length);
        }
      }
    } catch (e) {
      console.warn("Failed to fetch dynamic TURN from worker, using defaults:", e);
    }
  }

  setCurrentUser(user) {
    if (user) {
      if (!user.blockedNumbers) user.blockedNumbers = [];
    }
    this.currentUser = user;
    this.syncPublicKey();
    if (user && user.phoneNumber) {
      this.startPresenceHeartbeat();
    }
  }

  /**
   * Updates user's online presence and lastSeen timestamp in Firestore.
   */
  async updateUserPresence(isOnline) {
    if (!this.currentUser || !this.currentUser.phoneNumber) return;
    const phone = this.currentUser.phoneNumber;
    const now = Date.now();
    const payload = {
      phoneNumber: phone,
      isOnline: Boolean(isOnline),
      online: Boolean(isOnline),
      lastSeen: now
    };
    try {
      const userRef = doc(db, 'users', phone);
      await setDoc(userRef, payload, { merge: true });
    } catch (e) {
      console.warn('Failed to update presence:', e);
    }
  }

  /**
   * Starts periodic presence heartbeat while user is active on the site.
   * Heartbeat sends every 20 seconds.
   * On visibility hidden, waits 10s grace before marking offline.
   * On page unload, marks offline immediately.
   */
  startPresenceHeartbeat() {
    this.stopPresenceHeartbeat(false);
    if (!this.currentUser || !this.currentUser.phoneNumber) return;

    // Mark online immediately
    this.updateUserPresence(true);

    // Periodic heartbeat every 20 seconds
    this.presenceInterval = setInterval(() => {
      if (typeof document !== 'undefined' && !document.hidden) {
        this.updateUserPresence(true);
      }
    }, 20000);

    // Handle tab visibility change
    if (typeof document !== 'undefined') {
      this.boundVisibilityHandler = () => {
        if (document.visibilityState === 'visible') {
          if (this.presenceGraceTimer) {
            clearTimeout(this.presenceGraceTimer);
            this.presenceGraceTimer = null;
          }
          this.updateUserPresence(true);
        } else {
          // Grace period to prevent flicker on rapid tab switching
          this.presenceGraceTimer = setTimeout(() => {
            this.updateUserPresence(false);
          }, 10000);
        }
      };
      document.addEventListener('visibilitychange', this.boundVisibilityHandler);
    }

    // Handle tab close / refresh
    if (typeof window !== 'undefined') {
      this.boundUnloadHandler = () => {
        this.updateUserPresence(false);
      };
      window.addEventListener('beforeunload', this.boundUnloadHandler);
      window.addEventListener('pagehide', this.boundUnloadHandler);
    }
  }

  /**
   * Stops presence heartbeat and cleans up listeners.
   */
  stopPresenceHeartbeat(markOffline = true) {
    if (this.presenceInterval) {
      clearInterval(this.presenceInterval);
      this.presenceInterval = null;
    }
    if (this.presenceGraceTimer) {
      clearTimeout(this.presenceGraceTimer);
      this.presenceGraceTimer = null;
    }
    if (this.boundVisibilityHandler && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.boundVisibilityHandler);
      this.boundVisibilityHandler = null;
    }
    if (this.boundUnloadHandler && typeof window !== 'undefined') {
      window.removeEventListener('beforeunload', this.boundUnloadHandler);
      window.removeEventListener('pagehide', this.boundUnloadHandler);
      this.boundUnloadHandler = null;
    }
    if (markOffline) {
      this.updateUserPresence(false);
    }
  }

  async syncPublicKey() {
    if (!this.currentUser || !this.currentUser.phoneNumber) return;
    try {
      const pubKey = await chatCryptoWeb.getMyPublicKeyBase64();
      if (pubKey && this.currentUser.publicKey !== pubKey) {
        const userRef = doc(db, 'users', this.currentUser.phoneNumber);
        await updateDoc(userRef, { publicKey: pubKey });
        this.currentUser.publicKey = pubKey;
        localStorage.setItem('lksDialerUser', JSON.stringify(this.currentUser));
        console.log("Synced E2EE public key to Firestore for:", this.currentUser.phoneNumber);
      }
    } catch (e) {
      console.warn('Failed to sync public key:', e);
    }
  }

  async setupLocalStream(callType = 'AUDIO') {
    if (this.localStream) {
      this.localStream.getTracks().forEach(t => t.stop());
    }
    
    try {
      const audioConstraints = {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        googEchoCancellation: true,
        googAutoGainControl: true,
        googNoiseSuppression: true,
        googHighpassFilter: true,
        channelCount: 1
      };
      const constraints = {
        audio: audioConstraints,
        video: callType === 'VIDEO' ? { facingMode: this.isFrontCamera ? 'user' : 'environment' } : false
      };
      this.localStream = await navigator.mediaDevices.getUserMedia(constraints);
      if (this.onLocalStream) this.onLocalStream(this.localStream);
      return this.localStream;
    } catch (e) {
      console.error("Error accessing media devices.", e);
      throw e;
    }
  }

  createPeerConnection(isCaller = false) {
    this.peerConnection = new RTCPeerConnection(this.iceServers || servers);
    
    this.remoteStream = new MediaStream();
    if (this.onRemoteStream) this.onRemoteStream(this.remoteStream);

    this.localStream?.getTracks().forEach((track) => {
      this.peerConnection.addTrack(track, this.localStream);
    });

    this.peerConnection.ontrack = (event) => {
      if (event.streams && event.streams[0]) {
        event.streams[0].getTracks().forEach((track) => {
          track.enabled = true;
          if (!this.remoteStream.getTracks().some(t => t.id === track.id)) {
            this.remoteStream.addTrack(track);
          }
        });
      } else if (event.track) {
        event.track.enabled = true;
        if (!this.remoteStream.getTracks().some(t => t.id === event.track.id)) {
          this.remoteStream.addTrack(event.track);
        }
      }
      if (this.onRemoteStream) {
        this.onRemoteStream(this.remoteStream);
      }
    };

    this.peerConnection.oniceconnectionstatechange = async () => {
      if (this.peerConnection.iceConnectionState === 'disconnected' || this.peerConnection.iceConnectionState === 'failed') {
        if (this.onCallStateChange) this.onCallStateChange({ status: "Reconnecting..." });
        
        if (isCaller && this.activeCallId) {
          try {
            this.peerConnection.restartIce();
            const offerDescription = await this.peerConnection.createOffer({ iceRestart: true });
            await this.peerConnection.setLocalDescription(offerDescription);
            await updateDoc(doc(db, 'calls', this.activeCallId), {
              offer: { type: offerDescription.type, sdp: offerDescription.sdp },
              offerSdp: offerDescription.sdp
            });
          } catch (e) {
            console.error("ICE Restart failed:", e);
          }
        }
      } else if (this.peerConnection.iceConnectionState === 'connected') {
        if (this.onCallStateChange) this.onCallStateChange({ status: "ANSWERED" });
      }
    };
  }

  async lookupUser(phoneNumber) {
    if (!phoneNumber) return null;
    const variations = [phoneNumber];
    const cleanDigits = phoneNumber.replace(/[^0-9]/g, '');
    if (cleanDigits) {
      variations.push(cleanDigits);
      if (cleanDigits.length > 10) {
        variations.push(cleanDigits.slice(-10));
      }
      if (!phoneNumber.startsWith('+')) {
        variations.push('+' + phoneNumber);
      }
    }
    const distinctVariations = Array.from(new Set(variations)).slice(0, 10);

    try {
      const q = query(collection(db, 'users'), where('phoneNumber', 'in', distinctVariations));
      const snapshot = await getDocs(q);
      if (!snapshot.empty) {
        const d = snapshot.docs[0].data();
        if (d && d.profilePictureUrl) d.profilePictureUrl = formatAvatarUrl(d.profilePictureUrl);
        return d;
      }
    } catch (e) {
      console.warn("Query by variations failed, falling back to direct get:", e);
    }

    try {
      const docSnap = await getDoc(doc(db, 'users', phoneNumber));
      if (docSnap.exists()) {
        const d = docSnap.data();
        if (d && d.profilePictureUrl) d.profilePictureUrl = formatAvatarUrl(d.profilePictureUrl);
        return d;
      }
    } catch (e) {
      console.warn("Direct lookup failed:", e);
    }
    return null;
  }

  async getRegisteredUsers() {
    try {
      const q = query(collection(db, 'users'));
      const snapshot = await getDocs(q);
      return snapshot.docs.map(doc => {
        const data = doc.data() || {};
        const phone = data.phoneNumber || doc.id || '';
        return {
          id: doc.id,
          displayName: data.displayName || phone || 'Unknown User',
          ...data,
          profilePictureUrl: formatAvatarUrl(data.profilePictureUrl) || '',
          phoneNumber: String(phone)
        };
      });
    } catch (e) {
      console.error("Error fetching registered users:", e);
      return [];
    }
  }

  async getCallHistory() {
    if (!this.currentUser || !this.currentUser.phoneNumber) return [];
    
    try {
      const callerQuery = query(collection(db, 'calls'), where('callerNumber', '==', this.currentUser.phoneNumber));
      const calleeQuery = query(collection(db, 'calls'), where('calleeNumber', '==', this.currentUser.phoneNumber));
      
      const [callerSnap, calleeSnap] = await Promise.all([getDocs(callerQuery), getDocs(calleeQuery)]);
      
      const calls = [];
      callerSnap.forEach(doc => {
        const d = doc.data() || {};
        calls.push({
          id: doc.id,
          ...d,
          callerProfilePic: formatAvatarUrl(d.callerProfilePic) || '',
          calleeProfilePic: formatAvatarUrl(d.calleeProfilePic) || ''
        });
      });
      calleeSnap.forEach(doc => {
        if (!calls.find(c => c.id === doc.id)) {
          const d = doc.data() || {};
          calls.push({
            id: doc.id,
            ...d,
            callerProfilePic: formatAvatarUrl(d.callerProfilePic) || '',
            calleeProfilePic: formatAvatarUrl(d.calleeProfilePic) || ''
          });
        }
      });
      
      return calls.sort((a, b) => {
        const timeA = a.createdAt?.seconds ? a.createdAt.seconds * 1000 : (Number(a.createdAt) || 0);
        const timeB = b.createdAt?.seconds ? b.createdAt.seconds * 1000 : (Number(b.createdAt) || 0);
        return timeB - timeA;
      });
    } catch (e) {
      console.error("Error fetching call history:", e);
      return [];
    }
  }

  async registerUser(phoneNumber, displayName) {
    let webToken = null;
    try {
      if (typeof window !== 'undefined' && 'Notification' in window && 'serviceWorker' in navigator && messaging) {
        const permission = await Notification.requestPermission();
        if (permission === 'granted') {
          const registration = await navigator.serviceWorker.register('/firebase-messaging-sw.js');
          await navigator.serviceWorker.ready;
          webToken = await getToken(messaging, { 
            vapidKey: 'BItSp6sbgw96jK3fsvISihhymmDj-XTx9uAHvNaiPwgqCdxtTPH96umi2khxaPmNBfHh2c_KwkeTbW5sbNoty8k',
            serviceWorkerRegistration: registration
          });
          console.log('✅ FCM WebPush token obtained on registration:', webToken?.substring(0, 15) + '...');
        }
      }
    } catch (e) {
      console.error('Failed to get FCM web token on registration:', e);
    }

    let publicKey = '';
    try {
      publicKey = await chatCryptoWeb.getMyPublicKeyBase64();
    } catch (e) {
      console.warn('Failed to get E2EE public key during registration:', e);
    }

    const cleanPhone = phoneNumber.replace(/[^0-9]/g, '');
    const userRef = doc(db, 'users', phoneNumber);
    const userSnap = await getDoc(userRef);
    const now = Date.now();
    const userData = {
      phoneNumber,
      displayName,
      registeredDeviceId: 'web-device-' + Math.random().toString(36).substring(7),
      isOnline: true,
      online: true,
      lastSeen: now,
      ...(webToken && { webToken }),
      ...(publicKey && { publicKey })
    };
    
    if (userSnap.exists()) {
      await updateDoc(userRef, { 
        registeredDeviceId: userData.registeredDeviceId,
        isOnline: true,
        online: true,
        lastSeen: now,
        ...(webToken && { webToken }),
        ...(publicKey && { publicKey })
      });
      // Merge existing data back into currentUser so we don't lose profilePictureUrl or blockedNumbers
      const existingData = userSnap.data();
      this.setCurrentUser({ 
        ...existingData, 
        ...userData,
        blockedNumbers: existingData.blockedNumbers || [] 
      });
    } else {
      userData.blockedNumbers = [];
      await setDoc(userRef, userData);
      this.setCurrentUser(userData);
    }

    // Also persist webToken on clean phone variation so lookup from Android or Web never misses
    if (webToken && cleanPhone && cleanPhone !== phoneNumber) {
      try {
        await setDoc(doc(db, 'users', cleanPhone), { webToken }, { merge: true });
      } catch {}
    }
    
    this.listenForIncomingCalls();
    return this.currentUser;
  }

  async initWebPush() {
    if (!this.currentUser) return;
    try {
      if (typeof window === 'undefined' || !('Notification' in window) || !('serviceWorker' in navigator) || !messaging) {
        return;
      }
      const permission = await Notification.requestPermission();
      if (permission === 'granted') {
        const registration = await navigator.serviceWorker.register('/firebase-messaging-sw.js');
        await navigator.serviceWorker.ready;
        const webToken = await getToken(messaging, { 
          vapidKey: 'BItSp6sbgw96jK3fsvISihhymmDj-XTx9uAHvNaiPwgqCdxtTPH96umi2khxaPmNBfHh2c_KwkeTbW5sbNoty8k',
          serviceWorkerRegistration: registration
        });
        if (webToken) {
          const phone = this.currentUser.phoneNumber;
          const cleanPhone = phone.replace(/[^0-9]/g, '');

          await setDoc(doc(db, 'users', phone), { webToken }, { merge: true });
          if (cleanPhone && cleanPhone !== phone) {
            await setDoc(doc(db, 'users', cleanPhone), { webToken }, { merge: true });
          }

          this.currentUser.webToken = webToken;
          localStorage.setItem('lksDialerUser', JSON.stringify(this.currentUser));
          console.log('✅ Web push token registered & synced to Firestore:', webToken.substring(0, 15) + '...');
        }
      }
    } catch (e) {
      console.error('Failed to init web push:', e);
    }
  }

  async updateProfile(displayName, profilePictureUrl) {
    if (!this.currentUser) return;
    
    const userRef = doc(db, 'users', this.currentUser.phoneNumber);
    const updates = {};
    if (displayName) updates.displayName = displayName;
    if (profilePictureUrl !== undefined) updates.profilePictureUrl = profilePictureUrl;
    
    await updateDoc(userRef, updates);
    
    this.setCurrentUser({
      ...this.currentUser,
      ...updates
    });
    
    return this.currentUser;
  }

  isNumberBlocked(phoneNumber) {
    if (!this.currentUser || !this.currentUser.blockedNumbers) return false;
    const clean = String(phoneNumber || '').replace(/[^0-9]/g, '');
    if (!clean) return false;
    return this.currentUser.blockedNumbers.some(b => {
      const bClean = String(b || '').replace(/[^0-9]/g, '');
      if (!bClean) return false;
      return bClean === clean || (bClean.length >= 10 && clean.length >= 10 && bClean.slice(-10) === clean.slice(-10));
    });
  }

  async blockNumber(phoneNumber) {
    if (!this.currentUser || !phoneNumber) return;
    const clean = String(phoneNumber).replace(/[^0-9+]/g, '');
    const current = this.currentUser.blockedNumbers || [];
    if (current.includes(clean)) return;

    const updated = [...current, clean];
    const userRef = doc(db, 'users', this.currentUser.phoneNumber);
    await updateDoc(userRef, { blockedNumbers: updated });

    this.currentUser.blockedNumbers = updated;
    localStorage.setItem('lksDialerUser', JSON.stringify(this.currentUser));
  }

  async unblockNumber(phoneNumber) {
    if (!this.currentUser || !phoneNumber) return;
    const current = this.currentUser.blockedNumbers || [];
    const updated = current.filter(b => !numbersMatch(b, phoneNumber));

    const userRef = doc(db, 'users', this.currentUser.phoneNumber);
    await updateDoc(userRef, { blockedNumbers: updated });

    this.currentUser.blockedNumbers = updated;
    localStorage.setItem('lksDialerUser', JSON.stringify(this.currentUser));
  }

  async startCall(calleeNumber, callType = 'AUDIO') {
    const callee = await this.lookupUser(calleeNumber);
    if (!callee) throw new Error("User not found on LKS-DIALER");

    await this.setupLocalStream(callType);
    this.createPeerConnection(true);

    this.activeCallId = crypto.randomUUID();
    const callDoc = doc(db, 'calls', this.activeCallId);
    
    this.peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        addDoc(collection(callDoc, 'candidates'), {
          serverUrl: "",
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex,
          sdpCandidate: event.candidate.candidate,
          type: 'offerCandidate'
        });
      }
    };

    const offerDescription = await this.peerConnection.createOffer();
    await this.peerConnection.setLocalDescription(offerDescription);

    const callData = {
      callId: this.activeCallId,
      callerNumber: this.currentUser.phoneNumber,
      callerName: this.currentUser.displayName,
      callerProfilePic: this.currentUser.profilePictureUrl || "",
      calleeNumber: callee.phoneNumber,
      calleeName: callee.displayName,
      calleeProfilePic: callee.profilePictureUrl || "",
      callType: callType,
      status: "CALLING",
      offer: { type: offerDescription.type, sdp: offerDescription.sdp },
      offerSdp: offerDescription.sdp,
      createdAt: Date.now(),
      timestamp: serverTimestamp() // Keeping this just in case, but Android uses createdAt
    };

    await setDoc(callDoc, callData);
    
    // Play supervisory outgoing ringback tone immediately
    callSounds.startRingbackTone();

    // Trigger Push Notification via Cloudflare Worker
    this.triggerPushNotification(callee, callType, this.activeCallId);

    // Listen for Answer and ICE candidates
    this.listenToActiveCall(callDoc, true);

    return this.activeCallId;
  }

  async triggerPushNotification(callee, callType, callId, notificationType = "incoming_call") {
    try {
      const url = "https://lks-dialer-call-notifier.subhojit.workers.dev/call";
      await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Worker-Secret": "LKS_DIALER_EsA2u7uNJMiE0ZhbtRUnzs7tkZPe4WvJ" },
        body: JSON.stringify({ 
          token: callee.fcmToken || null, // Android FCM Token
          webToken: callee.webToken || null, // Web FCM Token
          callType, 
          callId, 
          callerName: this.currentUser?.displayName || "Unknown",
          callerNumber: this.currentUser?.phoneNumber || "",
          callerProfilePic: this.currentUser?.profilePictureUrl || "",
          type: notificationType
        })
      });
    } catch (e) {
      console.error("Failed to send push notification:", e);
    }
  }

  listenForIncomingCalls() {
    if (!this.currentUser || !this.currentUser.phoneNumber) return;
    const phone = this.currentUser.phoneNumber;
    const variations = [phone];
    const cleanDigits = phone.replace(/[^0-9]/g, '');
    if (cleanDigits) {
      variations.push(cleanDigits);
      if (cleanDigits.length > 10) {
        variations.push(cleanDigits.slice(-10));
      }
      if (!phone.startsWith('+')) {
        variations.push('+' + phone);
      }
    }
    const distinctVariations = Array.from(new Set(variations)).slice(0, 10);

    const q = query(
      collection(db, 'calls'), 
      where('calleeNumber', 'in', distinctVariations),
      where('status', 'in', ['CALLING', 'RINGING'])
    );
    
    onSnapshot(q, (snapshot) => {
      snapshot.docChanges().forEach(change => {
        if (change.type === 'added' || change.type === 'modified') {
          const data = change.doc.data();
          // Check if caller is blocked
          if (data.callerNumber && this.isNumberBlocked(data.callerNumber)) {
            console.log("Incoming call auto-declined by Blocklist from:", data.callerNumber);
            updateDoc(change.doc.ref, { status: 'DECLINED', endedAt: Date.now() }).catch(() => {});
            return;
          }
          // Update status to RINGING to notify caller
          if (data.status === 'CALLING') {
            updateDoc(change.doc.ref, { status: 'RINGING' });
          }
          if (this.onCallStateChange) this.onCallStateChange({ id: change.doc.id, ...data });
        } else if (change.type === 'removed') {
          // If the call falls out of CALLING/RINGING (e.g. caller hung up), clear it from the UI.
          if (this.onCallStateChange) this.onCallStateChange({ id: change.doc.id, status: 'REMOVED' });
        }
      });
    });
  }

  async acceptCall(callId, offer, callType) {
    this.activeCallId = callId;
    callSounds.stopRingbackTone();
    const callDoc = doc(db, 'calls', callId);

    // Immediately mark status as ANSWERED in Firestore so caller screen switches to Speak Mode immediately (<100ms)
    updateDoc(callDoc, {
      status: "ANSWERED",
      answeredAt: Date.now()
    }).catch(e => console.warn("Initial answered status update:", e));

    let offerObj = offer;
    if (!offerObj) {
      const snap = await getDoc(callDoc);
      const data = snap.data();
      if (data && data.offerSdp) {
        offerObj = { type: 'offer', sdp: data.offerSdp };
      }
    }
    
    await this.setupLocalStream(callType);
    this.createPeerConnection();

    this.peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        addDoc(collection(callDoc, 'candidates'), {
          serverUrl: "",
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex,
          sdpCandidate: event.candidate.candidate,
          type: 'answerCandidate'
        });
      }
    };

    if (offerObj) {
      await this.peerConnection.setRemoteDescription(new RTCSessionDescription(offerObj));
      this.hasProcessedOffer = true;
    } else {
      console.error("No valid offer found to accept the call!");
      return;
    }
    const answerDescription = await this.peerConnection.createAnswer();
    await this.peerConnection.setLocalDescription(answerDescription);

    await updateDoc(callDoc, {
      answer: { type: answerDescription.type, sdp: answerDescription.sdp },
      answerSdp: answerDescription.sdp,
      status: "ANSWERED",
      answeredAt: Date.now()
    });

    this.listenToActiveCall(callDoc, false);
  }

  async endCall() {
    callSounds.stopRingbackTone();
    callSounds.playCallEndedTone();
    if (this.activeCallId) {
      const callDocRef = doc(db, 'calls', this.activeCallId);
      const callSnap = await getDoc(callDocRef);
      const callData = callSnap.data();

      const isUnanswered = callData && (callData.status === 'CALLING' || callData.status === 'RINGING');
      const endStatus = isUnanswered ? 'MISSED' : 'ENDED';

      await updateDoc(callDocRef, { status: endStatus, endedAt: Date.now() });

      // If we hung up before it was answered, send push to silence ringing and show missed call
      if (isUnanswered && callData?.calleeNumber) {
        try {
          const calleeNum = callData.calleeNumber;
          const variations = [calleeNum];
          const clean = calleeNum.replace(/[^0-9]/g, '');
          if (clean) {
            variations.push(clean);
            if (clean.length > 10) variations.push(clean.slice(-10));
            if (!calleeNum.startsWith('+')) variations.push('+' + calleeNum);
          }
          const distinctVariations = Array.from(new Set(variations)).slice(0, 10);
          const userQ = query(collection(db, 'users'), where('phoneNumber', 'in', distinctVariations));
          const userSnap = await getDocs(userQ);
          if (!userSnap.empty) {
            const calleeData = userSnap.docs[0].data();
            // First cancel ringing notification
            this.triggerPushNotification(calleeData, callData.callType, this.activeCallId, 'cancel_call');
            // Then record missed call
            this.triggerPushNotification(calleeData, callData.callType, this.activeCallId, 'missed_call');
          }
        } catch (e) {
          console.warn('Failed to send cancel/missed push:', e);
        }
      }
    }
    this.cleanup();
  }

  async declineCall(callId) {
    callSounds.stopRingbackTone();
    callSounds.playCallEndedTone();
    await updateDoc(doc(db, 'calls', callId), { status: 'DECLINED' });
    this.cleanup();
  }

  cleanup() {
    callSounds.resetAll();
    this.isOnHold = false;
    if (this.peerConnection) {
      this.peerConnection.close();
      this.peerConnection = null;
    }
    if (this.localStream) {
      this.localStream.getTracks().forEach(t => t.stop());
      this.localStream = null;
    }
    this.activeCallId = null;
    this.callUnsubscribers.forEach(unsub => unsub());
    this.callUnsubscribers = [];
    this.hasProcessedAnswer = false;
    this.hasProcessedOffer = false;
    this.didIRequestVideoUpgrade = false;
    this.isVideoUpgradeRequested = false;
    this.isUpgradingVideo = false;
    this.pendingOffer = null;
    if (this.onCallStateChange) this.onCallStateChange(null);
  }

  async processOfferObj(offerObj) {
    if (!this.activeCallId || !this.peerConnection) return;
    try {
      await this.peerConnection.setRemoteDescription(new RTCSessionDescription(offerObj));
      const answerDescription = await this.peerConnection.createAnswer();
      await this.peerConnection.setLocalDescription(answerDescription);
      const callDoc = doc(db, 'calls', this.activeCallId);
      await updateDoc(callDoc, {
        answer: { type: answerDescription.type, sdp: answerDescription.sdp },
        answerSdp: answerDescription.sdp
      });
    } catch (e) {
      console.error("Error processing offer obj:", e);
    }
  }

  listenToActiveCall(callDoc, isCaller = true) {
    const unsubStatus = onSnapshot(callDoc, (snapshot) => {
      const data = snapshot.data();
      if (!data) return;

      if (this.onCallStateChange) this.onCallStateChange({ id: snapshot.id, ...data });

      // Video Upgrade Logic
      if (data.videoUpgradeStatus === 'REQUESTED') {
        if (!this.didIRequestVideoUpgrade && data.callType === 'AUDIO' && !this.isVideoUpgradeRequested) {
          this.isVideoUpgradeRequested = true;
          if (this.onVideoUpgradeRequested) this.onVideoUpgradeRequested();
        }
      } else {
        this.isVideoUpgradeRequested = false;
        if (!data.videoUpgradeStatus) this.didIRequestVideoUpgrade = false;
      }

      if (data.videoUpgradeStatus === 'ACCEPTED' && data.callType === 'AUDIO') {
        this.executeVideoUpgrade(isCaller);
      }

      if (data.videoUpgradeStatus === 'DECLINED' && data.callType === 'AUDIO') {
        if (this.didIRequestVideoUpgrade) {
          if (this.onVideoUpgradeDeclined) this.onVideoUpgradeDeclined();
          updateDoc(callDoc, { videoUpgradeStatus: null });
          this.didIRequestVideoUpgrade = false;
        }
      }

      const answerObj = data.answer || (data.answerSdp ? { type: 'answer', sdp: data.answerSdp } : null);

      if (isCaller && !this.hasProcessedAnswer && answerObj) {
        this.hasProcessedAnswer = true;
        this.peerConnection.setRemoteDescription(new RTCSessionDescription(answerObj));
      }

      // Handle renegotiated answer if caller
      if (isCaller && this.hasProcessedAnswer && answerObj && this.peerConnection.signalingState === 'have-local-offer') {
        this.peerConnection.setRemoteDescription(new RTCSessionDescription(answerObj));
      }

      // Handle renegotiated offer if callee
      const offerObj = data.offer || (data.offerSdp ? { type: 'offer', sdp: data.offerSdp } : null);
      if (!isCaller && this.hasProcessedOffer && offerObj && this.peerConnection.signalingState === 'stable' && offerObj.sdp !== this.peerConnection.currentRemoteDescription?.sdp) {
        if (this.isUpgradingVideo) {
          this.pendingOffer = offerObj;
        } else {
          this.processOfferObj(offerObj);
        }
      }

      if (isCaller && data.status === 'RINGING') {
        callSounds.startRingbackTone();
      }
      if (isCaller && data.status === 'ANSWERED') {
        callSounds.stopRingbackTone();
      }

      // Hold state audio synchronization
      const remoteHold = data.isOnHold === true || data.onHold === true;
      if (remoteHold !== this.isOnHold) {
        this.isOnHold = remoteHold;
        if (remoteHold) {
          callSounds.playHoldTone();
        } else {
          callSounds.playUnholdTone();
        }
      }

      if (data.status === 'ENDED' || data.status === 'DECLINED' || data.status === 'MISSED') {
        callSounds.stopRingbackTone();
        callSounds.playCallEndedTone();
        this.cleanup();
      }
    });

    const targetType = isCaller ? 'answerCandidate' : 'offerCandidate';
    const qCandidates = query(collection(callDoc, 'candidates'), where('type', '==', targetType));
    
    const unsubIce = onSnapshot(qCandidates, (snapshot) => {
      snapshot.docChanges().forEach(change => {
        if (change.type === 'added') {
          const data = change.doc.data();
          const candidate = new RTCIceCandidate({
            sdpMid: data.sdpMid,
            sdpMLineIndex: data.sdpMLineIndex,
            candidate: data.sdpCandidate
          });
          this.peerConnection.addIceCandidate(candidate);
        }
      });
    });

    this.callUnsubscribers.push(unsubStatus, unsubIce);
  }

  toggleMute(isMuted) {
    if (this.localStream) {
      this.localStream.getAudioTracks().forEach(t => t.enabled = !isMuted);
    }
  }

  toggleVideo(isVideoEnabled) {
    if (this.localStream) {
      this.localStream.getVideoTracks().forEach(t => t.enabled = isVideoEnabled);
    }
  }

  async switchCamera() {
    if (!this.localStream || !this.peerConnection) return;
    
    try {
      this.isFrontCamera = !this.isFrontCamera;
      const newStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: this.isFrontCamera ? 'user' : 'environment' }
      });
      const newVideoTrack = newStream.getVideoTracks()[0];
      
      // Find old track
      const oldVideoTrack = this.localStream.getVideoTracks()[0];
      
      // Replace in local stream
      if (oldVideoTrack) {
        this.localStream.removeTrack(oldVideoTrack);
        oldVideoTrack.stop(); // release camera hardware
      }
      this.localStream.addTrack(newVideoTrack);
      
      // Update UI
      if (this.onLocalStream) {
        this.onLocalStream(this.localStream);
      }
      
      // Replace in peer connection
      const sender = this.peerConnection.getSenders().find(s => s.track && s.track.kind === 'video');
      if (sender) {
        await sender.replaceTrack(newVideoTrack);
      }
    } catch (e) {
      console.error("Failed to switch camera:", e);
      // Revert if failed
      this.isFrontCamera = !this.isFrontCamera;
    }
  }

  async requestVideoUpgrade() {
    if (!this.activeCallId) return;
    this.didIRequestVideoUpgrade = true;
    await updateDoc(doc(db, 'calls', this.activeCallId), {
      videoUpgradeStatus: 'REQUESTED'
    });
  }

  async acceptVideoUpgrade() {
    if (!this.activeCallId) return;
    this.isVideoUpgradeRequested = false;
    await updateDoc(doc(db, 'calls', this.activeCallId), {
      videoUpgradeStatus: 'ACCEPTED'
    });
  }

  async declineVideoUpgrade() {
    if (!this.activeCallId) return;
    this.isVideoUpgradeRequested = false;
    await updateDoc(doc(db, 'calls', this.activeCallId), {
      videoUpgradeStatus: 'DECLINED'
    });
  }

  async executeVideoUpgrade(isCaller) {
    if (!this.activeCallId) return;

    this.isUpgradingVideo = true;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ 
        video: { facingMode: this.isFrontCamera ? 'user' : 'environment' }, 
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          googEchoCancellation: true,
          googAutoGainControl: true,
          googNoiseSuppression: true,
          googHighpassFilter: true,
          channelCount: 1
        }
      });
      const videoTrack = stream.getVideoTracks()[0];
      
      // Update local stream
      this.localStream.addTrack(videoTrack);
      if (this.onLocalStream) this.onLocalStream(this.localStream);

      // Add track to peer connection
      const senders = this.peerConnection.getSenders();
      const videoSender = senders.find(s => s.track && s.track.kind === 'video');
      
      if (videoSender) {
        videoSender.replaceTrack(videoTrack);
      } else {
        this.peerConnection.addTrack(videoTrack, this.localStream);
      }

      // Update callType to VIDEO
      await updateDoc(doc(db, 'calls', this.activeCallId), {
        callType: 'VIDEO'
      });

      // If caller, we renegotiate SDP by creating a new offer
      if (isCaller) {
        const offerDescription = await this.peerConnection.createOffer();
        await this.peerConnection.setLocalDescription(offerDescription);
        
        await updateDoc(doc(db, 'calls', this.activeCallId), {
          offer: { type: offerDescription.type, sdp: offerDescription.sdp },
          offerSdp: offerDescription.sdp,
          videoUpgradeStatus: null // reset
        });
      } else {
        // If callee, wait for the new offer, setRemoteDescription, createAnswer
        // This is handled in listenToActiveCall where it checks offerSdp change
        await updateDoc(doc(db, 'calls', this.activeCallId), {
          videoUpgradeStatus: null
        });
      }
    } catch (e) {
      console.error("Failed to upgrade to video:", e);
      if (this.onCallStateChange) this.onCallStateChange("Video Error");
    } finally {
      this.isUpgradingVideo = false;
      if (this.pendingOffer) {
        this.processOfferObj(this.pendingOffer);
        this.pendingOffer = null;
      }
    }
  }
}

export const webRtcEngine = new WebRtcEngine();
