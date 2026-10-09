import React, { useState, useEffect, useRef } from 'react';
import { QrCode, RefreshCw, Smartphone, ShieldCheck, CheckCircle2, ArrowRight } from 'lucide-react';
import { QrLoginManager } from '../lib/QrLoginWeb';

export default function QrLoginView({ onLoginSuccess, showInstructions = true }) {
  const [status, setStatus] = useState('PENDING'); // PENDING | SCANNED | APPROVED | EXPIRED | ERROR
  const [secondsRemaining, setSecondsRemaining] = useState(60);
  const [errorMessage, setErrorMessage] = useState('');
  const [qrManager] = useState(() => new QrLoginManager());
  const canvasRef = useRef(null);
  const timerIntervalRef = useRef(null);

  const startNewSession = async () => {
    setStatus('PENDING');
    setSecondsRemaining(60);
    setErrorMessage('');

    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }

    try {
      if (canvasRef.current) {
        await qrManager.startSession({
          canvas: canvasRef.current,
          onStatusChange: (newStatus) => {
            setStatus(newStatus);
            if (newStatus === 'APPROVED' || newStatus === 'EXPIRED') {
              if (timerIntervalRef.current) {
                clearInterval(timerIntervalRef.current);
                timerIntervalRef.current = null;
              }
            }
          },
          onSuccess: (userData) => {
            setStatus('APPROVED');
            if (timerIntervalRef.current) {
              clearInterval(timerIntervalRef.current);
              timerIntervalRef.current = null;
            }
            setTimeout(() => {
              onLoginSuccess(userData);
            }, 600);
          },
          onError: (err) => {
            setErrorMessage(err.message || 'Failed to authenticate QR session');
            setStatus('ERROR');
          }
        });

        // Start countdown interval
        const startTime = Date.now();
        const totalDuration = 60000;
        timerIntervalRef.current = setInterval(() => {
          const elapsed = Date.now() - startTime;
          const remaining = Math.max(0, Math.ceil((totalDuration - elapsed) / 1000));
          setSecondsRemaining(remaining);
          if (remaining <= 0) {
            clearInterval(timerIntervalRef.current);
            timerIntervalRef.current = null;
            setStatus('EXPIRED');
          }
        }, 1000);
      }
    } catch (e) {
      console.error('Failed to start QR session:', e);
      setErrorMessage(e.message || 'Network error');
      setStatus('ERROR');
    }
  };

  useEffect(() => {
    startNewSession();
    return () => {
      qrManager.cleanup();
      if (timerIntervalRef.current) {
        clearInterval(timerIntervalRef.current);
        timerIntervalRef.current = null;
      }
    };
  }, []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '100%' }}>
      {/* QR Code Container */}
      <div 
        style={{ 
          position: 'relative', 
          width: 'min(280px, 86vw)', 
          height: 'min(280px, 86vw)',
          background: '#ffffff',
          borderRadius: '24px',
          padding: '16px',
          boxShadow: '0 12px 36px rgba(0,0,0,0.12)',
          border: '3px solid #0f172a',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          overflow: 'hidden'
        }}
      >
        <canvas 
          ref={canvasRef} 
          width={248} 
          height={248} 
          style={{ 
            maxWidth: '100%',
            maxHeight: '100%',
            borderRadius: '16px', 
            display: 'block',
            filter: (status === 'EXPIRED' || status === 'APPROVED') ? 'blur(4px) opacity(0.35)' : 'none',
            transition: 'filter 0.3s ease'
          }} 
        />

        {/* Expired Overlay */}
        {status === 'EXPIRED' && (
          <div 
            style={{ 
              position: 'absolute', 
              inset: 0, 
              display: 'flex', 
              flexDirection: 'column', 
              alignItems: 'center', 
              justifyContent: 'center', 
              background: 'rgba(255,255,255,0.92)',
              borderRadius: '24px',
              padding: '20px',
              textAlign: 'center',
              backdropFilter: 'blur(3px)'
            }}
          >
            <p style={{ fontWeight: '800', fontSize: '15px', color: '#dc2626', marginBottom: '8px' }}>
              QR code expired
            </p>
            <p style={{ fontSize: '12px', color: '#64748b', marginBottom: '16px', lineHeight: 1.4 }}>
              For your security, login codes expire every 60 seconds.
            </p>
            <button
              type="button"
              onClick={startNewSession}
              className="neo-btn"
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '8px',
                padding: '10px 18px',
                fontSize: '13px',
                backgroundColor: '#10b981',
                color: '#ffffff'
              }}
            >
              <RefreshCw size={16} />
              Reload QR Code
            </button>
          </div>
        )}

        {/* Scanned / Pending Confirmation Overlay */}
        {status === 'SCANNED' && (
          <div 
            style={{ 
              position: 'absolute', 
              inset: 0, 
              display: 'flex', 
              flexDirection: 'column', 
              alignItems: 'center', 
              justifyContent: 'center', 
              background: 'rgba(255,255,255,0.95)',
              borderRadius: '24px',
              padding: '20px',
              textAlign: 'center'
            }}
          >
            <div style={{
              width: '48px',
              height: '48px',
              borderRadius: '50%',
              border: '3px solid #10b981',
              borderTopColor: 'transparent',
              animation: 'spin 1s linear infinite',
              marginBottom: '14px'
            }} />
            <p style={{ fontWeight: '800', fontSize: '15px', color: '#0f172a', marginBottom: '4px' }}>
              QR Code Scanned!
            </p>
            <p style={{ fontSize: '12px', color: '#64748b' }}>
              Tap <strong>"Link Device"</strong> on your phone to complete login.
            </p>
          </div>
        )}

        {/* Approved Overlay */}
        {status === 'APPROVED' && (
          <div 
            style={{ 
              position: 'absolute', 
              inset: 0, 
              display: 'flex', 
              flexDirection: 'column', 
              alignItems: 'center', 
              justifyContent: 'center', 
              background: 'rgba(255,255,255,0.95)',
              borderRadius: '24px',
              padding: '20px',
              textAlign: 'center'
            }}
          >
            <CheckCircle2 size={54} color="#10b981" style={{ marginBottom: '10px' }} />
            <p style={{ fontWeight: '900', fontSize: '17px', color: '#0f172a', marginBottom: '4px' }}>
              Logged In!
            </p>
            <p style={{ fontSize: '12px', color: '#64748b' }}>
              Opening your conversations...
            </p>
          </div>
        )}
      </div>

      {/* Countdown and Status Pill */}
      <div style={{ marginTop: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
        {status === 'PENDING' && (
          <div 
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              background: '#f1f5f9',
              padding: '6px 14px',
              borderRadius: '20px',
              fontSize: '13px',
              fontWeight: '700',
              color: '#334155',
              border: '1px solid #cbd5e1'
            }}
          >
            <span 
              style={{ 
                width: '8px', 
                height: '8px', 
                borderRadius: '50%', 
                backgroundColor: '#10b981',
                boxShadow: '0 0 8px #10b981'
              }} 
            />
            <span>Expires in {secondsRemaining}s</span>
            <button
              type="button"
              onClick={startNewSession}
              title="Refresh QR Code"
              style={{
                background: 'none',
                border: 'none',
                cursor: 'pointer',
                display: 'inline-flex',
                alignItems: 'center',
                color: '#64748b',
                padding: '2px',
                marginLeft: '2px'
              }}
            >
              <RefreshCw size={13} />
            </button>
          </div>
        )}

        {status === 'ERROR' && (
          <p style={{ color: '#ef4444', fontSize: '13px', fontWeight: '700', margin: 0 }}>
            {errorMessage || 'Connection error. Please try again.'}
          </p>
        )}
      </div>

      {/* Instructions list (WhatsApp Web style) */}
      {showInstructions && (
        <div 
          style={{ 
            marginTop: '20px', 
            width: '100%', 
            maxWidth: '380px',
            background: 'rgba(248, 250, 252, 0.85)',
            borderRadius: '16px',
            border: '2px solid #e2e8f0',
            padding: '16px 20px',
            textAlign: 'left'
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
            <ShieldCheck size={18} color="#10b981" />
            <span style={{ fontSize: '13px', fontWeight: '800', color: '#0f172a', letterSpacing: '0.3px', textTransform: 'uppercase' }}>
              How to log in with QR code:
            </span>
          </div>

          <ol style={{ margin: 0, paddingLeft: '20px', display: 'flex', flexDirection: 'column', gap: '8px', fontSize: '13px', color: '#334155', lineHeight: 1.4 }}>
            <li>
              Open <strong>LKS Dialer</strong> on your Android phone.
            </li>
            <li>
              Tap <strong>Settings ⚙️</strong> or <strong>Menu (⋮)</strong> &rarr; <strong>Linked Devices</strong>.
            </li>
            <li>
              Tap <strong>"Link a Device"</strong> and point your camera here.
            </li>
          </ol>
        </div>
      )}

      <style>{`
        @keyframes spin {
          0% { transform: rotate(0deg); }
          100% { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
}
