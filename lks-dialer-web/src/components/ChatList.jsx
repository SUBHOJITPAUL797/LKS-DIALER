import React, { useState, useEffect } from 'react';
import { 
  MessageSquare, Plus, Search, Check, CheckCheck, X, UserPlus, Phone 
} from 'lucide-react';
import { chatRepositoryWeb, numbersMatch } from '../lib/ChatRepositoryWeb';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { formatAvatarUrl } from '../lib/ImageUtils';
import { allCountries, defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';

export default function ChatList({ onOpenChat, activePeerNumber }) {
  const [conversations, setConversations] = useState([]);
  const [search, setSearch] = useState('');
  const [showNewChatModal, setShowNewChatModal] = useState(false);
  const [registeredUsers, setRegisteredUsers] = useState([]);
  const [loadingUsers, setLoadingUsers] = useState(false);

  // New Chat Modal state
  const [selectedCountry, setSelectedCountry] = useState(defaultCountry);
  const [manualPhone, setManualPhone] = useState('');
  const [userSearch, setUserSearch] = useState('');

  useEffect(() => {
    // Initial load
    setConversations(chatRepositoryWeb.getConversations());

    // Subscribe to chat updates (incoming messages, acks, status changes)
    const unsubscribe = chatRepositoryWeb.subscribe(() => {
      setConversations(chatRepositoryWeb.getConversations());
    });

    return () => unsubscribe();
  }, []);

  const openNewChatModal = async () => {
    setShowNewChatModal(true);
    setLoadingUsers(true);
    try {
      const users = await webRtcEngine.getRegisteredUsers();
      const myPhone = webRtcEngine.currentUser?.phoneNumber;
      setRegisteredUsers((users || []).filter(u => u && u.phoneNumber && !numbersMatch(u.phoneNumber, myPhone)));
    } catch (e) {
      console.error('Failed to load registered users:', e);
    } finally {
      setLoadingUsers(false);
    }
  };

  const handleStartManualChat = () => {
    if (!manualPhone.trim()) return;
    const fullNumber = formatPhoneNumber(selectedCountry.dialCode, manualPhone);
    setShowNewChatModal(false);
    setManualPhone('');
    onOpenChat(fullNumber, fullNumber, '');
  };

  const formatTime = (timestamp) => {
    if (!timestamp) return '';
    try {
      const d = new Date(timestamp);
      const now = new Date();
      const isToday = d.toDateString() === now.toDateString();
      if (isToday) {
        return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      }
      return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
    } catch {
      return '';
    }
  };

  const renderStatusTicks = (status) => {
    switch (status) {
      case 'READ':
        return <CheckCheck size={16} color="#00C9FF" strokeWidth={2.5} style={{ marginRight: '4px', flexShrink: 0 }} />;
      case 'DELIVERED':
        return <CheckCheck size={16} color="#777" strokeWidth={2} style={{ marginRight: '4px', flexShrink: 0 }} />;
      case 'SENT':
        return <Check size={16} color="#777" strokeWidth={2} style={{ marginRight: '4px', flexShrink: 0 }} />;
      default:
        return null;
    }
  };

  const searchLower = (search || '').trim().toLowerCase();
  const filteredConversations = conversations.filter(c => {
    if (!c) return false;
    const nameMatch = c.contactName ? c.contactName.toLowerCase().includes(searchLower) : false;
    const phoneMatch = c.phoneNumber ? c.phoneNumber.includes(search.trim()) : false;
    const textMatch = c.lastMessageText ? c.lastMessageText.toLowerCase().includes(searchLower) : false;
    return nameMatch || phoneMatch || textMatch;
  });

  const modalUsersFiltered = registeredUsers.filter(u => {
    const q = (userSearch || '').trim().toLowerCase();
    if (!q) return true;
    const nameMatch = u.displayName ? u.displayName.toLowerCase().includes(q) : false;
    const phoneMatch = u.phoneNumber ? u.phoneNumber.includes(q) : false;
    return nameMatch || phoneMatch;
  });

  return (
    <div className="scrollable-content" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      {/* Header with New Chat Action */}
      <div style={{ 
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        borderBottom: '4px solid #000', paddingBottom: '8px'
      }}>
        <h2 style={{ fontSize: '32px', fontWeight: '900', margin: 0 }}>
          CHATS
        </h2>
        <button
          onClick={openNewChatModal}
          className="neo-box"
          style={{
            padding: '8px 16px',
            backgroundColor: 'var(--primary)',
            color: '#fff',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            fontWeight: '900',
            fontSize: '14px',
            cursor: 'pointer'
          }}
        >
          <Plus size={18} strokeWidth={3} />
          <span>NEW CHAT</span>
        </button>
      </div>

      {/* Search Input */}
      <div style={{ position: 'relative' }}>
        <Search size={20} style={{ position: 'absolute', left: '16px', top: '16px', color: '#000' }} />
        <input 
          type="text" 
          className="neo-input" 
          placeholder="Search conversations..." 
          style={{ paddingLeft: '48px' }}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      {/* Conversation List */}
      {filteredConversations.length === 0 ? (
        <div className="neo-box" style={{ padding: '36px 20px', textAlign: 'center' }}>
          <div style={{
            width: '64px', height: '64px', borderRadius: '50%',
            backgroundColor: 'var(--accent)', border: '3px solid #000',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            margin: '0 auto 16px auto', boxShadow: '3px 3px 0 #000'
          }}>
            <MessageSquare size={32} color="#000" />
          </div>
          <h3 style={{ fontSize: '20px', fontWeight: '900', marginBottom: '8px' }}>
            No Conversations Yet
          </h3>
          <p style={{ color: '#666', fontWeight: '600', marginBottom: '16px' }}>
            Start an End-to-End Encrypted chat with zero server retention.
          </p>
          <button 
            className="neo-btn" 
            style={{ margin: '0 auto', fontSize: '15px', padding: '12px 24px' }}
            onClick={openNewChatModal}
          >
            <Plus size={18} /> START FIRST CHAT
          </button>
        </div>
      ) : (
        filteredConversations.map(conv => {
          const avatarUrl = formatAvatarUrl(conv.profilePicUrl);
          const initial = (conv.contactName || conv.phoneNumber || '?')[0]?.toUpperCase() || '?';
          const isSelected = activePeerNumber && numbersMatch(conv.phoneNumber, activePeerNumber);

          return (
            <div
              key={conv.phoneNumber}
              onClick={() => onOpenChat(conv.phoneNumber, conv.contactName, conv.profilePicUrl)}
              className="neo-box"
              style={{
                padding: '16px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                cursor: 'pointer',
                backgroundColor: isSelected ? '#FFE600' : 'var(--card-bg)',
                boxShadow: isSelected ? '4px 4px 0 var(--primary)' : 'var(--shadow-offset) var(--shadow-offset) 0 var(--shadow-color)',
                transform: isSelected ? 'translate(-1px, -1px)' : 'none',
                borderLeft: isSelected ? '6px solid var(--primary)' : 'var(--border-width) solid var(--border-color)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '14px', flex: 1, minWidth: 0 }}>
                {/* Avatar */}
                <div style={{
                  width: '50px', height: '50px', borderRadius: '50%',
                  backgroundColor: 'var(--secondary)', border: '3px solid #000',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontWeight: '900', fontSize: '20px', overflow: 'hidden', flexShrink: 0,
                  boxShadow: '2px 2px 0 #000'
                }}>
                  {avatarUrl && (
                    <img 
                      src={avatarUrl} 
                      alt={conv.contactName} 
                      style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                      onError={(e) => {
                        e.target.style.display = 'none';
                        if (e.target.nextSibling) e.target.nextSibling.style.display = 'block';
                      }}
                    />
                  )}
                  <span style={{ display: avatarUrl ? 'none' : 'block' }}>
                    {initial}
                  </span>
                </div>

                {/* Title & Preview */}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '4px' }}>
                    <h4 style={{
                      margin: 0, fontSize: '17px', fontWeight: '800',
                      whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'
                    }}>
                      {conv.contactName || conv.phoneNumber}
                    </h4>
                    <span style={{ fontSize: '12px', fontWeight: '700', color: '#666', flexShrink: 0, marginLeft: '8px' }}>
                      {formatTime(conv.lastMessageTimestamp)}
                    </span>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <div style={{
                      display: 'flex', alignItems: 'center',
                      fontSize: '14px', fontWeight: conv.unreadCount > 0 ? '800' : '600',
                      color: conv.unreadCount > 0 ? '#000' : '#666',
                      whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                      flex: 1, marginRight: '8px'
                    }}>
                      {conv.lastMessageIsOutgoing && renderStatusTicks(conv.lastMessageStatus)}
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {conv.lastMessageText || 'Tap to chat'}
                      </span>
                    </div>

                    {/* Unread Pill Badge */}
                    {conv.unreadCount > 0 && (
                      <div style={{
                        backgroundColor: 'var(--accent)',
                        color: '#000',
                        border: '2px solid #000',
                        borderRadius: '20px',
                        padding: '2px 8px',
                        fontSize: '12px',
                        fontWeight: '900',
                        flexShrink: 0,
                        boxShadow: '1px 1px 0 #000'
                      }}>
                        {conv.unreadCount}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>
          );
        })
      )}

      {/* NEW CHAT MODAL */}
      {showNewChatModal && (
        <div style={{
          position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.6)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          padding: '20px', zIndex: 100
        }}>
          <div className="neo-box" style={{
            width: '100%', maxWidth: '440px', maxHeight: '85vh',
            display: 'flex', flexDirection: 'column',
            backgroundColor: 'var(--bg-color)', padding: '20px', gap: '16px'
          }}>
            {/* Modal Header */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <h3 style={{ fontSize: '22px', fontWeight: '900', margin: 0 }}>NEW ENCRYPTED CHAT</h3>
              <button
                onClick={() => setShowNewChatModal(false)}
                className="neo-box"
                style={{
                  width: '36px', height: '36px', padding: 0,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  backgroundColor: '#fff', cursor: 'pointer'
                }}
              >
                <X size={20} />
              </button>
            </div>

            {/* Direct Number Input */}
            <div className="neo-box" style={{ padding: '14px', backgroundColor: '#fff' }}>
              <label style={{ fontSize: '12px', fontWeight: '800', display: 'block', marginBottom: '8px' }}>
                ENTER PHONE NUMBER DIRECTLY:
              </label>
              <div style={{ display: 'flex', gap: '8px' }}>
                <select
                  value={selectedCountry.code}
                  onChange={(e) => {
                    const c = allCountries.find(x => x.code === e.target.value);
                    if (c) setSelectedCountry(c);
                  }}
                  className="neo-input"
                  style={{ width: '110px', padding: '10px 6px', fontSize: '14px', cursor: 'pointer' }}
                >
                  {allCountries.map(c => (
                    <option key={c.code} value={c.code}>
                      {c.flag} {c.dialCode}
                    </option>
                  ))}
                </select>
                <input
                  type="tel"
                  className="neo-input"
                  placeholder="Phone number"
                  style={{ flex: 1, padding: '10px 12px', fontSize: '15px' }}
                  value={manualPhone}
                  onChange={(e) => setManualPhone(e.target.value)}
                />
                <button
                  onClick={handleStartManualChat}
                  disabled={!manualPhone.trim()}
                  className="neo-btn"
                  style={{
                    padding: '0 16px', fontSize: '14px',
                    backgroundColor: 'var(--accent)', color: '#000'
                  }}
                >
                  CHAT
                </button>
              </div>
            </div>

            {/* Registered Users List */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', flex: 1, minHeight: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: '13px', fontWeight: '900' }}>REGISTERED CONTACTS:</span>
                <span style={{ fontSize: '12px', fontWeight: '700', color: '#666' }}>{modalUsersFiltered.length} available</span>
              </div>

              <div style={{ position: 'relative' }}>
                <Search size={16} style={{ position: 'absolute', left: '12px', top: '12px', color: '#000' }} />
                <input
                  type="text"
                  className="neo-input"
                  placeholder="Filter users..."
                  style={{ padding: '8px 12px 8px 36px', fontSize: '14px' }}
                  value={userSearch}
                  onChange={(e) => setUserSearch(e.target.value)}
                />
              </div>

              <div style={{
                flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column',
                gap: '8px', maxHeight: '250px', paddingRight: '4px'
              }}>
                {loadingUsers ? (
                  <p style={{ textAlign: 'center', padding: '16px', fontWeight: '700', color: '#666' }}>
                    Loading contacts...
                  </p>
                ) : modalUsersFiltered.length === 0 ? (
                  <p style={{ textAlign: 'center', padding: '16px', fontWeight: '700', color: '#666' }}>
                    No other registered users found.
                  </p>
                ) : (
                  modalUsersFiltered.map(u => {
                    const uAvatar = formatAvatarUrl(u.profilePictureUrl);
                    const initial = (u.displayName || u.phoneNumber || '?')[0]?.toUpperCase() || '?';
                    return (
                      <div
                        key={u.phoneNumber}
                        onClick={() => {
                          setShowNewChatModal(false);
                          onOpenChat(u.phoneNumber, u.displayName || u.phoneNumber, u.profilePictureUrl || '');
                        }}
                        className="neo-box"
                        style={{
                          padding: '10px 12px', display: 'flex', alignItems: 'center',
                          justifyContent: 'space-between', cursor: 'pointer', backgroundColor: '#fff'
                        }}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                          <div style={{
                            width: '38px', height: '38px', borderRadius: '50%',
                            backgroundColor: 'var(--primary)', border: '2px solid #000',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            fontWeight: '900', fontSize: '16px', color: '#fff', overflow: 'hidden'
                          }}>
                            {uAvatar ? (
                              <img src={uAvatar} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                            ) : initial}
                          </div>
                          <div>
                            <div style={{ fontSize: '15px', fontWeight: '800' }}>{u.displayName || u.phoneNumber}</div>
                            <div style={{ fontSize: '12px', color: '#666', fontWeight: '600' }}>{u.phoneNumber}</div>
                          </div>
                        </div>
                        <span style={{
                          fontSize: '11px', fontWeight: '800', backgroundColor: '#e0f7fa',
                          border: '1.5px solid #00838f', color: '#00838f', padding: '2px 6px',
                          borderRadius: '10px'
                        }}>
                          Available
                        </span>
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
