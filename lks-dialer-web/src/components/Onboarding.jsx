import React, { useState, useEffect } from 'react';
import { Phone, User, ChevronDown, QrCode, Monitor, Sparkles } from 'lucide-react';
import { defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';
import CountryCodePickerModal from './CountryCodePickerModal';
import QrLoginView from './QrLoginView';
import { LATEST_APP_VERSION } from './AppDownloadModal';

// Helper to determine if the user is visiting on a mobile device or small screen
const checkIsMobile = () => {
  if (typeof window === 'undefined') return false;
  const isSmallScreen = window.innerWidth <= 768;
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

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100%', width: '100%', alignItems: 'center', justifyContent: 'center', padding: '32px 16px', position: 'relative' }}>
      
      {showCountryPicker && (
        <CountryCodePickerModal 
          selectedCountry = {selectedCountry}
          onCountrySelected = {setSelectedCountry}
          onDismiss = {() => setShowCountryPicker(false)}
        />
      )}

      <div style={{ width: '100%', maxWidth: '440px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <img 
          src="/icon.png" 
          alt="LKS Dialer Logo"
          style={{ 
            width: '76px', 
            height: '76px', 
            borderRadius: '20px',
            border: '3px solid rgba(255,255,255,0.1)',
            boxShadow: '0 8px 24px rgba(124, 58, 237, 0.35)',
            marginBottom: '16px',
            objectFit: 'cover'
          }} 
        />
        
        <h1 style={{ fontSize: '38px', fontWeight: '900', lineHeight: 1.05, marginBottom: '6px', textTransform: 'uppercase', textAlign: 'center', letterSpacing: '-0.5px' }}>
          LKS DIALER WEB
        </h1>
        
        <p style={{ fontSize: '14px', fontWeight: '600', marginBottom: '22px', color: '#64748b', textAlign: 'center' }}>
          {authMethod === 'qr' 
            ? 'Scan with your phone to sync chats & calls instantly' 
            : 'Enter your phone number to sign in or create an account'}
        </p>

        {/* Tab Selector: Intelligently orders and highlights based on Mobile vs Desktop */}
        <div 
          style={{ 
            display: 'flex', 
            width: '100%', 
            maxWidth: '400px',
            gap: '6px', 
            marginBottom: '24px', 
            background: '#e2e8f0', 
            padding: '5px', 
            borderRadius: '16px',
            boxShadow: 'inset 0 1px 3px rgba(0,0,0,0.06)'
          }}
        >
          {/* On Mobile: Phone Tab comes first. On Desktop: QR Code Tab comes first. */}
          {isMobile ? (
            <>
              {/* Phone Tab (Default on Mobile) */}
              <button
                type="button"
                onClick={() => selectMethod('phone')}
                style={{
                  flex: 1,
                  padding: '10px 12px',
                  borderRadius: '12px',
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
                  boxShadow: authMethod === 'phone' ? '0 3px 10px rgba(0,0,0,0.1)' : 'none',
                  transition: 'all 0.2s cubic-bezier(0.16, 1, 0.3, 1)'
                }}
              >
                <Phone size={16} />
                <span>Phone Number</span>
                {authMethod === 'phone' && (
                  <span style={{ 
                    fontSize: '10px', 
                    padding: '1px 6px', 
                    background: '#e0f2fe', 
                    color: '#0369a1', 
                    borderRadius: '8px',
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
                  padding: '10px 12px',
                  borderRadius: '12px',
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
                  boxShadow: authMethod === 'qr' ? '0 3px 10px rgba(0,0,0,0.1)' : 'none',
                  transition: 'all 0.2s cubic-bezier(0.16, 1, 0.3, 1)'
                }}
              >
                <QrCode size={16} />
                <span>Scan QR Code</span>
              </button>
            </>
          ) : (
            <>
              {/* QR Code Tab (Default on Desktop) */}
              <button
                type="button"
                onClick={() => selectMethod('qr')}
                style={{
                  flex: 1,
                  padding: '10px 12px',
                  borderRadius: '12px',
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
                  boxShadow: authMethod === 'qr' ? '0 3px 10px rgba(0,0,0,0.1)' : 'none',
                  transition: 'all 0.2s cubic-bezier(0.16, 1, 0.3, 1)'
                }}
              >
                <QrCode size={16} />
                <span>Scan QR Code</span>
                {authMethod === 'qr' && (
                  <span style={{ 
                    fontSize: '10px', 
                    padding: '1px 6px', 
                    background: '#dcfce7', 
                    color: '#15803d', 
                    borderRadius: '8px',
                    fontWeight: '700'
                  }}>
                    Recommended
                  </span>
                )}
              </button>

              {/* Phone Tab */}
              <button
                type="button"
                onClick={() => selectMethod('phone')}
                style={{
                  flex: 1,
                  padding: '10px 12px',
                  borderRadius: '12px',
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
                  boxShadow: authMethod === 'phone' ? '0 3px 10px rgba(0,0,0,0.1)' : 'none',
                  transition: 'all 0.2s cubic-bezier(0.16, 1, 0.3, 1)'
                }}
              >
                <Phone size={16} />
                <span>Phone Number</span>
              </button>
            </>
          )}
        </div>

        {/* Content based on selected tab */}
        {authMethod === 'qr' ? (
          <>
            {/* If viewed on mobile, show friendly helper */}
            {isMobile && (
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
            )}
            <QrLoginView onLoginSuccess={handleQrSuccess} />
          </>
        ) : (
          <form onSubmit={handleSubmit} style={{ width: '100%', maxWidth: '400px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div style={{ position: 'relative' }}>
              <User size={20} style={{ position: 'absolute', left: '16px', top: '15px', color: '#64748b' }} />
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
                marginTop: '6px', 
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
        <div style={{ marginTop: '28px', width: '100%', maxWidth: '400px', textAlign: 'center' }}>
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
