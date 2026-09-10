import React, { useEffect, useState } from 'react';
import { 
  Phone, PhoneIncoming, PhoneOutgoing, PhoneMissed, Video, 
  MessageSquare, Ban, RotateCcw 
} from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { formatAvatarUrl } from '../lib/ImageUtils';

export default function RecentCalls({ onStartCall, onOpenChat }) {
  const [calls, setCalls] = useState([]);
  const [loading, setLoading] = useState(true);
  const [, setBlockedUpdate] = useState(0);

  useEffect(() => {
    loadHistory();
  }, []);

  const loadHistory = async () => {
    try {
      const history = await webRtcEngine.getCallHistory();
      setCalls(history || []);
    } catch (e) {
      console.error("Failed to load call history:", e);
      setCalls([]);
    } finally {
      setLoading(false);
    }
  };

  const getCallIcon = (call) => {
    if (!call) return <PhoneIncoming size={20} color="var(--accent)" />;
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
    try {
      let date;
      if (typeof timestamp === 'number') {
        date = new Date(timestamp);
      } else if (timestamp?.seconds) {
        date = new Date(timestamp.seconds * 1000);
      } else if (typeof timestamp?.toDate === 'function') {
        date = timestamp.toDate();
      } else {
        date = new Date(timestamp);
      }
      return isNaN(date.getTime()) ? '' : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
      return '';
    }
  };

  const handleToggleBlock = async (peerNumber) => {
    if (!peerNumber) return;
    const isBlocked = webRtcEngine.isNumberBlocked(peerNumber);
    if (isBlocked) {
      await webRtcEngine.unblockNumber(peerNumber);
      alert(`Unblocked ${peerNumber}`);
    } else {
      if (confirm(`Block ${peerNumber}? Future calls and messages from this number will be rejected.`)) {
        await webRtcEngine.blockNumber(peerNumber);
        alert(`Blocked ${peerNumber}`);
      }
    }
    setBlockedUpdate(v => v + 1);
  };

  return (
    <div className="scrollable-content" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <h2 style={{ fontSize: '32px', fontWeight: '900', borderBottom: '4px solid #000', paddingBottom: '8px' }}>
        RECENTS
      </h2>
      
      {loading ? (
        <>
          {[1, 2, 3, 4, 5].map(i => (
            <div key={`skeleton-${i}`} className="neo-box" style={{ padding: '16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderColor: '#ccc' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '16px', flex: 1 }}>
                <div className="neo-skeleton-circle" style={{ width: '48px', height: '48px', flexShrink: 0 }} />
                <div style={{ flex: 1 }}>
                  <div className="neo-skeleton" style={{ width: '50%', height: '22px', marginBottom: '8px' }} />
                  <div className="neo-skeleton" style={{ width: '70%', height: '14px' }} />
                </div>
              </div>
              <div style={{ display: 'flex', gap: '12px', marginLeft: '16px' }}>
                <div className="neo-skeleton" style={{ width: '40px', height: '40px' }} />
                <div className="neo-skeleton" style={{ width: '40px', height: '40px' }} />
              </div>
            </div>
          ))}
        </>
      ) : calls.length === 0 ? (
        <div className="neo-box" style={{ padding: '24px', textAlign: 'center' }}>
          <h3>No Recent Calls</h3>
        </div>
      ) : (
        (calls || []).map(call => {
          if (!call) return null;
          const isCaller = call.callerNumber === webRtcEngine.currentUser?.phoneNumber;
          const peerNumber = (isCaller ? call.calleeNumber : call.callerNumber) || "";
          const peerName = (isCaller ? call.calleeName : call.callerName) || peerNumber || "Unknown";
          const rawAvatar = isCaller ? call.calleeProfilePic : call.callerProfilePic;
          const peerAvatar = formatAvatarUrl(rawAvatar);
          const avatarInitial = (peerName || peerNumber || "?")[0]?.toUpperCase() || "?";
          const isMissed = (call.status === 'MISSED' || call.status === 'DECLINED' || (call.status === 'CALLING' && !isCaller));
          const isBlocked = webRtcEngine.isNumberBlocked ? webRtcEngine.isNumberBlocked(peerNumber) : false;
          
          return (
            <div key={call.id || `${peerNumber}-${call.createdAt || Math.random()}`} className="neo-box" style={{ padding: '14px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flex: 1, minWidth: 0 }}>
                <div style={{ 
                  width: '46px', height: '46px', borderRadius: '50%', 
                  backgroundColor: 'var(--accent)', border: '3px solid #000',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontWeight: '900', fontSize: '18px', overflow: 'hidden', flexShrink: 0
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
                    {avatarInitial}
                  </span>
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '2px' }}>
                    <h4 style={{ 
                      margin: 0, fontSize: '16px', fontWeight: 'bold', 
                      whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                      color: isMissed && !isCaller ? 'var(--primary)' : 'inherit'
                    }}>
                      {peerName}
                    </h4>
                    {call.callType === 'VIDEO' && <Video size={15} color="#666" />}
                    {isBlocked && (
                      <span style={{
                        fontSize: '10px', fontWeight: '900', backgroundColor: '#ffebee',
                        color: '#c62828', border: '1px solid #c62828', padding: '1px 5px',
                        borderRadius: '6px'
                      }}>
                        BLOCKED
                      </span>
                    )}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '13px', color: '#555', fontWeight: '600' }}>
                    {getCallIcon(call)}
                    <span>
                      {isCaller ? 'Outgoing' : 'Incoming'} • {formatTime(call.createdAt)} 
                      {(call.status === 'MISSED' || call.status === 'DECLINED') ? ` • ${call.status}` : ''}
                    </span>
                  </div>

                  {/* Call Back Action for Missed Calls */}
                  {isMissed && !isCaller && (
                    <button
                      onClick={() => peerNumber && onStartCall(peerNumber, call.callType || 'AUDIO')}
                      className="neo-box"
                      style={{
                        marginTop: '4px', padding: '2px 8px', fontSize: '11px', fontWeight: '900',
                        backgroundColor: 'var(--primary)', color: '#fff', display: 'inline-flex',
                        alignItems: 'center', gap: '4px', cursor: 'pointer', borderRadius: '6px'
                      }}
                    >
                      <RotateCcw size={12} strokeWidth={3} /> CALL BACK
                    </button>
                  )}
                </div>
              </div>

              {/* Action Buttons Row */}
              <div style={{ display: 'flex', gap: '6px', alignItems: 'center', flexShrink: 0 }}>
                {/* Chat Button */}
                {onOpenChat && (
                  <button 
                    onClick={() => peerNumber && onOpenChat(peerNumber, peerName, rawAvatar || '')}
                    className="neo-box"
                    disabled={!peerNumber}
                    title="Chat"
                    style={{ 
                      width: '36px', height: '36px', padding: 0, display: 'flex', 
                      alignItems: 'center', justifyContent: 'center', cursor: peerNumber ? 'pointer' : 'default',
                      backgroundColor: 'var(--secondary)'
                    }}
                  >
                    <MessageSquare size={18} color="#000" />
                  </button>
                )}

                {/* Audio Call */}
                <button 
                  onClick={() => peerNumber && onStartCall(peerNumber, 'AUDIO')}
                  className="neo-box"
                  disabled={!peerNumber}
                  title="Audio Call"
                  style={{ 
                    width: '36px', height: '36px', padding: 0, display: 'flex', 
                    alignItems: 'center', justifyContent: 'center', cursor: peerNumber ? 'pointer' : 'default',
                    backgroundColor: 'var(--accent)'
                  }}
                >
                  <Phone size={18} color="#000" />
                </button>

                {/* Video Call */}
                <button 
                  onClick={() => peerNumber && onStartCall(peerNumber, 'VIDEO')}
                  className="neo-box"
                  disabled={!peerNumber}
                  title="Video Call"
                  style={{ 
                    width: '36px', height: '36px', padding: 0, display: 'flex', 
                    alignItems: 'center', justifyContent: 'center', cursor: peerNumber ? 'pointer' : 'default',
                    backgroundColor: 'var(--primary)'
                  }}
                >
                  <Video size={18} color="#fff" />
                </button>

                {/* Block / Unblock Button */}
                <button 
                  onClick={() => handleToggleBlock(peerNumber)}
                  className="neo-box"
                  disabled={!peerNumber}
                  title={isBlocked ? "Unblock Number" : "Block Number"}
                  style={{ 
                    width: '36px', height: '36px', padding: 0, display: 'flex', 
                    alignItems: 'center', justifyContent: 'center', cursor: peerNumber ? 'pointer' : 'default',
                    backgroundColor: isBlocked ? '#ffcdd2' : '#ffffff'
                  }}
                >
                  <Ban size={16} color={isBlocked ? "#c62828" : "#666"} />
                </button>
              </div>
            </div>
          );
        })
      )}
    </div>
  );
}
