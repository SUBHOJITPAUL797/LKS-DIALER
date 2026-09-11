import React from 'react';
import { Shield, Lock, Zap, Phone, MessageSquare, Grid } from 'lucide-react';
import appLogo from '../assets/app_logo.png';

export default function DesktopChatPlaceholder({ onOpenDialer, onNewChat }) {
  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      height: '100%',
      width: '100%',
      backgroundColor: 'var(--bg-color)',
      padding: '40px 24px',
      textAlign: 'center',
      position: 'relative',
      overflow: 'hidden'
    }}>
      <div style={{
        maxWidth: '560px',
        width: '100%',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '24px'
      }}>
        {/* App Logo with Neo-Brutalist frame */}
        <div className="neo-box" style={{
          width: '96px',
          height: '96px',
          borderRadius: '24px',
          backgroundColor: '#fff',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          overflow: 'hidden',
          padding: '8px'
        }}>
          <img 
            src={appLogo} 
            alt="LKS Dialer" 
            style={{ width: '100%', height: '100%', objectFit: 'contain' }}
            onError={(e) => {
              e.target.style.display = 'none';
              if (e.target.nextSibling) e.target.nextSibling.style.display = 'flex';
            }}
          />
          <div style={{ display: 'none', alignItems: 'center', justifyContent: 'center' }}>
            <MessageSquare size={48} color="var(--primary)" />
          </div>
        </div>

        {/* Title & Tagline */}
        <div>
          <h2 style={{
            fontSize: '32px',
            fontWeight: '900',
            letterSpacing: '1px',
            margin: '0 0 8px 0',
            color: 'var(--text-main)'
          }}>
            LKS DIALER WEB
          </h2>
          <p style={{
            fontSize: '15px',
            fontWeight: '600',
            color: '#666',
            lineHeight: '1.5',
            margin: 0
          }}>
            Send and receive end-to-end encrypted messages and make crystal-clear HD voice & video calls.
          </p>
        </div>

        {/* Security / Feature Badges */}
        <div style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: '12px',
          justifyContent: 'center'
        }}>
          <div className="neo-box" style={{
            padding: '8px 14px',
            borderRadius: '20px',
            backgroundColor: '#fff',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            fontSize: '13px',
            fontWeight: '800'
          }}>
            <Lock size={16} color="var(--primary)" strokeWidth={2.5} />
            <span>End-to-End Encrypted</span>
          </div>

          <div className="neo-box" style={{
            padding: '8px 14px',
            borderRadius: '20px',
            backgroundColor: '#fff',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            fontSize: '13px',
            fontWeight: '800'
          }}>
            <Zap size={16} color="var(--accent)" strokeWidth={2.5} />
            <span>Zero Server Retention</span>
          </div>

          <div className="neo-box" style={{
            padding: '8px 14px',
            borderRadius: '20px',
            backgroundColor: '#fff',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            fontSize: '13px',
            fontWeight: '800'
          }}>
            <Phone size={16} color="#00C9FF" strokeWidth={2.5} />
            <span>VoIP & HD Video</span>
          </div>
        </div>

        {/* Prompt Card */}
        <div className="neo-box" style={{
          padding: '20px 24px',
          backgroundColor: '#fff',
          width: '100%',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '14px'
        }}>
          <p style={{
            margin: 0,
            fontSize: '14px',
            fontWeight: '700',
            color: '#444'
          }}>
            👈 Select a conversation from the left to start chatting, or jump straight to the keypad:
          </p>
          <div style={{ display: 'flex', gap: '12px' }}>
            {onOpenDialer && (
              <button 
                onClick={onOpenDialer} 
                className="neo-btn" 
                style={{ 
                  padding: '10px 20px', 
                  fontSize: '14px',
                  backgroundColor: 'var(--accent)',
                  color: '#000'
                }}
              >
                <Grid size={16} strokeWidth={2.5} />
                <span>Open Keypad</span>
              </button>
            )}
          </div>
        </div>

        {/* Footer Security Note */}
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: '6px',
          fontSize: '12px',
          fontWeight: '700',
          color: '#888'
        }}>
          <Shield size={14} color="#888" />
          <span>Secured with AES-256-GCM & ECDH P-256</span>
        </div>
      </div>
    </div>
  );
}
