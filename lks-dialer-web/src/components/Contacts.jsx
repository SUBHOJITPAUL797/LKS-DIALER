import React, { useEffect, useState } from 'react';
import { Phone, Video, Search } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { formatAvatarUrl } from '../lib/ImageUtils';

export default function Contacts({ onStartCall }) {
  const [contacts, setContacts] = useState([]);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadContacts();
  }, []);

  const loadContacts = async () => {
    try {
      const users = await webRtcEngine.getRegisteredUsers();
      // Filter out self and users without valid phone numbers
      const myPhone = webRtcEngine.currentUser?.phoneNumber;
      const others = (users || []).filter(u => u && u.phoneNumber && u.phoneNumber !== myPhone);
      setContacts(others);
    } catch (e) {
      console.error("Failed to load contacts:", e);
      setContacts([]);
    } finally {
      setLoading(false);
    }
  };

  const searchLower = (search || '').trim().toLowerCase();
  const filtered = contacts.filter(c => {
    if (!c) return false;
    const nameMatch = c.displayName ? String(c.displayName).toLowerCase().includes(searchLower) : false;
    const phoneMatch = c.phoneNumber ? String(c.phoneNumber).includes(search.trim()) : false;
    return nameMatch || phoneMatch;
  });

  return (
    <div className="scrollable-content" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <h2 style={{ fontSize: '32px', fontWeight: '900', borderBottom: '4px solid #000', paddingBottom: '8px' }}>
        CONTACTS
      </h2>
      
      <div style={{ position: 'relative' }}>
        <Search size={20} style={{ position: 'absolute', left: '16px', top: '16px', color: '#000' }} />
        <input 
          type="text" 
          className="neo-input" 
          placeholder="Search..." 
          style={{ paddingLeft: '48px' }}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      {loading ? (
        <>
          {[1, 2, 3, 4].map(i => (
            <div key={`skeleton-${i}`} className="neo-box" style={{ padding: '16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderColor: '#ccc' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '16px', flex: 1 }}>
                <div className="neo-skeleton-circle" style={{ width: '48px', height: '48px' }} />
                <div style={{ flex: 1 }}>
                  <div className="neo-skeleton" style={{ width: '60%', height: '24px', marginBottom: '8px' }} />
                  <div className="neo-skeleton" style={{ width: '40%', height: '16px' }} />
                </div>
              </div>
              <div style={{ display: 'flex', gap: '12px', marginLeft: '16px' }}>
                <div className="neo-skeleton" style={{ width: '40px', height: '40px' }} />
                <div className="neo-skeleton" style={{ width: '40px', height: '40px' }} />
              </div>
            </div>
          ))}
        </>
      ) : filtered.length === 0 ? (
        <div className="neo-box" style={{ padding: '24px', textAlign: 'center' }}>
          <h3>No Contacts Found</h3>
        </div>
      ) : (
        filtered.map(contact => {
          const displayName = contact.displayName || contact.phoneNumber || "Unknown";
          const phoneNumber = contact.phoneNumber || "";
          const contactAvatar = formatAvatarUrl(contact.profilePictureUrl);
          const avatarInitial = (displayName || phoneNumber || "?")[0]?.toUpperCase() || "?";

          return (
            <div key={contact.phoneNumber || contact.id} className="neo-box" style={{ padding: '16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                <div style={{ 
                  width: '48px', height: '48px', borderRadius: '50%', 
                  backgroundColor: 'var(--secondary)', border: '3px solid #000',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontWeight: '900', fontSize: '20px', overflow: 'hidden'
                }}>
                  {contactAvatar && (
                    <img 
                      src={contactAvatar} 
                      alt={displayName} 
                      style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                      onError={(e) => {
                        e.target.style.display = 'none';
                        if (e.target.nextSibling) e.target.nextSibling.style.display = 'block';
                      }}
                    />
                  )}
                  <span style={{ display: contactAvatar ? 'none' : 'block' }}>
                    {avatarInitial}
                  </span>
                </div>
                <div>
                  <h3 style={{ margin: 0, fontSize: '18px', fontWeight: '800' }}>{displayName}</h3>
                  <div style={{ fontSize: '14px', fontWeight: '600', color: '#555', marginTop: '4px' }}>
                    {phoneNumber}
                  </div>
                </div>
              </div>

              <div style={{ display: 'flex', gap: '12px' }}>
                <button 
                  onClick={() => phoneNumber && onStartCall(phoneNumber, 'AUDIO')}
                  className="neo-box"
                  disabled={!phoneNumber}
                  style={{ 
                    width: '40px', height: '40px', padding: 0, display: 'flex', 
                    alignItems: 'center', justifyContent: 'center', cursor: phoneNumber ? 'pointer' : 'default',
                    backgroundColor: 'var(--accent)'
                  }}
                >
                  <Phone size={20} color="#000" />
                </button>
                <button 
                  onClick={() => phoneNumber && onStartCall(phoneNumber, 'VIDEO')}
                  className="neo-box"
                  disabled={!phoneNumber}
                  style={{ 
                    width: '40px', height: '40px', padding: 0, display: 'flex', 
                    alignItems: 'center', justifyContent: 'center', cursor: phoneNumber ? 'pointer' : 'default',
                    backgroundColor: 'var(--primary)'
                  }}
                >
                  <Video size={20} color="#fff" />
                </button>
              </div>
            </div>
          );
        })
      )}
    </div>
  );
}
