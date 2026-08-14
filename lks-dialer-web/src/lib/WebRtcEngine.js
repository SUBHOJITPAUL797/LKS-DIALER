import { db, messaging } from './firebase';
import { getToken } from 'firebase/messaging';
import { 
  collection, doc, setDoc, getDoc, updateDoc, onSnapshot, 
  query, where, getDocs, addDoc, serverTimestamp 
} from 'firebase/firestore';

const servers = {
  iceServers: [
    { urls: ['stun:stun1.l.google.com:19302', 'stun:stun2.l.google.com:19302'] }
  ],
  iceCandidatePoolSize: 10,
};

class WebRtcEngine {
  constructor() {
    this.peerConnection = null;
    this.localStream = null;
    this.remoteStream = new MediaStream();
    this.activeCallId = null;
    this.currentUser = null;
    this.isFrontCamera = true;
    
    // Callbacks for UI updates
    this.onCallStateChange = null;
    this.onLocalStream = null;
    this.onRemoteStream = null;
    
    this.callUnsubscribers = [];
  }

  setCurrentUser(user) {
    this.currentUser = user;
  }

  async setupLocalStream(callType = 'AUDIO') {
    if (this.localStream) {
      this.localStream.getTracks().forEach(t => t.stop());
    }
    
    try {
      const constraints = {
        audio: true,
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

  createPeerConnection() {
    this.peerConnection = new RTCPeerConnection(servers);
    
    this.remoteStream = new MediaStream();
    if (this.onRemoteStream) this.onRemoteStream(this.remoteStream);

    this.localStream?.getTracks().forEach((track) => {
      this.peerConnection.addTrack(track, this.localStream);
    });

    this.peerConnection.ontrack = (event) => {
      event.streams[0].getTracks().forEach((track) => {
        this.remoteStream.addTrack(track);
      });
    };
  }

  async lookupUser(phoneNumber) {
    const q = query(collection(db, 'users'), where('phoneNumber', '==', phoneNumber));
    const snapshot = await getDocs(q);
    if (snapshot.empty) return null;
    return snapshot.docs[0].data();
  }

  async getRegisteredUsers() {
    const q = query(collection(db, 'users'));
    const snapshot = await getDocs(q);
    return snapshot.docs.map(doc => doc.data());
  }

  async getCallHistory() {
    if (!this.currentUser) return [];
    
    // We fetch where the user is caller OR callee
    // Firestore requires separate queries or an 'in' query if we had an array of participants, 
    // but we have callerNumber and calleeNumber. We will fetch all calls and filter locally 
    // for simplicity since this is a demo/small scale, or fetch both and combine.
    
    const callerQuery = query(collection(db, 'calls'), where('callerNumber', '==', this.currentUser.phoneNumber));
    const calleeQuery = query(collection(db, 'calls'), where('calleeNumber', '==', this.currentUser.phoneNumber));
    
    const [callerSnap, calleeSnap] = await Promise.all([getDocs(callerQuery), getDocs(calleeQuery)]);
    
    const calls = [];
    callerSnap.forEach(doc => calls.push({ id: doc.id, ...doc.data() }));
    calleeSnap.forEach(doc => {
      if (!calls.find(c => c.id === doc.id)) {
        calls.push({ id: doc.id, ...doc.data() });
      }
    });
    
    return calls.sort((a, b) => b.createdAt - a.createdAt);
  }

  async registerUser(phoneNumber, displayName) {
    let webToken = null;
    try {
      if (messaging) {
        const permission = await Notification.requestPermission();
        if (permission === 'granted') {
          webToken = await getToken(messaging, { 
            vapidKey: 'BItSp6sbgw96jK3fsvISihhymmDj-XTx9uAHvNaiPwgqCdxtTPH96umi2khxaPmNBfHh2c_KwkeTbW5sbNoty8k' 
          });
        }
      }
    } catch (e) {
      console.error('Failed to get FCM web token', e);
    }

    const userRef = doc(db, 'users', phoneNumber);
    const userSnap = await getDoc(userRef);
    const userData = {
      phoneNumber,
      displayName,
      registeredDeviceId: 'web-device-' + Math.random().toString(36).substring(7),
      ...(webToken && { webToken })
    };
    
    if (userSnap.exists()) {
      await updateDoc(userRef, { 
        registeredDeviceId: userData.registeredDeviceId,
        ...(webToken && { webToken }) 
      });
      // Merge existing data back into currentUser so we don't lose profilePictureUrl
      const existingData = userSnap.data();
      this.setCurrentUser({ ...existingData, ...userData });
    } else {
      await setDoc(userRef, userData);
      this.setCurrentUser(userData);
    }
    
    this.listenForIncomingCalls();
    return this.currentUser;
  }

  async initWebPush() {
    if (!this.currentUser) return;
    try {
      if (messaging) {
        const permission = await Notification.requestPermission();
        if (permission === 'granted') {
          const webToken = await getToken(messaging, { 
            vapidKey: 'BItSp6sbgw96jK3fsvISihhymmDj-XTx9uAHvNaiPwgqCdxtTPH96umi2khxaPmNBfHh2c_KwkeTbW5sbNoty8k' 
          });
          if (webToken) {
            const userRef = doc(db, 'users', this.currentUser.phoneNumber);
            await updateDoc(userRef, { webToken });
            this.currentUser.webToken = webToken;
            localStorage.setItem('lksDialerUser', JSON.stringify(this.currentUser));
          }
        }
      }
    } catch (e) {
      console.error('Failed to init web push', e);
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

  async startCall(calleeNumber, callType = 'AUDIO') {
    const callee = await this.lookupUser(calleeNumber);
    if (!callee) throw new Error("User not found on LKS-DIALER");

    await this.setupLocalStream(callType);
    this.createPeerConnection();

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
    
    // Trigger Push Notification via Cloudflare Worker
    this.triggerPushNotification(callee, callType, this.activeCallId);

    // Listen for Answer and ICE candidates
    this.listenToActiveCall(callDoc, true);

    return this.activeCallId;
  }

  async triggerPushNotification(callee, callType, callId, forceNotification = false) {
    try {
      const url = "https://lks-dialer-call-notifier.subhojit.workers.dev/call";
      await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Worker-Secret": "LKS_DIALER_EsA2u7uNJMiE0ZhbtRUnzs7tkZPe4WvJ" },
        body: JSON.stringify({ 
          token: callee.fcmToken, // Android FCM Token
          webToken: callee.webToken || null, // Web FCM Token
          callType, 
          callId, 
          callerName: this.currentUser.displayName,
          callerNumber: this.currentUser.phoneNumber,
          type: forceNotification ? "cancel_call" : "incoming_call"
        })
      });
    } catch (e) {
      console.error("Failed to send push notification:", e);
    }
  }

  listenForIncomingCalls() {
    if (!this.currentUser) return;
    const q = query(
      collection(db, 'calls'), 
      where('calleeNumber', '==', this.currentUser.phoneNumber),
      where('status', 'in', ['CALLING', 'RINGING'])
    );
    
    onSnapshot(q, (snapshot) => {
      snapshot.docChanges().forEach(change => {
        if (change.type === 'added' || change.type === 'modified') {
          const data = change.doc.data();
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
    const callDoc = doc(db, 'calls', callId);

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
      status: "ANSWERED"
    });

    this.listenToActiveCall(callDoc, false);
  }

  async endCall() {
    if (this.activeCallId) {
      const callDocRef = doc(db, 'calls', this.activeCallId);
      const callSnap = await getDoc(callDocRef);
      const callData = callSnap.data();

      await updateDoc(callDocRef, { status: 'ENDED' });

      // If we hung up before it was answered, send a push to silence the ringing on the other end
      if (callData && callData.status !== 'ANSWERED') {
        // Query the callee's fcm token
        const userQ = query(collection(db, 'users'), where('phoneNumber', '==', callData.calleeNumber));
        const userSnap = await getDocs(userQ);
        if (!userSnap.empty) {
          const calleeData = userSnap.docs[0].data();
          this.triggerPushNotification(calleeData, callData.callType, this.activeCallId, true);
        }
      }
    }
    this.cleanup();
  }

  async declineCall(callId) {
    await updateDoc(doc(db, 'calls', callId), { status: 'DECLINED' });
    this.cleanup();
  }

  cleanup() {
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

      if (data.status === 'ENDED' || data.status === 'DECLINED') {
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
        audio: true 
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
