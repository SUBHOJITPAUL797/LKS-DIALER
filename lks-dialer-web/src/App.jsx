import React, { useState, useEffect } from 'react';
import { Clock, Users, Grid, User as UserIcon } from 'lucide-react';
import Onboarding from './components/Onboarding';
import Dialer from './components/Dialer';
import CallScreen from './components/CallScreen';
import IncomingCallModal from './components/IncomingCallModal';
import RecentCalls from './components/RecentCalls';
import Contacts from './components/Contacts';
import Profile from './components/Profile';
import { webRtcEngine } from './lib/WebRtcEngine';

function App() {
  const [currentUser, setCurrentUser] = useState(null);
  const [activeCall, setActiveCall] = useState(null);
  const [incomingCall, setIncomingCall] = useState(null);
  const [activeTab, setActiveTab] = useState('dialer'); // 'recents', 'contacts', 'dialer', 'profile'
  const [isInitializing, setIsInitializing] = useState(true);

  useEffect(() => {
    // Check local storage for persistent login
    const savedUser = localStorage.getItem('lksDialerUser');
    if (savedUser) {
      const user = JSON.parse(savedUser);
      webRtcEngine.setCurrentUser(user);
      webRtcEngine.listenForIncomingCalls();
      webRtcEngine.initWebPush();
      setCurrentUser(user);
    }
    setIsInitializing(false);

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
      } else {
        setIncomingCall(null);
        setActiveCall(callData);
      }
    };
  }, []);

  const handleRegister = async (phone, name) => {
    const user = await webRtcEngine.registerUser(phone, name);
    localStorage.setItem('lksDialerUser', JSON.stringify(user));
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

  return (
    <div className="app-container">
      
      {activeTab === 'recents' && <RecentCalls onStartCall={handleStartCall} />}
      {activeTab === 'contacts' && <Contacts onStartCall={handleStartCall} />}
      {activeTab === 'dialer' && <Dialer onStartCall={handleStartCall} />}
      {activeTab === 'profile' && <Profile />}

      <div className="bottom-nav">
        <div 
          className={`nav-item ${activeTab === 'recents' ? 'active' : ''}`}
          onClick={() => setActiveTab('recents')}
        >
          <Clock size={24} />
          <span>Recents</span>
        </div>
        <div 
          className={`nav-item ${activeTab === 'contacts' ? 'active' : ''}`}
          onClick={() => setActiveTab('contacts')}
        >
          <Users size={24} />
          <span>Contacts</span>
        </div>
        <div 
          className={`nav-item ${activeTab === 'dialer' ? 'active' : ''}`}
          onClick={() => setActiveTab('dialer')}
        >
          <Grid size={24} />
          <span>Keypad</span>
        </div>
        <div 
          className={`nav-item ${activeTab === 'profile' ? 'active' : ''}`}
          onClick={() => setActiveTab('profile')}
        >
          <UserIcon size={24} />
          <span>Profile</span>
        </div>
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
