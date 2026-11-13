import React, { useEffect, useRef } from 'react';
import { Phone, PhoneOff, Video } from 'lucide-react';
import { formatAvatarUrl } from '../lib/ImageUtils';

export default function IncomingCallModal({ callData, onAccept, onDecline }) {
  const audioRef = useRef(null);

  useEffect(() => {
    if (audioRef.current) {
      audioRef.current.play().catch(e => console.warn("Ringtone autoplay blocked by browser:", e));
    }
  }, []);

  if (!callData) return null;

  const isVideoCall = callData.callType === 'VIDEO';
  const callerAvatar = formatAvatarUrl(callData.callerProfilePic);
  const callerName = callData.callerName || 'Someone';
  const callerPhone = callData.callerNumber || '';

  return (
    <div style={{
      position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
      backgroundColor: 'rgba(5, 8, 17, 0.85)',
      backdropFilter: 'blur(20px)',
      WebkitBackdropFilter: 'blur(20px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      zIndex: 200, padding: '24px'
    }}>
      <div className="liquid-glass-card" style={{ 
        width: '100%', maxWidth: '360px', padding: '36px 24px', 
        display: 'flex', flexDirection: 'column', alignItems: 'center',
        textAlign: 'center', position: 'relative'
      }}>
        {/* Pulsing Avatar Container */}
        <div style={{ position: 'relative', width: '100px', height: '100px', marginBottom: '24px' }}>
          <div className="pulsing-halo-ring" />
          <div className="pulsing-halo-ring-2" />

          <div style={{
            width: '100%', height: '100%', borderRadius: '50%',
            backgroundColor: '#1E293B', border: '3px solid rgba(0, 230, 118, 0.8)',
            boxShadow: '0 8px 24px rgba(0, 230, 118, 0.35)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            overflow: 'hidden', position: 'relative', zIndex: 2
          }}>
            {callerAvatar ? (
              <img 
                src={callerAvatar} 
                alt={callerName} 
                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                onError={(e) => {
                  if (e.target.parentElement) e.target.style.display = 'none';
                }}
              />
            ) : (
              isVideoCall ? <Video size={40} color="#00E676" /> : <Phone size={40} color="#00E676" />
            )}
          </div>
        </div>

        {/* Caller Name & Subtext */}
        <h3 style={{ fontSize: '26px', fontWeight: '900', color: '#FFFFFF', marginBottom: '6px' }}>
          {callerName}
        </h3>
        
        {callerPhone && (
          <p style={{ fontSize: '13px', fontWeight: '600', color: 'rgba(255, 255, 255, 0.65)', marginBottom: '8px' }}>
            {callerPhone}
          </p>
        )}

        <div className="liquid-glass-chip" style={{
          padding: '6px 16px', fontSize: '12px', fontWeight: '800',
          letterSpacing: '1px', textTransform: 'uppercase', marginBottom: '36px',
          color: '#00E676', border: '1px solid rgba(0, 230, 118, 0.4)'
        }}>
          Incoming {isVideoCall ? 'HD Video' : 'HD Voice'} Call
        </div>

        {/* Action Buttons: Decline (Red) & Accept (Green) */}
        <div style={{ display: 'flex', gap: '20px', width: '100%', maxWidth: '280px', justifyContent: 'center' }}>
          <button 
            type="button"
            className="glass-control-btn danger" 
            style={{ width: '68px', height: '68px' }}
            onClick={onDecline}
            title="Decline Call"
          >
            <PhoneOff size={28} color="#fff" />
          </button>
          
          <button 
            type="button"
            className="glass-control-btn" 
            style={{ 
              width: '68px', height: '68px',
              background: 'linear-gradient(135deg, #00E676 0%, #00C853 100%)',
              border: '2px solid rgba(255, 255, 255, 0.5)',
              boxShadow: '0 8px 24px rgba(0, 230, 118, 0.55)'
            }}
            onClick={onAccept}
            title="Accept Call"
          >
            <Phone size={28} color="#000" />
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
