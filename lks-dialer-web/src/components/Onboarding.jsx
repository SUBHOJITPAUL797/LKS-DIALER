import React, { useState, useEffect } from 'react';
import { 
  Phone, User, ChevronDown, QrCode, Sparkles, ShieldCheck, 
  Smartphone, CheckCircle2, ArrowRight, Download, Zap, Lock 
} from 'lucide-react';
import { defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';
import CountryCodePickerModal from './CountryCodePickerModal';
import QrLoginView from './QrLoginView';
import { LATEST_APP_VERSION, DIRECT_APK_URL } from './AppDownloadModal';
import appLogo from '../assets/app_logo.png';

// Helper to determine if the user is visiting on a mobile screen or mobile UA
const checkIsMobile = () => {
  if (typeof window === 'undefined') return false;
  const isSmallScreen = window.innerWidth < 768;
  const isMobileUA = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile/i.test(
    navigator.userAgent || ''
  );
  return isSmallScreen || isMobileUA;
};

export default function Onboarding({ onRegister, onOpenDownloadModal }) {
  const [isMobile, setIsMobile] = useState(() => checkIsMobile());
  const [userExplicitSelection, setUserExplicitSelection] = useState(false);
  // Default to 'phone' on mobile devices, and 'qr' on desktop / PC browsers
  const [authMethod, setAuthMethod] = useState(() => (checkIsMobile() ? 'phone' : 'qr'));
  
  const [phone, setPhone] = useState("");
  const [name, setName] = useState("");
  const [selectedCountry, setSelectedCountry] = useState(defaultCountry);
  const [showCountryPicker, setShowCountryPicker] = useState(false);
  const [loading, setLoading] = useState(false);

  // Dynamically adapt on window resize unless user explicitly switched tabs
  useEffect(() => {
    const handleResize = () => {
      const mobile = checkIsMobile();
      setIsMobile(mobile);
      if (!userExplicitSelection) {
        setAuthMethod(mobile ? 'phone' : 'qr');
      }
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [userExplicitSelection]);

  const selectMethod = (method) => {
    setUserExplicitSelection(true);
    setAuthMethod(method);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!phone || !name) return;
    setLoading(true);
    const fullNumber = formatPhoneNumber(selectedCountry.dialCode, phone);
    await onRegister(fullNumber, name);
    setLoading(false);
  };

  const handleQrSuccess = async (userData) => {
    await onRegister(
      userData.phoneNumber, 
      userData.displayName, 
      userData.profilePictureUrl, 
      userData.statusMessage, 
      userData.linkedSessionId
    );
  };

  // =========================================================================
  // 💻 DESKTOP LAYOUT (WhatsApp Web Benchmark: Spread 2-Column Wide View)
  // =========================================================================
  if (!isMobile) {
    return (
      <div className="desktop-onboarding-wrapper">
        {showCountryPicker && (
          <CountryCodePickerModal 
            selectedCountry={selectedCountry}
            onCountrySelected={setSelectedCountry}
            onDismiss={() => setShowCountryPicker(false)}
          />
        )}

        {/* Top Emerald Header Band */}
        <div className="desktop-onboarding-top-band">
          <div style={{ width: '100%', maxWidth: '1040px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
              <div style={{
                width: '42px',
                height: '42px',
                borderRadius: '12px',
                backgroundColor: '#ffffff',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
                overflow: 'hidden'
              }}>
                <img src={appLogo} alt="LKS Logo" style={{ width: '32px', height: '32px', objectFit: 'contain' }} />
              </div>
              <div>
                <div style={{ color: '#ffffff', fontSize: '18px', fontWeight: '900', letterSpacing: '0.5px' }}>
                  LKS DIALER WEB
                </div>
                <div style={{ color: 'rgba(255, 255, 255, 0.85)', fontSize: '11px', fontWeight: '700', display: 'flex', alignItems: 'center', gap: '5px' }}>
                  <span style={{ width: '7px', height: '7px', borderRadius: '50%', backgroundColor: '#00E676' }} />
                  End-to-End Encrypted VoIP & Messaging
                </div>
              </div>
            </div>

            <button
              type="button"
              onClick={onOpenDownloadModal}
              style={{
                backgroundColor: 'rgba(255, 255, 255, 0.18)',
                backdropFilter: 'blur(8px)',
                border: '1px solid rgba(255, 255, 255, 0.35)',
                color: '#ffffff',
                padding: '8px 14px',
                borderRadius: '12px',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                fontSize: '12px',
                fontWeight: '800',
                transition: 'all 0.2s ease'
              }}
              onMouseEnter={e => e.currentTarget.style.backgroundColor = 'rgba(255, 255, 255, 0.28)'}
              onMouseLeave={e => e.currentTarget.style.backgroundColor = 'rgba(255, 255, 255, 0.18)'}
            >
              <span>📱 Android App ({LATEST_APP_VERSION})</span>
              <Download size={14} />
            </button>
          </div>
        </div>

        {/* Floating Spread Desktop Card */}
        <div className="desktop-onboarding-card">
          {/* Card Top Mode Switcher Bar */}
          <div style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '16px 36px',
            borderBottom: '1px solid #f1f5f9',
            backgroundColor: '#ffffff'
          }}>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                type="button"
                onClick={() => selectMethod('qr')}
                style={{
                  padding: '9px 18px',
                  borderRadius: '12px',
                  border: authMethod === 'qr' ? '2px solid #008069' : '1px solid #e2e8f0',
                  backgroundColor: authMethod === 'qr' ? '#E8FAF6' : '#ffffff',
                  color: authMethod === 'qr' ? '#008069' : '#64748b',
                  fontWeight: authMethod === 'qr' ? '800' : '600',
                  fontSize: '13px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  cursor: 'pointer',
                  transition: 'all 0.18s ease'
                }}
              >
                <QrCode size={16} color={authMethod === 'qr' ? '#008069' : '#64748b'} />
                <span>Scan QR Code</span>
                <span style={{
                  fontSize: '10px',
                  padding: '1px 6px',
                  borderRadius: '6px',
                  backgroundColor: authMethod === 'qr' ? '#008069' : '#f1f5f9',
                  color: authMethod === 'qr' ? '#ffffff' : '#64748b',
                  fontWeight: '800'
                }}>
                  Recommended
                </span>
              </button>

              <button
                type="button"
                onClick={() => selectMethod('phone')}
                style={{
                  padding: '9px 18px',
                  borderRadius: '12px',
                  border: authMethod === 'phone' ? '2px solid #008069' : '1px solid #e2e8f0',
                  backgroundColor: authMethod === 'phone' ? '#E8FAF6' : '#ffffff',
                  color: authMethod === 'phone' ? '#008069' : '#64748b',
                  fontWeight: authMethod === 'phone' ? '800' : '600',
                  fontSize: '13px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  cursor: 'pointer',
                  transition: 'all 0.18s ease'
                }}
              >
                <Phone size={16} color={authMethod === 'phone' ? '#008069' : '#64748b'} />
                <span>Phone Number</span>
              </button>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: '#64748b', fontWeight: '600' }}>
              <Lock size={13} color="#008069" />
              <span>Zero-Knowledge Peer Encryption</span>
            </div>
          </div>

          {/* 2-Column Desktop Grid */}
          <div style={{
            display: 'grid',
            gridTemplateColumns: '1.18fr 0.82fr',
            gap: '40px',
            padding: '36px 44px',
            alignItems: 'center'
          }}>
            {/* LEFT COLUMN: Instructions, Explanations & Quick Actions */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
              {authMethod === 'qr' ? (
                <>
                  <div>
                    <h2 style={{ fontSize: '26px', fontWeight: '900', color: '#111b21', margin: '0 0 6px 0', letterSpacing: '-0.3px' }}>
                      To use LKS Dialer on your computer:
                    </h2>
                    <p style={{ fontSize: '14px', color: '#54656f', margin: 0, lineHeight: 1.45 }}>
                      Link your Android app to seamlessly sync conversations, contacts, and VoIP calls directly in your browser.
                    </p>
                  </div>

                  {/* 4 Step Numbered Instructions */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: '14px' }}>
                      <div style={{
                        width: '28px',
                        height: '28px',
                        borderRadius: '50%',
                        backgroundColor: '#008069',
                        color: '#ffffff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '13px',
                        fontWeight: '900',
                        flexShrink: 0
                      }}>
                        1
                      </div>
                      <div style={{ fontSize: '14px', color: '#111b21', lineHeight: '28px' }}>
                        Open <strong>LKS Dialer</strong> on your Android phone
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: '14px' }}>
                      <div style={{
                        width: '28px',
                        height: '28px',
                        borderRadius: '50%',
                        backgroundColor: '#008069',
                        color: '#ffffff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '13px',
                        fontWeight: '900',
                        flexShrink: 0
                      }}>
                        2
                      </div>
                      <div style={{ fontSize: '14px', color: '#111b21', lineHeight: '28px' }}>
                        Tap <strong>Settings ⚙️</strong> or <strong>Menu (⋮)</strong> in the top bar
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: '14px' }}>
                      <div style={{
                        width: '28px',
                        height: '28px',
                        borderRadius: '50%',
                        backgroundColor: '#008069',
                        color: '#ffffff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '13px',
                        fontWeight: '900',
                        flexShrink: 0
                      }}>
                        3
                      </div>
                      <div style={{ fontSize: '14px', color: '#111b21', lineHeight: '28px' }}>
                        Select <strong>Linked Devices</strong> &rarr; tap <strong>"Link a Device"</strong>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: '14px' }}>
                      <div style={{
                        width: '28px',
                        height: '28px',
                        borderRadius: '50%',
                        backgroundColor: '#008069',
                        color: '#ffffff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '13px',
                        fontWeight: '900',
                        flexShrink: 0
                      }}>
                        4
                      </div>
                      <div style={{ fontSize: '14px', color: '#111b21', lineHeight: '28px' }}>
                        Point your camera at this screen to capture the QR code
                      </div>
                    </div>
                  </div>

                  <div style={{
                    padding: '12px 16px',
                    borderRadius: '12px',
                    backgroundColor: '#F0FDF4',
                    border: '1px solid #BBF7D0',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '10px'
                  }}>
                    <ShieldCheck size={18} color="#166534" />
                    <span style={{ fontSize: '12px', color: '#166534', fontWeight: '700' }}>
                      Pairwise E2EE Keys are generated locally and stored securely in this browser.
                    </span>
                  </div>

                  {/* Switch Option & APK download */}
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: '8px', borderTop: '1px solid #f1f5f9' }}>
                    <button
                      type="button"
                      onClick={() => selectMethod('phone')}
                      style={{
                        background: 'none',
                        border: 'none',
                        color: '#008069',
                        fontSize: '13px',
                        fontWeight: '800',
                        cursor: 'pointer',
                        padding: 0,
                        display: 'flex',
                        alignItems: 'center',
                        gap: '4px'
                      }}
                    >
                      <span>Log in with phone number instead</span>
                      <ArrowRight size={14} />
                    </button>

                    <button
                      type="button"
                      onClick={onOpenDownloadModal}
                      style={{
                        background: '#FFF9C4',
                        border: '1px solid #FBC02D',
                        borderRadius: '8px',
                        padding: '6px 12px',
                        color: '#000000',
                        fontSize: '12px',
                        fontWeight: '800',
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '6px'
                      }}
                    >
                      <span>📱 Download Android APK</span>
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <div>
                    <h2 style={{ fontSize: '26px', fontWeight: '900', color: '#111b21', margin: '0 0 6px 0', letterSpacing: '-0.3px' }}>
                      Sign in with Phone Number
                    </h2>
                    <p style={{ fontSize: '14px', color: '#54656f', margin: 0, lineHeight: 1.45 }}>
                      Access your LKS Dialer account directly in any web browser without needing your phone nearby.
                    </p>
                  </div>

                  {/* Feature Highlights */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: '14px' }}>
                      <div style={{
                        width: '32px',
                        height: '32px',
                        borderRadius: '10px',
                        backgroundColor: '#E8FAF6',
                        color: '#008069',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        flexShrink: 0
                      }}>
                        <Zap size={18} />
                      </div>
                      <div>
                        <div style={{ fontSize: '14px', fontWeight: '800', color: '#111b21' }}>
                          Instant Web VoIP Calling
                        </div>
                        <div style={{ fontSize: '12px', color: '#64748b' }}>
                          Crystal-clear WebRTC audio & video calling right from your desktop.
                        </div>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: '14px' }}>
                      <div style={{
                        width: '32px',
                        height: '32px',
                        borderRadius: '10px',
                        backgroundColor: '#E8FAF6',
                        color: '#008069',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        flexShrink: 0
                      }}>
                        <Lock size={18} />
                      </div>
                      <div>
                        <div style={{ fontSize: '14px', fontWeight: '800', color: '#111b21' }}>
                          End-to-End Encrypted Chats
                        </div>
                        <div style={{ fontSize: '12px', color: '#64748b' }}>
                          Pairwise AES-GCM & ECDH security keeps your messages 100% private.
                        </div>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'flex-start', gap: '14px' }}>
                      <div style={{
                        width: '32px',
                        height: '32px',
                        borderRadius: '10px',
                        backgroundColor: '#E8FAF6',
                        color: '#008069',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        flexShrink: 0
                      }}>
                        <Smartphone size={18} />
                      </div>
                      <div>
                        <div style={{ fontSize: '14px', fontWeight: '800', color: '#111b21' }}>
                          Synced with Mobile
                        </div>
                        <div style={{ fontSize: '12px', color: '#64748b' }}>
                          Your contacts and chat histories sync effortlessly with the Android app.
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Switch Option */}
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: '8px', borderTop: '1px solid #f1f5f9' }}>
                    <button
                      type="button"
                      onClick={() => selectMethod('qr')}
                      style={{
                        background: 'none',
                        border: 'none',
                        color: '#008069',
                        fontSize: '13px',
                        fontWeight: '800',
                        cursor: 'pointer',
                        padding: 0,
                        display: 'flex',
                        alignItems: 'center',
                        gap: '4px'
                      }}
                    >
                      <span>Have your phone? Scan QR Code instead</span>
                      <ArrowRight size={14} />
                    </button>

                    <button
                      type="button"
                      onClick={onOpenDownloadModal}
                      style={{
                        background: '#FFF9C4',
                        border: '1px solid #FBC02D',
                        borderRadius: '8px',
                        padding: '6px 12px',
                        color: '#000000',
                        fontSize: '12px',
                        fontWeight: '800',
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '6px'
                      }}
                    >
                      <span>📱 Download Android APK</span>
                    </button>
                  </div>
                </>
              )}
            </div>

            {/* RIGHT COLUMN: QR Container OR Phone Input Form */}
            <div style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: '#fafbfc',
              borderRadius: '20px',
              border: '1.5px solid #e2e8f0',
              padding: '28px 24px',
              minHeight: '380px',
              boxShadow: 'inset 0 1px 3px rgba(0,0,0,0.02)'
            }}>
              {authMethod === 'qr' ? (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '100%' }}>
                  <QrLoginView onLoginSuccess={handleQrSuccess} showInstructions={false} />
                  <div style={{ marginTop: '16px', fontSize: '12px', color: '#64748b', fontWeight: '600', display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span>🔒 Code updates automatically every 60s</span>
                  </div>
                </div>
              ) : (
                <form onSubmit={handleSubmit} style={{ width: '100%', maxWidth: '340px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
                  <div style={{ textAlign: 'center', marginBottom: '4px' }}>
                    <div style={{
                      width: '50px',
                      height: '50px',
                      borderRadius: '50%',
                      backgroundColor: '#E8FAF6',
                      color: '#008069',
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      marginBottom: '10px'
                    }}>
                      <Phone size={24} />
                    </div>
                    <div style={{ fontSize: '18px', fontWeight: '900', color: '#111b21' }}>
                      Enter Your Details
                    </div>
                    <div style={{ fontSize: '12px', color: '#64748b' }}>
                      Sign in or create your LKS Dialer account
                    </div>
                  </div>

                  <div style={{ position: 'relative' }}>
                    <User size={18} style={{ position: 'absolute', left: '16px', top: '15px', color: '#64748b' }} />
                    <input 
                      type="text" 
                      className="neo-input"
                      style={{ paddingLeft: '46px', fontSize: '14px' }}
                      placeholder="Display Name" 
                      value={name} 
                      onChange={e => setName(e.target.value)}
                      required 
                    />
                  </div>

                  <div style={{ display: 'flex', gap: '8px' }}>
                    <button 
                      type="button"
                      className="neo-input"
                      onClick={() => setShowCountryPicker(true)}
                      style={{ 
                        width: 'auto', 
                        padding: '12px 14px', 
                        cursor: 'pointer',
                        display: 'flex', 
                        alignItems: 'center', 
                        gap: '6px',
                        background: '#ffffff',
                        border: '1px solid var(--border-color)',
                        flexShrink: 0
                      }}
                    >
                      <span style={{ fontSize: '16px' }}>{selectedCountry.flag}</span>
                      <span style={{ fontWeight: '800', fontSize: '13px' }}>{selectedCountry.dialCode}</span>
                      <ChevronDown size={14} color="#64748b" />
                    </button>
                    
                    <input 
                      type="tel" 
                      className="neo-input"
                      style={{ flex: 1, fontSize: '14px', letterSpacing: '0.5px' }}
                      placeholder="Phone Number" 
                      value={phone} 
                      onChange={e => setPhone(e.target.value)}
                      required 
                    />
                  </div>

                  <button 
                    type="submit" 
                    className="neo-btn" 
                    style={{ 
                      marginTop: '6px', 
                      width: '100%',
                      padding: '13px',
                      fontSize: '14px',
                      fontWeight: '800',
                      letterSpacing: '0.5px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      gap: '8px'
                    }}
                    disabled={loading}
                  >
                    {loading ? (
                      <>
                        <div style={{ width: '16px', height: '16px', border: '2px solid #ffffff', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 1s linear infinite' }} />
                        <span>CONNECTING...</span>
                      </>
                    ) : (
                      <>
                        <Phone size={16} />
                        <span>SIGN IN TO LKS DIALER</span>
                      </>
                    )}
                  </button>
                </form>
              )}
            </div>
          </div>
        </div>

        <style>{`
          @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
          }
        `}</style>
      </div>
    );
  }

  // =========================================================================
  // 📱 MOBILE LAYOUT (Compact Phone View)
  // =========================================================================
  return (
    <div style={{ 
      display: 'flex', 
      flexDirection: 'column', 
      minHeight: '100%', 
      width: '100%', 
      alignItems: 'center', 
      justifyContent: 'center', 
      padding: '28px 16px', 
      position: 'relative' 
    }}>
      {showCountryPicker && (
        <CountryCodePickerModal 
          selectedCountry={selectedCountry}
          onCountrySelected={setSelectedCountry}
          onDismiss={() => setShowCountryPicker(false)}
        />
      )}

      <div style={{ width: '100%', maxWidth: '420px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <img 
          src={appLogo} 
          alt="LKS Dialer Logo"
          style={{ 
            width: '68px', 
            height: '68px', 
            borderRadius: '18px',
            border: '2px solid rgba(0,0,0,0.06)',
            boxShadow: '0 8px 24px rgba(0, 128, 105, 0.25)',
            marginBottom: '14px',
            objectFit: 'contain'
          }} 
        />
        
        <h1 style={{ fontSize: '32px', fontWeight: '900', lineHeight: 1.1, marginBottom: '6px', textTransform: 'uppercase', textAlign: 'center', letterSpacing: '-0.5px' }}>
          LKS DIALER WEB
        </h1>
        
        <p style={{ fontSize: '13px', fontWeight: '600', marginBottom: '20px', color: '#64748b', textAlign: 'center' }}>
          {authMethod === 'qr' 
            ? 'Scan with another phone to link this device' 
            : 'Enter your phone number to sign in or create an account'}
        </p>

        {/* Tab Selector: Phone first on mobile */}
        <div 
          style={{ 
            display: 'flex', 
            width: '100%', 
            maxWidth: '380px',
            gap: '6px', 
            marginBottom: '20px', 
            background: '#e2e8f0', 
            padding: '4px', 
            borderRadius: '14px',
            boxShadow: 'inset 0 1px 3px rgba(0,0,0,0.06)'
          }}
        >
          {/* Phone Tab (Default on Mobile) */}
          <button
            type="button"
            onClick={() => selectMethod('phone')}
            style={{
              flex: 1,
              padding: '9px 12px',
              borderRadius: '11px',
              border: 'none',
              cursor: 'pointer',
              fontWeight: authMethod === 'phone' ? '800' : '600',
              fontSize: '13px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '6px',
              backgroundColor: authMethod === 'phone' ? '#ffffff' : 'transparent',
              color: authMethod === 'phone' ? '#0f172a' : '#64748b',
              boxShadow: authMethod === 'phone' ? '0 2px 8px rgba(0,0,0,0.08)' : 'none',
              transition: 'all 0.18s ease'
            }}
          >
            <Phone size={15} />
            <span>Phone Number</span>
            {authMethod === 'phone' && (
              <span style={{ 
                fontSize: '10px', 
                padding: '1px 5px', 
                background: '#e0f2fe', 
                color: '#0369a1', 
                borderRadius: '6px',
                fontWeight: '700'
              }}>
                Direct
              </span>
            )}
          </button>

          {/* QR Code Tab */}
          <button
            type="button"
            onClick={() => selectMethod('qr')}
            style={{
              flex: 1,
              padding: '9px 12px',
              borderRadius: '11px',
              border: 'none',
              cursor: 'pointer',
              fontWeight: authMethod === 'qr' ? '800' : '600',
              fontSize: '13px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '6px',
              backgroundColor: authMethod === 'qr' ? '#ffffff' : 'transparent',
              color: authMethod === 'qr' ? '#0f172a' : '#64748b',
              boxShadow: authMethod === 'qr' ? '0 2px 8px rgba(0,0,0,0.08)' : 'none',
              transition: 'all 0.18s ease'
            }}
          >
            <QrCode size={15} />
            <span>Scan QR</span>
          </button>
        </div>

        {/* Content based on selected tab */}
        {authMethod === 'qr' ? (
          <div style={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <div 
              style={{ 
                width: '100%', 
                maxWidth: '380px', 
                marginBottom: '14px', 
                background: '#f0fdf4', 
                border: '1px solid #bbf7d0', 
                borderRadius: '12px', 
                padding: '9px 13px',
                display: 'flex',
                alignItems: 'center',
                gap: '8px'
              }}
            >
              <span style={{ fontSize: '16px' }}>📷</span>
              <p style={{ margin: 0, fontSize: '12px', color: '#166534', lineHeight: 1.35, fontWeight: '600' }}>
                Point another phone's LKS Dialer camera at this code to link this device.
              </p>
            </div>
            <QrLoginView onLoginSuccess={handleQrSuccess} showInstructions={true} />
          </div>
        ) : (
          <form onSubmit={handleSubmit} style={{ width: '100%', maxWidth: '380px', display: 'flex', flexDirection: 'column', gap: '15px' }}>
            <div style={{ position: 'relative' }}>
              <User size={19} style={{ position: 'absolute', left: '16px', top: '15px', color: '#64748b' }} />
              <input 
                type="text" 
                className="neo-input"
                style={{ paddingLeft: '48px', fontSize: '15px' }}
                placeholder="Display Name" 
                value={name} 
                onChange={e => setName(e.target.value)}
                required 
              />
            </div>

            <div style={{ display: 'flex', gap: '8px' }}>
              <button 
                type="button"
                className="neo-input"
                onClick={() => setShowCountryPicker(true)}
                style={{ 
                  width: 'auto', 
                  padding: '12px 14px', 
                  cursor: 'pointer',
                  display: 'flex', 
                  alignItems: 'center', 
                  gap: '6px',
                  background: '#ffffff',
                  border: '1px solid var(--border-color)',
                  flexShrink: 0
                }}
              >
                <span style={{ fontSize: '18px' }}>{selectedCountry.flag}</span>
                <span style={{ fontWeight: '800', fontSize: '14px' }}>{selectedCountry.dialCode}</span>
                <ChevronDown size={15} color="#64748b" />
              </button>
              
              <input 
                type="tel" 
                className="neo-input"
                style={{ flex: 1, fontSize: '15px', letterSpacing: '0.5px' }}
                placeholder="Phone Number" 
                value={phone} 
                onChange={e => setPhone(e.target.value)}
                required 
              />
            </div>

            <button 
              type="submit" 
              className="neo-btn" 
              style={{ 
                marginTop: '4px', 
                width: '100%',
                padding: '14px',
                fontSize: '15px',
                fontWeight: '800',
                letterSpacing: '0.5px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '8px'
              }}
              disabled={loading}
            >
              {loading ? (
                <>
                  <div style={{ width: '18px', height: '18px', border: '2px solid #ffffff', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 1s linear infinite' }} />
                  <span>CONNECTING...</span>
                </>
              ) : (
                <>
                  <Phone size={18} />
                  <span>SIGN IN TO LKS DIALER</span>
                </>
              )}
            </button>
          </form>
        )}

        {/* Android Download Banner */}
        <div style={{ marginTop: '24px', width: '100%', maxWidth: '380px', textAlign: 'center' }}>
          <button
            type="button"
            onClick={onOpenDownloadModal}
            className="neo-box"
            style={{
              width: '100%',
              padding: '12px 16px',
              backgroundColor: '#FFF9C4',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '10px',
              fontSize: '13px',
              fontWeight: '800'
            }}
          >
            <span>📱 Using Android?</span>
            <span style={{ color: 'var(--primary)', textDecoration: 'underline' }}>
              Download Latest APK ({LATEST_APP_VERSION})
            </span>
          </button>
        </div>
      </div>

      <style>{`
        @keyframes spin {
          0% { transform: rotate(0deg); }
          100% { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
}
