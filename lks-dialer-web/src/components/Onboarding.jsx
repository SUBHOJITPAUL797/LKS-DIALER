import React, { useState } from 'react';
import { Phone, User, ChevronDown } from 'lucide-react';
import { defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';
import CountryCodePickerModal from './CountryCodePickerModal';

export default function Onboarding({ onRegister }) {
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

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', width: '100%', alignItems: 'center', justifyContent: 'center', padding: '32px', position: 'relative' }}>
      
      {showCountryPicker && (
        <CountryCodePickerModal 
          selectedCountry={selectedCountry}
          onCountrySelected={setSelectedCountry}
          onDismiss={() => setShowCountryPicker(false)}
        />
      )}

      <div style={{ width: '100%', maxWidth: '480px', display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
        <img 
          src="/icon.png" 
          alt="LKS Dialer Logo"
          style={{ 
            width: '84px', 
            height: '84px', 
            borderRadius: '20px',
            border: '3px solid rgba(255,255,255,0.1)',
            boxShadow: '0 8px 24px rgba(124, 58, 237, 0.35)',
            marginBottom: '24px',
            objectFit: 'cover'
          }} 
        />
        
        <h1 style={{ fontSize: '48px', fontWeight: '900', lineHeight: 1.1, marginBottom: '16px', textTransform: 'uppercase' }}>
          LKS<br/>DIALER<br/>WEB
        </h1>
        
        <p style={{ fontSize: '18px', fontWeight: '600', marginBottom: '40px', color: '#555' }}>
          Connect securely using WebRTC.
        </p>

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
          <div style={{ position: 'relative' }}>
            <User size={24} style={{ position: 'absolute', left: '16px', top: '16px' }} />
            <input 
              type="text" 
              className="neo-input"
              style={{ paddingLeft: '56px' }}
              placeholder="Display Name" 
              value={name} 
              onChange={e => setName(e.target.value)}
              required 
            />
          </div>

          <div style={{ display: 'flex', gap: '12px' }}>
            <div 
              className="neo-input"
              onClick={() => setShowCountryPicker(true)}
              style={{ 
                width: 'auto', padding: '16px 12px', cursor: 'pointer',
                display: 'flex', alignItems: 'center', gap: '8px'
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
            style={{ marginTop: '16px', width: '100%' }}
            disabled={loading}
          >
            {loading ? "CONNECTING..." : "ENTER"}
          </button>
        </form>
      </div>

    </div>
  );
}
