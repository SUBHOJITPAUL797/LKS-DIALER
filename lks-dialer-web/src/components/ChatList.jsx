import React, { useState, useEffect } from 'react';
import { 
  MessageSquare, Plus, Search, Check, CheckCheck, X, Users 
} from 'lucide-react';
import { db } from '../lib/firebase';
import { collection, query, onSnapshot } from 'firebase/firestore';
import { chatRepositoryWeb, numbersMatch } from '../lib/ChatRepositoryWeb';
import { webRtcEngine, isUserOnline } from '../lib/WebRtcEngine';
import { formatAvatarUrl } from '../lib/ImageUtils';
import { allCountries, defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';

export default function ChatList({ onOpenChat, activePeerNumber }) {
  const [conversations, setConversations] = useState([]);
  const [search, setSearch] = useState('');
  const [showNewChatModal, setShowNewChatModal] = useState(false);
  const [registeredUsers, setRegisteredUsers] = useState([]);
  const [usersMap, setUsersMap] = useState({});
  const [nowTick, setNowTick] = useState(Date.now());
  const [loadingUsers, setLoadingUsers] = useState(false);

  // New Chat Modal state
  const [selectedCountry, setSelectedCountry] = useState(defaultCountry);
  const [manualPhone, setManualPhone] = useState('');
  const [userSearch, setUserSearch] = useState('');

  // New Group Modal state
  const [showNewGroupModal, setShowNewGroupModal] = useState(false);
  const [newGroupName, setNewGroupName] = useState('');
  const [selectedGroupMembers, setSelectedGroupMembers] = useState(new Set());
  const [groupUserSearch, setGroupUserSearch] = useState('');
  const [creatingGroup, setCreatingGroup] = useState(false);

  // Periodically tick for online staleness calculations
  useEffect(() => {
    const timer = setInterval(() => setNowTick(Date.now()), 10000);
    return () => clearInterval(timer);
  }, []);

  // Real-time listener for users collection to track online presence
  useEffect(() => {
    const q = query(collection(db, 'users'));
    const unsubscribe = onSnapshot(q, (snapshot) => {
      const map = {};
      const list = [];
      const myPhone = webRtcEngine.currentUser?.phoneNumber;
      snapshot.forEach(docSnap => {
        const d = docSnap.data() || {};
        const phone = d.phoneNumber || docSnap.id;
        if (phone) {
          const userData = { ...d, id: docSnap.id, phoneNumber: String(phone) };
          map[phone] = userData;
          const clean = String(phone).replace(/[^0-9]/g, '');
          if (clean) {
            map[clean] = userData;
            if (clean.length >= 10) {
              map[clean.slice(-10)] = userData;
            }
          }
          if (myPhone && !numbersMatch(phone, myPhone)) {
            list.push({
              id: docSnap.id,
              displayName: d.displayName || phone || 'Unknown User',
              ...d,
              profilePictureUrl: formatAvatarUrl(d.profilePictureUrl) || '',
              phoneNumber: String(phone)
            });
          }
        }
      });
      setUsersMap(map);
      if (list.length > 0) {
        setRegisteredUsers(list);
      }
    }, (err) => {
      console.warn('Failed to listen to users collection in ChatList:', err);
    });

    return () => unsubscribe();
  }, []);

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
      {/* Header with New Chat & Group Actions */}
      <div style={{ 
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        borderBottom: '4px solid #000', paddingBottom: '10px'
      }}>
        <h2 style={{ fontSize: '28px', fontWeight: '900', margin: 0, letterSpacing: '-0.5px' }}>
          CHATS
        </h2>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <button
            onClick={() => {
              setShowNewGroupModal(true);
              setNewGroupName('');
              setSelectedGroupMembers(new Set());
              setGroupUserSearch('');
              if (registeredUsers.length === 0) openNewChatModal();
            }}
            title="Create New Group"
            className="neo-box"
            style={{
              height: '36px',
              padding: '0 12px',
              borderRadius: '10px',
              backgroundColor: '#F0FDF4',
              color: '#008069',
              border: '2px solid #000',
              boxShadow: '2px 2px 0 #000',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              fontWeight: '800',
              fontSize: '13px',
              cursor: 'pointer',
              transition: 'transform 0.1s ease, box-shadow 0.1s ease'
            }}
          >
            <Users size={16} strokeWidth={2.5} />
            <span>Group</span>
          </button>
          <button
            onClick={openNewChatModal}
            title="Start New Chat"
            className="neo-box"
            style={{
              height: '36px',
              padding: '0 14px',
              borderRadius: '10px',
              backgroundColor: 'var(--primary)',
              color: '#fff',
              border: '2px solid #000',
              boxShadow: '2px 2px 0 #000',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
              fontWeight: '800',
              fontSize: '13px',
              cursor: 'pointer',
              transition: 'transform 0.1s ease, box-shadow 0.1s ease'
            }}
          >
            <Plus size={16} strokeWidth={3} />
            <span>Chat</span>
          </button>
        </div>
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
        <div className="neo-box" style={{ padding: '36px 20px', textAlign: 'center', backgroundColor: '#FFFFFF', border: '1px solid rgba(0,0,0,0.08)' }}>
          <div style={{
            width: '64px', height: '64px', borderRadius: '50%',
            backgroundColor: 'var(--primary-light)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            margin: '0 auto 16px auto', boxShadow: '0 4px 14px rgba(0, 128, 105, 0.15)'
          }}>
            <MessageSquare size={30} color="var(--primary)" />
          </div>
          <h3 style={{ fontSize: '19px', fontWeight: '800', marginBottom: '8px', color: 'var(--text-main)' }}>
            No Conversations Yet
          </h3>
          <p style={{ color: 'var(--text-muted)', fontWeight: '500', marginBottom: '16px', fontSize: '14px' }}>
            Start an End-to-End Encrypted chat with zero server retention.
          </p>
          <button 
            className="neo-btn" 
            style={{ margin: '0 auto', fontSize: '14px', padding: '12px 22px' }}
            onClick={openNewChatModal}
          >
            <Plus size={18} /> Start First Chat
          </button>
        </div>
      ) : (
        filteredConversations.map(conv => {
          const isGroup = Boolean(conv.isGroup || (conv.phoneNumber && conv.phoneNumber.startsWith('group_')));
          const cleanPeer = String(conv.phoneNumber || '').replace(/[^0-9]/g, '');
          const peerUserData = !isGroup ? (usersMap[conv.phoneNumber] || usersMap[cleanPeer] || (cleanPeer.length >= 10 ? usersMap[cleanPeer.slice(-10)] : null)) : null;
          const isOnline = !isGroup && isUserOnline(peerUserData, nowTick);
          const avatarUrl = formatAvatarUrl(peerUserData?.profilePictureUrl) || formatAvatarUrl(conv.profilePicUrl);
          const contactDisplayName = conv.contactName || peerUserData?.displayName || conv.phoneNumber;
          const initial = (contactDisplayName || '?')[0]?.toUpperCase() || '?';
          const isSelected = activePeerNumber && (isGroup ? conv.phoneNumber === activePeerNumber : numbersMatch(conv.phoneNumber, activePeerNumber));

          return (
            <div
              key={conv.phoneNumber}
              onClick={() => onOpenChat(conv.phoneNumber, contactDisplayName, avatarUrl)}
              className="neo-box"
              style={{
                padding: '14px 16px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                cursor: 'pointer',
                backgroundColor: isSelected ? '#E8FAF6' : 'var(--card-bg)',
                boxShadow: isSelected ? '0 2px 8px rgba(0, 128, 105, 0.12)' : 'var(--shadow-sm)',
                transform: 'none',
                borderLeft: isSelected ? '4px solid var(--primary)' : '1px solid var(--border-color)',
                borderTop: '1px solid var(--border-color)',
                borderRight: '1px solid var(--border-color)',
                borderBottom: '1px solid var(--border-color)',
                borderRadius: '14px',
                marginBottom: '8px'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '14px', flex: 1, minWidth: 0 }}>
                {/* Avatar */}
                <div style={{ position: 'relative', flexShrink: 0 }}>
                  <div style={{
                    width: '48px', height: '48px', borderRadius: '50%',
                    backgroundColor: '#E0F2F1',
                    color: 'var(--primary)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontWeight: '800', fontSize: '18px', overflow: 'hidden',
                    boxShadow: '0 2px 6px rgba(0,0,0,0.06)'
                  }}>
                    {avatarUrl ? (
                      <img 
                        src={avatarUrl} 
                        alt={contactDisplayName} 
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        onError={(e) => {
                          e.target.style.display = 'none';
                          if (e.target.nextSibling) e.target.nextSibling.style.display = 'block';
                        }}
                      />
                    ) : isGroup ? (
                      <Users size={22} color="var(--primary)" />
                    ) : (
                      <span style={{ display: 'block' }}>
                        {initial}
                      </span>
                    )}
                  </div>
                  {!isGroup && isOnline && (
                    <span style={{
                      position: 'absolute', bottom: 1, right: 1,
                      width: 14, height: 14, borderRadius: '50%',
                      backgroundColor: '#00e676', border: '2.5px solid #fff',
                      boxShadow: '0 0 4px rgba(0,0,0,0.3)',
                      zIndex: 2
                    }} />
                  )}
                </div>

                {/* Title & Preview */}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '4px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0, flex: 1 }}>
                      <h4 style={{
                        margin: 0, fontSize: '17px', fontWeight: '800',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'
                      }}>
                        {contactDisplayName}
                      </h4>
                      {isGroup && (
                        <span style={{
                          fontSize: '10px', fontWeight: '900', backgroundColor: '#E0F2F1',
                          color: '#008069', padding: '1px 6px', borderRadius: '4px',
                          border: '1px solid #008069', flexShrink: 0
                        }}>
                          GROUP
                        </span>
                      )}
                    </div>
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

      {/* NEW GROUP MODAL */}
      {showNewGroupModal && (
        <div style={{
          position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.65)',
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
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <div style={{
                  width: 36, height: 36, borderRadius: '50%', backgroundColor: '#E0F2F1',
                  display: 'flex', alignItems: 'center', justifyContent: 'center'
                }}>
                  <Users size={20} color="#008069" />
                </div>
                <h3 style={{ fontSize: '20px', fontWeight: '900', margin: 0 }}>NEW GROUP CHAT</h3>
              </div>
              <button
                onClick={() => setShowNewGroupModal(false)}
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

            {/* Group Name Input */}
            <div className="neo-box" style={{ padding: '14px', backgroundColor: '#fff' }}>
              <label style={{ fontSize: '12px', fontWeight: '800', display: 'block', marginBottom: '8px' }}>
                GROUP NAME:
              </label>
              <input
                type="text"
                className="neo-input"
                placeholder="e.g. Project Team, Family..."
                style={{ width: '100%', padding: '10px 12px', fontSize: '15px' }}
                value={newGroupName}
                onChange={(e) => setNewGroupName(e.target.value)}
                maxLength={35}
              />
            </div>

            {/* Members Selector */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', flex: 1, minHeight: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: '13px', fontWeight: '900' }}>SELECT MEMBERS:</span>
                <span style={{ fontSize: '12px', fontWeight: '800', color: '#008069' }}>
                  {selectedGroupMembers.size} selected
                </span>
              </div>

              <div style={{ position: 'relative' }}>
                <Search size={16} style={{ position: 'absolute', left: '12px', top: '12px', color: '#000' }} />
                <input
                  type="text"
                  className="neo-input"
                  placeholder="Search contacts..."
                  style={{ padding: '8px 12px 8px 36px', fontSize: '14px' }}
                  value={groupUserSearch}
                  onChange={(e) => setGroupUserSearch(e.target.value)}
                />
              </div>

              <div style={{
                flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column',
                gap: '8px', maxHeight: '220px', paddingRight: '4px'
              }}>
                {registeredUsers.length === 0 ? (
                  <p style={{ textAlign: 'center', padding: '16px', fontWeight: '700', color: '#666' }}>
                    No other contacts available.
                  </p>
                ) : (
                  registeredUsers
                    .filter(u => {
                      const q = (groupUserSearch || '').trim().toLowerCase();
                      if (!q) return true;
                      return (u.displayName && u.displayName.toLowerCase().includes(q)) ||
                             (u.phoneNumber && u.phoneNumber.includes(q));
                    })
                    .map(u => {
                      const isSelected = selectedGroupMembers.has(u.phoneNumber);
                      const uAvatar = formatAvatarUrl(u.profilePictureUrl);
                      const initial = (u.displayName || u.phoneNumber || '?')[0]?.toUpperCase() || '?';

                      return (
                        <div
                          key={u.phoneNumber}
                          onClick={() => {
                            const next = new Set(selectedGroupMembers);
                            if (isSelected) next.delete(u.phoneNumber);
                            else next.add(u.phoneNumber);
                            setSelectedGroupMembers(next);
                          }}
                          className="neo-box"
                          style={{
                            padding: '10px 12px', display: 'flex', alignItems: 'center',
                            justifyContent: 'space-between', cursor: 'pointer',
                            backgroundColor: isSelected ? '#E0F2F1' : '#fff',
                            border: isSelected ? '2px solid #008069' : '1px solid rgba(0,0,0,0.1)'
                          }}
                        >
                          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                            <div style={{
                              width: '36px', height: '36px', borderRadius: '50%',
                              backgroundColor: 'var(--primary)', border: '2px solid #000',
                              display: 'flex', alignItems: 'center', justifyContent: 'center',
                              fontWeight: '900', fontSize: '15px', color: '#fff', overflow: 'hidden'
                            }}>
                              {uAvatar ? (
                                <img src={uAvatar} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                              ) : initial}
                            </div>
                            <div>
                              <div style={{ fontSize: '14px', fontWeight: '800' }}>{u.displayName || u.phoneNumber}</div>
                              <div style={{ fontSize: '12px', color: '#666', fontWeight: '600' }}>{u.phoneNumber}</div>
                            </div>
                          </div>

                          <div style={{
                            width: 22, height: 22, borderRadius: 6,
                            border: '2px solid #000',
                            backgroundColor: isSelected ? '#008069' : '#fff',
                            display: 'flex', alignItems: 'center', justifyContent: 'center'
                          }}>
                            {isSelected && <Check size={16} color="#fff" strokeWidth={3} />}
                          </div>
                        </div>
                      );
                    })
                )}
              </div>
            </div>

            {/* Modal Bottom Actions */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, paddingTop: 4 }}>
              <button
                onClick={() => setShowNewGroupModal(false)}
                style={{ padding: '8px 16px', background: 'none', border: 'none', fontWeight: 800, cursor: 'pointer' }}
              >
                Cancel
              </button>
              <button
                disabled={creatingGroup || !newGroupName.trim() || selectedGroupMembers.size === 0}
                onClick={async () => {
                  if (!newGroupName.trim()) return;
                  if (selectedGroupMembers.size === 0) return;
                  try {
                    setCreatingGroup(true);
                    const group = await chatRepositoryWeb.createGroup(
                      newGroupName.trim(),
                      Array.from(selectedGroupMembers)
                    );
                    setShowNewGroupModal(false);
                    onOpenChat(group.groupId, group.name, '');
                  } catch (e) {
                    alert('Failed to create group: ' + (e.message || e));
                  } finally {
                    setCreatingGroup(false);
                  }
                }}
                className="neo-btn"
                style={{
                  padding: '10px 20px', fontSize: '14px',
                  backgroundColor: '#008069', color: '#fff',
                  opacity: (!newGroupName.trim() || selectedGroupMembers.size === 0) ? 0.5 : 1
                }}
              >
                {creatingGroup ? 'Creating...' : `Create Group (${selectedGroupMembers.size})`}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
