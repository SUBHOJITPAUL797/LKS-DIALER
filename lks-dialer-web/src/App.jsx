import React, { useState, useEffect } from 'react';
import { Clock, Users, Grid, User as UserIcon, MessageSquare, Download, X } from 'lucide-react';
import Onboarding from './components/Onboarding';
import Dialer from './components/Dialer';
import CallScreen from './components/CallScreen';
import IncomingCallModal from './components/IncomingCallModal';
import RecentCalls from './components/RecentCalls';
import Contacts from './components/Contacts';
import Profile from './components/Profile';
import ChatList from './components/ChatList';
import ChatConversation from './components/ChatConversation';
import DesktopChatPlaceholder from './components/DesktopChatPlaceholder';
import AppDownloadModal, { DIRECT_APK_URL, LATEST_APP_VERSION } from './components/AppDownloadModal';
import { webRtcEngine } from './lib/WebRtcEngine';
import { chatRepositoryWeb } from './lib/ChatRepositoryWeb';
import { formatAvatarUrl } from './lib/ImageUtils';
import appLogo from './assets/app_logo.png';

function App() {
  const [currentUser, setCurrentUser] = useState(null);
  const [activeCall, setActiveCall] = useState(null);
  const [incomingCall, setIncomingCall] = useState(null);
  const [activeTab, setActiveTab] = useState('dialer'); // 'recents', 'contacts', 'chats', 'dialer', 'profile'
  const [activeConversation, setActiveConversation] = useState(null); // { phoneNumber, contactName, profilePicUrl }
  const [unreadChatCount, setUnreadChatCount] = useState(0);
  const [isInitializing, setIsInitializing] = useState(true);
  const [showDownloadModal, setShowDownloadModal] = useState(false);
  const [showMobileBanner, setShowMobileBanner] = useState(true);

  // Responsive desktop detection
  const [isDesktop, setIsDesktop] = useState(() => (typeof window !== 'undefined' ? window.innerWidth >= 768 : false));

  useEffect(() => {
    const handleResize = () => setIsDesktop(window.innerWidth >= 768);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  useEffect(() => {
    // Request browser notification permission
    if ("Notification" in window && Notification.permission === "default") {
      Notification.requestPermission();
    }

    // Check local storage for persistent login
    const savedUser = localStorage.getItem('lksDialerUser');
    if (savedUser) {
      const user = JSON.parse(savedUser);
      webRtcEngine.setCurrentUser(user);
      webRtcEngine.listenForIncomingCalls();
      webRtcEngine.initWebPush();
      chatRepositoryWeb.attachChatListeners(user.phoneNumber);
      setUnreadChatCount(chatRepositoryWeb.getTotalUnreadCount());
      setCurrentUser(user);
    }
    setIsInitializing(false);

    // Subscribe to chat repository updates for unread badge count
    const unsubChat = chatRepositoryWeb.subscribe(() => {
      setUnreadChatCount(chatRepositoryWeb.getTotalUnreadCount());
    });

    // Remove splash screen after initialization
    const splash = document.getElementById('splash-screen');
    if (splash) {
      setTimeout(() => {
        splash.classList.add('fade-out');
        setTimeout(() => splash.remove(), 500); // Wait for transition to finish
      }, 800); // Show splash for at least 800ms
    }

    webRtcEngine.onCallStateChange = (callData) => {
      if (!callData) {
        setActiveCall(null);
        setIncomingCall(null);
        return;
      }

      // If the incoming call was canceled by the caller, it will send status: 'REMOVED'
      if (callData.status === 'REMOVED') {
        if ('serviceWorker' in navigator) {
          navigator.serviceWorker.ready.then(reg => {
            reg.getNotifications().then(notifs => {
              notifs.forEach(n => {
                if (n.tag && n.tag.startsWith('call_')) n.close();
              });
            });
          }).catch(() => {});
        }
        setIncomingCall(prev => (prev && prev.id === callData.id) ? null : prev);
        return;
      }

      const isMeCaller = callData.callerNumber === webRtcEngine.currentUser?.phoneNumber;
      
      if (!isMeCaller && (callData.status === 'CALLING' || callData.status === 'RINGING')) {
        setIncomingCall(callData);

        // Check for pending auto-answer from notification action
        if (window.__autoAnswerCallId === callData.id) {
          delete window.__autoAnswerCallId;
          setTimeout(() => {
            handleAcceptCall();
          }, 400);
        }

        // Show browser notification if tab is in background
        if ("Notification" in window && Notification.permission === "granted" && document.hidden) {
          const avatarUrl = formatAvatarUrl(callData.callerProfilePic);
          const iconUrl = (avatarUrl && (avatarUrl.startsWith('http') || avatarUrl.startsWith('data:image'))) 
            ? avatarUrl 
            : '/icon-192.png';
          const title = `Incoming ${callData.callType === 'VIDEO' ? 'Video' : 'Audio'} Call`;
          const options = {
            body: `${callData.callerName || 'Someone'} is calling you.`,
            icon: iconUrl,
            badge: '/icon-192.png',
            tag: `call_${callData.id}`,
            requireInteraction: true,
            renotify: true,
            vibrate: [500, 250, 500, 250, 500, 250, 500],
            data: {
              url: `/?callId=${encodeURIComponent(callData.id)}`,
              callId: callData.id
            }
          };

          if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
            navigator.serviceWorker.ready.then(reg => {
              reg.showNotification(title, options);
            }).catch(() => {
              try {
                const notif = new Notification(title, options);
                notif.onclick = () => { window.focus(); notif.close(); };
              } catch {}
            });
          } else {
            try {
              const notif = new Notification(title, options);
              notif.onclick = () => { window.focus(); notif.close(); };
            } catch {}
          }
        }
      } else {
        // Dismiss ringing notification when call is answered, ended, or declined
        if ('serviceWorker' in navigator) {
          navigator.serviceWorker.ready.then(reg => {
            reg.getNotifications().then(notifs => {
              notifs.forEach(n => {
                if (n.tag && n.tag.startsWith('call_')) n.close();
              });
            });
          }).catch(() => {});
        }
        setIncomingCall(null);
        setActiveCall(callData);
      }
    };

    // Listen for Service Worker notification actions (Answer/Decline/Open Chat)
    let swMsgHandler = null;
    if ('serviceWorker' in navigator) {
      swMsgHandler = (event) => {
        const { type, action, data } = event.data || {};
        if (type === 'NOTIFICATION_ACTION') {
          console.log('[App] Received NOTIFICATION_ACTION from SW:', action, data);
          if (data?.type === 'incoming_call') {
            if (action === 'decline') {
              webRtcEngine.declineCall(data.callId);
              setIncomingCall(null);
            } else if (action === 'answer') {
              if (incomingCall && incomingCall.id === data.callId) {
                handleAcceptCall();
              } else {
                window.__autoAnswerCallId = data.callId;
              }
            }
          } else if (data?.type === 'chat_message' && data?.peerNumber) {
            handleOpenChat(data.peerNumber, data.callerName, data.callerProfilePic);
          }
        }
      };
      navigator.serviceWorker.addEventListener('message', swMsgHandler);
    }

    // Process initial URL parameters (e.g. from notification clicks)
    try {
      const urlParams = new URLSearchParams(window.location.search);
      const targetTab = urlParams.get('tab');
      const peer = urlParams.get('peer');
      const callId = urlParams.get('callId');
      const autoAnswer = urlParams.get('autoAnswer');

      if (targetTab === 'chats' && peer) {
        handleOpenChat(peer, urlParams.get('callerName') || peer, urlParams.get('callerProfilePic') || '');
      }
      if (callId && autoAnswer === 'true') {
        window.__autoAnswerCallId = callId;
      }
      if (window.location.search) {
        window.history.replaceState({}, document.title, window.location.pathname);
      }
    } catch (e) {
      console.warn('Failed to parse URL query params:', e);
    }

    return () => {
      unsubChat();
      chatRepositoryWeb.detachChatListeners();
      webRtcEngine.stopPresenceHeartbeat(true);
      if (swMsgHandler && 'serviceWorker' in navigator) {
        navigator.serviceWorker.removeEventListener('message', swMsgHandler);
      }
    };
  }, [incomingCall]);

  const handleRegister = async (phone, name) => {
    const user = await webRtcEngine.registerUser(phone, name);
    localStorage.setItem('lksDialerUser', JSON.stringify(user));
    webRtcEngine.initWebPush();
    chatRepositoryWeb.attachChatListeners(user.phoneNumber);
    setUnreadChatCount(chatRepositoryWeb.getTotalUnreadCount());
    setCurrentUser(user);
  };

  const handleStartCall = async (number, type) => {
    try {
      await webRtcEngine.startCall(number, type);
    } catch (e) {
      alert(e.message);
    }
  };

  const handleAcceptCall = async () => {
    if (incomingCall) {
      try {
        await webRtcEngine.acceptCall(incomingCall.id, incomingCall.offer, incomingCall.callType);
        setIncomingCall(null);
      } catch (e) {
        console.error("Failed to answer call:", e);
        alert("Failed to answer call: " + e.message + "\nPlease check your camera/microphone permissions.");
      }
    }
  };

  const handleDeclineCall = async () => {
    if (incomingCall) {
      await webRtcEngine.declineCall(incomingCall.id);
      setIncomingCall(null);
    }
  };

  const handleEndCall = async () => {
    await webRtcEngine.endCall();
  };

  const handleOpenChat = (phoneNumber, contactName, profilePicUrl) => {
    setActiveConversation({
      phoneNumber,
      contactName: contactName || phoneNumber,
      profilePicUrl: profilePicUrl || ''
    });
    setActiveTab('chats');
  };

  if (isInitializing) return null;

  if (!currentUser) {
    return (
      <div className="app-container">
        <Onboarding 
          onRegister={handleRegister} 
          onOpenDownloadModal={() => setShowDownloadModal(true)} 
        />
        <AppDownloadModal 
          isOpen={showDownloadModal} 
          onClose={() => setShowDownloadModal(false)} 
        />
      </div>
    );
  }

  if (activeCall) {
    return (
      <div className="app-container" style={{ background: '#000' }}>
        <CallScreen callData={activeCall} onEndCall={handleEndCall} />
      </div>
    );
  }

  // Mobile full-screen chat conversation view
  if (!isDesktop && activeConversation) {
    return (
      <div className="app-container">
        <ChatConversation
          peerNumber={activeConversation.phoneNumber}
          peerName={activeConversation.contactName}
          peerAvatar={activeConversation.profilePicUrl}
          onBack={() => setActiveConversation(null)}
          onStartCall={handleStartCall}
          isDesktop={false}
        />
        <IncomingCallModal 
          callData={incomingCall} 
          onAccept={handleAcceptCall} 
          onDecline={handleDeclineCall} 
        />
      </div>
    );
  }

  const renderNavItems = () => (
    <>
      {isDesktop && (
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: '12px',
          padding: '6px 8px 18px 8px',
          borderBottom: '3px solid #000',
          marginBottom: '6px'
        }}>
          <div className="neo-box" style={{
            width: '42px',
            height: '42px',
            borderRadius: '12px',
            backgroundColor: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            overflow: 'hidden',
            flexShrink: 0
          }}>
            <img src={appLogo} alt="Logo" style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
          </div>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: '16px', fontWeight: '900', letterSpacing: '0.5px', whiteSpace: 'nowrap' }}>
              LKS DIALER
            </div>
            <div style={{ fontSize: '11px', fontWeight: '700', color: '#666' }}>
              Web Edition
            </div>
          </div>
        </div>
      )}

      <div 
        className={`nav-item ${activeTab === 'dialer' ? 'active' : ''}`}
        onClick={() => setActiveTab('dialer')}
      >
        <Grid size={22} />
        <span>Keypad</span>
      </div>
      <div 
        className={`nav-item ${activeTab === 'recents' ? 'active' : ''}`}
        onClick={() => setActiveTab('recents')}
      >
        <Clock size={22} />
        <span>Recents</span>
      </div>
      <div 
        className={`nav-item ${activeTab === 'contacts' ? 'active' : ''}`}
        onClick={() => setActiveTab('contacts')}
      >
        <Users size={22} />
        <span>Contacts</span>
      </div>
      <div 
        className={`nav-item ${activeTab === 'chats' ? 'active' : ''}`}
        onClick={() => setActiveTab('chats')}
        style={{ position: 'relative' }}
      >
        <div style={{ position: 'relative', display: 'inline-flex' }}>
          <MessageSquare size={22} />
          {unreadChatCount > 0 && (
            <div style={{
              position: 'absolute', top: '-6px', right: '-10px',
              backgroundColor: 'var(--primary)', color: '#fff',
              fontSize: '10px', fontWeight: '900', borderRadius: '10px',
              padding: '1px 5px', border: '1.5px solid #000',
              boxShadow: '1px 1px 0 #000'
            }}>
              {unreadChatCount > 99 ? '99+' : unreadChatCount}
            </div>
          )}
        </div>
        <span>Chats</span>
      </div>
      <div 
        className={`nav-item ${activeTab === 'profile' ? 'active' : ''}`}
        onClick={() => setActiveTab('profile')}
      >
        <UserIcon size={22} />
        <span>Profile</span>
      </div>

      {isDesktop && (
        <div 
          className="neo-box" 
          style={{
            marginTop: 'auto',
            padding: '12px',
            backgroundColor: '#FFF9C4',
            border: '2.5px solid #000',
            borderRadius: '12px',
            display: 'flex',
            flexDirection: 'column',
            gap: '6px'
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <span style={{ fontSize: '15px' }}>📱</span>
              <span style={{ fontSize: '12px', fontWeight: '900' }}>Android App</span>
            </div>
            <span style={{ fontSize: '10px', fontWeight: '900', backgroundColor: '#00E676', border: '1px solid #000', padding: '1px 5px', borderRadius: '4px' }}>
              {LATEST_APP_VERSION}
            </span>
          </div>
          <div style={{ fontSize: '11px', fontWeight: '700', color: '#555', lineHeight: '1.3' }}>
            24/7 background call ringing & lock screen answer.
          </div>
          <div style={{ display: 'flex', gap: '6px', marginTop: '2px' }}>
            <a
              href={DIRECT_APK_URL}
              download="LKS-DIALER-v2.8.1.apk"
              target="_blank"
              rel="noopener noreferrer"
              className="neo-btn"
              style={{
                flex: 1,
                backgroundColor: '#00E676',
                color: '#000',
                textDecoration: 'none',
                padding: '6px 8px',
                fontSize: '11px',
                fontWeight: '900',
                borderRadius: '6px',
                border: '2px solid #000',
                boxShadow: '2px 2px 0 #000',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '4px'
              }}
            >
              <Download size={12} strokeWidth={2.5} /> Download
            </a>
            <button
              type="button"
              onClick={() => setShowDownloadModal(true)}
              className="neo-btn"
              style={{
                backgroundColor: '#fff',
                color: '#000',
                padding: '6px 8px',
                fontSize: '11px',
                fontWeight: '800',
                borderRadius: '6px',
                border: '2px solid #000',
                boxShadow: '2px 2px 0 #000',
                cursor: 'pointer'
              }}
              title="Install Instructions & Details"
            >
              Info
            </button>
          </div>
        </div>
      )}
    </>
  );

  return (
    <div className="app-container">
      {/* Main Content Workspace */}
      <div className="desktop-main-workspace" style={{ flex: 1, minWidth: 0, height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
        {/* Mobile Top Download Banner */}
        {!isDesktop && showMobileBanner && (
          <div 
            style={{
              backgroundColor: '#00E676',
              borderBottom: '2.5px solid #000',
              padding: '6px 12px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: '8px',
              zIndex: 40,
              flexShrink: 0
            }}
          >
            <div 
              onClick={() => setShowDownloadModal(true)}
              style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer', flex: 1, minWidth: 0 }}
            >
              <span style={{ fontSize: '15px' }}>📱</span>
              <div style={{ fontSize: '12px', fontWeight: '900', color: '#000', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                Get Android App <span style={{ backgroundColor: '#000', color: '#fff', fontSize: '9px', padding: '1px 5px', borderRadius: '4px', marginLeft: '2px' }}>{LATEST_APP_VERSION}</span>
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <a
                href={DIRECT_APK_URL}
                download="LKS-DIALER-v2.8.1.apk"
                target="_blank"
                rel="noopener noreferrer"
                className="neo-box"
                style={{
                  backgroundColor: '#fff',
                  color: '#000',
                  textDecoration: 'none',
                  fontSize: '11px',
                  fontWeight: '900',
                  padding: '3px 8px',
                  borderRadius: '6px',
                  border: '1.5px solid #000',
                  boxShadow: '1.5px 1.5px 0 #000',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '4px'
                }}
              >
                <Download size={12} strokeWidth={3} /> APK
              </a>
              <button
                onClick={() => setShowMobileBanner(false)}
                aria-label="Dismiss banner"
                style={{
                  background: 'transparent',
                  border: 'none',
                  cursor: 'pointer',
                  padding: '2px 4px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontWeight: '900'
                }}
              >
                <X size={15} />
              </button>
            </div>
          </div>
        )}

        {activeTab === 'chats' ? (
          isDesktop ? (
            /* Desktop Split View: Left ChatList, Right ChatConversation or Placeholder */
            <div className="desktop-chats-split">
              <div className="desktop-chatlist-pane">
                <ChatList 
                  onOpenChat={handleOpenChat} 
                  activePeerNumber={activeConversation?.phoneNumber} 
                />
              </div>
              <div className="desktop-chatconvo-pane">
                {activeConversation ? (
                  <ChatConversation
                    peerNumber={activeConversation.phoneNumber}
                    peerName={activeConversation.contactName}
                    peerAvatar={activeConversation.profilePicUrl}
                    onBack={() => setActiveConversation(null)}
                    onStartCall={handleStartCall}
                    isDesktop={true}
                  />
                ) : (
                  <DesktopChatPlaceholder 
                    onOpenDialer={() => setActiveTab('dialer')} 
                  />
                )}
              </div>
            </div>
          ) : (
            /* Mobile View: ChatList (ChatConversation handled by early return if active) */
            <ChatList onOpenChat={handleOpenChat} />
          )
        ) : (
          <div className={isDesktop ? "desktop-tab-content" : "mobile-tab-viewport"} style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden', flex: 1, minHeight: 0 }}>
            <div className={isDesktop ? "desktop-card-container" : ""} style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
              {activeTab === 'recents' && (
                <RecentCalls onStartCall={handleStartCall} onOpenChat={handleOpenChat} />
              )}
              {activeTab === 'contacts' && (
                <Contacts onStartCall={handleStartCall} onOpenChat={handleOpenChat} />
              )}
              {activeTab === 'dialer' && (
                <Dialer onStartCall={handleStartCall} />
              )}
              {activeTab === 'profile' && (
                <Profile onOpenDownloadModal={() => setShowDownloadModal(true)} />
              )}
            </div>
          </div>
        )}
      </div>

      {/* Navigation: Sidebar on desktop, bottom bar on mobile */}
      <div className="bottom-nav">
        {renderNavItems()}
      </div>

      <IncomingCallModal 
        callData={incomingCall} 
        onAccept={handleAcceptCall} 
        onDecline={handleDeclineCall} 
      />

      <AppDownloadModal 
        isOpen={showDownloadModal} 
        onClose={() => setShowDownloadModal(false)} 
      />
    </div>
  );
}

export default App;
