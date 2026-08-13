import React, { useState, useRef } from 'react';
import { Camera, Save } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';

export default function Profile() {
  const [name, setName] = useState(webRtcEngine.currentUser?.displayName || "");
  const [avatar, setAvatar] = useState(webRtcEngine.currentUser?.profilePictureUrl || "");
  const [customRingtone, setCustomRingtone] = useState(localStorage.getItem('customRingtone') || "");
  const [loading, setLoading] = useState(false);
  const fileInputRef = useRef(null);
  const audioInputRef = useRef(null);

  const handleImageUpload = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const img = new Image();
      img.onload = () => {
        // Compress and resize image to 150x150 max
        const canvas = document.createElement('canvas');
        const MAX_SIZE = 150;
        let width = img.width;
        let height = img.height;

        if (width > height) {
          if (width > MAX_SIZE) {
            height *= MAX_SIZE / width;
            width = MAX_SIZE;
          }
        } else {
          if (height > MAX_SIZE) {
            width *= MAX_SIZE / height;
            height = MAX_SIZE;
          }
        }

        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, width, height);

        // Convert to Base64 (JPEG format with 0.7 quality to keep it tiny)
        const base64String = canvas.toDataURL('image/jpeg', 0.7);
        setAvatar(base64String);
      };
      img.src = event.target.result;
    };
    reader.readAsDataURL(file);
  };

  const handleAudioUpload = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (file.size > 2 * 1024 * 1024) { // 2MB limit
      alert("Ringtone must be under 2MB!");
      return;
    }

    const reader = new FileReader();
    reader.onload = (event) => {
      const base64Audio = event.target.result;
      setCustomRingtone(base64Audio);
      localStorage.setItem('customRingtone', base64Audio);
      alert("Custom ringtone saved successfully!");
    };
    reader.readAsDataURL(file);
  };

  const handleSave = async () => {
    setLoading(true);
    try {
      const updatedUser = await webRtcEngine.updateProfile(name, avatar);
      localStorage.setItem('lksDialerUser', JSON.stringify(updatedUser));
      alert("Profile updated successfully!");
    } catch (e) {
      console.error(e);
      alert("Failed to update profile.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="scrollable-content" style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
      <h2 style={{ fontSize: '32px', fontWeight: '900', borderBottom: '4px solid #000', paddingBottom: '8px' }}>
        PROFILE
      </h2>

      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '16px' }}>
        <div 
          onClick={() => fileInputRef.current?.click()}
          style={{ 
            width: '120px', height: '120px', borderRadius: '50%',
            backgroundColor: 'var(--primary)', border: '4px solid #000',
            boxShadow: '4px 4px 0 #000',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer', position: 'relative', overflow: 'hidden'
          }}
        >
          {avatar && (
            <img 
              src={avatar} 
              alt="Profile" 
              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              onError={(e) => {
                e.target.style.display = 'none';
                if (e.target.nextSibling) e.target.nextSibling.style.display = 'flex';
              }}
            />
          )}
          <div style={{ 
            display: avatar ? 'none' : 'flex', 
            width: '100%', height: '100%', alignItems: 'center', justifyContent: 'center',
            fontSize: '48px', fontWeight: '900', color: '#fff' 
          }}>
            {name?.[0]?.toUpperCase() || '?'}
          </div>
          
          <div style={{ 
            position: 'absolute', bottom: 0, left: 0, right: 0, height: '30px',
            backgroundColor: 'rgba(0,0,0,0.6)', display: 'flex',
            alignItems: 'center', justifyContent: 'center'
          }}>
            <Camera size={16} color="#fff" />
          </div>
        </div>
        <input 
          type="file" 
          accept="image/*" 
          ref={fileInputRef} 
          style={{ display: 'none' }} 
          onChange={handleImageUpload}
        />
        <div style={{ fontWeight: '800', color: '#555' }}>
          {webRtcEngine.currentUser?.phoneNumber}
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <label style={{ fontWeight: '800' }}>DISPLAY NAME</label>
        <input 
          type="text" 
          className="neo-input" 
          value={name} 
          onChange={(e) => setName(e.target.value)}
        />
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <label style={{ fontWeight: '800' }}>CUSTOM RINGTONE</label>
        <input 
          type="file" 
          accept="audio/*" 
          ref={audioInputRef} 
          style={{ display: 'none' }} 
          onChange={handleAudioUpload}
        />
        <div style={{ display: 'flex', gap: '10px' }}>
          <button 
            className="neo-btn" 
            style={{ flex: 1, backgroundColor: 'var(--secondary)' }}
            onClick={() => audioInputRef.current?.click()}
          >
            {customRingtone ? "CHANGE RINGTONE" : "UPLOAD RINGTONE"}
          </button>
          {customRingtone && (
            <button 
              className="neo-btn danger" 
              style={{ padding: '0 16px' }}
              onClick={() => {
                localStorage.removeItem('customRingtone');
                setCustomRingtone("");
              }}
            >
              CLEAR
            </button>
          )}
        </div>
        {customRingtone && (
          <audio controls src={customRingtone} style={{ width: '100%', marginTop: '8px' }} />
        )}
      </div>

      <button 
        className="neo-btn" 
        onClick={handleSave} 
        disabled={loading}
        style={{ marginTop: 'auto', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px' }}
      >
        <Save size={20} />
        {loading ? "SAVING..." : "SAVE PROFILE"}
      </button>

    </div>
  );
}
