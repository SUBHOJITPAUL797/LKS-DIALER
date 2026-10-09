import React, { useState } from 'react';
import { Phone, User, ChevronDown, QrCode } from 'lucide-react';
import { defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';
import CountryCodePickerModal from './CountryCodePickerModal';
import QrLoginView from './QrLoginView';
import { LATEST_APP_VERSION } from './AppDownloadModal';

export default function Onboarding({ onRegister, onOpenDownloadModal }) {
  const [authMethod, setAuthMethod] = useState('qr'); // 'qr' | 'phone'
  const [phone, setPhone] = useState("");
  const [name, setName] = useState("");
  const [selectedCountry, setSelectedCountry] = useState(defaultCountry);
  const [showCountryPicker, setShowCountryPicker] = useState(false);
  const [loading, setLoading] = useState(false);

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
          selectedCountry={selectedCountry}
          onCountrySelected={setSelectedCountry}
          onDismiss={() => setShowCountryPicker(false)}
        />
      )}

      <div style={{ width: '100%', maxWidth: '480px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <img 
          src="/icon.png" 
          alt="LKS Dialer Logo"
          style={{ 
            width: '80px', 
            height: '80px', 
            borderRadius: '20px',
            border: '3px solid rgba(255,255,255,0.1)',
            boxShadow: '0 8px 24px rgba(124, 58, 237, 0.35)',
            marginBottom: '18px',
            objectFit: 'cover'
          }} 
        />
        
        <h1 style={{ fontSize: '42px', fontWeight: '900', lineHeight: 1.05, marginBottom: '8px', textTransform: 'uppercase', textAlign: 'center' }}>
          LKS DIALER WEB
        </h1>
        
        <p style={{ fontSize: '15px', fontWeight: '600', marginBottom: '24px', color: '#64748b', textAlign: 'center' }}>
          Real-time VoIP calls, encrypted chats & fast sync
        </p>

        {/* Tab Selector: Scan QR Code (WhatsApp-style) vs Phone Number */}
        <div 
          style={{ 
            display: 'flex', 
            width: '100%', 
            maxWidth: '380px',
            gap: '8px', 
            marginBottom: '24px', 
            background: '#e2e8f0', 
            padding: '5px', 
            borderRadius: '16px' 
          }}
        >
          <button
            type="button"
            onClick={() => setAuthMethod('qr')}
            style={{
              flex: 1,
              padding: '10px 14px',
              borderRadius: '12px',
              border: 'none',
              cursor: 'pointer',
              fontWeight: '800',
              fontSize: '13px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '6px',
              backgroundColor: authMethod === 'qr' ? '#ffffff' : 'transparent',
              color: authMethod === 'qr' ? '#0f172a' : '#64748b',
              boxShadow: authMethod === 'qr' ? '0 3px 10px rgba(0,0,0,0.1)' : 'none',
              transition: 'all 0.2s ease'
            }}
          >
            <QrCode size={18} />
            <span>Scan QR Code</span>
          </button>
          
          <button
            type="button"
            onClick={() => setAuthMethod('phone')}
            style={{
              flex: 1,
              padding: '10px 14px',
              borderRadius: '12px',
              border: 'none',
              cursor: 'pointer',
              fontWeight: '800',
              fontSize: '13px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '6px',
              backgroundColor: authMethod === 'phone' ? '#ffffff' : 'transparent',
              color: authMethod === 'phone' ? '#0f172a' : '#64748b',
              boxShadow: authMethod === 'phone' ? '0 3px 10px rgba(0,0,0,0.1)' : 'none',
              transition: 'all 0.2s ease'
            }}
          >
            <Phone size={18} />
            <span>Phone Number</span>
          </button>
        </div>

        {/* Content based on selected tab */}
        {authMethod === 'qr' ? (
          <QrLoginView onLoginSuccess={handleQrSuccess} />
        ) : (
          <form onSubmit={handleSubmit} style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: '18px' }}>
            <div style={{ position: 'relative' }}>
              <User size={22} style={{ position: 'absolute', left: '16px', top: '16px', color: '#64748b' }} />
              <input 
                type="text" 
                className="neo-input"
                style={{ paddingLeft: '54px' }}
                placeholder="Display Name" 
                value={name} 
                onChange={e => setName(e.target.value)}
                required 
              />
            </div>

            <div style={{ display: 'flex', gap: '10px' }}>
              <div 
                className="neo-input"
                onClick={() => setShowCountryPicker(true)}
                style={{ 
                  width: 'auto', padding: '16px 12px', cursor: 'pointer',
                  display: 'flex', alignItems: 'center', gap: '6px'
                }}
              >
                <span>{selectedCountry.flag}</span>
                <span style={{ fontWeight: '800' }}>{selectedCountry.dialCode}</span>
                <ChevronDown size={16} />
              </div>
              
              <input 
                type="tel" 
                className="neo-input"
                style={{ flex: 1 }}
                placeholder="Phone Number" 
                value={phone} 
                onChange={e => setPhone(e.target.value)}
                required 
              />
            </div>

            <button 
              type="submit" 
              className="neo-btn" 
              style={{ marginTop: '10px', width: '100%' }}
              disabled={loading}
            >
              {loading ? "CONNECTING..." : "ENTER"}
            </button>
          </form>
        )}

        {/* Android Download Banner */}
        <div style={{ marginTop: '28px', width: '100%', textAlign: 'center' }}>
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

    </div>
  );
}
