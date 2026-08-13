import React, { useEffect, useRef } from 'react';
import { Phone, PhoneOff, Video } from 'lucide-react';

export default function IncomingCallModal({ callData, onAccept, onDecline }) {
  const audioRef = useRef(null);

  useEffect(() => {
    if (audioRef.current) {
      audioRef.current.play().catch(e => console.warn("Ringtone autoplay blocked by browser:", e));
    }
  }, []);

  if (!callData) return null;

  const isVideoCall = callData.callType === 'VIDEO';
  const callerAvatar = callData.callerProfilePic;

  return (
    <div style={{
      position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
      backgroundColor: 'rgba(0,0,0,0.8)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      zIndex: 100, padding: '20px'
    }}>
      <div className="neo-box" style={{ 
        width: '100%', maxWidth: '360px', padding: '32px 24px', 
        display: 'flex', flexDirection: 'column', alignItems: 'center',
        backgroundColor: 'var(--bg-color)'
      }}>
        <div style={{
          width: '80px', height: '80px', borderRadius: '50%',
          backgroundColor: 'var(--secondary)', border: '3px solid #000',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          marginBottom: '24px', overflow: 'hidden'
        }}>
          {callerAvatar ? (
            <img src={callerAvatar} alt={callData.callerName} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          ) : (
            isVideoCall ? <Video size={32} /> : <Phone size={32} />
          )}
        </div>

        <h3 style={{ fontSize: '28px', fontWeight: '900', marginBottom: '8px', textAlign: 'center' }}>
          {callData.callerName}
        </h3>
        <p style={{ fontSize: '16px', fontWeight: '600', color: '#555', marginBottom: '40px' }}>
          Incoming {isVideoCall ? 'Video' : 'Audio'} Call
        </p>

        <div style={{ display: 'flex', gap: '20px', width: '100%' }}>
          <button 
            className="neo-btn danger" 
            style={{ flex: 1, padding: '16px 0' }}
            onClick={onDecline}
          >
            <PhoneOff size={24} />
          </button>
          
          <button 
            className="neo-btn success" 
            style={{ flex: 1, padding: '16px 0' }}
            onClick={onAccept}
          >
            <Phone size={24} />
          </button>
        </div>
      </div>
      <audio 
        ref={audioRef}
        src={localStorage.getItem('customRingtone') || "https://actions.google.com/sounds/v1/alarms/phone_ringing.ogg"} 
        autoPlay 
        loop 
        style={{ display: 'none' }} 
      />
    </div>
  );
}
