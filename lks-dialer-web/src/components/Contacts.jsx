import React, { useEffect, useState } from 'react';
import { Phone, Video, Search } from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';

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
      // Filter out self
      const others = users.filter(u => u.phoneNumber !== webRtcEngine.currentUser?.phoneNumber);
      setContacts(others);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const filtered = contacts.filter(c => 
    c.displayName?.toLowerCase().includes(search.toLowerCase()) || 
    c.phoneNumber.includes(search)
  );

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
        filtered.map(contact => (
          <div key={contact.phoneNumber} className="neo-box" style={{ padding: '16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                <div style={{ 
                  width: '48px', height: '48px', borderRadius: '50%', 
                  backgroundColor: 'var(--secondary)', border: '3px solid #000',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontWeight: '900', fontSize: '20px', overflow: 'hidden'
                }}>
                  {contact.profilePictureUrl && (
                    <img 
                      src={contact.profilePictureUrl} 
                      alt={contact.displayName} 
                      style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                      onError={(e) => {
                        e.target.style.display = 'none';
                        if (e.target.nextSibling) e.target.nextSibling.style.display = 'block';
                      }}
                    />
                  )}
                  <span style={{ display: contact.profilePictureUrl ? 'none' : 'block' }}>
                    {contact.displayName?.[0]?.toUpperCase()}
                  </span>
                </div>
              <div>
                <h3 style={{ margin: 0, fontSize: '18px', fontWeight: '800' }}>{contact.displayName}</h3>
                <div style={{ fontSize: '14px', fontWeight: '600', color: '#555', marginTop: '4px' }}>
                  {contact.phoneNumber}
                </div>
              </div>
            </div>

            <div style={{ display: 'flex', gap: '12px' }}>
              <button 
                onClick={() => onStartCall(contact.phoneNumber, 'AUDIO')}
                className="neo-box"
                style={{ 
                  width: '40px', height: '40px', padding: 0, display: 'flex', 
                  alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
                  backgroundColor: 'var(--accent)'
                }}
              >
                <Phone size={20} color="#000" />
              </button>
              <button 
                onClick={() => onStartCall(contact.phoneNumber, 'VIDEO')}
                className="neo-box"
                style={{ 
                  width: '40px', height: '40px', padding: 0, display: 'flex', 
                  alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
                  backgroundColor: 'var(--primary)'
                }}
              >
                <Video size={20} color="#fff" />
              </button>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
