import React, { useState, useEffect, useMemo, useRef } from 'react';
import { Phone, Video, Delete, ChevronDown, CheckCircle, Music, Clipboard, Sparkles } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { defaultCountry, formatPhoneNumber } from '../lib/CountryCodes';
import { callSounds } from '../lib/CallSounds';
import CountryCodePickerModal from './CountryCodePickerModal';
import CallerTuneModal from './CallerTuneModal';

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
  const [showCallerTuneModal, setShowCallerTuneModal] = useState(false);
  const [matchedUser, setMatchedUser] = useState(null);
  const [contacts, setContacts] = useState([]);
  const [activeKey, setActiveKey] = useState(null);

  const longPressTimerRef = useRef(null);
  const backspaceTimerRef = useRef(null);

  useEffect(() => {
    webRtcEngine.getRegisteredUsers().then(users => {
      const myPhone = webRtcEngine.currentUser?.phoneNumber;
      setContacts((users || []).filter(u => u && u.phoneNumber && u.phoneNumber !== myPhone));
    }).catch(() => setContacts([]));
  }, []);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
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
    try {
      callSounds.playDtmfTone(digit);
      if (navigator.vibrate) navigator.vibrate(15);
    } catch {}
    if (number.length < 18) {
      setNumber(prev => prev + digit);
    }
  };

  const handlePointerDownKey = (digit) => {
    setActiveKey(digit);
    if (digit === '0') {
      longPressTimerRef.current = setTimeout(() => {
        try {
          callSounds.playDtmfTone('+');
          if (navigator.vibrate) navigator.vibrate(30);
        } catch {}
        setNumber(prev => {
          // Replace trailing '0' with '+' if just typed, or append '+'
          if (prev.endsWith('0')) {
            return prev.slice(0, -1) + '+';
          }
          return prev + '+';
        });
        longPressTimerRef.current = null;
      }, 550);
    }
  };

  const handlePointerUpKey = (digit) => {
    setActiveKey(null);
    if (digit === '0' && longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }
  };

  const handleDelete = () => {
    try {
      if (navigator.vibrate) navigator.vibrate(10);
    } catch {}
    setNumber(prev => prev.slice(0, -1));
  };

  const handlePointerDownDelete = () => {
    backspaceTimerRef.current = setTimeout(() => {
      setNumber('');
      try {
        if (navigator.vibrate) navigator.vibrate([20, 40, 20]);
      } catch {}
    }, 600);
  };

  const handlePointerUpDelete = () => {
    if (backspaceTimerRef.current) {
      clearTimeout(backspaceTimerRef.current);
      backspaceTimerRef.current = null;
    }
  };

  const handlePasteClipboard = async () => {
    try {
      if (navigator.clipboard?.readText) {
        const text = await navigator.clipboard.readText();
        const cleaned = text.replace(/[^0-9+*#]/g, '');
        if (cleaned) {
          setNumber(cleaned);
        }
      }
    } catch (e) {
      console.warn("Clipboard paste failed:", e);
    }
  };

  const handleStartCall = (type, targetNumber = null) => {
    const fullNumber = targetNumber 
      ? (targetNumber.startsWith('+') ? targetNumber : formatPhoneNumber(selectedCountry.dialCode, targetNumber))
      : (number.startsWith('+') ? number : formatPhoneNumber(selectedCountry.dialCode, number));
    onStartCall(fullNumber, type);
  };

  const callBtnDisabled = number.length < 3;

  // Auto-scale font size for number display
  const getFontSize = () => {
    if (number.length > 14) return '24px';
    if (number.length > 10) return '30px';
    return '38px';
  };

  const currentTuneTitle = webRtcEngine.currentUser?.callerTuneTitle;

  return (
    <div className="scrollable-content" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', position: 'relative', width: '100%', maxWidth: '420px', margin: '0 auto', padding: '12px 16px' }}>
      
      {showCountryPicker && (
        <CountryCodePickerModal 
          selectedCountry={selectedCountry}
          onCountrySelected={setSelectedCountry}
          onDismiss={() => setShowCountryPicker(false)}
        />
      )}

      {showCallerTuneModal && (
        <CallerTuneModal
          isOpen={showCallerTuneModal}
          onClose={() => setShowCallerTuneModal(false)}
          onTuneUpdated={() => {}}
        />
      )}

      {/* Top Bar: Country selector + Caller Tune Quick Shortcut */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', marginBottom: '14px', gap: '8px' }}>
        <button 
          type="button"
          className="neo-box"
          onClick={() => setShowCountryPicker(true)}
          style={{ 
            padding: '7px 14px', cursor: 'pointer',
            display: 'flex', alignItems: 'center', gap: '8px',
            backgroundColor: 'var(--card-bg)', border: '1px solid var(--border-color)',
            borderRadius: '20px'
          }}
          title="Select Country Code"
        >
          <span style={{ fontSize: '20px' }}>{selectedCountry.flag}</span>
          <span style={{ fontSize: '15px', fontWeight: '800', color: 'var(--text-main)' }}>{selectedCountry.dialCode}</span>
          <ChevronDown size={16} color="var(--text-muted)" />
        </button>

        <button
          type="button"
          onClick={() => setShowCallerTuneModal(true)}
          style={{
            display: 'flex', alignItems: 'center', gap: '6px',
            padding: '7px 14px', borderRadius: '20px',
            backgroundColor: currentTuneTitle ? '#E8FAF6' : 'rgba(0, 128, 105, 0.08)',
            border: currentTuneTitle ? '1px solid #00A884' : '1px solid rgba(0, 128, 105, 0.2)',
            color: '#008069', fontWeight: '800', fontSize: '12px',
            cursor: 'pointer', transition: 'all 0.18s ease'
          }}
          title={currentTuneTitle ? `Caller Tune: ${currentTuneTitle}` : "Set Caller Tune for callers"}
        >
          <Music size={14} color="#008069" />
          <span style={{ maxWidth: '140px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {currentTuneTitle || "Caller Tune"}
          </span>
          <Sparkles size={12} color="#00A884" />
        </button>
      </div>

      {/* Number Display Screen */}
      <div style={{ 
        width: '100%', minHeight: '90px', 
        display: 'flex', flexDirection: 'column', 
        justifyContent: 'center', alignItems: 'center',
        padding: '8px 12px', position: 'relative'
      }}>
        <div style={{ 
          fontSize: getFontSize(), 
          fontWeight: '900', 
          height: '48px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          letterSpacing: '2px',
          textAlign: 'center',
          color: number.length === 0 ? 'var(--text-secondary, #999)' : 'var(--text-main, #111)',
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          width: '100%',
          transition: 'font-size 0.15s ease'
        }}>
          {number || "Enter Number"}
        </div>

        {/* Paste helper when empty */}
        {number.length === 0 && (
          <button
            type="button"
            onClick={handlePasteClipboard}
            style={{
              display: 'flex', alignItems: 'center', gap: '4px',
              background: 'none', border: 'none', color: 'var(--primary, #008069)',
              fontSize: '11px', fontWeight: '700', cursor: 'pointer', marginTop: '4px',
              padding: '4px 8px', borderRadius: '8px'
            }}
          >
            <Clipboard size={12} /> Paste Number
          </button>
        )}

        {/* Match badges / T9 Carousel */}
        <div style={{ minHeight: '40px', width: '100%', marginTop: '6px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
          {matchedUser && (
            <div style={{ 
              padding: '5px 14px', backgroundColor: '#E8FAF6', border: '1px solid #00A884',
              borderRadius: '16px', display: 'flex', alignItems: 'center', gap: '6px',
              marginBottom: t9Matches.length > 0 ? '6px' : '0'
            }}>
              <CheckCircle size={14} color="#008069" />
              <span style={{ fontWeight: '800', fontSize: '12px', color: '#008069' }}>
                {matchedUser.displayName} • Verified LKS VoIP
              </span>
            </div>
          )}

          {t9Matches.length > 0 && (
            <div style={{
              display: 'flex',
              gap: '8px',
              overflowX: 'auto',
              width: '100%',
              padding: '4px 6px',
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
                    padding: '5px 10px',
                    backgroundColor: 'var(--card-bg, #fff)',
                    border: '1px solid var(--border-color, #e0e0e0)',
                    borderRadius: '16px',
                    cursor: 'pointer',
                    flexShrink: 0,
                    boxShadow: '0 2px 6px rgba(0,0,0,0.04)'
                  }}
                  title={`Tap to select ${m.displayName}`}
                >
                  <div style={{
                    width: '26px',
                    height: '26px',
                    borderRadius: '50%',
                    backgroundColor: 'var(--primary-light, #e0f2f1)',
                    color: 'var(--primary, #008069)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontWeight: '800',
                    fontSize: '11px'
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
                      width: '22px',
                      height: '22px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      cursor: 'pointer',
                      padding: 0,
                      marginLeft: '2px'
                    }}
                    title={`Call ${m.displayName}`}
                  >
                    <Phone size={11} color="#fff" />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* 3x4 Dual-Frequency DTMF Keypad Grid */}
      <div style={{ 
        display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', 
        gap: '14px', width: '100%', maxWidth: '290px', margin: '8px auto 18px auto'
      }}>
        {KEYPAD_KEYS.map(({ digit, sub }) => (
          <div key={digit} style={{ display: 'flex', justifyContent: 'center' }}>
            <button 
              type="button"
              className="keypad-btn" 
              onClick={() => handlePress(digit)}
              onPointerDown={() => handlePointerDownKey(digit)}
              onPointerUp={() => handlePointerUpKey(digit)}
              onPointerLeave={() => handlePointerUpKey(digit)}
              style={{
                width: '74px', height: '74px', borderRadius: '50%',
                display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
                backgroundColor: activeKey === digit ? 'var(--primary-light, #e0f2f1)' : 'var(--card-bg, #fff)',
                border: activeKey === digit ? '1.5px solid var(--primary, #008069)' : '1px solid var(--border-color, rgba(0,0,0,0.08))',
                boxShadow: '0 3px 10px rgba(0,0,0,0.05)',
                cursor: 'pointer',
                transition: 'all 0.12s ease'
              }}
            >
              <span className="number" style={{ fontSize: '26px', fontWeight: '800', lineHeight: 1, color: 'var(--text-main, #111)' }}>
                {digit}
              </span>
              {sub ? (
                <span className="letters" style={{ fontSize: '9px', fontWeight: '800', color: 'var(--text-secondary, #888)', marginTop: '3px', letterSpacing: '1px' }}>
                  {sub}
                </span>
              ) : (
                <span style={{ height: '11px' }} />
              )}
            </button>
          </div>
        ))}
      </div>

      {/* Bottom Action Row: Audio Call, Video Call, Backspace with Long-press Clear */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '14px', width: '100%', maxWidth: '290px', paddingBottom: '16px' }}>
        <button 
          type="button"
          className="neo-btn" 
          style={{ 
            flex: 1.4, height: '54px', borderRadius: '27px',
            backgroundColor: callBtnDisabled ? '#E0E0E0' : '#008069', 
            color: '#FFFFFF', border: 'none', cursor: callBtnDisabled ? 'not-allowed' : 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px',
            boxShadow: callBtnDisabled ? 'none' : '0 4px 14px rgba(0, 128, 105, 0.35)',
            transition: 'all 0.18s ease'
          }}
          onClick={() => handleStartCall('AUDIO')}
          disabled={callBtnDisabled}
          title="Start Audio Call"
        >
          <Phone size={22} color="#fff" />
          <span style={{ fontSize: '15px', fontWeight: '900', letterSpacing: '0.4px' }}>CALL</span>
        </button>

        <button 
          type="button"
          className="neo-btn" 
          style={{ 
            width: '54px', height: '54px', borderRadius: '50%', padding: 0,
            backgroundColor: callBtnDisabled ? '#E0E0E0' : '#00A884',
            color: '#FFFFFF', border: 'none', cursor: callBtnDisabled ? 'not-allowed' : 'pointer',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: callBtnDisabled ? 'none' : '0 4px 14px rgba(0, 168, 132, 0.35)',
            transition: 'all 0.18s ease'
          }}
          onClick={() => handleStartCall('VIDEO')}
          disabled={callBtnDisabled}
          title="Start Video Call"
        >
          <Video size={22} color="#fff" />
        </button>

        <button 
          type="button"
          style={{ 
            width: '50px', height: '50px', borderRadius: '50%', border: 'none', 
            background: number.length > 0 ? 'rgba(0,0,0,0.04)' : 'transparent', 
            cursor: number.length > 0 ? 'pointer' : 'default',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            transition: 'all 0.15s ease',
            opacity: number.length > 0 ? 1 : 0.25
          }}
          onClick={handleDelete}
          onPointerDown={handlePointerDownDelete}
          onPointerUp={handlePointerUpDelete}
          onPointerLeave={handlePointerUpDelete}
          disabled={number.length === 0}
          title="Backspace (Hold to Clear)"
        >
          <Delete size={26} color="var(--text-main, #333)" />
        </button>
      </div>
    </div>
  );
}
