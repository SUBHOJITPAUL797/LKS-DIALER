import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Mic, MicOff, Video, VideoOff, PhoneOff, SwitchCamera, Headphones, Volume2, Check, X } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';

export default function CallScreen({ callData, onEndCall }) {
  const localVideoRef = useRef(null);
  const remoteVideoRef = useRef(null);
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoEnabled, setIsVideoEnabled] = useState(true);
  const [videoUpgradeRequested, setVideoUpgradeRequested] = useState(false);
  const [audioOutputs, setAudioOutputs] = useState([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState("");
  const [showDeviceModal, setShowDeviceModal] = useState(false);
  const [durationStr, setDurationStr] = useState("");

  const isVideoCall = callData.callType === 'VIDEO';

  useEffect(() => {
    let interval = null;
    if (callData.status === 'ANSWERED' && callData.answeredAt) {
      interval = setInterval(() => {
        const diffInSeconds = Math.floor((Date.now() - callData.answeredAt) / 1000);
        const mins = Math.floor(diffInSeconds / 60).toString().padStart(2, '0');
        const secs = (diffInSeconds % 60).toString().padStart(2, '0');
        setDurationStr(`${mins}:${secs}`);
      }, 1000);
    } else {
      setDurationStr("");
    }
    return () => {
      if (interval) clearInterval(interval);
    };
  }, [callData.status, callData.answeredAt]);

  const remoteVideoRefCb = useCallback((el) => {
    remoteVideoRef.current = el;
    if (el && webRtcEngine.remoteStream) {
      el.srcObject = webRtcEngine.remoteStream;
    }
  }, []);

  const localVideoRefCb = useCallback((el) => {
    localVideoRef.current = el;
    if (el && webRtcEngine.localStream) {
      el.srcObject = webRtcEngine.localStream;
    }
  }, []);

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

  // Fetch and monitor audio output devices (Headphones, Bluetooth, Speakers)
  const refreshAudioOutputs = useCallback(async () => {
    try {
      if (!navigator.mediaDevices?.enumerateDevices) return;
      const devices = await navigator.mediaDevices.enumerateDevices();
      const outputs = devices.filter(d => d.kind === 'audiooutput');
      setAudioOutputs(outputs);
      if (outputs.length > 0 && !selectedDeviceId) {
        setSelectedDeviceId(outputs[0].deviceId);
      }
    } catch (e) {
      console.error("Error enumerating devices:", e);
    }
  }, [selectedDeviceId]);

  useEffect(() => {
    refreshAudioOutputs();
    if (navigator.mediaDevices?.addEventListener) {
      navigator.mediaDevices.addEventListener('devicechange', refreshAudioOutputs);
      return () => {
        navigator.mediaDevices.removeEventListener('devicechange', refreshAudioOutputs);
      };
    }
  }, [refreshAudioOutputs]);

  const selectAudioDevice = async (deviceId) => {
    setSelectedDeviceId(deviceId);
    setShowDeviceModal(false);
    if (remoteVideoRef.current && typeof remoteVideoRef.current.setSinkId === 'function') {
      try {
        await remoteVideoRef.current.setSinkId(deviceId);
      } catch (e) {
        console.error("Error setting sink id:", e);
      }
    }
  };

  const isHeadphoneActive = () => {
    const current = audioOutputs.find(d => d.deviceId === selectedDeviceId);
    if (!current) return false;
    const label = current.label.toLowerCase();
    return label.includes('headphone') || label.includes('earphone') || label.includes('headset') || 
           label.includes('bluetooth') || label.includes('airpods') || label.includes('buds');
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
            {peerAvatar ? (
              <img 
                src={peerAvatar} 
                alt={peerName} 
                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                onError={(e) => {
                  e.target.style.display = 'none';
                  if (e.target.nextSibling) e.target.nextSibling.style.display = 'block';
                }}
              />
            ) : (
              <span style={{ fontSize: '72px', fontWeight: '900', color: '#000' }}>
                {peerName?.[0]?.toUpperCase() || '?'}
              </span>
            )}
          </div>
          <h2 style={{ fontSize: '40px', fontWeight: '900', marginBottom: '8px', textTransform: 'uppercase', textAlign: 'center' }}>
            {peerName}
          </h2>
          <div style={{ 
            padding: '8px 24px', backgroundColor: '#000', color: '#fff', 
            borderRadius: '24px', fontWeight: '800', letterSpacing: '2px' 
          }}>
            {callData.status === 'ANSWERED' && durationStr ? `${callData.status} • ${durationStr}` : callData.status}
          </div>
        </div>
      )}

      {/* Controls */}
      <div style={{
        position: 'absolute', bottom: '40px', left: '50%', transform: 'translateX(-50%)',
        display: 'flex', gap: '20px', zIndex: 20
      }}>
        {/* Mute Button */}
        <button 
          className="neo-box" 
          style={{ 
            width: '64px', height: '64px', borderRadius: '50%', padding: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
            backgroundColor: isMuted ? 'var(--primary)' : '#fff'
          }} 
          onClick={toggleMute}
          title={isMuted ? "Unmute" : "Mute"}
        >
          {isMuted ? <MicOff size={28} color="#000" /> : <Mic size={28} color="#000" />}
        </button>

        {/* Audio Device (Headphone / Speaker) Button */}
        {audioOutputs.length > 0 && (
          <button 
            className="neo-box" 
            style={{ 
              width: '64px', height: '64px', borderRadius: '50%', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
              backgroundColor: isHeadphoneActive() ? 'var(--accent)' : '#fff'
            }} 
            onClick={() => {
              if (audioOutputs.length > 1) {
                setShowDeviceModal(true);
              } else {
                refreshAudioOutputs();
              }
            }}
            title="Audio Output Device (Headphones / Speaker)"
          >
            {isHeadphoneActive() ? (
              <Headphones size={28} color="#000" />
            ) : (
              <Volume2 size={28} color="#000" />
            )}
          </button>
        )}

        {/* Video Camera Switch */}
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

        {/* Video Toggle / Upgrade */}
        {isVideoCall ? (
          <button 
            className="neo-box" 
            style={{ 
              width: '64px', height: '64px', borderRadius: '50%', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
              backgroundColor: !isVideoEnabled ? 'var(--primary)' : '#fff'
            }} 
            onClick={toggleVideo}
            title={isVideoEnabled ? "Turn Off Video" : "Turn On Video"}
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
            title="Request Video Call"
          >
            <Video size={28} color="#000" />
          </button>
        )}

        {/* End Call Button */}
        <button 
          className="neo-box" 
          style={{ 
            width: '64px', height: '64px', borderRadius: '50%', padding: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
            backgroundColor: '#FF1744'
          }} 
          onClick={onEndCall}
          title="End Call"
        >
          <PhoneOff size={28} color="#fff" />
        </button>
      </div>

      {/* Audio Device Selector Modal (Headphones vs Speaker) */}
      {showDeviceModal && (
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
          backgroundColor: 'rgba(0,0,0,0.8)', display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 100, padding: '20px'
        }}>
          <div className="neo-box" style={{ width: '100%', maxWidth: '380px', padding: '24px', backgroundColor: '#fff' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Headphones size={24} color="#000" />
                <h3 style={{ fontSize: '20px', fontWeight: '900', margin: 0 }}>Select Audio Output</h3>
              </div>
              <button 
                onClick={() => setShowDeviceModal(false)}
                style={{ background: 'none', border: 'none', cursor: 'pointer', padding: '4px' }}
              >
                <X size={22} />
              </button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {audioOutputs.map((device, index) => {
                const isSelected = (selectedDeviceId === device.deviceId) || (!selectedDeviceId && index === 0);
                const label = device.label || `Audio Device ${index + 1}`;
                const isHeadphone = label.toLowerCase().includes('headphone') || 
                                    label.toLowerCase().includes('earphone') || 
                                    label.toLowerCase().includes('headset') || 
                                    label.toLowerCase().includes('bluetooth') ||
                                    label.toLowerCase().includes('airpods') ||
                                    label.toLowerCase().includes('buds');

                return (
                  <div
                    key={device.deviceId || index}
                    onClick={() => selectAudioDevice(device.deviceId)}
                    className="neo-box"
                    style={{
                      padding: '12px 16px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      cursor: 'pointer',
                      backgroundColor: isSelected ? 'var(--accent)' : '#fff',
                      transition: 'background-color 0.2s ease'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                      {isHeadphone ? <Headphones size={20} /> : <Volume2 size={20} />}
                      <span style={{ fontWeight: '700', fontSize: '14px' }}>{label}</span>
                    </div>
                    {isSelected && <Check size={18} color="#000" />}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* Video Upgrade Modal */}
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
