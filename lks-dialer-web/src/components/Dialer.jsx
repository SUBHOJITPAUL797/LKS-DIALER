import React, { useState, useEffect, useMemo } from 'react';
import { Phone, Video, Delete, ChevronDown, User, CheckCircle } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';
import { formatAvatarUrl } from '../lib/ImageUtils';
import CountryCodePickerModal from './CountryCodePickerModal';

const KEYPAD_KEYS = [
  { digit: '1', sub: '' },
  { digit: '2', sub: 'ABC' },
  { digit: '3', sub: 'DEF' },
  { digit: '4', sub: 'GHI' },
  { digit: '5', sub: 'JKL' },
  { digit: '6', sub: 'MNO' },
  { digit: '7', sub: 'PQRS' },
  { digit: '8', sub: 'TUV' },
  { digit: '9', sub: 'WXYZ' },
  { digit: '*', sub: '★' },
  { digit: '0', sub: '+' },
  { digit: '#', sub: '♯' }
];

const charToT9 = (ch) => {
  const c = ch.toUpperCase();
  if ('ABC'.includes(c)) return '2';
  if ('DEF'.includes(c)) return '3';
  if ('GHI'.includes(c)) return '4';
  if ('JKL'.includes(c)) return '5';
  if ('MNO'.includes(c)) return '6';
  if ('PQRS'.includes(c)) return '7';
  if ('TUV'.includes(c)) return '8';
  if ('WXYZ'.includes(c)) return '9';
  if (c >= '0' && c <= '9') return c;
  return '';
};

const nameToT9 = (name) => {
  return String(name || '').split('').map(charToT9).join('');
};

export default function Dialer({ onStartCall }) {
  const [number, setNumber] = useState("");
  const [selectedCountry, setSelectedCountry] = useState(defaultCountry);
  const [showCountryPicker, setShowCountryPicker] = useState(false);
  const [matchedUser, setMatchedUser] = useState(null);
  const [contacts, setContacts] = useState([]);

  useEffect(() => {
    webRtcEngine.getRegisteredUsers().then(users => {
      const myPhone = webRtcEngine.currentUser?.phoneNumber;
      setContacts((users || []).filter(u => u && u.phoneNumber && u.phoneNumber !== myPhone));
    }).catch(() => setContacts([]));
  }, []);

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

  const queryDigits = useMemo(() => number.replace(/\D/g, ''), [number]);

  const t9Matches = useMemo(() => {
    if (!queryDigits || contacts.length === 0) return [];
    return contacts.filter(contact => {
      const cleanPhone = (contact.phoneNumber || '').replace(/\D/g, '');
      const t9Name = nameToT9(contact.displayName || '');
      return cleanPhone.includes(queryDigits) || t9Name.includes(queryDigits);
    }).slice(0, 8);
  }, [queryDigits, contacts]);

  const handlePress = (digit) => {
    if (number.length < 16) setNumber(prev => prev + digit);
  };

  const handleDelete = () => {
    setNumber(prev => prev.slice(0, -1));
  };

  const handleStartCall = (type, targetNumber = null) => {
    const raw = targetNumber || number;
    const fullNumber = targetNumber 
      ? (targetNumber.startsWith('+') ? targetNumber : formatPhoneNumber(selectedCountry.dialCode, targetNumber))
      : formatPhoneNumber(selectedCountry.dialCode, number);
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

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', width: '100%', alignItems: 'center', minHeight: '120px' }}>
        
        <div 
          className="neo-box"
          onClick={() => setShowCountryPicker(true)}
          style={{ 
            padding: '8px 16px', marginBottom: '12px', cursor: 'pointer',
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

        {/* T9 Matches & Matched User Row */}
        <div style={{ minHeight: '44px', width: '100%', maxWidth: '340px', marginTop: '4px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
          {matchedUser && (
            <div className="neo-box" style={{ 
              padding: '6px 16px', backgroundColor: 'var(--success)', 
              display: 'flex', alignItems: 'center', gap: '8px', marginBottom: t9Matches.length > 0 ? '6px' : '0'
            }}>
              <CheckCircle size={14} color="#000" />
              <span style={{ fontWeight: '800', fontSize: '13px' }}>{matchedUser.displayName} • LKS VoIP</span>
            </div>
          )}

          {t9Matches.length > 0 && (
            <div style={{
              display: 'flex',
              gap: '8px',
              overflowX: 'auto',
              width: '100%',
              padding: '4px 8px',
              scrollbarWidth: 'none',
              msOverflowStyle: 'none'
            }}>
              {t9Matches.map(m => (
                <div
                  key={m.phoneNumber}
                  onClick={() => {
                    const clean = (m.phoneNumber || '').replace(/\D/g, '');
                    setNumber(clean);
                  }}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px',
                    padding: '6px 10px',
                    backgroundColor: 'var(--card-bg, #fff)',
                    border: '1px solid var(--border-color, #e0e0e0)',
                    borderRadius: '16px',
                    cursor: 'pointer',
                    flexShrink: 0,
                    boxShadow: '0 2px 6px rgba(0,0,0,0.06)'
                  }}
                  title={`Tap to select ${m.displayName}`}
                >
                  <div style={{
                    width: '28px',
                    height: '28px',
                    borderRadius: '50%',
                    backgroundColor: 'var(--primary-light, #e0f2f1)',
                    color: 'var(--primary, #008069)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontWeight: '800',
                    fontSize: '12px'
                  }}>
                    {(m.displayName || '?').charAt(0).toUpperCase()}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column' }}>
                    <span style={{ fontSize: '12px', fontWeight: '800', color: 'var(--text-main, #111)', whiteSpace: 'nowrap' }}>
                      {m.displayName || m.phoneNumber}
                    </span>
                    <span style={{ fontSize: '10px', color: 'var(--text-subtle, #666)', whiteSpace: 'nowrap' }}>
                      {m.phoneNumber}
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      handleStartCall('AUDIO', m.phoneNumber);
                    }}
                    style={{
                      background: 'var(--primary, #008069)',
                      border: 'none',
                      borderRadius: '50%',
                      width: '24px',
                      height: '24px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      cursor: 'pointer',
                      padding: 0,
                      marginLeft: '2px'
                    }}
                    title={`Call ${m.displayName}`}
                  >
                    <Phone size={12} color="#fff" />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div style={{ 
        display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', 
        gap: '12px', width: '100%', maxWidth: '280px', marginBottom: '16px'
      }}>
        {KEYPAD_KEYS.map(({ digit, sub }) => (
          <div key={digit} style={{ display: 'flex', justifyContent: 'center' }}>
            <button className="keypad-btn" onClick={() => handlePress(digit)}>
              <span className="number" style={{ fontSize: '26px', fontWeight: '800', lineHeight: 1 }}>{digit}</span>
              {sub ? (
                <span className="sub" style={{ fontSize: '10px', fontWeight: '700', color: '#888', marginTop: '2px', letterSpacing: '0.8px' }}>
                  {sub}
                </span>
              ) : (
                <span style={{ height: '12px' }} />
              )}
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
