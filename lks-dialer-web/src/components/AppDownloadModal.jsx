import React from 'react';
import { 
  Download, ExternalLink, X, Smartphone, Zap, BellRing, 
  ShieldCheck, CheckCircle2, ChevronRight, Sparkles, Volume2 
} from 'lucide-react';
import appLogo from '../assets/app_logo.png';

export const LATEST_APP_VERSION = 'v2.7.7';
export const DIRECT_APK_URL = 'https://github.com/SUBHOJITPAUL797/LKS-DIALER/releases/download/v2.7.7/app-release.apk';
export const GITHUB_RELEASES_URL = 'https://github.com/SUBHOJITPAUL797/LKS-DIALER/releases/latest';

export default function AppDownloadModal({ isOpen, onClose }) {
  if (!isOpen) return null;

  return (
    <div 
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.65)',
        backdropFilter: 'blur(4px)',
        zIndex: 9999,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '16px',
        animation: 'fadeIn 0.15s ease-out'
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div 
        className="neo-box"
        style={{
          width: '100%',
          maxWidth: '520px',
          maxHeight: '90vh',
          backgroundColor: '#ffffff',
          borderRadius: '16px',
          border: '3.5px solid #000',
          boxShadow: '8px 8px 0 #000',
          overflowY: 'auto',
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
          padding: '24px 20px'
        }}
      >
        {/* Close Button */}
        <button
          onClick={onClose}
          aria-label="Close"
          style={{
            position: 'absolute',
            top: '16px',
            right: '16px',
            width: '36px',
            height: '36px',
            borderRadius: '10px',
            border: '2.5px solid #000',
            backgroundColor: '#fff',
            boxShadow: '2px 2px 0 #000',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: '900',
            zIndex: 10
          }}
        >
          <X size={20} />
        </button>

        {/* Modal Header */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '14px', marginBottom: '18px' }}>
          <div 
            className="neo-box" 
            style={{
              width: '56px',
              height: '56px',
              borderRadius: '14px',
              border: '3px solid #000',
              backgroundColor: '#FFE600',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: '3px 3px 0 #000',
              flexShrink: 0,
              overflow: 'hidden'
            }}
          >
            <img 
              src={appLogo} 
              alt="LKS Dialer" 
              style={{ width: '85%', height: '85%', objectFit: 'contain' }}
              onError={(e) => {
                e.target.style.display = 'none';
              }} 
            />
          </div>

          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
              <h3 style={{ margin: 0, fontSize: '20px', fontWeight: '900', letterSpacing: '0.5px' }}>
                LKS DIALER FOR ANDROID
              </h3>
              <span 
                style={{
                  fontSize: '11px',
                  fontWeight: '900',
                  backgroundColor: '#00E676',
                  color: '#000',
                  border: '1.5px solid #000',
                  padding: '2px 8px',
                  borderRadius: '12px',
                  boxShadow: '1.5px 1.5px 0 #000'
                }}
              >
                {LATEST_APP_VERSION} LATEST
              </span>
            </div>
            <p style={{ margin: '3px 0 0 0', fontSize: '13px', color: '#555', fontWeight: '700' }}>
              Native VoIP, P2P Video & Encrypted Chat Engine
            </p>
          </div>
        </div>

        {/* Highlight Banner */}
        <div 
          className="neo-box"
          style={{
            backgroundColor: '#FFF9C4',
            border: '2.5px solid #000',
            borderRadius: '12px',
            padding: '12px 14px',
            marginBottom: '18px',
            boxShadow: '3px 3px 0 #000',
            display: 'flex',
            alignItems: 'flex-start',
            gap: '10px'
          }}
        >
          <Sparkles size={20} color="#D84315" style={{ flexShrink: 0, marginTop: '2px' }} />
          <div style={{ fontSize: '13px', fontWeight: '700', color: '#333', lineHeight: '1.4' }}>
            <span style={{ fontWeight: '900', color: '#000' }}>Why install the Android App?</span> Web browsers sleep in the background. The native Android app guarantees <strong>24/7 incoming call ringing</strong>, lock screen answer, and hardware button controls!
          </div>
        </div>

        {/* Feature List */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', marginBottom: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '13.5px', fontWeight: '700' }}>
            <Zap size={18} color="#FF3366" style={{ flexShrink: 0 }} />
            <span><strong>24/7 Deep Sleep Ringing</strong> — Rings instantly even when app is killed or device is sleeping.</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '13.5px', fontWeight: '700' }}>
            <BellRing size={18} color="#00E5FF" style={{ flexShrink: 0 }} />
            <span><strong>Lock Screen Call Pickup</strong> — Fullscreen WhatsApp-style incoming call UI on locked screen.</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '13.5px', fontWeight: '700' }}>
            <Smartphone size={18} color="#00E676" style={{ flexShrink: 0 }} />
            <span><strong>Home Screen Floating Pill</strong> — Seamless active call bubble with mute, speaker & hangup.</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '13.5px', fontWeight: '700' }}>
            <Volume2 size={18} color="#FF9100" style={{ flexShrink: 0 }} />
            <span><strong>Volume Key Silence</strong> — Press volume down to instantly silence ringer on incoming calls.</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '13.5px', fontWeight: '700' }}>
            <ShieldCheck size={18} color="#7C4DFF" style={{ flexShrink: 0 }} />
            <span><strong>Universal OEM Autostart</strong> — 1-tap setup helper for Xiaomi, Samsung, OnePlus & Vivo.</span>
          </div>
        </div>

        {/* Download Buttons */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', marginBottom: '20px' }}>
          <a
            href={DIRECT_APK_URL}
            download="LKS-DIALER-v2.7.7.apk"
            target="_blank"
            rel="noopener noreferrer"
            className="neo-btn"
            style={{
              backgroundColor: '#00E676',
              color: '#000',
              textDecoration: 'none',
              padding: '14px 18px',
              fontSize: '16px',
              fontWeight: '900',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '10px',
              border: '3px solid #000',
              borderRadius: '12px',
              boxShadow: '4px 4px 0 #000'
            }}
          >
            <Download size={22} strokeWidth={2.5} />
            <span>DOWNLOAD APK ({LATEST_APP_VERSION})</span>
          </a>
          <div style={{ textAlign: 'center', fontSize: '12px', color: '#666', fontWeight: '700', marginTop: '-4px' }}>
            Official Release • 62.8 MB • Direct APK Download
          </div>

          <a
            href={GITHUB_RELEASES_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="neo-btn"
            style={{
              backgroundColor: 'var(--secondary)',
              color: '#000',
              textDecoration: 'none',
              padding: '10px 16px',
              fontSize: '13px',
              fontWeight: '800',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
              border: '2.5px solid #000',
              borderRadius: '10px',
              boxShadow: '3px 3px 0 #000'
            }}
          >
            <ExternalLink size={16} />
            <span>View Release Notes on GitHub</span>
          </a>
        </div>

        {/* 3-Step Install Guide */}
        <div 
          className="neo-box"
          style={{
            backgroundColor: '#F7F7F7',
            border: '2px solid #000',
            borderRadius: '12px',
            padding: '14px',
            boxShadow: '2px 2px 0 #000'
          }}
        >
          <div style={{ fontSize: '12px', fontWeight: '900', textTransform: 'uppercase', marginBottom: '8px', letterSpacing: '0.5px' }}>
            Quick 3-Step Installation:
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '12px', color: '#333', fontWeight: '600' }}>
            <div style={{ display: 'flex', gap: '8px', alignItems: 'flex-start' }}>
              <span style={{ backgroundColor: '#000', color: '#fff', width: '18px', height: '18px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '10px', fontWeight: '900', flexShrink: 0 }}>1</span>
              <span>Tap <strong>Download APK</strong> and tap the notification when finished.</span>
            </div>
            <div style={{ display: 'flex', gap: '8px', alignItems: 'flex-start' }}>
              <span style={{ backgroundColor: '#000', color: '#fff', width: '18px', height: '18px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '10px', fontWeight: '900', flexShrink: 0 }}>2</span>
              <span>Tap <strong>Install</strong> (Allow <em>"Install unknown apps"</em> if prompted by browser).</span>
            </div>
            <div style={{ display: 'flex', gap: '8px', alignItems: 'flex-start' }}>
              <span style={{ backgroundColor: '#000', color: '#fff', width: '18px', height: '18px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '10px', fontWeight: '900', flexShrink: 0 }}>3</span>
              <span>Open the app and grant <strong>Autostart</strong> permissions for 100% background reliability.</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
