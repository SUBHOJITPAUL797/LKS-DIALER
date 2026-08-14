import React, { useState, useEffect } from 'react';
import { Phone, Video, Delete, ChevronDown } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';
import CountryCodePickerModal from './CountryCodePickerModal';

export default function Dialer({ onStartCall }) {
  const [number, setNumber] = useState("");
  const [selectedCountry, setSelectedCountry] = useState(defaultCountry);
  const [showCountryPicker, setShowCountryPicker] = useState(false);
  const [matchedUser, setMatchedUser] = useState(null);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key >= '0' && e.key <= '9') handlePress(e.key);
      else if (e.key === 'Backspace') handleDelete();
      else if (e.key === '+') handlePress('+');
      else if (e.key === '*') handlePress('*');
      else if (e.key === '#') handlePress('#');
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [number]);

  useEffect(() => {
    if (number.length >= 7) {
      const fullNumber = formatPhoneNumber(selectedCountry.dialCode, number);
      webRtcEngine.lookupUser(fullNumber).then(setMatchedUser);
    } else {
      setMatchedUser(null);
    }
  }, [number, selectedCountry]);

  const handlePress = (digit) => {
    if (number.length < 15) setNumber(prev => prev + digit);
  };

  const handleDelete = () => {
    setNumber(prev => prev.slice(0, -1));
  };

  const handleStartCall = (type) => {
    const fullNumber = formatPhoneNumber(selectedCountry.dialCode, number);
    onStartCall(fullNumber, type);
  };

  const callBtnDisabled = number.length < 3;

  return (
    <div className="scrollable-content" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', position: 'relative' }}>
      
      {showCountryPicker && (
        <CountryCodePickerModal 
          selectedCountry={selectedCountry}
          onCountrySelected={setSelectedCountry}
          onDismiss={() => setShowCountryPicker(false)}
        />
      )}

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', width: '100%', alignItems: 'center', minHeight: '180px' }}>
        
        <div 
          className="neo-box"
          onClick={() => setShowCountryPicker(true)}
          style={{ 
            padding: '8px 16px', marginBottom: '16px', cursor: 'pointer',
            display: 'flex', alignItems: 'center', gap: '8px',
            backgroundColor: 'var(--card-bg)'
          }}
        >
          <span style={{ fontSize: '24px' }}>{selectedCountry.flag}</span>
          <span style={{ fontSize: '18px', fontWeight: '800' }}>{selectedCountry.dialCode}</span>
          <ChevronDown size={20} />
        </div>

        <div style={{ 
          fontSize: number.length > 10 ? '32px' : '40px', 
          fontWeight: '900', 
          height: '48px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          letterSpacing: '2px',
          textAlign: 'center',
          color: number.length === 0 ? '#aaa' : '#000',
          whiteSpace: 'nowrap',
          overflow: 'hidden'
        }}>
          {number || "ENTER NUMBER"}
        </div>

        <div style={{ height: '44px', marginTop: '8px', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
          {matchedUser && (
            <div className="neo-box" style={{ 
              padding: '6px 16px', backgroundColor: 'var(--success)', 
              display: 'flex', alignItems: 'center', gap: '8px' 
            }}>
              <div style={{ width: '8px', height: '8px', borderRadius: '50%', backgroundColor: '#000' }} />
              <span style={{ fontWeight: '800', fontSize: '14px' }}>{matchedUser.displayName}</span>
            </div>
          )}
        </div>
      </div>

      <div style={{ 
        display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', 
        gap: '12px', width: '100%', maxWidth: '280px', marginBottom: '20px'
      }}>
        {['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#'].map((btn) => (
          <div key={btn} style={{ display: 'flex', justifyContent: 'center' }}>
            <button className="keypad-btn" onClick={() => handlePress(btn)}>
              <span className="number">{btn}</span>
            </button>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: '12px', width: '100%', maxWidth: '280px', paddingBottom: '10px' }}>
        <button 
          className="neo-btn accent" 
          style={{ flex: 1, padding: '12px' }}
          onClick={() => handleStartCall('AUDIO')}
          disabled={callBtnDisabled}
        >
          <Phone size={24} color="#000" />
        </button>
        <button 
          className="neo-btn" 
          style={{ flex: 1, padding: '12px' }}
          onClick={() => handleStartCall('VIDEO')}
          disabled={callBtnDisabled}
        >
          <Video size={24} />
        </button>
        <button 
          className="neo-box" 
          style={{ width: '64px', border: 'none', background: 'none', cursor: 'pointer', boxShadow: 'none' }}
          onClick={handleDelete}
        >
          <Delete size={32} color="#000" />
        </button>
      </div>
    </div>
  );
}
