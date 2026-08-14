import React, { useEffect, useState } from 'react';
import { Phone, PhoneCall, PhoneIncoming, PhoneOutgoing, PhoneMissed, Video } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';

export default function RecentCalls({ onStartCall }) {
  const [calls, setCalls] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadHistory();
  }, []);

  const loadHistory = async () => {
    try {
      const history = await webRtcEngine.getCallHistory();
      setCalls(history);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const getCallIcon = (call) => {
    const isCaller = call.callerNumber === webRtcEngine.currentUser?.phoneNumber;
    
    if (call.status === 'DECLINED' || call.status === 'MISSED' || (call.status === 'CALLING' && !isCaller)) {
      return <PhoneMissed size={20} color="var(--primary)" />;
    }
    if (isCaller) {
      return <PhoneOutgoing size={20} color="var(--secondary)" />;
    }
    return <PhoneIncoming size={20} color="var(--accent)" />;
  };

  const formatTime = (timestamp) => {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  if (loading) {
    return <div style={{ padding: '20px', fontWeight: 'bold' }}>Loading...</div>;
  }

  return (
    <div className="scrollable-content" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <h2 style={{ fontSize: '32px', fontWeight: '900', borderBottom: '4px solid #000', paddingBottom: '8px' }}>
        RECENTS
      </h2>
      
      {calls.length === 0 ? (
        <div className="neo-box" style={{ padding: '24px', textAlign: 'center' }}>
          <h3>No Recent Calls</h3>
        </div>
      ) : (
        calls.map(call => {
          const isCaller = call.callerNumber === webRtcEngine.currentUser?.phoneNumber;
          const peerName = isCaller ? call.calleeName : call.callerName;
          const peerNumber = isCaller ? call.calleeNumber : call.callerNumber;
          const peerAvatar = isCaller ? call.calleeProfilePic : call.callerProfilePic;
          
          return (
            <div key={call.id} className="neo-box" style={{ padding: '16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '16px', flex: 1, minWidth: 0 }}>
                <div style={{ 
                  width: '48px', height: '48px', borderRadius: '50%', 
                  backgroundColor: 'var(--accent)', border: '3px solid #000',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontWeight: '900', fontSize: '20px', overflow: 'hidden', flexShrink: 0
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
                  <span style={{ display: peerAvatar ? 'none' : 'block' }}>
                    {peerName?.[0]?.toUpperCase()}
                  </span>
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                    <h4 style={{ 
                      margin: 0, fontSize: '18px', fontWeight: 'bold', 
                      whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                      color: (call.status === 'MISSED' || call.status === 'DECLINED') && !isCaller ? 'var(--primary)' : 'inherit'
                    }}>
                      {peerName}
                    </h4>
                    {call.callType === 'VIDEO' && <Video size={16} color="#666" />}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '14px', color: '#555', fontWeight: '600' }}>
                    {getCallIcon(call)}
                    <span>
                      {isCaller ? 'Outgoing' : 'Incoming'} • {formatTime(call.createdAt)} 
                      {(call.status === 'MISSED' || call.status === 'DECLINED') ? ` • ${call.status}` : ''}
                    </span>
                  </div>
                </div>
              </div>

              <div style={{ display: 'flex', gap: '12px' }}>
                <button 
                  onClick={() => onStartCall(peerNumber, 'AUDIO')}
                  className="neo-box"
                  style={{ 
                    width: '40px', height: '40px', padding: 0, display: 'flex', 
                    alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
                    backgroundColor: 'var(--accent)'
                  }}
                >
                  <Phone size={20} color="#000" />
                </button>
                <button 
                  onClick={() => onStartCall(peerNumber, 'VIDEO')}
                  className="neo-box"
                  style={{ 
                    width: '40px', height: '40px', padding: 0, display: 'flex', 
                    alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
                    backgroundColor: 'var(--primary)'
                  }}
                >
                  <Video size={20} color="#fff" />
                </button>
              </div>
            </div>
          );
        })
      )}
    </div>
  );
}
