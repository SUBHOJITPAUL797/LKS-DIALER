import React, { useState } from 'react';
import { Search, X } from 'lucide-react';
import { allCountries } from '../lib/CountryCodes';

export default function CountryCodePickerModal({ selectedCountry, onCountrySelected, onDismiss }) {
  const [search, setSearch] = useState("");

  const filtered = allCountries.filter(c => 
    c.name.toLowerCase().includes(search.toLowerCase()) || 
    c.dialCode.includes(search)
  );

  return (
    <div style={{
      position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
      backgroundColor: 'rgba(0,0,0,0.8)',
      display: 'flex', alignItems: 'flex-end',
      zIndex: 200
    }}>
      <div className="neo-box" style={{ 
        width: '100%', height: '80%', padding: '0', 
        display: 'flex', flexDirection: 'column',
        backgroundColor: 'var(--bg-color)',
        borderBottomLeftRadius: 0, borderBottomRightRadius: 0,
        boxShadow: 'none', borderBottom: 'none'
      }}>
        <div style={{ 
          padding: '20px', borderBottom: '4px solid #000', 
          display: 'flex', justifyContent: 'space-between', alignItems: 'center' 
        }}>
          <h2 style={{ margin: 0, fontSize: '24px', fontWeight: '900' }}>Select Country</h2>
          <button 
            onClick={onDismiss}
            style={{ 
              background: 'none', border: 'none', cursor: 'pointer',
              display: 'flex', alignItems: 'center', justifyContent: 'center' 
            }}
          >
            <X size={28} color="#000" />
          </button>
        </div>

        <div style={{ padding: '20px', borderBottom: '4px solid #000' }}>
          <div style={{ position: 'relative' }}>
            <Search size={20} style={{ position: 'absolute', left: '16px', top: '16px', color: '#000' }} />
            <input 
              type="text" 
              className="neo-input" 
              placeholder="Search country or code..." 
              style={{ paddingLeft: '48px', boxShadow: 'none' }}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
        </div>

        <div className="scrollable-content" style={{ padding: 0 }}>
          {filtered.map(country => (
            <div 
              key={country.code} 
              onClick={() => {
                onCountrySelected(country);
                onDismiss();
              }}
              style={{ 
                padding: '20px', borderBottom: '2px solid #000',
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                cursor: 'pointer', backgroundColor: selectedCountry.code === country.code ? 'var(--accent)' : 'transparent'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                <span style={{ fontSize: '28px' }}>{country.flag}</span>
                <span style={{ fontSize: '18px', fontWeight: '700' }}>{country.name}</span>
              </div>
              <span style={{ fontSize: '18px', fontWeight: '900' }}>{country.dialCode}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
