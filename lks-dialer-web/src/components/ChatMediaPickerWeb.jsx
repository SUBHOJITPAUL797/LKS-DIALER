import React, { useState, useMemo } from 'react';
import { Search, X, Smile, Sparkles, Film, ArrowLeft, Maximize2, Minimize2 } from 'lucide-react';

// ── Emoji Catalog ─────────────────────────────────────────────────────────────
const EMOJI_CATEGORIES = [
  {
    name: 'Smileys',
    icon: '😀',
    emojis: [
      '😀', '😃', '😄', '😁', '😆', '😅', '😂', '🤣', '🥹', '😊',
      '😇', '🙂', '🙃', '😉', '😌', '😍', '🥰', '😘', '😗', '😙',
      '😚', '😋', '😛', '😝', '😜', '🤪', '🤨', '🧐', '🤓', '😎',
      '🥸', '🤩', '🥳', '😏', '😒', '😞', '😔', '😟', '😕', '🙁',
      '☹️', '😣', '😖', '😫', '😩', '🥺', '😢', '😭', '😮‍💨', '😤',
      '😠', '😡', '🤬', '🤯', '😳', '🥵', '🥶', '😱', '😨', '😰',
      '😥', '😓', '🤗', '🤔', '🫣', '🤭', '🫢', '🫡', '🤫', '🫠'
    ]
  },
  {
    name: 'Gestures',
    icon: '👋',
    emojis: [
      '👍', '👎', '👌', '🤌', '🤏', '✌️', '🤞', '🫰', '🤟', '🤘',
      '🤙', '👈', '👉', '👆', '🖕', '👇', '☝️', '🫵', '👋', '🤚',
      '🖐️', '✋', '🖖', '🫱', '🫲', '🫳', '🫴', '👏', '🙌', '🫶',
      '👐', '🤲', '🤝', '🙏', '✍️', '💅', '🤳', '💪', '🦾', '🦿',
      '🦵', '🦶', '👂', '🦻', '👃', '🫀', '🫁', '🧠', '🫵', '👀'
    ]
  },
  {
    name: 'Hearts',
    icon: '❤️',
    emojis: [
      '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '🤍', '🤎', '💔',
      '❤️‍🔥', '❤️‍🩹', '❣️', '💕', '💞', '💓', '💗', '💖', '💘', '💝',
      '💟', '💌', '💋', '💍', '💎', '💐', '🌹', '🥀', '🌺', '🌸'
    ]
  },
  {
    name: 'Animals',
    icon: '🐶',
    emojis: [
      '🐶', '🐱', '🐭', '🐹', '🐰', '🦊', '🐻', '🐼', '🐻‍❄️', '🐨',
      '🐯', '🦁', '🐮', '🐷', '🐸', '🐵', '🐔', '🐧', '🐦', '🐤',
      '🦆', '🦅', '🦉', '🦇', '🐺', '🐗', '🐴', '🦄', '🐝', '🐛',
      '🦋', '🐌', '🐞', '🐜', '🦟', '🦗', '🕷️', '🦂', '🐢', '🐍'
    ]
  },
  {
    name: 'Food',
    icon: '🍕',
    emojis: [
      '🍎', '🍐', '🍊', '🍋', '🍌', '🍉', '🍇', '🍓', '🫐', '🍈',
      '🍒', '🍑', '🥭', '🍍', '🥥', '🥝', '🍅', '🥑', '🥦', '🌽',
      '🌶️', '🍔', '🍟', '🍕', '🌭', '🥪', '🌮', '🌯', '🫔', '🥙',
      '🍜', '🍝', '🍣', '🍱', '🍦', '🍧', '🍨', '🍩', '🍪', '🎂'
    ]
  },
  {
    name: 'Activities',
    icon: '⚽',
    emojis: [
      '⚽', '🏀', '🏈', '⚾', '🥎', '🎾', '🏐', '🏉', '🥏', '🎱',
      '🪀', '🏓', '🏸', '🏒', '🏑', '🥍', '🏏', '🥊', '🥋', '🎯',
      '⛳', '🪁', '🎮', '🕹️', '🎲', '🧩', '🏆', '🥇', '🥈', '🥉'
    ]
  }
];

// ── Sticker Packs ─────────────────────────────────────────────────────────────
const STICKER_PACKS = [
  {
    id: 'pack_expressive',
    name: '3D Expressive',
    stickers: [
      { code: '🦄', name: 'Magic Unicorn', url: '' },
      { code: '🔥', name: 'Fire', url: '' },
      { code: '✨', name: 'Sparkles', url: '' },
      { code: '💯', name: '100 Percent', url: '' },
      { code: '🚀', name: 'Rocket', url: '' },
      { code: '🥳', name: 'Party Fun', url: '' },
      { code: '🎉', name: 'Tada Celebration', url: '' },
      { code: '💖', name: 'Heart Sparkle', url: '' },
      { code: '🤩', name: 'Star Eyes', url: '' },
      { code: '😎', name: 'Cool Shades', url: '' },
      { code: '💣', name: 'Boom Bomb', url: '' },
      { code: '⚡', name: 'Lightning Zap', url: '' },
      { code: '💫', name: 'Dizzy Star', url: '' },
      { code: '🌈', name: 'Rainbow Joy', url: '' },
      { code: '🧁', name: 'Sweet Cupcake', url: '' },
      { code: '👑', name: 'Royal Crown', url: '' }
    ]
  },
  {
    id: 'pack_animals',
    name: 'Cute Animals',
    stickers: [
      { code: '🐱', name: 'Happy Kitty', url: '' },
      { code: '🐶', name: 'Good Doggo', url: '' },
      { code: '🐼', name: 'Chubby Panda', url: '' },
      { code: '🦊', name: 'Clever Fox', url: '' },
      { code: '🐰', name: 'Fluffy Bunny', url: '' },
      { code: '🐨', name: 'Sleepy Koala', url: '' },
      { code: '🦁', name: 'Brave Lion', url: '' },
      { code: '🐯', name: 'Playful Tiger', url: '' },
      { code: '🐵', name: 'Cheeky Monkey', url: '' },
      { code: '🐸', name: 'Vibe Frog', url: '' },
      { code: '🐙', name: 'Octo Hugs', url: '' },
      { code: '🦋', name: 'Butterfly', url: '' },
      { code: '🐧', name: 'Waddle Penguin', url: '' },
      { code: '🦉', name: 'Wise Owl', url: '' },
      { code: '🐢', name: 'Chill Turtle', url: '' },
      { code: '🐬', name: 'Dolphin Leap', url: '' }
    ]
  },
  {
    id: 'pack_vibes',
    name: 'Chat Vibes',
    stickers: [
      { code: '👋', name: 'Big Wave', url: '' },
      { code: '❤️', name: 'Big Love', url: '' },
      { code: '🙌', name: 'Praise Hands', url: '' },
      { code: '👏', name: 'Applause', url: '' },
      { code: '👍', name: 'Super Thumbs Up', url: '' },
      { code: '🤙', name: 'Call Me Bro', url: '' },
      { code: '✌️', name: 'Peace Out', url: '' },
      { code: '🤝', name: 'Deal Sealed', url: '' },
      { code: '🫂', name: 'Warm Hug', url: '' },
      { code: '🫶', name: 'Heart Hands', url: '' },
      { code: '💃', name: 'Salsa Dance', url: '' },
      { code: '🕺', name: 'Disco Boogie', url: '' },
      { code: '🏄', name: 'Surfing High', url: '' },
      { code: '🚴', name: 'Speed Cycle', url: '' },
      { code: '🧘', name: 'Zen Calm', url: '' },
      { code: '🧗', name: 'Climbing Peak', url: '' }
    ]
  }
];

// ── Curated GIFs Catalog ──────────────────────────────────────────────────────
const GIF_CATALOG = [
  {
    category: 'Trending',
    gifs: [
      { url: 'https://media.giphy.com/media/artj92V8o75VPL7AeQ/giphy.gif', title: 'Happy Dance' },
      { url: 'https://media.giphy.com/media/3o7TKSjRrfIPjeiVyM/giphy.gif', title: 'Thumbs Up' },
      { url: 'https://media.giphy.com/media/26u4cqiYI30juCOGY/giphy.gif', title: 'Celebration Confetti' },
      { url: 'https://media.giphy.com/media/l0amJzVHIAfl7jMDos/giphy.gif', title: 'Heart Eyes Love' },
      { url: 'https://media.giphy.com/media/ibolLe3mOqHE3PQTtk/giphy.gif', title: 'Laughing Cat' },
      { url: 'https://media.giphy.com/media/5GoVLqeAOo6PK/giphy.gif', title: 'Excited Yay' }
    ]
  },
  {
    category: 'Laugh',
    gifs: [
      { url: 'https://media.giphy.com/media/ZqlvCTNHpqrio/giphy.gif', title: 'LOL Rolling' },
      { url: 'https://media.giphy.com/media/10JhviFuU2gWD6/giphy.gif', title: 'LMAO Minions' },
      { url: 'https://media.giphy.com/media/3oEjHAUOqG3lSS0f1C/giphy.gif', title: 'Snicker Chuckle' },
      { url: 'https://media.giphy.com/media/CoDp6NnSmItoY/giphy.gif', title: 'Hard Laugh' }
    ]
  },
  {
    category: 'Thumbs Up',
    gifs: [
      { url: 'https://media.giphy.com/media/111ebonMs90YLu/giphy.gif', title: 'Awesome Approval' },
      { url: 'https://media.giphy.com/media/XreQmk7ETCak0/giphy.gif', title: 'Nodding Yes' },
      { url: 'https://media.giphy.com/media/mgqefOvJJToHk2MWRY/giphy.gif', title: 'Great Job' },
      { url: 'https://media.giphy.com/media/3o7abKhOpu0NwenH3O/giphy.gif', title: 'Double Thumbs' }
    ]
  },
  {
    category: 'Love',
    gifs: [
      { url: 'https://media.giphy.com/media/26FLdmIp6wJr91JAI/giphy.gif', title: 'Heart Bloom' },
      { url: 'https://media.giphy.com/media/3oEjI4sFlIEAKqm96E/giphy.gif', title: 'Bear Hug' },
      { url: 'https://media.giphy.com/media/l4pTdcifPZLpDjL1e/giphy.gif', title: 'Blowing Kiss' },
      { url: 'https://media.giphy.com/media/MeIucajx7YeLA2lfnd/giphy.gif', title: 'Puppy Love' }
    ]
  },
  {
    category: 'Dance',
    gifs: [
      { url: 'https://media.giphy.com/media/blSTtZehjAZ8I/giphy.gif', title: 'Groovy Moves' },
      { url: 'https://media.giphy.com/media/l3vRlT2k2L35Cnn5C/giphy.gif', title: 'Party Animal' },
      { url: 'https://media.giphy.com/media/mKMGLhoD8L4yc/giphy.gif', title: 'Cute Penguin Dance' },
      { url: 'https://media.giphy.com/media/DhstvI3CH03DO/giphy.gif', title: 'Friday Vibe' }
    ]
  },
  {
    category: 'Fire',
    gifs: [
      { url: 'https://media.giphy.com/media/QMHoU66sBXCAU/giphy.gif', title: 'This is Fine' },
      { url: 'https://media.giphy.com/media/yr7n0u3qzO9nG/giphy.gif', title: 'Elmo Fire' },
      { url: 'https://media.giphy.com/media/P7JmDW75B5yA8/giphy.gif', title: 'Flame On' },
      { url: 'https://media.giphy.com/media/l0HlHFRbmaZtBRhXG/giphy.gif', title: 'Fire Drop' }
    ]
  }
];

export default function ChatMediaPickerWeb({
  onSelectEmoji,
  onSelectSticker,
  onSelectGif,
  onClose
}) {
  const [activeTab, setActiveTab] = useState('EMOJI'); // 'EMOJI' | 'STICKER' | 'GIF'
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedEmojiCategory, setSelectedEmojiCategory] = useState(0);
  const [selectedStickerPack, setSelectedStickerPack] = useState(0);
  const [isExpanded, setIsExpanded] = useState(false);

  // Filtered Emojis
  const displayEmojis = useMemo(() => {
    if (!searchQuery.trim()) {
      return EMOJI_CATEGORIES[selectedEmojiCategory]?.emojis || [];
    }
    const q = searchQuery.toLowerCase();
    const matched = [];
    EMOJI_CATEGORIES.forEach(cat => {
      cat.emojis.forEach(e => {
        if (cat.name.toLowerCase().includes(q) || e.includes(q)) {
          matched.push(e);
        }
      });
    });
    return matched.length ? matched : EMOJI_CATEGORIES[0].emojis;
  }, [searchQuery, selectedEmojiCategory]);

  // Filtered Stickers
  const displayStickers = useMemo(() => {
    const pack = STICKER_PACKS[selectedStickerPack] || STICKER_PACKS[0];
    if (!searchQuery.trim()) return pack.stickers;
    const q = searchQuery.toLowerCase();
    return pack.stickers.filter(s => s.name.toLowerCase().includes(q) || s.code.includes(q));
  }, [searchQuery, selectedStickerPack]);

  // Filtered GIFs
  const displayGifs = useMemo(() => {
    const all = [];
    GIF_CATALOG.forEach(c => all.push(...c.gifs));
    if (!searchQuery.trim()) return all;
    const q = searchQuery.toLowerCase();
    return all.filter(g => g.title.toLowerCase().includes(q));
  }, [searchQuery]);

  return (
    <div
      style={{
        width: '100%',
        maxWidth: 420,
        height: isExpanded ? 520 : 400,
        backgroundColor: '#FFFFFF',
        borderRadius: 18,
        border: '1px solid rgba(0, 0, 0, 0.08)',
        boxShadow: '0 8px 30px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.04)',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        transition: 'height 0.22s cubic-bezier(0.16, 1, 0.3, 1)',
        animation: 'pickerSlideUp 0.22s cubic-bezier(0.16, 1, 0.3, 1)'
      }}
    >
      {/* ── Header with Tab Switcher & Close ── */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '10px 14px',
          borderBottom: '1px solid rgba(0, 0, 0, 0.06)',
          backgroundColor: '#FAFAFA'
        }}
      >
        {/* Segmented Control */}
        <div
          style={{
            display: 'flex',
            backgroundColor: 'rgba(0, 0, 0, 0.05)',
            borderRadius: 12,
            padding: 3,
            gap: 2
          }}
        >
          <button
            type="button"
            onClick={() => { setActiveTab('EMOJI'); setSearchQuery(''); }}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 5,
              padding: '6px 14px',
              borderRadius: 9,
              border: 'none',
              cursor: 'pointer',
              fontWeight: 700,
              fontSize: 12,
              color: activeTab === 'EMOJI' ? '#008069' : '#666',
              backgroundColor: activeTab === 'EMOJI' ? '#FFFFFF' : 'transparent',
              boxShadow: activeTab === 'EMOJI' ? '0 1px 4px rgba(0,0,0,0.1)' : 'none',
              transition: 'all 0.15s ease'
            }}
          >
            <Smile size={15} />
            <span>Emojis</span>
          </button>

          <button
            type="button"
            onClick={() => { setActiveTab('STICKER'); setSearchQuery(''); }}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 5,
              padding: '6px 14px',
              borderRadius: 9,
              border: 'none',
              cursor: 'pointer',
              fontWeight: 700,
              fontSize: 12,
              color: activeTab === 'STICKER' ? '#008069' : '#666',
              backgroundColor: activeTab === 'STICKER' ? '#FFFFFF' : 'transparent',
              boxShadow: activeTab === 'STICKER' ? '0 1px 4px rgba(0,0,0,0.1)' : 'none',
              transition: 'all 0.15s ease'
            }}
          >
            <Sparkles size={15} />
            <span>Stickers</span>
          </button>

          <button
            type="button"
            onClick={() => { setActiveTab('GIF'); setSearchQuery(''); }}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 5,
              padding: '6px 14px',
              borderRadius: 9,
              border: 'none',
              cursor: 'pointer',
              fontWeight: 700,
              fontSize: 12,
              color: activeTab === 'GIF' ? '#008069' : '#666',
              backgroundColor: activeTab === 'GIF' ? '#FFFFFF' : 'transparent',
              boxShadow: activeTab === 'GIF' ? '0 1px 4px rgba(0,0,0,0.1)' : 'none',
              transition: 'all 0.15s ease'
            }}
          >
            <Film size={15} />
            <span>GIFs</span>
          </button>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          {/* Expand/Collapse Button */}
          <button
            type="button"
            onClick={() => setIsExpanded(!isExpanded)}
            style={{
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              padding: 6,
              borderRadius: '50%',
              color: '#777',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'background-color 0.15s'
            }}
            title={isExpanded ? "Collapse picker" : "Expand picker"}
          >
            {isExpanded ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
          </button>

          {/* Close Button */}
          <button
            type="button"
            onClick={onClose}
            style={{
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              padding: 6,
              borderRadius: '50%',
              color: '#777',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'background-color 0.15s'
            }}
            title="Close picker"
          >
            <X size={18} />
          </button>
        </div>
      </div>

      {/* ── Search Bar ── */}
      <div style={{ padding: '8px 12px 6px' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            backgroundColor: '#F3F4F6',
            borderRadius: 10,
            padding: '6px 10px',
            border: '1px solid rgba(0, 0, 0, 0.04)'
          }}
        >
          <Search size={15} color="#888" />
          <input
            type="text"
            placeholder={
              activeTab === 'EMOJI' ? 'Search emojis...'
              : activeTab === 'STICKER' ? 'Search stickers...'
              : 'Search GIFs...'
            }
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            style={{
              border: 'none',
              background: 'transparent',
              outline: 'none',
              fontSize: 13,
              fontWeight: 500,
              width: '100%',
              color: '#111'
            }}
          />
          {searchQuery && (
            <button
              type="button"
              onClick={() => setSearchQuery('')}
              style={{ border: 'none', background: 'transparent', cursor: 'pointer', padding: 0, color: '#888' }}
            >
              <X size={14} />
            </button>
          )}
        </div>
      </div>

      {/* ── Secondary Category Bar ── */}
      {activeTab === 'EMOJI' && !searchQuery && (
        <div
          style={{
            display: 'flex',
            gap: 6,
            padding: '2px 12px 6px',
            overflowX: 'auto',
            scrollbarWidth: 'none'
          }}
        >
          {EMOJI_CATEGORIES.map((cat, idx) => (
            <button
              key={cat.name}
              type="button"
              onClick={() => setSelectedEmojiCategory(idx)}
              style={{
                border: 'none',
                background: selectedEmojiCategory === idx ? '#E0F2F1' : 'transparent',
                borderRadius: 14,
                padding: '3px 8px',
                fontSize: 14,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: 4,
                color: selectedEmojiCategory === idx ? '#008069' : '#666',
                fontWeight: selectedEmojiCategory === idx ? 800 : 500
              }}
            >
              <span>{cat.icon}</span>
              <span style={{ fontSize: 11 }}>{cat.name}</span>
            </button>
          ))}
        </div>
      )}

      {activeTab === 'STICKER' && !searchQuery && (
        <div
          style={{
            display: 'flex',
            gap: 6,
            padding: '2px 12px 6px',
            overflowX: 'auto',
            scrollbarWidth: 'none'
          }}
        >
          {STICKER_PACKS.map((pack, idx) => (
            <button
              key={pack.id}
              type="button"
              onClick={() => setSelectedStickerPack(idx)}
              style={{
                border: 'none',
                background: selectedStickerPack === idx ? '#E0F2F1' : 'transparent',
                borderRadius: 14,
                padding: '4px 10px',
                fontSize: 11,
                cursor: 'pointer',
                color: selectedStickerPack === idx ? '#008069' : '#666',
                fontWeight: selectedStickerPack === idx ? 800 : 600
              }}
            >
              {pack.name}
            </button>
          ))}
        </div>
      )}

      {/* ── Main Content Area ── */}
      <div
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '8px 12px 12px',
          minHeight: 0
        }}
      >
        {/* 1. EMOJI GRID */}
        {activeTab === 'EMOJI' && (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(7, 1fr)',
              gap: 4,
              textAlign: 'center'
            }}
          >
            {displayEmojis.map((emoji, index) => (
              <button
                key={`${emoji}-${index}`}
                type="button"
                onClick={() => onSelectEmoji(emoji)}
                style={{
                  border: 'none',
                  background: 'transparent',
                  fontSize: 24,
                  cursor: 'pointer',
                  padding: '6px 2px',
                  borderRadius: 8,
                  transition: 'transform 0.1s ease, background-color 0.1s',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center'
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.backgroundColor = 'rgba(0,0,0,0.06)';
                  e.currentTarget.style.transform = 'scale(1.22)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.backgroundColor = 'transparent';
                  e.currentTarget.style.transform = 'scale(1)';
                }}
              >
                {emoji}
              </button>
            ))}
          </div>
        )}

        {/* 2. STICKER GRID */}
        {activeTab === 'STICKER' && (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(4, 1fr)',
              gap: 8,
              padding: '4px 0'
            }}
          >
            {displayStickers.map((sticker) => (
              <button
                key={sticker.name}
                type="button"
                onClick={() => onSelectSticker(sticker.code, sticker.name, sticker.url)}
                style={{
                  border: '1px solid rgba(0,0,0,0.05)',
                  background: '#F9FAFB',
                  borderRadius: 14,
                  padding: 8,
                  cursor: 'pointer',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 4,
                  transition: 'all 0.15s ease'
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.backgroundColor = '#E0F2F1';
                  e.currentTarget.style.transform = 'translateY(-2px)';
                  e.currentTarget.style.borderColor = 'rgba(0, 128, 105, 0.3)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.backgroundColor = '#F9FAFB';
                  e.currentTarget.style.transform = 'translateY(0)';
                  e.currentTarget.style.borderColor = 'rgba(0,0,0,0.05)';
                }}
                title={`Send ${sticker.name}`}
              >
                {sticker.url ? (
                  <img
                    src={sticker.url}
                    alt={sticker.name}
                    style={{ width: 52, height: 52, objectFit: 'contain' }}
                  />
                ) : (
                  <span style={{ fontSize: 38 }}>{sticker.code}</span>
                )}
                <span
                  style={{
                    fontSize: 10,
                    fontWeight: 600,
                    color: '#555',
                    textAlign: 'center',
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    maxWidth: 70
                  }}
                >
                  {sticker.name}
                </span>
              </button>
            ))}
          </div>
        )}

        {/* 3. GIF GRID */}
        {activeTab === 'GIF' && (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(2, 1fr)',
              gap: 8
            }}
          >
            {displayGifs.map((gif, idx) => (
              <div
                key={`${gif.url}-${idx}`}
                onClick={() => onSelectGif(gif.url, gif.title)}
                style={{
                  position: 'relative',
                  borderRadius: 12,
                  overflow: 'hidden',
                  cursor: 'pointer',
                  aspectRatio: '16/10',
                  backgroundColor: '#E5E7EB',
                  transition: 'transform 0.15s ease, box-shadow 0.15s'
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.transform = 'scale(1.03)';
                  e.currentTarget.style.boxShadow = '0 6px 16px rgba(0,0,0,0.18)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.transform = 'scale(1)';
                  e.currentTarget.style.boxShadow = 'none';
                }}
                title={`Send "${gif.title}"`}
              >
                <img
                  src={gif.url}
                  alt={gif.title}
                  loading="lazy"
                  style={{
                    width: '100%',
                    height: '100%',
                    objectFit: 'cover',
                    display: 'block'
                  }}
                />
                <div
                  style={{
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    right: 0,
                    padding: '4px 6px',
                    background: 'linear-gradient(to top, rgba(0,0,0,0.7), transparent)',
                    color: '#fff',
                    fontSize: 10,
                    fontWeight: 700,
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis'
                  }}
                >
                  {gif.title}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
