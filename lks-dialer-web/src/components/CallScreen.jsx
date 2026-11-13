import React, { useEffect, useRef, useState, useCallback } from 'react';
import { 
  Mic, MicOff, Video, VideoOff, PhoneOff, SwitchCamera, 
  Headphones, Volume2, Check, X, Grid, Pause, Play, Sparkles
} from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { formatAvatarUrl } from '../lib/ImageUtils';

const DTMF_KEYS = [
  '1', '2', '3',
  '4', '5', '6',
  '7', '8', '9',
  '*', '0', '#'
];

export default function CallScreen({ callData, onEndCall }) {
  const localVideoRef = useRef(null);
  const remoteVideoRef = useRef(null);
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoEnabled, setIsVideoEnabled] = useState(true);
  const [videoUpgradeRequested, setVideoUpgradeRequested] = useState(false);
  const [upgradeNotice, setUpgradeNotice] = useState("");
  const [audioOutputs, setAudioOutputs] = useState([]);
  const [selectedDeviceId, setSelectedDeviceId] = useState("");
  const [showDeviceModal, setShowDeviceModal] = useState(false);
  const [showDtmfKeypad, setShowDtmfKeypad] = useState(false);
  const [durationStr, setDurationStr] = useState("");
  const [isHeld, setIsHeld] = useState(false);

  const isVideoCall = callData.callType === 'VIDEO';

  // Synchronize hold state from call data
  useEffect(() => {
    const callIsHold = Boolean(callData.isHold || callData.isOnHold || callData.onHold);
    setIsHeld(callIsHold);
  }, [callData.isHold, callData.isOnHold, callData.onHold]);

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
      el.play?.().catch(() => {});
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
        remoteVideoRef.current.play?.().catch(() => {});
      }
    };

    if (webRtcEngine.localStream && localVideoRef.current) {
      localVideoRef.current.srcObject = webRtcEngine.localStream;
    }
    if (webRtcEngine.remoteStream && remoteVideoRef.current) {
      remoteVideoRef.current.srcObject = webRtcEngine.remoteStream;
      remoteVideoRef.current.play?.().catch(() => {});
    }

    webRtcEngine.onVideoUpgradeRequested = () => setVideoUpgradeRequested(true);
    webRtcEngine.onVideoUpgradeDeclined = () => {
      setUpgradeNotice("Video upgrade was declined by the other party.");
      setTimeout(() => setUpgradeNotice(""), 4500);
    };
    webRtcEngine.onVideoUpgradeFailed = (errMsg) => {
      setUpgradeNotice("📷 Camera not detected or permission denied. Continued as audio call.");
      setTimeout(() => setUpgradeNotice(""), 5000);
    };

    return () => {
      webRtcEngine.onVideoUpgradeRequested = null;
      webRtcEngine.onVideoUpgradeDeclined = null;
      webRtcEngine.onVideoUpgradeFailed = null;
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
    const label = (current.label || '').toLowerCase();
    return label.includes('headphone') || label.includes('earphone') || label.includes('headset') || 
           label.includes('bluetooth') || label.includes('airpods') || label.includes('buds');
  };

  const toggleMute = () => {
    const nextMuted = !isMuted;
    setIsMuted(nextMuted);
    webRtcEngine.toggleMute(nextMuted);
  };

  const toggleVideo = () => {
    const nextVideo = !isVideoEnabled;
    setIsVideoEnabled(nextVideo);
    webRtcEngine.toggleVideo(nextVideo);
  };

  const toggleHoldCall = async () => {
    const nextHold = !isHeld;
    setIsHeld(nextHold);
    await webRtcEngine.toggleHold(nextHold);
  };

  const handleDtmfPress = (digit) => {
    webRtcEngine.sendDtmf(digit);
  };

  const isMeCaller = callData.callerNumber === webRtcEngine.currentUser?.phoneNumber;
  const peerName = isMeCaller ? (callData.calleeName || 'User') : (callData.callerName || 'Caller');
  const peerPhone = isMeCaller ? (callData.calleeNumber || '') : (callData.callerNumber || '');
  const rawAvatar = isMeCaller ? callData.calleeProfilePic : callData.callerProfilePic;
  const peerAvatar = formatAvatarUrl(rawAvatar);
  const isAnswered = callData.status === 'ANSWERED';

  return (
    <div className="liquid-glass-bg" style={{ position: 'relative', height: '100%', width: '100%', overflow: 'hidden' }}>
      
      {/* Floating Notice / Upgrade Feedback Banner */}
      {upgradeNotice && (
        <div style={{
          position: 'absolute', top: '24px', left: '50%', transform: 'translateX(-50%)',
          backgroundColor: 'rgba(15, 23, 42, 0.9)', color: '#fff', padding: '10px 24px', borderRadius: '24px',
          fontWeight: '800', fontSize: '13px', zIndex: 110, border: '1px solid #FFD600',
          boxShadow: '0 8px 24px rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', gap: '8px',
          backdropFilter: 'blur(12px)'
        }}>
          <span>{upgradeNotice}</span>
        </div>
      )}

      {callData.videoUpgradeStatus === 'REQUESTED' && webRtcEngine.didIRequestVideoUpgrade && !upgradeNotice && (
        <div style={{
          position: 'absolute', top: '24px', left: '50%', transform: 'translateX(-50%)',
          backgroundColor: 'rgba(15, 23, 42, 0.9)', color: '#fff', padding: '8px 20px', borderRadius: '24px',
          fontWeight: '800', fontSize: '13px', zIndex: 110, border: '1px solid rgba(0, 230, 118, 0.5)',
          boxShadow: '0 8px 24px rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', gap: '10px',
          backdropFilter: 'blur(12px)'
        }}>
          <span style={{ width: '8px', height: '8px', borderRadius: '50%', backgroundColor: '#00E676', display: 'inline-block' }}></span>
          <span>Requesting Video Call... Waiting for answer</span>
        </div>
      )}

      {/* Remote Video / Audio Element */}
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

      {/* Local Video (Floating Picture-in-Picture) */}
      {isVideoCall && (
        <div style={{
          position: 'absolute', top: '24px', right: '24px',
          width: '124px', height: '170px', borderRadius: '16px',
          overflow: 'hidden', border: '1.5px solid rgba(255, 255, 255, 0.3)',
          boxShadow: '0 12px 30px rgba(0,0,0,0.6)', zIndex: 30,
          background: '#0B0F19'
        }}>
          <video
            ref={localVideoRefCb}
            autoPlay
            playsInline
            muted
            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
          />
          <button
            type="button"
            onClick={() => webRtcEngine.switchCamera()}
            style={{
              position: 'absolute', bottom: '6px', right: '6px',
              width: '28px', height: '28px', borderRadius: '50%',
              backgroundColor: 'rgba(0, 0, 0, 0.65)', border: '1px solid rgba(255,255,255,0.4)',
              color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer', padding: 0
            }}
            title="Flip Camera"
          >
            <SwitchCamera size={14} />
          </button>
        </div>
      )}

      {/* Audio Call Liquid Glass Central Hero */}
      {!isVideoCall && (
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          padding: '24px', zIndex: 10
        }}>
          {/* Avatar with Pulsing Halo Waves */}
          <div style={{ position: 'relative', width: '144px', height: '144px', marginBottom: '32px' }}>
            {isAnswered && !isHeld && (
              <>
                <div className="pulsing-halo-ring" />
                <div className="pulsing-halo-ring-2" />
              </>
            )}

            <div style={{
              width: '100%', height: '100%', borderRadius: '50%',
              backgroundColor: '#1E293B', border: '3px solid rgba(255, 255, 255, 0.35)',
              boxShadow: '0 16px 40px rgba(0, 0, 0, 0.5), inset 0 2px 4px rgba(255, 255, 255, 0.2)',
              overflow: 'hidden', display: 'flex', alignItems: 'center', justifyContent: 'center',
              position: 'relative', zIndex: 2
            }}>
              {peerAvatar ? (
                <img 
                  src={peerAvatar} 
                  alt={peerName} 
                  style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                  onError={(e) => {
                    if (e.target.parentElement) e.target.style.display = 'none';
                  }}
                />
              ) : (
                <div style={{
                  fontSize: '48px', fontWeight: '900', color: '#00E676',
                  textShadow: '0 2px 8px rgba(0,0,0,0.5)'
                }}>
                  {peerName.charAt(0).toUpperCase()}
                </div>
              )}
            </div>
          </div>

          {/* Peer Name & Phone */}
          <h2 style={{ 
            fontSize: '36px', fontWeight: '900', marginBottom: '6px', 
            color: '#FFFFFF', textAlign: 'center', letterSpacing: '0.5px',
            textShadow: '0 2px 12px rgba(0,0,0,0.6)'
          }}>
            {peerName}
          </h2>

          {peerPhone && (
            <div style={{ fontSize: '15px', fontWeight: '600', color: 'rgba(255, 255, 255, 0.7)', marginBottom: '16px' }}>
              {peerPhone}
            </div>
          )}

          {/* Status & Duration Pill */}
          {isHeld ? (
            <div className="on-hold-pulse" style={{ 
              padding: '8px 22px', backgroundColor: 'rgba(255, 179, 0, 0.25)', 
              color: '#FFB300', border: '1.5px solid #FFB300',
              borderRadius: '9999px', fontWeight: '900', fontSize: '14px', letterSpacing: '1px',
              display: 'flex', alignItems: 'center', gap: '8px', backdropFilter: 'blur(16px)'
            }}>
              <Pause size={16} /> ON HOLD
            </div>
          ) : (
            <div className="liquid-glass-chip" style={{ 
              padding: '8px 22px', fontWeight: '800', fontSize: '14px', letterSpacing: '1.5px',
              boxShadow: '0 6px 18px rgba(0,0,0,0.3)', display: 'flex', alignItems: 'center', gap: '8px'
            }}>
              {isAnswered && (
                <span style={{ width: '8px', height: '8px', borderRadius: '50%', backgroundColor: '#00E676', display: 'inline-block' }} />
              )}
              <span>{isAnswered && durationStr ? durationStr : callData.status}</span>
            </div>
          )}
        </div>
      )}

      {/* In-Call DTMF Dialpad Overlay Modal */}
      {showDtmfKeypad && (
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
          backgroundColor: 'rgba(5, 8, 17, 0.85)', backdropFilter: 'blur(20px)',
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          zIndex: 80, padding: '20px'
        }}>
          <div className="liquid-glass-card" style={{ width: '100%', maxWidth: '320px', padding: '24px 20px', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <div style={{ width: '100%', display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '18px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Grid size={20} color="#00E676" />
                <span style={{ fontWeight: '800', fontSize: '16px', color: '#fff' }}>Touch Tone Keypad</span>
              </div>
              <button 
                type="button"
                onClick={() => setShowDtmfKeypad(false)}
                style={{ background: 'none', border: 'none', color: '#fff', cursor: 'pointer', padding: '4px' }}
              >
                <X size={20} />
              </button>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '14px', width: '100%', maxWidth: '240px' }}>
              {DTMF_KEYS.map(digit => (
                <div key={digit} style={{ display: 'flex', justifyContent: 'center' }}>
                  <button
                    type="button"
                    className="glass-keypad-btn"
                    onClick={() => handleDtmfPress(digit)}
                    style={{ width: '64px', height: '64px' }}
                  >
                    <span style={{ fontSize: '24px', fontWeight: '800' }}>{digit}</span>
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Floating Liquid Glass Controls Dock */}
      <div style={{
        position: 'absolute', bottom: '34px', left: '50%', transform: 'translateX(-50%)',
        display: 'flex', alignItems: 'center', gap: '14px', zIndex: 50,
        padding: '12px 18px', borderRadius: '36px',
        background: 'rgba(15, 23, 42, 0.55)', backdropFilter: 'blur(24px)',
        WebkitBackdropFilter: 'blur(24px)', border: '1px solid rgba(255, 255, 255, 0.16)',
        boxShadow: '0 16px 40px rgba(0, 0, 0, 0.5)'
      }}>
        {/* Mute Button */}
        <button 
          type="button"
          className={`glass-control-btn ${isMuted ? 'active' : ''}`}
          onClick={toggleMute}
          title={isMuted ? "Unmute Microphone" : "Mute Microphone"}
        >
          {isMuted ? <MicOff size={24} color="#00E676" /> : <Mic size={24} color="#fff" />}
        </button>

        {/* DTMF Keypad Overlay Button */}
        <button 
          type="button"
          className={`glass-control-btn ${showDtmfKeypad ? 'active' : ''}`}
          onClick={() => setShowDtmfKeypad(!showDtmfKeypad)}
          title="Keypad (DTMF Dial tones for IVR)"
        >
          <Grid size={24} color={showDtmfKeypad ? "#00E676" : "#fff"} />
        </button>

        {/* Audio Output (Speaker / Headphones) */}
        {audioOutputs.length > 0 && (
          <button 
            type="button"
            className={`glass-control-btn ${isHeadphoneActive() ? 'active' : ''}`}
            onClick={() => {
              if (audioOutputs.length > 1) setShowDeviceModal(true);
              else refreshAudioOutputs();
            }}
            title="Audio Output Device (Speaker / Headset)"
          >
            {isHeadphoneActive() ? <Headphones size={24} color="#00E676" /> : <Volume2 size={24} color="#fff" />}
          </button>
        )}

        {/* Hold / Resume Call Button */}
        <button 
          type="button"
          className={`glass-control-btn ${isHeld ? 'warn' : ''}`}
          onClick={toggleHoldCall}
          title={isHeld ? "Resume Call" : "Hold Call"}
        >
          {isHeld ? <Play size={24} color="#FFB300" /> : <Pause size={24} color="#fff" />}
        </button>

        {/* Video Toggle / Upgrade Button */}
        {isVideoCall ? (
          <button 
            type="button"
            className={`glass-control-btn ${!isVideoEnabled ? 'warn' : ''}`}
            onClick={toggleVideo}
            title={isVideoEnabled ? "Turn Off Video" : "Turn On Video"}
          >
            {!isVideoEnabled ? <VideoOff size={24} color="#FFB300" /> : <Video size={24} color="#fff" />}
          </button>
        ) : (
          <button 
            type="button"
            className="glass-control-btn"
            onClick={async () => {
              setUpgradeNotice("Requesting video call...");
              await webRtcEngine.requestVideoUpgrade();
            }}
            title="Switch to Video Call"
          >
            <Video size={24} color="#fff" />
          </button>
        )}

        {/* End Call Button */}
        <button 
          type="button"
          className="glass-control-btn danger"
          onClick={onEndCall}
          title="End Call"
        >
          <PhoneOff size={26} color="#fff" />
        </button>
      </div>

      {/* Audio Output Device Modal */}
      {showDeviceModal && (
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
          backgroundColor: 'rgba(5, 8, 17, 0.85)', backdropFilter: 'blur(16px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 100, padding: '20px'
        }}>
          <div className="liquid-glass-card" style={{ width: '100%', maxWidth: '380px', padding: '24px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Headphones size={22} color="#00E676" />
                <h3 style={{ fontSize: '18px', fontWeight: '800', margin: 0, color: '#fff' }}>Audio Output Device</h3>
              </div>
              <button 
                type="button"
                onClick={() => setShowDeviceModal(false)}
                style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#fff', padding: '4px' }}
              >
                <X size={20} />
              </button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {audioOutputs.map((device, index) => {
                const isSelected = (selectedDeviceId === device.deviceId) || (!selectedDeviceId && index === 0);
                const label = device.label || `Speaker / Device ${index + 1}`;
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
                    style={{
                      padding: '12px 16px',
                      borderRadius: '14px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      cursor: 'pointer',
                      backgroundColor: isSelected ? 'rgba(0, 230, 118, 0.2)' : 'rgba(255, 255, 255, 0.08)',
                      border: isSelected ? '1px solid #00E676' : '1px solid rgba(255, 255, 255, 0.12)',
                      transition: 'all 0.18s ease'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                      {isHeadphone ? <Headphones size={18} color={isSelected ? "#00E676" : "#fff"} /> : <Volume2 size={18} color={isSelected ? "#00E676" : "#fff"} />}
                      <span style={{ fontWeight: '700', fontSize: '13px', color: '#fff' }}>{label}</span>
                    </div>
                    {isSelected && <Check size={18} color="#00E676" />}
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* Video Upgrade Request Modal */}
      {videoUpgradeRequested && (
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
          backgroundColor: 'rgba(5, 8, 17, 0.85)', backdropFilter: 'blur(16px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 100, padding: '20px'
        }}>
          <div className="liquid-glass-card" style={{ width: '100%', maxWidth: '340px', padding: '24px', textAlign: 'center' }}>
            <h3 style={{ fontSize: '22px', fontWeight: '900', marginBottom: '12px', color: '#fff' }}>Switch to Video Call</h3>
            <p style={{ fontWeight: '600', marginBottom: '24px', color: 'rgba(255, 255, 255, 0.8)', fontSize: '14px' }}>
              {peerName} wants to turn on camera and switch this to a video call.
            </p>
            <div style={{ display: 'flex', gap: '14px' }}>
              <button 
                type="button"
                className="neo-btn" 
                style={{ flex: 1, backgroundColor: 'rgba(255, 255, 255, 0.15)', color: '#fff', border: '1px solid rgba(255,255,255,0.2)' }}
                onClick={() => {
                  setVideoUpgradeRequested(false);
                  webRtcEngine.declineVideoUpgrade();
                }}
              >
                Decline
              </button>
              <button 
                type="button"
                className="neo-btn" 
                style={{ flex: 1, backgroundColor: '#00E676', color: '#000', fontWeight: '900', border: 'none' }}
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
