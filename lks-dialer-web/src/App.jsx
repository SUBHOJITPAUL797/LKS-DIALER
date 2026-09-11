import React, { useState, useEffect } from 'react';
import { Clock, Users, Grid, User as UserIcon, MessageSquare } from 'lucide-react';
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
        setIncomingCall(prev => (prev && prev.id === callData.id) ? null : prev);
        return;
      }

      const isMeCaller = callData.callerNumber === webRtcEngine.currentUser?.phoneNumber;
      
      if (!isMeCaller && (callData.status === 'CALLING' || callData.status === 'RINGING')) {
        setIncomingCall(callData);
        // Show browser notification if tab is in background
        if ("Notification" in window && Notification.permission === "granted" && document.hidden) {
          const avatarUrl = formatAvatarUrl(callData.callerProfilePic);
          const iconUrl = (avatarUrl && avatarUrl.startsWith('http')) ? avatarUrl : '/logo192.png';
          const notif = new Notification("Incoming Call", {
            body: `${callData.callerName} is calling you.`,
            icon: iconUrl,
            requireInteraction: true
          });
          notif.onclick = () => {
            window.focus();
            notif.close();
          };
        }
      } else {
        setIncomingCall(null);
        setActiveCall(callData);
      }
    };

    return () => {
      unsubChat();
      chatRepositoryWeb.detachChatListeners();
    };
  }, []);

  const handleRegister = async (phone, name) => {
    const user = await webRtcEngine.registerUser(phone, name);
    localStorage.setItem('lksDialerUser', JSON.stringify(user));
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
        <Onboarding onRegister={handleRegister} />
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
    </>
  );

  return (
    <div className="app-container">
      {/* Navigation: Sidebar on desktop, bottom bar on mobile */}
      <div className="bottom-nav">
        {renderNavItems()}
      </div>

      {/* Main Content Workspace */}
      <div className="desktop-main-workspace" style={{ flex: 1, minWidth: 0, height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
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
          <div className={isDesktop ? "desktop-tab-content" : "scrollable-content"} style={{ width: '100%', height: '100%' }}>
            <div className={isDesktop ? "desktop-card-container" : ""} style={{ width: '100%' }}>
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
                <Profile />
              )}
            </div>
          </div>
        )}
      </div>

      <IncomingCallModal 
        callData={incomingCall} 
        onAccept={handleAcceptCall} 
        onDecline={handleDeclineCall} 
      />
    </div>
  );
}

export default App;
