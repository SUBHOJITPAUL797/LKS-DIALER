import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Mic, MicOff, Video, VideoOff, PhoneOff, SwitchCamera } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';

export default function CallScreen({ callData, onEndCall }) {
  const localVideoRef = useRef(null);
  const remoteVideoRef = useRef(null);
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoEnabled, setIsVideoEnabled] = useState(true);
  const [videoUpgradeRequested, setVideoUpgradeRequested] = useState(false);
  const [audioOutputs, setAudioOutputs] = useState([]);
  const [currentOutputIndex, setCurrentOutputIndex] = useState(0);

  const isVideoCall = callData.callType === 'VIDEO';

  const remoteVideoRefCb = useCallback((el) => {
    remoteVideoRef.current = el;
    if (el && webRtcEngine.remoteStream) {
      el.srcObject = webRtcEngine.remoteStream;
    }
  }, [isVideoCall]);

  const localVideoRefCb = useCallback((el) => {
    localVideoRef.current = el;
    if (el && webRtcEngine.localStream) {
      el.srcObject = webRtcEngine.localStream;
    }
  }, [isVideoCall]);

  useEffect(() => {
    webRtcEngine.onLocalStream = (stream) => {
      if (localVideoRef.current) {
        localVideoRef.current.srcObject = stream;
      }
    };
    webRtcEngine.onRemoteStream = (stream) => {
      if (remoteVideoRef.current) {
        remoteVideoRef.current.srcObject = null;
        remoteVideoRef.current.srcObject = stream;
      }
    };

    if (webRtcEngine.localStream && localVideoRef.current) {
      localVideoRef.current.srcObject = webRtcEngine.localStream;
    }
    if (webRtcEngine.remoteStream && remoteVideoRef.current) {
      remoteVideoRef.current.srcObject = webRtcEngine.remoteStream;
    }

    webRtcEngine.onVideoUpgradeRequested = () => setVideoUpgradeRequested(true);
    webRtcEngine.onVideoUpgradeDeclined = () => alert("Video upgrade was declined.");

    return () => {
      webRtcEngine.onVideoUpgradeRequested = null;
      webRtcEngine.onVideoUpgradeDeclined = null;
    };
  }, []);

  useEffect(() => {
    navigator.mediaDevices.enumerateDevices().then(devices => {
      const outputs = devices.filter(d => d.kind === 'audiooutput');
      setAudioOutputs(outputs);
    }).catch(e => console.error("Error enumerating devices:", e));
  }, []);

  const toggleSpeaker = async () => {
    if (audioOutputs.length === 0) return;
    const nextIndex = (currentOutputIndex + 1) % audioOutputs.length;
    const deviceId = audioOutputs[nextIndex].deviceId;
    
    if (remoteVideoRef.current && typeof remoteVideoRef.current.setSinkId === 'function') {
      try {
        await remoteVideoRef.current.setSinkId(deviceId);
        setCurrentOutputIndex(nextIndex);
      } catch (e) {
        console.error("Error setting sink id", e);
      }
    }
  };

  const toggleMute = () => {
    setIsMuted(!isMuted);
    webRtcEngine.toggleMute(!isMuted);
  };

  const toggleVideo = () => {
    setIsVideoEnabled(!isVideoEnabled);
    webRtcEngine.toggleVideo(!isVideoEnabled);
  };

  const isMeCaller = callData.callerNumber === webRtcEngine.currentUser?.phoneNumber;
  const peerName = isMeCaller ? callData.calleeName : callData.callerName;
  const peerAvatar = isMeCaller ? callData.calleeProfilePic : callData.callerProfilePic;
  const isRingingOut = isMeCaller && (callData.status === 'CALLING' || callData.status === 'RINGING');

  return (
    <div style={{ position: 'relative', height: '100%', width: '100%', background: 'var(--bg-color)', overflow: 'hidden' }}>
      
      {/* Outgoing Ringback Tone */}
      {isRingingOut && (
        <audio 
          src={localStorage.getItem('customRingtone') || "https://actions.google.com/sounds/v1/alarms/phone_ringing.ogg"} 
          autoPlay 
          loop 
          style={{ display: 'none' }} 
        />
      )}

      {/* Remote Video / Audio (Full Screen) */}
      {isVideoCall ? (
        <video
          ref={remoteVideoRefCb}
          autoPlay
          playsInline
          style={{
            width: '100%', height: '100%', objectFit: 'cover'
          }}
        />
      ) : (
        <audio 
          ref={remoteVideoRefCb} 
          autoPlay 
        />
      )}

      {/* Local Video (Floating Thumbnail) */}
      {isVideoCall && (
        <video
          ref={localVideoRefCb}
          autoPlay
          playsInline
          muted
          style={{
            position: 'absolute', top: '24px', right: '24px',
            width: '120px', height: '160px', objectFit: 'cover',
            borderRadius: '12px', border: '3px solid #000',
            boxShadow: '4px 4px 0 #000', zIndex: 10,
            background: '#000'
          }}
        />
      )}

      {/* Call Info Overlay if Audio Call */}
      {!isVideoCall && (
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
        }}>
            <div style={{
              width: '160px', height: '160px', borderRadius: '50%',
              backgroundColor: 'var(--accent)', border: '4px solid #000',
              boxShadow: '8px 8px 0 #000', overflow: 'hidden',
              display: 'flex', alignItems: 'center', justifyContent: 'center', 
              marginBottom: '40px'
            }}>
              {peerAvatar && (
                <img 
                  src={peerAvatar} 
                  alt={peerName} 
                  style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                  onError={(e) => {
                    e.target.style.display = 'none';
                    if (e.target.nextSibling) e.target.nextSibling.style.display = 'block';
                  }}
                />
              )}
              <span style={{ display: peerAvatar ? 'none' : 'block', fontSize: '72px', fontWeight: '900', color: '#000' }}>
                {peerName?.[0]?.toUpperCase() || '?'}
              </span>
            </div>
          <h2 style={{ fontSize: '40px', fontWeight: '900', marginBottom: '8px', textTransform: 'uppercase', textAlign: 'center' }}>
            {peerName}
          </h2>
          <div style={{ 
            padding: '8px 24px', backgroundColor: '#000', color: '#fff', 
            borderRadius: '24px', fontWeight: '800', letterSpacing: '2px' 
          }}>
            {callData.status}
          </div>
        </div>
      )}

      {/* Controls */}
      <div style={{
        position: 'absolute', bottom: '40px', left: '50%', transform: 'translateX(-50%)',
        display: 'flex', gap: '20px', zIndex: 20
      }}>
        <button 
          className="neo-box" 
          style={{ 
            width: '64px', height: '64px', borderRadius: '50%', padding: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
            backgroundColor: isMuted ? 'var(--primary)' : '#fff'
          }} 
          onClick={toggleMute}
        >
          {isMuted ? <MicOff size={28} color="#000" /> : <Mic size={28} color="#000" />}
        </button>

        {audioOutputs.length > 1 && (
          <button 
            className="neo-box" 
            style={{ 
              width: '64px', height: '64px', borderRadius: '50%', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
              backgroundColor: currentOutputIndex !== 0 ? 'var(--accent)' : '#fff'
            }} 
            onClick={toggleSpeaker}
            title="Switch Audio Output"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              {currentOutputIndex === 0 ? (
                // Speaker/Loudspeaker Icon
                <><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><path d="M15.54 8.46a5 5 0 0 1 0 7.07"></path><path d="M19.07 4.93a10 10 0 0 1 0 14.14"></path></>
              ) : (
                // Earpiece/Phone Icon
                <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path>
              )}
            </svg>
          </button>
        )}

        {isVideoCall && (
          <button 
            className="neo-box" 
            style={{ 
              width: '64px', height: '64px', borderRadius: '50%', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
              backgroundColor: '#fff'
            }} 
            onClick={() => webRtcEngine.switchCamera()}
            title="Switch Camera"
          >
            <SwitchCamera size={28} color="#000" />
          </button>
        )}

        {isVideoCall ? (
          <button 
            className="neo-box" 
            style={{ 
              width: '64px', height: '64px', borderRadius: '50%', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
              backgroundColor: !isVideoEnabled ? 'var(--primary)' : '#fff'
            }} 
            onClick={toggleVideo}
          >
            {!isVideoEnabled ? <VideoOff size={28} color="#000" /> : <Video size={28} color="#000" />}
          </button>
        ) : (
          <button 
            className="neo-box" 
            style={{ 
              width: '64px', height: '64px', borderRadius: '50%', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
              backgroundColor: 'var(--secondary)'
            }} 
            onClick={() => webRtcEngine.requestVideoUpgrade()}
          >
            <Video size={28} color="#000" />
          </button>
        )}

        <button 
          className="neo-box" 
          style={{ 
            width: '64px', height: '64px', borderRadius: '50%', padding: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
            backgroundColor: '#FF1744'
          }} 
          onClick={onEndCall}
        >
          <PhoneOff size={28} color="#fff" />
        </button>
      </div>

      {videoUpgradeRequested && (
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
          backgroundColor: 'rgba(0,0,0,0.8)', display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 100, padding: '20px'
        }}>
          <div className="neo-box" style={{ width: '100%', maxWidth: '320px', padding: '24px', backgroundColor: '#fff', textAlign: 'center' }}>
            <h3 style={{ fontSize: '24px', fontWeight: '900', marginBottom: '16px' }}>Video Upgrade</h3>
            <p style={{ fontWeight: '600', marginBottom: '24px' }}>{peerName} wants to switch to video.</p>
            <div style={{ display: 'flex', gap: '16px' }}>
              <button 
                className="neo-btn danger" style={{ flex: 1 }}
                onClick={() => {
                  setVideoUpgradeRequested(false);
                  webRtcEngine.declineVideoUpgrade();
                }}
              >
                Decline
              </button>
              <button 
                className="neo-btn success" style={{ flex: 1 }}
                onClick={() => {
                  setVideoUpgradeRequested(false);
                  webRtcEngine.acceptVideoUpgrade();
                }}
              >
                Accept
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
