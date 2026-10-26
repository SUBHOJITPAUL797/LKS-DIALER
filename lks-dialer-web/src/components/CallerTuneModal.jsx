import React, { useState, useRef, useEffect } from 'react';
import { 
  X, Music, Search, Play, Square, Check, Trash2, 
  Upload, Sparkles, Volume2, Loader2, Disc 
} from 'lucide-react';
import { webRtcEngine } from '../lib/WebRtcEngine';

// Curated royalty-free tunes (matches Android app 1:1)
const CURATED_TUNES = [
  {
    id: "curated_1",
    title: "Acoustic Guitar",
    artist: "Acoustic Melody",
    category: "Guitar & Chill",
    emoji: "🎸",
    streamUrl: "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview125/v4/59/8e/e7/598ee705-61af-531b-0a3a-03de11613905/mzaf_17596824643264671711.plus.aac.p.m4a"
  },
  {
    id: "curated_2",
    title: "Motivational LoFi Chill",
    artist: "Lo-Fi Beats",
    category: "Lo-Fi",
    emoji: "🎧",
    streamUrl: "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/37/18/65/3718655b-2dd7-1367-1374-0f0b0dff8fce/mzaf_6222293724942473305.plus.aac.p.m4a"
  },
  {
    id: "curated_3",
    title: "Corporate Uplifting",
    artist: "Soundpower",
    category: "Professional",
    emoji: "💼",
    streamUrl: "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview115/v4/5e/f5/81/5ef5810e-332e-2570-f060-d301931adbfb/mzaf_17429935323152206949.plus.aac.p.m4a"
  },
  {
    id: "curated_4",
    title: "Soft Piano Horizon",
    artist: "Peaceful Piano",
    category: "Piano",
    emoji: "🎹",
    streamUrl: "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/0e/06/c3/0e06c37e-1fc6-290d-a3dc-e25b711392ad/mzaf_1930168324025856273.plus.aac.p.m4a"
  },
  {
    id: "curated_5",
    title: "Romantic Violin",
    artist: "Jean-Claude Bensimon",
    category: "Romantic",
    emoji: "🎻",
    streamUrl: "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview125/v4/e2/bb/cb/e2bbcb87-f40e-da0f-0766-3b3d67dfdfe0/mzaf_15871035304556593605.plus.aac.p.m4a"
  }
];

const GENRE_CHIPS = [
  "Bollywood Hits", "Arijit Singh", "Punjabi Pop", "Lo-Fi Chill", "Trending", "Romantic", "Ed Sheeran"
];

export default function CallerTuneModal({ isOpen, onClose, onTuneUpdated }) {
  const [activeTab, setActiveTab] = useState('search'); // 'search' | 'curated' | 'custom'
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [isSearching, setIsSearching] = useState(false);
  const [activePreviewUrl, setActivePreviewUrl] = useState(null);
  const [savingId, setSavingId] = useState(null);
  const [customFile, setCustomFile] = useState(null);
  const [customTitle, setCustomTitle] = useState('');
  const [customAudioUrl, setCustomAudioUrl] = useState('');
  const [isConvertingAudio, setIsConvertingAudio] = useState(false);

  const previewAudioRef = useRef(null);
  const fileInputRef = useRef(null);

  const currentTune = webRtcEngine.currentUser?.callerTuneTitle ? {
    title: webRtcEngine.currentUser.callerTuneTitle,
    artist: webRtcEngine.currentUser.callerTuneArtist || "Artist",
    url: webRtcEngine.currentUser.callerTuneUrl || "",
    base64: webRtcEngine.currentUser.callerTuneAudioBase64 || ""
  } : null;

  // Cleanup audio preview when modal closes
  useEffect(() => {
    if (!isOpen) {
      stopPreview();
    }
  }, [isOpen]);

  // Initial trending search on load
  useEffect(() => {
    if (isOpen && searchResults.length === 0) {
      performSearch("Trending Songs");
    }
  }, [isOpen]);

  const stopPreview = () => {
    if (previewAudioRef.current) {
      previewAudioRef.current.pause();
      previewAudioRef.current.currentTime = 0;
      previewAudioRef.current.src = '';
    }
    setActivePreviewUrl(null);
  };

  const togglePreview = (url) => {
    if (!url) return;
    if (activePreviewUrl === url) {
      stopPreview();
    } else {
      stopPreview();
      const audio = new Audio(url);
      previewAudioRef.current = audio;
      audio.volume = 0.6;
      audio.onended = () => setActivePreviewUrl(null);
      audio.onerror = () => {
        console.warn("Preview playback failed for:", url);
        setActivePreviewUrl(null);
      };
      audio.play().catch(e => {
        console.warn("Playback error:", e);
        setActivePreviewUrl(null);
      });
      setActivePreviewUrl(url);
    }
  };

  const performSearch = async (query) => {
    const q = (query || searchQuery).trim();
    if (!q) return;
    setIsSearching(true);
    stopPreview();
    try {
      const endpoint = `https://itunes.apple.com/search?term=${encodeURIComponent(q)}&media=music&limit=25`;
      const res = await fetch(endpoint);
      const data = await res.json();
      const songs = (data.results || []).filter(item => item.previewUrl).map(item => ({
        id: String(item.trackId),
        title: item.trackName,
        artist: item.artistName,
        previewUrl: item.previewUrl,
        artworkUrl: item.artworkUrl100 ? item.artworkUrl100.replace("100x100", "200x200") : null
      }));
      setSearchResults(songs);
    } catch (e) {
      console.error("Music search failed:", e);
    } finally {
      setIsSearching(false);
    }
  };

  // Convert remote audio URL into Base64 so it works even offline or cross-platform
  const fetchAudioAsBase64 = async (url) => {
    try {
      const res = await fetch(url);
      const blob = await res.blob();
      return new Promise((resolve) => {
        const reader = new FileReader();
        reader.onloadend = () => {
          const base64data = reader.result;
          resolve(base64data);
        };
        reader.onerror = () => resolve("");
        reader.readAsDataURL(blob);
      });
    } catch (e) {
      console.warn("Could not convert audio to Base64:", e);
      return "";
    }
  };

  const handleSelectSearchSong = async (song) => {
    setSavingId(song.id);
    stopPreview();
    try {
      // Fetch 30-sec audio clip and encode as Base64 for guaranteed Firestore sync
      let base64 = "";
      try {
        base64 = await fetchAudioAsBase64(song.previewUrl);
      } catch (_) {}

      await webRtcEngine.updateCallerTune({
        title: song.title,
        artist: song.artist,
        audioUrl: song.previewUrl,
        audioBase64: base64,
        source: "SEARCH",
        startMs: 0,
        endMs: 30000
      });

      if (onTuneUpdated) onTuneUpdated();
      alert(`🎉 Caller Tune set to: "${song.title}"!\nCallers will now hear this tune while waiting.`);
      onClose();
    } catch (e) {
      console.error("Error setting caller tune:", e);
      alert("Failed to save caller tune. Please try again.");
    } finally {
      setSavingId(null);
    }
  };

  const handleSelectCuratedTune = async (tune) => {
    setSavingId(tune.id);
    stopPreview();
    try {
      let base64 = "";
      try {
        base64 = await fetchAudioAsBase64(tune.streamUrl);
      } catch (_) {}

      await webRtcEngine.updateCallerTune({
        title: tune.title,
        artist: tune.artist,
        audioUrl: tune.streamUrl,
        audioBase64: base64,
        source: "PRESET",
        startMs: 0,
        endMs: 30000
      });

      if (onTuneUpdated) onTuneUpdated();
      alert(`🎉 Caller Tune set to: "${tune.title}"!\nCallers will now hear this tune while waiting.`);
      onClose();
    } catch (e) {
      console.error("Error setting curated tune:", e);
      alert("Failed to save caller tune. Please try again.");
    } finally {
      setSavingId(null);
    }
  };

  const handleCustomAudioPicked = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 2.5 * 1024 * 1024) {
      alert("Audio file must be under 2.5MB!");
      return;
    }
    setCustomFile(file);
    setCustomTitle(file.name.replace(/\.[^/.]+$/, ""));
    const localUrl = URL.createObjectURL(file);
    setCustomAudioUrl(localUrl);
  };

  const handleSaveCustomTune = async () => {
    if (!customFile) return;
    setIsConvertingAudio(true);
    stopPreview();
    try {
      const reader = new FileReader();
      reader.onload = async (event) => {
        const base64Audio = event.target.result;
        await webRtcEngine.updateCallerTune({
          title: customTitle || "My Custom Tune",
          artist: "Personal Audio",
          audioUrl: "",
          audioBase64: base64Audio,
          source: "LOCAL",
          startMs: 0,
          endMs: 30000
        });
        setIsConvertingAudio(false);
        if (onTuneUpdated) onTuneUpdated();
        alert(`🎉 Custom Caller Tune set!\nCallers will now hear your audio.`);
        onClose();
      };
      reader.onerror = () => {
        setIsConvertingAudio(false);
        alert("Failed to read audio file.");
      };
      reader.readAsDataURL(customFile);
    } catch (e) {
      console.error("Error saving custom audio:", e);
      setIsConvertingAudio(false);
      alert("Failed to save custom audio.");
    }
  };

  const handleRemoveTune = async () => {
    if (!confirm("Are you sure you want to remove your Caller Tune? Callers will hear the standard ringback tone.")) return;
    stopPreview();
    try {
      await webRtcEngine.removeCallerTune();
      if (onTuneUpdated) onTuneUpdated();
      alert("Caller Tune removed. Reverted to standard ringback tone.");
    } catch (e) {
      console.error("Error removing caller tune:", e);
      alert("Failed to remove caller tune.");
    }
  };

  if (!isOpen) return null;

  return (
    <div style={{
      position: 'fixed',
      top: 0, left: 0, right: 0, bottom: 0,
      backgroundColor: 'rgba(0, 0, 0, 0.65)',
      backdropFilter: 'blur(4px)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 1000,
      padding: '16px'
    }}>
      <div className="neo-box" style={{
        width: '100%',
        maxWidth: '560px',
        maxHeight: '90vh',
        backgroundColor: '#FFFFFF',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        borderRadius: '20px',
        boxShadow: '0 20px 40px rgba(0,0,0,0.3)',
        border: '3px solid #000'
      }}>
        {/* Modal Header */}
        <div style={{
          padding: '16px 20px',
          borderBottom: '3px solid #000',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          backgroundColor: '#F7F7F7'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <div style={{
              width: '36px', height: '36px', borderRadius: '10px',
              backgroundColor: '#6750A4', color: '#fff',
              display: 'flex', alignItems: 'center', justifyContent: 'center'
            }}>
              <Music size={20} />
            </div>
            <div>
              <h3 style={{ fontSize: '18px', fontWeight: '900', margin: 0, letterSpacing: '-0.5px' }}>
                CALLER TUNE (HELLO TUNE)
              </h3>
              <p style={{ fontSize: '12px', color: '#666', margin: 0, fontWeight: '600' }}>
                Callers hear music while waiting for you to pick up
              </p>
            </div>
          </div>
          <button 
            onClick={() => { stopPreview(); onClose(); }}
            style={{
              background: 'none', border: 'none', cursor: 'pointer', padding: '6px',
              borderRadius: '8px', display: 'flex', alignItems: 'center', justifyContent: 'center'
            }}
          >
            <X size={22} color="#000" />
          </button>
        </div>

        {/* Current Active Tune Status Bar */}
        <div style={{
          padding: '12px 20px',
          backgroundColor: currentTune ? 'rgba(103, 80, 164, 0.08)' : '#FAFAFA',
          borderBottom: '2px solid rgba(0,0,0,0.08)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: '12px'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', minWidth: 0 }}>
            <Volume2 size={18} color={currentTune ? '#6750A4' : '#888'} />
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: '11px', fontWeight: '800', color: currentTune ? '#6750A4' : '#888', textTransform: 'uppercase' }}>
                {currentTune ? "Active Caller Tune" : "Default Ringback Active"}
              </div>
              <div style={{ 
                fontSize: '13px', fontWeight: '700', 
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' 
              }}>
                {currentTune ? `${currentTune.title} • ${currentTune.artist}` : "Standard telecom supervisory tone (tuuut... tuuut...)"}
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            {currentTune && (
              <>
                <button
                  className="neo-btn"
                  style={{
                    padding: '4px 10px', fontSize: '11px', fontWeight: '800',
                    backgroundColor: activePreviewUrl === (currentTune.url || currentTune.base64) ? '#ff3366' : '#6750A4',
                    color: '#fff', borderRadius: '8px'
                  }}
                  onClick={() => togglePreview(currentTune.url || currentTune.base64)}
                >
                  {activePreviewUrl === (currentTune.url || currentTune.base64) ? "STOP" : "TEST"}
                </button>
                <button
                  className="neo-btn danger"
                  style={{ padding: '4px 8px', fontSize: '11px', borderRadius: '8px' }}
                  onClick={handleRemoveTune}
                  title="Remove Caller Tune"
                >
                  <Trash2 size={13} />
                </button>
              </>
            )}
          </div>
        </div>

        {/* Tab Selector */}
        <div style={{
          display: 'flex',
          padding: '8px 16px 0 16px',
          gap: '8px',
          backgroundColor: '#fff',
          borderBottom: '2px solid #000'
        }}>
          <button
            onClick={() => { stopPreview(); setActiveTab('search'); }}
            style={{
              flex: 1,
              padding: '10px 4px',
              fontSize: '13px',
              fontWeight: '800',
              border: 'none',
              background: 'none',
              cursor: 'pointer',
              borderBottom: activeTab === 'search' ? '4px solid #6750A4' : '4px solid transparent',
              color: activeTab === 'search' ? '#6750A4' : '#666',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px'
            }}
          >
            <Search size={15} /> SEARCH MUSIC
          </button>
          <button
            onClick={() => { stopPreview(); setActiveTab('curated'); }}
            style={{
              flex: 1,
              padding: '10px 4px',
              fontSize: '13px',
              fontWeight: '800',
              border: 'none',
              background: 'none',
              cursor: 'pointer',
              borderBottom: activeTab === 'curated' ? '4px solid #6750A4' : '4px solid transparent',
              color: activeTab === 'curated' ? '#6750A4' : '#666',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px'
            }}
          >
            <Sparkles size={15} /> CURATED HITS
          </button>
          <button
            onClick={() => { stopPreview(); setActiveTab('custom'); }}
            style={{
              flex: 1,
              padding: '10px 4px',
              fontSize: '13px',
              fontWeight: '800',
              border: 'none',
              background: 'none',
              cursor: 'pointer',
              borderBottom: activeTab === 'custom' ? '4px solid #6750A4' : '4px solid transparent',
              color: activeTab === 'custom' ? '#6750A4' : '#666',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px'
            }}
          >
            <Upload size={15} /> MY AUDIO
          </button>
        </div>

        {/* Tab Content Area */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '16px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
          
          {/* ────── TAB 1: SEARCH MUSIC ────── */}
          {activeTab === 'search' && (
            <>
              {/* Search Bar */}
              <div style={{ display: 'flex', gap: '8px' }}>
                <input
                  type="text"
                  className="neo-input"
                  placeholder="Search song, artist, Bollywood, Punjabi..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && performSearch()}
                  style={{ flex: 1, fontSize: '14px', padding: '10px 14px' }}
                />
                <button
                  className="neo-btn"
                  style={{ backgroundColor: '#6750A4', color: '#fff', padding: '0 18px', fontWeight: '800' }}
                  onClick={() => performSearch()}
                  disabled={isSearching}
                >
                  {isSearching ? <Loader2 size={16} className="animate-spin" /> : <Search size={16} />}
                </button>
              </div>

              {/* Genre Chips */}
              <div style={{ display: 'flex', gap: '6px', overflowX: 'auto', paddingBottom: '4px' }}>
                {GENRE_CHIPS.map(chip => (
                  <button
                    key={chip}
                    onClick={() => {
                      setSearchQuery(chip);
                      performSearch(chip);
                    }}
                    style={{
                      whiteSpace: 'nowrap',
                      padding: '4px 10px',
                      fontSize: '11px',
                      fontWeight: '700',
                      borderRadius: '14px',
                      border: '1.5px solid #000',
                      backgroundColor: searchQuery === chip ? '#6750A4' : '#F0F2F5',
                      color: searchQuery === chip ? '#fff' : '#000',
                      cursor: 'pointer'
                    }}
                  >
                    {chip}
                  </button>
                ))}
              </div>

              {/* Search Results List */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', minHeight: '200px' }}>
                {isSearching ? (
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '40px 0', gap: '10px' }}>
                    <Loader2 size={32} color="#6750A4" className="animate-spin" />
                    <span style={{ fontSize: '13px', fontWeight: '700', color: '#666' }}>Searching songs...</span>
                  </div>
                ) : searchResults.length === 0 ? (
                  <div style={{ textAlign: 'center', padding: '40px 16px', color: '#888' }}>
                    <Disc size={36} color="#ccc" style={{ marginBottom: '8px' }} />
                    <div style={{ fontWeight: '700', fontSize: '14px' }}>No songs found</div>
                    <div style={{ fontSize: '12px' }}>Try searching for a different song name or artist</div>
                  </div>
                ) : (
                  searchResults.map(song => {
                    const isPreviewing = activePreviewUrl === song.previewUrl;
                    const isSaving = savingId === song.id;

                    return (
                      <div 
                        key={song.id}
                        className="neo-box"
                        style={{
                          padding: '10px 12px',
                          display: 'flex',
                          alignItems: 'center',
                          gap: '12px',
                          backgroundColor: isPreviewing ? 'rgba(103, 80, 164, 0.05)' : '#fff',
                          borderRadius: '12px'
                        }}
                      >
                        {/* Artwork */}
                        <div style={{
                          width: '46px', height: '46px', borderRadius: '10px',
                          overflow: 'hidden', backgroundColor: '#eee', flexShrink: 0,
                          border: '1.5px solid #000', position: 'relative'
                        }}>
                          {song.artworkUrl ? (
                            <img src={song.artworkUrl} alt={song.title} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                          ) : (
                            <div style={{ width: '100%', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                              <Music size={20} color="#888" />
                            </div>
                          )}
                        </div>

                        {/* Song Details */}
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{
                            fontWeight: '800', fontSize: '13px',
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'
                          }}>
                            {song.title}
                          </div>
                          <div style={{
                            fontSize: '11px', color: '#666', fontWeight: '600',
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'
                          }}>
                            {song.artist}
                          </div>
                          {isPreviewing && (
                            <div style={{ fontSize: '10px', fontWeight: '800', color: '#00A884', marginTop: '2px' }}>
                              ▶ Previewing in browser...
                            </div>
                          )}
                        </div>

                        {/* Action Buttons */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <button
                            className="neo-btn"
                            style={{
                              padding: '6px 10px',
                              backgroundColor: isPreviewing ? '#ff3366' : '#F0F2F5',
                              color: isPreviewing ? '#fff' : '#000',
                              borderRadius: '8px',
                              display: 'flex', alignItems: 'center', gap: '4px'
                            }}
                            onClick={() => togglePreview(song.previewUrl)}
                            title={isPreviewing ? "Stop Preview" : "Listen Preview"}
                          >
                            {isPreviewing ? <Square size={13} /> : <Play size={13} />}
                            <span style={{ fontSize: '11px', fontWeight: '800' }}>
                              {isPreviewing ? "STOP" : "PREVIEW"}
                            </span>
                          </button>

                          <button
                            className="neo-btn"
                            disabled={isSaving}
                            style={{
                              padding: '6px 12px',
                              backgroundColor: '#6750A4',
                              color: '#fff',
                              borderRadius: '8px',
                              display: 'flex', alignItems: 'center', gap: '4px'
                            }}
                            onClick={() => handleSelectSearchSong(song)}
                          >
                            {isSaving ? (
                              <Loader2 size={13} className="animate-spin" />
                            ) : (
                              <Check size={13} />
                            )}
                            <span style={{ fontSize: '11px', fontWeight: '800' }}>
                              {isSaving ? "SETTING..." : "SET TUNE"}
                            </span>
                          </button>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </>
          )}

          {/* ────── TAB 2: CURATED HITS ────── */}
          {activeTab === 'curated' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <div style={{ fontSize: '12px', color: '#666', fontWeight: '600', padding: '0 4px' }}>
                Instant high-quality royalty-free caller tunes. Ready to activate with 1 tap.
              </div>

              {CURATED_TUNES.map(tune => {
                const isPreviewing = activePreviewUrl === tune.streamUrl;
                const isSaving = savingId === tune.id;

                return (
                  <div
                    key={tune.id}
                    className="neo-box"
                    style={{
                      padding: '12px 14px',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '12px',
                      backgroundColor: isPreviewing ? 'rgba(103, 80, 164, 0.05)' : '#fff',
                      borderRadius: '12px'
                    }}
                  >
                    <div style={{
                      width: '46px', height: '46px', borderRadius: '12px',
                      backgroundColor: 'rgba(103, 80, 164, 0.12)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: '22px', border: '1.5px solid #000', flexShrink: 0
                    }}>
                      {tune.emoji}
                    </div>

                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontWeight: '800', fontSize: '14px' }}>
                        {tune.title}
                      </div>
                      <div style={{ fontSize: '12px', color: '#666', fontWeight: '600' }}>
                        {tune.artist} • {tune.category}
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                      <button
                        className="neo-btn"
                        style={{
                          padding: '6px 10px',
                          backgroundColor: isPreviewing ? '#ff3366' : '#F0F2F5',
                          color: isPreviewing ? '#fff' : '#000',
                          borderRadius: '8px',
                          display: 'flex', alignItems: 'center', gap: '4px'
                        }}
                        onClick={() => togglePreview(tune.streamUrl)}
                      >
                        {isPreviewing ? <Square size={13} /> : <Play size={13} />}
                        <span style={{ fontSize: '11px', fontWeight: '800' }}>
                          {isPreviewing ? "STOP" : "PREVIEW"}
                        </span>
                      </button>

                      <button
                        className="neo-btn"
                        disabled={isSaving}
                        style={{
                          padding: '6px 12px',
                          backgroundColor: '#6750A4',
                          color: '#fff',
                          borderRadius: '8px',
                          display: 'flex', alignItems: 'center', gap: '4px'
                        }}
                        onClick={() => handleSelectCuratedTune(tune)}
                      >
                        {isSaving ? <Loader2 size={13} className="animate-spin" /> : <Check size={13} />}
                        <span style={{ fontSize: '11px', fontWeight: '800' }}>
                          {isSaving ? "SETTING..." : "SET TUNE"}
                        </span>
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* ────── TAB 3: CUSTOM AUDIO UPLOAD ────── */}
          {activeTab === 'custom' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <input
                type="file"
                accept="audio/*"
                ref={fileInputRef}
                style={{ display: 'none' }}
                onChange={handleCustomAudioPicked}
              />

              <div 
                onClick={() => fileInputRef.current?.click()}
                className="neo-box"
                style={{
                  border: '2px dashed #000',
                  padding: '30px 20px',
                  textAlign: 'center',
                  cursor: 'pointer',
                  backgroundColor: '#FAFAFA',
                  borderRadius: '16px',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: '10px'
                }}
              >
                <div style={{
                  width: '52px', height: '52px', borderRadius: '50%',
                  backgroundColor: '#6750A4', color: '#fff',
                  display: 'flex', alignItems: 'center', justifyContent: 'center'
                }}>
                  <Upload size={24} />
                </div>
                <div>
                  <div style={{ fontWeight: '800', fontSize: '15px' }}>
                    {customFile ? customFile.name : "Choose Audio File (MP3, WAV, AAC)"}
                  </div>
                  <div style={{ fontSize: '12px', color: '#666', marginTop: '4px' }}>
                    Maximum 2.5MB • First 30 seconds will play to callers
                  </div>
                </div>
              </div>

              {customFile && (
                <div className="neo-box" style={{ padding: '16px', display: 'flex', flexDirection: 'column', gap: '12px', borderRadius: '12px' }}>
                  <div>
                    <label style={{ fontSize: '12px', fontWeight: '800', color: '#555' }}>TUNE TITLE</label>
                    <input
                      type="text"
                      className="neo-input"
                      value={customTitle}
                      onChange={(e) => setCustomTitle(e.target.value)}
                      style={{ marginTop: '4px', fontSize: '13px', padding: '8px 12px' }}
                    />
                  </div>

                  {customAudioUrl && (
                    <div>
                      <label style={{ fontSize: '12px', fontWeight: '800', color: '#555' }}>PREVIEW AUDIO</label>
                      <audio controls src={customAudioUrl} style={{ width: '100%', marginTop: '6px' }} />
                    </div>
                  )}

                  <button
                    className="neo-btn"
                    disabled={isConvertingAudio}
                    style={{
                      backgroundColor: '#6750A4',
                      color: '#fff',
                      padding: '12px',
                      fontWeight: '800',
                      fontSize: '14px',
                      borderRadius: '10px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      gap: '8px'
                    }}
                    onClick={handleSaveCustomTune}
                  >
                    {isConvertingAudio ? (
                      <>
                        <Loader2 size={16} className="animate-spin" />
                        PROCESSING & SAVING CALLER TUNE...
                      </>
                    ) : (
                      <>
                        <Check size={16} />
                        SET AS MY CALLER TUNE
                      </>
                    )}
                  </button>
                </div>
              )}
            </div>
          )}

        </div>
      </div>
    </div>
  );
}
