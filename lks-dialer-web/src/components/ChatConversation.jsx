import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { 
  ArrowLeft, Phone, Video, MoreVertical, Send, Image as ImageIcon, 
  Mic, Trash2, Check, CheckCheck, Play, Pause, X, Shield, Ban, CornerUpLeft, Reply, Edit2, Paperclip, Download
} from 'lucide-react';
import { db } from '../lib/firebase';
import { collection, doc, query, where, onSnapshot, getDoc } from 'firebase/firestore';
import { chatRepositoryWeb, normalizePhoneNumber } from '../lib/ChatRepositoryWeb';
import { webRtcEngine, isUserOnline, formatLastSeen } from '../lib/WebRtcEngine';
import { formatAvatarUrl } from '../lib/ImageUtils';
import { mediaStorageWeb } from '../lib/MediaStorageWeb';

// ─── Swipeable Message Bubble ─────────────────────────────────────────────────
function SwipeableMessage({ msg, onSwipeReply, children }) {
  const touchStartX = useRef(null);
  const touchStartY = useRef(null);
  const [swipeOffset, setSwipeOffset] = useState(0);
  const [swipeTriggered, setSwipeTriggered] = useState(false);
  const containerRef = useRef(null);

  const THRESHOLD = 60; // px to trigger reply
  const MAX_DRAG = 80;

  const handleTouchStart = (e) => {
    touchStartX.current = e.touches[0].clientX;
    touchStartY.current = e.touches[0].clientY;
    setSwipeTriggered(false);
  };

  const handleTouchMove = (e) => {
    if (touchStartX.current === null) return;
    const dx = e.touches[0].clientX - touchStartX.current;
    const dy = e.touches[0].clientY - touchStartY.current;

    // Ignore vertical scrolling
    if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 10) {
      touchStartX.current = null;
      return;
    }

    // Incoming → right swipe (dx > 0). Outgoing → left swipe (dx < 0).
    const isOut = msg.isOutgoing;
    const correctDir = isOut ? dx < 0 : dx > 0;
    if (!correctDir) return;

    const clamped = Math.min(Math.abs(dx), MAX_DRAG);
    const dir = isOut ? -1 : 1;
    setSwipeOffset(dir * clamped);

    if (clamped >= THRESHOLD && !swipeTriggered) {
      setSwipeTriggered(true);
    }
  };

  const handleTouchEnd = () => {
    if (swipeTriggered) {
      onSwipeReply(msg);
      // Haptic feedback
      if (navigator.vibrate) navigator.vibrate(30);
    }
    // Snap back
    setSwipeOffset(0);
    setSwipeTriggered(false);
    touchStartX.current = null;
  };

  const progress = Math.min(Math.abs(swipeOffset) / THRESHOLD, 1);
  const iconOpacity = progress;
  const iconScale = 0.7 + 0.3 * progress;

  return (
    <div
      ref={containerRef}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
      style={{ position: 'relative', display: 'flex', alignSelf: msg.isOutgoing ? 'flex-end' : 'flex-start', maxWidth: '82%' }}
    >
      {/* Reply Icon — appears on the correct side */}
      {!msg.isOutgoing && (
        <div style={{
          position: 'absolute',
          left: `-${40 * progress}px`,
          top: '50%',
          transform: `translateY(-50%) scale(${iconScale})`,
          opacity: iconOpacity,
          transition: 'opacity 0.1s',
          pointerEvents: 'none',
          zIndex: 1
        }}>
          <CornerUpLeft size={22} color="#00C9FF" strokeWidth={2.5} />
        </div>
      )}
      {msg.isOutgoing && (
        <div style={{
          position: 'absolute',
          right: `-${40 * progress}px`,
          top: '50%',
          transform: `translateY(-50%) scale(${iconScale})`,
          opacity: iconOpacity,
          transition: 'opacity 0.1s',
          pointerEvents: 'none',
          zIndex: 1
        }}>
          <CornerUpLeft size={22} color="#FF3366" strokeWidth={2.5} style={{ transform: 'scaleX(-1)' }} />
        </div>
      )}

      {/* The actual bubble, translated by swipeOffset */}
      <div style={{
        transform: `translateX(${swipeOffset}px)`,
        transition: swipeOffset === 0 ? 'transform 0.25s cubic-bezier(0.25, 0.46, 0.45, 0.94)' : 'none',
        willChange: 'transform',
        width: '100%'
      }}>
        {children}
      </div>
    </div>
  );
}

// ─── Audio Waveform & Scrubber Helpers ─────────────────────────────────────────
function getWaveformData(seedStr, count = 28) {
  let hash = 0;
  const str = String(seedStr || 'lks-audio');
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  const bars = [];
  for (let i = 0; i < count; i++) {
    const pseudo = Math.abs(Math.sin((hash + (i + 1) * 37) * 0.23));
    const harmonic = Math.sin((i / (count - 1)) * Math.PI) * 0.45 + 0.55;
    const pct = Math.max(18, Math.min(100, Math.round((pseudo * 0.6 + harmonic * 0.4) * 100)));
    bars.push(pct);
  }
  return bars;
}

function formatAudioTime(seconds) {
  if (!seconds || isNaN(seconds) || !isFinite(seconds) || seconds < 0) return '0:00';
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
}

// ─── Interactive Waveform & Scrubber Component ────────────────────────────────
function AudioWaveformScrubber({
  msgId,
  isCurrent,
  isPlaying,
  currentTime,
  duration,
  waveformBars,
  isOut,
  onSeek
}) {
  const progressRatio = duration > 0 ? Math.min(1, Math.max(0, currentTime / duration)) : 0;
  const trackRef = useRef(null);
  const [isDragging, setIsDragging] = useState(false);
  const [hoverRatio, setHoverRatio] = useState(null);

  const calculateRatio = (e) => {
    if (!trackRef.current) return 0;
    const rect = trackRef.current.getBoundingClientRect();
    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
  };

  const handlePointerDown = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(true);
    const ratio = calculateRatio(e);
    onSeek(ratio);

    const onMove = (evt) => {
      evt.preventDefault();
      const moveRatio = calculateRatio(evt);
      onSeek(moveRatio);
    };

    const onUp = () => {
      setIsDragging(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      window.removeEventListener('touchmove', onMove);
      window.removeEventListener('touchend', onUp);
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    window.addEventListener('touchmove', onMove, { passive: false });
    window.addEventListener('touchend', onUp);
  };

  const activeColor = isOut ? '#00E5FF' : '#00E676';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, width: '100%', userSelect: 'none' }}>
      {/* Waveform Bars Container */}
      <div
        ref={trackRef}
        onMouseDown={handlePointerDown}
        onTouchStart={handlePointerDown}
        onMouseMove={(e) => setHoverRatio(calculateRatio(e))}
        onMouseLeave={() => setHoverRatio(null)}
        style={{
          display: 'flex',
          alignItems: 'flex-end',
          gap: 2.5,
          height: 32,
          padding: '4px 2px 2px 2px',
          cursor: 'pointer',
          position: 'relative',
          userSelect: 'none'
        }}
        title="Click or drag to seek"
      >
        {waveformBars.map((heightPct, idx) => {
          const barRatio = idx / (waveformBars.length - 1);
          const isPassed = barRatio <= progressRatio;
          const isPlayingBar = isPlaying && Math.abs(barRatio - progressRatio) < 0.12;

          return (
            <div
              key={idx}
              style={{
                flex: 1,
                minWidth: 2,
                maxWidth: 4,
                height: `${heightPct}%`,
                backgroundColor: isPassed ? activeColor : '#CFD8DC',
                borderRadius: 2,
                border: isPassed ? '1px solid #000' : '1px solid #B0BEC5',
                transformOrigin: 'bottom',
                animation: isPlaying
                  ? `waveBounce 0.75s ease-in-out ${(idx * 0.05) % 0.75}s infinite alternate`
                  : 'none',
                boxShadow: isPlayingBar ? `0 0 6px ${activeColor}` : 'none',
                transition: isPlaying ? 'none' : 'background-color 0.15s ease, height 0.2s ease',
                opacity: hoverRatio !== null && barRatio <= hoverRatio && !isPassed ? 0.7 : 1
              }}
            />
          );
        })}
      </div>

      {/* Scrubber Track with draggable thumb */}
      <div
        onMouseDown={handlePointerDown}
        onTouchStart={handlePointerDown}
        style={{
          position: 'relative',
          height: 14,
          display: 'flex',
          alignItems: 'center',
          cursor: 'pointer',
          userSelect: 'none',
          padding: '0 2px'
        }}
      >
        {/* Rail background */}
        <div style={{
          width: '100%',
          height: 6,
          backgroundColor: '#E0E0E0',
          borderRadius: 4,
          border: '1.5px solid #000',
          overflow: 'hidden',
          position: 'relative'
        }}>
          {/* Progress fill */}
          <div style={{
            height: '100%',
            width: `${Math.min(100, Math.max(0, progressRatio * 100))}%`,
            background: isOut
              ? 'linear-gradient(90deg, #00C9FF, #92FE9D)'
              : 'linear-gradient(90deg, #FF9100, #00E676)',
            transition: isDragging ? 'none' : 'width 0.1s linear'
          }} />
        </div>

        {/* Thumb */}
        <div style={{
          position: 'absolute',
          left: `calc(${Math.min(100, Math.max(0, progressRatio * 100))}% - 7px)`,
          width: 14,
          height: 14,
          borderRadius: '50%',
          backgroundColor: '#FFE600',
          border: '2px solid #000',
          boxShadow: '1px 1px 0px #000',
          transform: isDragging ? 'scale(1.25)' : 'scale(1)',
          animation: isPlaying ? 'scrubberPulse 1.2s infinite ease-in-out' : 'none',
          transition: isDragging ? 'none' : 'transform 0.12s ease',
          pointerEvents: 'none'
        }} />
      </div>

      {/* Timestamps Row */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        fontSize: 10,
        fontWeight: 800,
        fontFamily: 'monospace',
        color: '#424242',
        padding: '0 2px'
      }}>
        <span>{formatAudioTime(currentTime)}</span>
        {isPlaying && (
          <span style={{
            fontSize: 9,
            fontWeight: 900,
            color: '#00E676',
            letterSpacing: 0.5,
            textTransform: 'uppercase',
            display: 'flex',
            alignItems: 'center',
            gap: 3
          }}>
            <span style={{
              display: 'inline-block',
              width: 6,
              height: 6,
              borderRadius: '50%',
              backgroundColor: '#00E676',
              border: '1px solid #000'
            }} />
            Playing
          </span>
        )}
        <span>{duration > 0 ? formatAudioTime(duration) : '--:--'}</span>
      </div>
    </div>
  );
}

// ─── Rich Audio Document Message Card ─────────────────────────────────────────
function AudioDocumentCard({
  msg,
  isOut,
  audioPlaybackState,
  onTogglePlay,
  onSeek,
  onCycleSpeed,
  onDownload,
  formatFileSize
}) {
  const isCurrent = audioPlaybackState.msgId === msg.id;
  const isPlaying = isCurrent && audioPlaybackState.isPlaying;
  const currentTime = isCurrent ? audioPlaybackState.currentTime : 0;
  const duration = isCurrent && audioPlaybackState.duration > 0
    ? audioPlaybackState.duration
    : (msg.mediaDurationMs ? msg.mediaDurationMs / 1000 : 0);
  const playbackRate = isCurrent ? audioPlaybackState.playbackRate : 1.0;

  const fileName = msg.fileName || msg.text || 'Audio file';
  const ext = ((fileName.split('.').pop() || 'MP3')).toUpperCase().slice(0, 4);

  const waveformBars = useMemo(() => getWaveformData(msg.id || fileName, 30), [msg.id, fileName]);

  const handleSeek = (ratio) => {
    onSeek(msg.id, ratio, msg.mediaUrl || msg.mediaData);
  };

  return (
    <div
      className="neo-box"
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        padding: '12px 14px',
        backgroundColor: isOut ? '#E1F5FE' : '#FFFFFF',
        borderRadius: 12,
        marginBottom: 4,
        border: '2.5px solid #000',
        boxShadow: '3px 3px 0px #000',
        minWidth: 260,
        maxWidth: 340
      }}
    >
      {/* Top Header: Badge + Title + Action Controls */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{
          width: 42,
          height: 42,
          borderRadius: 10,
          backgroundColor: '#E91E63',
          color: '#fff',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          border: '2px solid #000',
          boxShadow: '1.5px 1.5px 0px #000',
          flexShrink: 0
        }}>
          <span style={{ fontSize: 13, lineHeight: 1 }}>🎵</span>
          <span style={{ fontWeight: 900, fontSize: 9, marginTop: 1, letterSpacing: 0.5 }}>
            {ext}
          </span>
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontWeight: 800,
              fontSize: 13,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              color: '#000'
            }}
            title={fileName}
          >
            {fileName}
          </div>
          <div style={{ fontSize: 11, fontWeight: 700, color: '#666', marginTop: 2 }}>
            {formatFileSize(msg.fileSize)}
            {msg.fileSize ? ' • ' : ''}
            Audio
          </div>
        </div>

        {/* Speed button + Play/Pause button + Download button */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
          <button
            type="button"
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
              onCycleSpeed(msg.id);
            }}
            className="neo-box"
            style={{
              padding: '3px 7px',
              fontSize: 11,
              fontWeight: 900,
              borderRadius: 6,
              border: '2px solid #000',
              boxShadow: '1.5px 1.5px 0px #000',
              backgroundColor: playbackRate === 1.5 ? '#FFE600' : playbackRate === 2.0 ? '#FF3366' : '#FFFFFF',
              color: playbackRate === 2.0 ? '#FFFFFF' : '#000000',
              cursor: 'pointer',
              lineHeight: '16px',
              transition: 'all 0.15s ease'
            }}
            title={`Playback Speed: ${playbackRate}x (Click to change)`}
          >
            {playbackRate}x
          </button>

          <button
            type="button"
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
              onTogglePlay(msg.id, msg.mediaUrl || msg.mediaData);
            }}
            className="neo-box"
            style={{
              width: 36,
              height: 36,
              borderRadius: '50%',
              backgroundColor: isPlaying ? '#FF5252' : '#00E676',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              border: '2px solid #000',
              boxShadow: '1.5px 1.5px 0px #000',
              cursor: 'pointer',
              padding: 0,
              transition: 'transform 0.1s ease'
            }}
            title={isPlaying ? "Pause" : "Play"}
          >
            {isPlaying ? <Pause size={17} color="#000" /> : <Play size={17} color="#000" style={{ marginLeft: 2 }} />}
          </button>

          <button
            type="button"
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
              onDownload(e, msg);
            }}
            className="neo-box"
            style={{
              width: 36,
              height: 36,
              borderRadius: '50%',
              backgroundColor: '#FFE600',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              border: '2px solid #000',
              boxShadow: '1.5px 1.5px 0px #000',
              cursor: 'pointer',
              padding: 0
            }}
            title="Download Audio"
          >
            <Download size={16} color="#000" />
          </button>
        </div>
      </div>

      {/* Waveform Visualizer and Scrubber */}
      <AudioWaveformScrubber
        msgId={msg.id}
        isCurrent={isCurrent}
        isPlaying={isPlaying}
        currentTime={currentTime}
        duration={duration}
        waveformBars={waveformBars}
        isOut={isOut}
        onSeek={handleSeek}
      />
    </div>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────
export default function ChatConversation({
  peerNumber,
  peerName,
  peerAvatar,
  onBack,
  onStartCall,
  isDesktop = false
}) {
  const normPeer = normalizePhoneNumber(peerNumber);
  const [messages, setMessages] = useState([]);
  const [inputText, setInputText] = useState('');
  const [isTypingPeer, setIsTypingPeer] = useState(false);
  const [peerUser, setPeerUser] = useState(null);
  const [nowTick, setNowTick] = useState(Date.now());
  const [menuOpen, setMenuOpen] = useState(false);
  const [selectedImageModal, setSelectedImageModal] = useState(null);
  const [sending, setSending] = useState(false);

  // ── Dynamic tick to periodically re-evaluate online staleness and last seen ──
  useEffect(() => {
    const timer = setInterval(() => setNowTick(Date.now()), 4000);
    return () => clearInterval(timer);
  }, []);

  // ── Real-time presence listener for peer ─────────────────────────────────────
  useEffect(() => {
    if (!normPeer) return;
    let unsubQuery = null;
    let unsubDoc = null;
    let isMounted = true;

    const variations = [normPeer, peerNumber].filter(Boolean);
    const clean = normPeer.replace(/[^0-9]/g, '');
    if (clean) {
      variations.push(clean);
      if (clean.length > 10) variations.push(clean.slice(-10));
      if (!normPeer.startsWith('+')) variations.push('+' + clean);
    }
    // Also include existing conversation's saved phone if known
    try {
      const convs = chatRepositoryWeb.getConversations();
      const existing = convs.find(c => numbersMatch(c.phoneNumber, normPeer));
      if (existing && existing.phoneNumber) variations.push(existing.phoneNumber);
    } catch {}

    const distinct = Array.from(new Set(variations)).slice(0, 10);

    let currentListeningDocId = null;
    const attachDocListener = (docId) => {
      if (!docId || currentListeningDocId === docId) return;
      if (unsubDoc) {
        unsubDoc();
        unsubDoc = null;
      }
      currentListeningDocId = docId;
      try {
        unsubDoc = onSnapshot(doc(db, 'users', docId), (docSnap) => {
          if (docSnap.exists() && isMounted) {
            const d = docSnap.data() || {};
            setPeerUser({ ...d, id: docSnap.id });
          }
        }, (err) => console.warn('Direct user doc listener error:', err));
      } catch {}
    };

    try {
      const q = query(collection(db, 'users'), where('phoneNumber', 'in', distinct));
      unsubQuery = onSnapshot(q, (snapshot) => {
        if (!isMounted) return;
        if (!snapshot.empty) {
          const docSnap = snapshot.docs[0];
          const d = docSnap.data() || {};
          setPeerUser({ ...d, id: docSnap.id });
          attachDocListener(docSnap.id);
        } else {
          // Multi-variation direct doc fallback
          (async () => {
            for (const v of distinct) {
              try {
                const snap = await getDoc(doc(db, 'users', v));
                if (snap.exists() && isMounted) {
                  setPeerUser({ ...snap.data(), id: snap.id });
                  attachDocListener(snap.id);
                  return;
                }
              } catch {}
            }
          })();
        }
      }, (err) => {
        console.warn("Peer presence listener error:", err);
      });
    } catch (e) {
      console.warn("Failed to set up peer presence listener:", e);
    }

    return () => {
      isMounted = false;
      if (unsubQuery) unsubQuery();
      if (unsubDoc) unsubDoc();
    };
  }, [normPeer, peerNumber]);

  // Swipe-to-reply state
  const [replyingTo, setReplyingTo] = useState(null); // { id, text, isOutgoing }

  // Message edit state (10 min window)
  const [editingMessage, setEditingMessage] = useState(null); // msg object

  // Message delete options modal state
  const [selectedMessageForDelete, setSelectedMessageForDelete] = useState(null);

  // Swipe hint state (show briefly on first open)
  const [showSwipeHint, setShowSwipeHint] = useState(false);

  // Keyboard / viewport offset to keep input visible
  const [inputPaddingBottom, setInputPaddingBottom] = useState(0);

  // Audio recording state
  const [isRecording, setIsRecording] = useState(false);
  const [recordSeconds, setRecordSeconds] = useState(0);
  const mediaRecorderRef = useRef(null);
  const audioChunksRef = useRef([]);
  const recordingTimerRef = useRef(null);

  // Audio playback state
  const [audioPlaybackState, setAudioPlaybackState] = useState({
    msgId: null,
    isPlaying: false,
    currentTime: 0,
    duration: 0,
    playbackRate: 1.0,
  });
  const playingAudioId = audioPlaybackState.isPlaying ? audioPlaybackState.msgId : null;
  const audioElementRef = useRef(null);

  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);
  const fileInputRef = useRef(null);
  const docFileInputRef = useRef(null);

  const scrollToBottom = (behavior = 'smooth') => {
    messagesEndRef.current?.scrollIntoView({ behavior });
  };

  // ── Visual Viewport API — keyboard awareness ────────────────────────────────
  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) return;

    const handleViewportResize = () => {
      const windowHeight = window.innerHeight;
      const viewportHeight = vv.height;
      const keyboardHeight = Math.max(0, windowHeight - viewportHeight - vv.offsetTop);
      setInputPaddingBottom(keyboardHeight);
      // Scroll to bottom when keyboard opens
      if (keyboardHeight > 50) {
        setTimeout(() => scrollToBottom('auto'), 50);
      }
    };

    vv.addEventListener('resize', handleViewportResize);
    vv.addEventListener('scroll', handleViewportResize);
    return () => {
      vv.removeEventListener('resize', handleViewportResize);
      vv.removeEventListener('scroll', handleViewportResize);
    };
  }, []);

  // ── Chat listeners ──────────────────────────────────────────────────────────
  useEffect(() => {
    chatRepositoryWeb.setActiveChatPeer(normPeer);

    const updateMessages = () => {
      const msgs = chatRepositoryWeb.getMessages(normPeer);
      setMessages(msgs);
      setIsTypingPeer(Boolean(chatRepositoryWeb.typingStatus[normPeer]));
    };

    updateMessages();
    setTimeout(() => scrollToBottom('auto'), 100);

    const unsubscribe = chatRepositoryWeb.subscribe(updateMessages);

    // Show swipe hint if there are messages and hint not seen
    const hintSeen = sessionStorage.getItem('lks_swipe_hint_seen');
    if (!hintSeen) {
      setTimeout(() => {
        setShowSwipeHint(true);
        setTimeout(() => {
          setShowSwipeHint(false);
          sessionStorage.setItem('lks_swipe_hint_seen', '1');
        }, 3500);
      }, 800);
    }

    return () => {
      chatRepositoryWeb.setActiveChatPeer(null);
      chatRepositoryWeb.setTyping(normPeer, false);
      unsubscribe();
      if (audioElementRef.current) {
        audioElementRef.current.pause();
        audioElementRef.current = null;
      }
      if (recordingTimerRef.current) clearInterval(recordingTimerRef.current);
      if (mediaRecorderRef.current?.state === 'recording') {
        try { mediaRecorderRef.current.stop(); } catch {}
      }
    };
  }, [normPeer]);

  useEffect(() => {
    scrollToBottom('smooth');
  }, [messages.length, isTypingPeer]);

  // ── Swipe reply handler ─────────────────────────────────────────────────────
  const handleSwipeReply = useCallback((msg) => {
    const preview = msg.mediaType === 'IMAGE' ? '📷 Photo'
                  : msg.mediaType === 'AUDIO' ? '🎤 Voice message'
                  : msg.mediaType === 'DOCUMENT' ? `📄 ${msg.fileName || msg.text || 'Document'}`
                  : msg.text || '(message)';
    setReplyingTo({
      id: msg.id,
      text: preview,
      senderLabel: msg.isOutgoing ? 'You' : (peerName || normPeer)
    });
    inputRef.current?.focus();
  }, [peerName, normPeer]);

  // ── Input change (typing indicator) ────────────────────────────────────────
  const handleInputChange = (e) => {
    const text = e.target.value;
    setInputText(text);
    chatRepositoryWeb.setTyping(normPeer, text.length > 0);
  };

  // ── Send text or Save edit ──────────────────────────────────────────────────
  const handleSendText = async (e) => {
    if (e) e.preventDefault();
    const trimmed = inputText.trim();
    if (!trimmed || sending) return;

    if (editingMessage) {
      const msgId = editingMessage.id;
      setEditingMessage(null);
      setInputText('');
      setSending(true);
      try {
        await chatRepositoryWeb.editMessage(msgId, trimmed, normPeer);
      } catch (err) {
        alert(err.message || 'Failed to edit message');
      } finally {
        setSending(false);
        setTimeout(() => scrollToBottom('smooth'), 50);
      }
      return;
    }

    const replyContext = replyingTo;
    setSending(true);
    setInputText('');
    setReplyingTo(null);
    chatRepositoryWeb.setTyping(normPeer, false);

    try {
      // Encode replyContext inside the text payload as JSON metadata
      const payload = replyContext
        ? JSON.stringify({ text: trimmed, replyTo: replyContext })
        : trimmed;
      await chatRepositoryWeb.sendMessage(normPeer, peerName || normPeer, payload, 'TEXT');
    } catch (err) {
      alert(err.message || 'Failed to send message');
    } finally {
      setSending(false);
      setTimeout(() => scrollToBottom('smooth'), 50);
    }
  };

  // ── Send image ──────────────────────────────────────────────────────────────
  const handleImageSelected = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      const img = new Image();
      img.onload = async () => {
        const canvas = document.createElement('canvas');
        const MAX = 1200;
        let w = img.width, h = img.height;
        if (w > h) { if (w > MAX) { h = Math.round(h * MAX / w); w = MAX; } }
        else        { if (h > MAX) { w = Math.round(w * MAX / h); h = MAX; } }
        canvas.width = w; canvas.height = h;
        canvas.getContext('2d').drawImage(img, 0, 0, w, h);
        const rawBase64 = canvas.toDataURL('image/jpeg', 0.75).replace(/^data:image\/[a-z]+;base64,/, '');
        try {
          await chatRepositoryWeb.sendMessage(normPeer, peerName || normPeer, '', 'IMAGE', rawBase64);
        } catch (err) {
          alert(err.message || 'Failed to send image');
        } finally {
          if (fileInputRef.current) fileInputRef.current.value = '';
        }
      };
      img.src = event.target.result;
    };
    reader.readAsDataURL(file);
  };

  // ── Send document ───────────────────────────────────────────────────────────
  const handleDocSelected = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 50 * 1024 * 1024) {
      alert('File size exceeds 50MB limit.');
      return;
    }
    const reader = new FileReader();
    reader.onload = async (event) => {
      const rawBase64 = String(event.target.result || '').split(',')[1] || '';
      try {
        await chatRepositoryWeb.sendMessage(normPeer, peerName || normPeer, file.name, 'DOCUMENT', rawBase64);
      } catch (err) {
        alert(err.message || 'Failed to send document');
      } finally {
        if (docFileInputRef.current) docFileInputRef.current.value = '';
      }
    };
    reader.readAsDataURL(file);
  };

  // ── Voice recording ─────────────────────────────────────────────────────────
  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioChunksRef.current = [];
      const mr = new MediaRecorder(stream);
      mediaRecorderRef.current = mr;
      mr.ondataavailable = (ev) => { if (ev.data.size > 0) audioChunksRef.current.push(ev.data); };
      mr.onstop = () => stream.getTracks().forEach(t => t.stop());
      mr.start();
      setIsRecording(true);
      setRecordSeconds(0);
      recordingTimerRef.current = setInterval(() => setRecordSeconds(s => s + 1), 1000);
    } catch {
      alert('Microphone permission is required to record voice messages.');
    }
  };

  const stopAndSendRecording = () => {
    if (!mediaRecorderRef.current || !isRecording) return;
    const durationMs = recordSeconds * 1000;
    clearInterval(recordingTimerRef.current);
    const recorder = mediaRecorderRef.current;
    recorder.onstop = async () => {
      const blob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
      const reader = new FileReader();
      reader.onload = async () => {
        const rawB64 = reader.result.replace(/^data:audio\/[a-z0-9]+;base64,/, '');
        try {
          await chatRepositoryWeb.sendMessage(normPeer, peerName || normPeer, 'Voice message', 'AUDIO', rawB64, durationMs);
        } catch (err) {
          alert(err.message || 'Failed to send voice message');
        }
      };
      reader.readAsDataURL(blob);
    };
    recorder.stop();
    setIsRecording(false);
    setRecordSeconds(0);
  };

  const cancelRecording = () => {
    if (recordingTimerRef.current) clearInterval(recordingTimerRef.current);
    if (mediaRecorderRef.current?.state === 'recording') mediaRecorderRef.current.stop();
    audioChunksRef.current = [];
    setIsRecording(false);
    setRecordSeconds(0);
  };

  // ── Audio playback ──────────────────────────────────────────────────────────
  const togglePlayAudio = async (msgId, rawAudio) => {
    // 1. If this message is already the active audio
    if (audioPlaybackState.msgId === msgId && audioElementRef.current) {
      if (audioPlaybackState.isPlaying) {
        audioElementRef.current.pause();
        setAudioPlaybackState(prev => ({ ...prev, isPlaying: false }));
      } else {
        try {
          await audioElementRef.current.play();
          setAudioPlaybackState(prev => ({ ...prev, isPlaying: true }));
        } catch (e) {
          console.warn('Resume play error:', e);
        }
      }
      return;
    }

    // 2. Otherwise stop and clean up previous audio
    if (audioElementRef.current) {
      audioElementRef.current.pause();
      audioElementRef.current.removeAttribute('src');
      audioElementRef.current.load();
      audioElementRef.current = null;
    }

    // 3. Resolve source
    let src = rawAudio;
    if (typeof src === 'string' && src.startsWith('idb:')) {
      src = await mediaStorageWeb.getMediaUrl(msgId);
    } else if (!src) {
      src = await mediaStorageWeb.getMediaUrl(msgId);
    }

    if (!src) {
      alert('Audio not ready yet');
      return;
    }

    const currentRate = audioPlaybackState.playbackRate || 1.0;

    const setupAndPlay = (audioSrc) => {
      const audio = new Audio(audioSrc);
      audio.preload = 'metadata';
      audio.playbackRate = currentRate;
      if ('preservesPitch' in audio) {
        audio.preservesPitch = true;
      }

      audio.onloadedmetadata = () => {
        if (audio.duration && !isNaN(audio.duration) && isFinite(audio.duration)) {
          setAudioPlaybackState(prev => prev.msgId === msgId ? { ...prev, duration: audio.duration } : prev);
        }
      };

      audio.ondurationchange = () => {
        if (audio.duration && !isNaN(audio.duration) && isFinite(audio.duration)) {
          setAudioPlaybackState(prev => prev.msgId === msgId ? { ...prev, duration: audio.duration } : prev);
        }
      };

      audio.ontimeupdate = () => {
        setAudioPlaybackState(prev => {
          if (prev.msgId !== msgId) return prev;
          const dur = (audio.duration && !isNaN(audio.duration) && isFinite(audio.duration))
            ? audio.duration
            : prev.duration;
          return {
            ...prev,
            currentTime: audio.currentTime || 0,
            duration: dur
          };
        });
      };

      audio.onplay = () => {
        setAudioPlaybackState(prev => prev.msgId === msgId ? { ...prev, isPlaying: true } : prev);
      };

      audio.onpause = () => {
        setAudioPlaybackState(prev => prev.msgId === msgId ? { ...prev, isPlaying: false } : prev);
      };

      audio.onended = () => {
        setAudioPlaybackState(prev => prev.msgId === msgId ? { ...prev, isPlaying: false, currentTime: 0 } : prev);
      };

      audio.onerror = (e) => {
        console.warn('Audio play error on source:', audioSrc, e);
        if (typeof src === 'string' && !src.startsWith('blob:') && !src.startsWith('http')) {
          if (!audioSrc.includes('audio/webm')) {
            setupAndPlay(`data:audio/webm;base64,${src}`);
            return;
          }
        }
        setAudioPlaybackState(prev => prev.msgId === msgId ? { ...prev, isPlaying: false } : prev);
      };

      audioElementRef.current = audio;
      setAudioPlaybackState({
        msgId,
        isPlaying: true,
        currentTime: 0,
        duration: (audio.duration && !isNaN(audio.duration) && isFinite(audio.duration)) ? audio.duration : 0,
        playbackRate: currentRate
      });

      audio.play().catch(err => {
        console.warn('Initial play error:', err);
        if (typeof src === 'string' && !src.startsWith('blob:') && !src.startsWith('http') && !audioSrc.includes('audio/webm')) {
          setupAndPlay(`data:audio/webm;base64,${src}`);
        } else {
          setAudioPlaybackState(prev => prev.msgId === msgId ? { ...prev, isPlaying: false } : prev);
        }
      });
    };

    if (src.startsWith('blob:') || src.startsWith('http')) {
      setupAndPlay(src);
    } else {
      const fullSrc = src.startsWith('data:') ? src : `data:audio/mp4;base64,${src}`;
      setupAndPlay(fullSrc);
    }
  };

  const seekAudio = async (msgId, targetRatio, rawAudio) => {
    if (audioPlaybackState.msgId !== msgId || !audioElementRef.current) {
      await togglePlayAudio(msgId, rawAudio);
    }

    if (audioElementRef.current) {
      const dur = audioElementRef.current.duration || audioPlaybackState.duration || 0;
      if (dur > 0 && isFinite(dur)) {
        const targetTime = Math.max(0, Math.min(dur, targetRatio * dur));
        audioElementRef.current.currentTime = targetTime;
        setAudioPlaybackState(prev => ({ ...prev, currentTime: targetTime }));
      }
    }
  };

  const cyclePlaybackRate = (msgId) => {
    const speeds = [1.0, 1.5, 2.0];
    const currentRate = audioPlaybackState.playbackRate || 1.0;
    const nextIdx = (speeds.indexOf(currentRate) + 1) % speeds.length;
    const nextRate = speeds[nextIdx];

    if (audioElementRef.current && audioPlaybackState.msgId === msgId) {
      audioElementRef.current.playbackRate = nextRate;
    }
    setAudioPlaybackState(prev => ({ ...prev, playbackRate: nextRate }));
  };

  const formatFileSize = (bytes) => {
    if (!bytes || bytes <= 0) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const isAudioDoc = (fileName = '') => {
    const ext = (fileName.split('.').pop() || '').toLowerCase();
    return ['mp3', 'm4a', 'wav', 'ogg', 'aac', 'flac', 'opus'].includes(ext);
  };

  const handleDownloadDocument = async (e, msg) => {
    e?.preventDefault?.();
    e?.stopPropagation?.();
    try {
      let url = msg.mediaUrl;
      if (!url && msg.mediaData) {
        if (msg.mediaData.startsWith('idb:')) {
          url = await mediaStorageWeb.getMediaUrl(msg.id);
        } else if (msg.mediaData.startsWith('blob:') || msg.mediaData.startsWith('data:')) {
          url = msg.mediaData;
        } else {
          url = `data:application/octet-stream;base64,${msg.mediaData}`;
        }
      } else if (!url && !msg.mediaData) {
        url = await mediaStorageWeb.getMediaUrl(msg.id);
      }

      if (!url) {
        alert('Document data is not available yet.');
        return;
      }

      const a = document.createElement('a');
      a.href = url;
      a.download = msg.fileName || msg.text || 'document';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    } catch (err) {
      console.error('Download error:', err);
      alert('Failed to download document: ' + (err.message || err));
    }
  };

  const formatDur = (ms) => {
    const s = Math.max(1, Math.round((ms || 0) / 1000));
    return `${Math.floor(s / 60)}:${(s % 60) < 10 ? '0' : ''}${s % 60}`;
  };

  // ── Tick renderer ───────────────────────────────────────────────────────────
  const renderTicks = (status) => {
    switch (status) {
      case 'READ':     return <CheckCheck size={14} color="#00C9FF" strokeWidth={2.5} style={{ marginLeft: 3 }} />;
      case 'DELIVERED':return <CheckCheck size={14} color="#555"    strokeWidth={2}   style={{ marginLeft: 3 }} />;
      case 'SENT':     return <Check      size={14} color="#555"    strokeWidth={2}   style={{ marginLeft: 3 }} />;
      case 'FAILED':   return <span style={{ color: '#ff3366', fontWeight: 900, marginLeft: 3, fontSize: 11 }}>!</span>;
      default:         return null;
    }
  };

  // ── Parse message payload (may contain reply metadata) ─────────────────────
  const parseMessage = (msg) => {
    if (msg.mediaType !== 'TEXT') return { text: msg.text, replyTo: null };
    try {
      const parsed = JSON.parse(msg.text);
      if (parsed && parsed.text !== undefined) {
        return { text: parsed.text, replyTo: parsed.replyTo || null };
      }
    } catch {}
    return { text: msg.text, replyTo: null };
  };

  const displayAvatar = formatAvatarUrl(peerUser?.profilePictureUrl) || formatAvatarUrl(peerAvatar);
  const peerDisplayName = peerName || peerUser?.displayName || normPeer;
  const initial = (peerDisplayName || '?')[0]?.toUpperCase() || '?';
  const isBlocked = webRtcEngine.isNumberBlocked ? webRtcEngine.isNumberBlocked(normPeer) : false;
  const isPeerOnlineStatus = isUserOnline(peerUser, nowTick);
  const lastSeenText = peerUser?.lastSeen ? formatLastSeen(peerUser.lastSeen, nowTick) : '';

  // ── Key press: Enter to send ────────────────────────────────────────────────
  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendText();
    }
  };

  return (
    <div style={{
      display: 'flex', flexDirection: 'column',
      height: '100%',
      width: '100%',
      flex: 1,
      minWidth: 0,
      backgroundColor: 'var(--bg-color)', position: 'relative', overflow: 'hidden'
    }}>
      {/* ── TOP BAR ── */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '12px 20px', backgroundColor: '#fff',
        borderBottom: '4px solid #000', zIndex: 10, flexShrink: 0,
        width: '100%'
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flex: 1, minWidth: 0 }}>
          <button 
            onClick={onBack} 
            className="neo-box" 
            title={isDesktop ? "Close chat" : "Back"}
            style={{
              width: 38, height: 38, padding: 0, display: 'flex',
              alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer', backgroundColor: 'var(--accent)', flexShrink: 0
            }}
          >
            <ArrowLeft size={20} color="#000" strokeWidth={3} />
          </button>
          <div style={{ position: 'relative', flexShrink: 0 }}>
            <div style={{
              width: 42, height: 42, borderRadius: '50%',
              backgroundColor: 'var(--secondary)', border: '2px solid #000',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontWeight: 900, fontSize: 18, overflow: 'hidden'
            }}>
              {displayAvatar ? (
                <img src={displayAvatar} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
              ) : initial}
            </div>
            {isPeerOnlineStatus && (
              <span style={{
                position: 'absolute', bottom: -1, right: -1,
                width: 12, height: 12, borderRadius: '50%',
                backgroundColor: '#00e676', border: '2px solid #fff',
                boxShadow: '0 0 4px rgba(0,0,0,0.3)',
                zIndex: 2
              }} />
            )}
          </div>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 900, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {peerDisplayName}
            </div>
            <div style={{ fontSize: 12, fontWeight: 700 }}>
              {isTypingPeer ? (
                <span style={{ color: '#00838f', fontStyle: 'italic' }}>typing...</span>
              ) : isPeerOnlineStatus ? (
                <span style={{ color: '#00a884', fontWeight: 800, display: 'flex', alignItems: 'center', gap: 5 }}>
                  <span style={{
                    width: 7, height: 7, borderRadius: '50%',
                    backgroundColor: '#00e676', display: 'inline-block',
                    boxShadow: '0 0 6px #00e676'
                  }}></span>
                  online
                </span>
              ) : lastSeenText ? (
                <span style={{ color: '#666' }}>{lastSeenText}</span>
              ) : (
                <span style={{ color: '#666', display: 'flex', alignItems: 'center', gap: 4 }}>
                  <Shield size={11} color="#00b4d8" /> E2E Encrypted
                </span>
              )}
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
          <button onClick={() => onStartCall?.(normPeer, 'AUDIO')} className="neo-box"
            style={{ width: 36, height: 36, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: 'var(--accent)', cursor: 'pointer' }}>
            <Phone size={18} color="#000" />
          </button>
          <button onClick={() => onStartCall?.(normPeer, 'VIDEO')} className="neo-box"
            style={{ width: 36, height: 36, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: 'var(--primary)', cursor: 'pointer' }}>
            <Video size={18} color="#fff" />
          </button>
          <div style={{ position: 'relative' }}>
            <button onClick={() => setMenuOpen(!menuOpen)} className="neo-box"
              style={{ width: 36, height: 36, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: '#fff', cursor: 'pointer' }}>
              <MoreVertical size={18} />
            </button>
            {menuOpen && (
              <div className="neo-box" style={{
                position: 'absolute', right: 0, top: 44, width: 160,
                backgroundColor: '#fff', padding: 6, zIndex: 50,
                display: 'flex', flexDirection: 'column', gap: 4
              }}>
                <button onClick={() => { setMenuOpen(false); if (confirm('Clear all messages?')) chatRepositoryWeb.clearChat(normPeer); }}
                  style={{ border: 'none', background: 'none', padding: '8px 10px', textAlign: 'left', fontWeight: 800, fontSize: 13, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Trash2 size={16} /> Clear Chat
                </button>
                <button onClick={async () => {
                  setMenuOpen(false);
                  if (isBlocked) { await webRtcEngine.unblockNumber(normPeer); alert(`Unblocked ${normPeer}`); }
                  else if (confirm(`Block ${normPeer}?`)) { await webRtcEngine.blockNumber(normPeer); alert(`Blocked ${normPeer}`); }
                }} style={{ border: 'none', background: 'none', padding: '8px 10px', textAlign: 'left', fontWeight: 800, fontSize: 13, cursor: 'pointer', color: '#ff3366', display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Ban size={16} /> {isBlocked ? 'Unblock' : 'Block'}
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── SWIPE HINT BANNER ── */}
      {showSwipeHint && (
        <div style={{
          position: 'absolute', top: 72, left: 0, right: 0, zIndex: 50,
          display: 'flex', justifyContent: 'center', padding: '0 20px',
          animation: 'slideDown 0.4s ease-out'
        }}>
          <div style={{
            backgroundColor: 'rgba(0,0,0,0.82)', color: '#fff', padding: '10px 18px',
            borderRadius: 20, display: 'flex', alignItems: 'center', gap: 10,
            fontSize: 13, fontWeight: 700, boxShadow: '0 4px 12px rgba(0,0,0,0.3)',
            border: '2px solid #FFCC00'
          }}>
            <Reply size={18} color="#FFCC00" />
            <div>
              <div>Swipe <strong>right</strong> on received msgs to reply</div>
              <div style={{ fontSize: 11, opacity: 0.8, marginTop: 2 }}>Swipe <strong>left</strong> on your own msgs to reply</div>
            </div>
          </div>
        </div>
      )}

      {/* ── MESSAGES AREA ── */}
      <div style={{
        flex: 1, overflowY: 'auto', padding: '16px 20px',
        display: 'flex', flexDirection: 'column', gap: 10,
        // Extra bottom padding so messages aren't hidden behind the input bar
        paddingBottom: 20,
        width: '100%',
        maxWidth: '920px',
        margin: '0 auto'
      }}>
        {/* E2EE Banner */}
        <div style={{
          backgroundColor: '#fffbe6', border: '2px dashed #000', borderRadius: 8,
          padding: '8px 16px', textAlign: 'center', fontSize: 12, fontWeight: 700,
          color: '#555', margin: '0 auto 8px', maxWidth: 440, width: '100%'
        }}>
          🔒 End-to-End Encrypted • Auto-deleted from server after delivery
        </div>

        {messages.length === 0 ? (
          <div style={{ textAlign: 'center', margin: 'auto', color: '#888', fontWeight: 700, fontSize: 14 }}>
            Say hello! Send an encrypted message below.
          </div>
        ) : (
          messages.map((msg) => {
            const isOut = Boolean(msg.isOutgoing);
            const timeStr = msg.timestamp
              ? new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
              : '';
            const { text, replyTo } = parseMessage(msg);
            const isDeleted = Boolean(msg.text && msg.text.startsWith('🚫 '));

            return (
              <SwipeableMessage key={msg.id} msg={msg} onSwipeReply={handleSwipeReply}>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: isOut ? 'flex-end' : 'flex-start' }}>
                  <div className="neo-box" style={{
                    backgroundColor: isOut ? '#d4fcd4' : '#ffffff',
                    padding: '8px 12px', borderRadius: 12, boxShadow: '3px 3px 0 #000',
                    display: 'flex', flexDirection: 'column', gap: 4,
                    maxWidth: '100%'
                  }}>
                    {isDeleted ? (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontStyle: 'italic', color: '#777', fontSize: 13, padding: '2px 4px' }}>
                        <Ban size={14} color="#888" />
                        <span>{msg.text}</span>
                      </div>
                    ) : (
                      <>
                        {/* Reply Context Quote */}
                        {replyTo && (
                          <div style={{
                            backgroundColor: isOut ? 'rgba(0,0,0,0.07)' : 'rgba(0,180,216,0.1)',
                            borderLeft: `3px solid ${isOut ? '#00838f' : '#FF3366'}`,
                            padding: '4px 8px', borderRadius: 6, marginBottom: 4
                          }}>
                            <div style={{ fontSize: 11, fontWeight: 900, color: isOut ? '#00838f' : '#FF3366', marginBottom: 2 }}>
                              {replyTo.senderLabel}
                            </div>
                            <div style={{
                              fontSize: 12, fontWeight: 600, color: '#444',
                              whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                              maxWidth: 220
                            }}>
                              {replyTo.text}
                            </div>
                          </div>
                        )}

                        {/* Image */}
                        {msg.mediaType === 'IMAGE' && msg.mediaData && (
                          <img
                            src={msg.mediaData.startsWith('data:') ? msg.mediaData : `data:image/jpeg;base64,${msg.mediaData}`}
                            alt="Photo"
                            onClick={() => setSelectedImageModal(msg.mediaData)}
                            style={{ width: '100%', maxHeight: 240, objectFit: 'cover', borderRadius: 8, border: '2px solid #000', cursor: 'pointer', marginBottom: 4 }}
                          />
                        )}

                        {/* Audio / Voice Note */}
                        {msg.mediaType === 'AUDIO' && msg.mediaData && (
                          <div
                            className="neo-box"
                            style={{
                              display: 'flex',
                              flexDirection: 'column',
                              gap: 6,
                              padding: '10px 12px',
                              backgroundColor: isOut ? '#E0F7FA' : '#FFFFFF',
                              borderRadius: 12,
                              border: '2px solid #000',
                              boxShadow: '2px 2px 0px #000',
                              minWidth: 230,
                              maxWidth: 310,
                              marginBottom: 4
                            }}
                          >
                            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                              <button
                                type="button"
                                onClick={(e) => {
                                  e.preventDefault();
                                  e.stopPropagation();
                                  togglePlayAudio(msg.id, msg.mediaData);
                                }}
                                className="neo-box"
                                style={{
                                  width: 36,
                                  height: 36,
                                  borderRadius: '50%',
                                  padding: 0,
                                  display: 'flex',
                                  alignItems: 'center',
                                  justifyContent: 'center',
                                  backgroundColor: (audioPlaybackState.msgId === msg.id && audioPlaybackState.isPlaying) ? '#FF5252' : '#00E676',
                                  border: '2px solid #000',
                                  boxShadow: '1.5px 1.5px 0px #000',
                                  cursor: 'pointer',
                                  flexShrink: 0,
                                  transition: 'transform 0.1s ease'
                                }}
                                title={(audioPlaybackState.msgId === msg.id && audioPlaybackState.isPlaying) ? "Pause" : "Play"}
                              >
                                {(audioPlaybackState.msgId === msg.id && audioPlaybackState.isPlaying) ? (
                                  <Pause size={17} color="#000" />
                                ) : (
                                  <Play size={17} color="#000" style={{ marginLeft: 2 }} />
                                )}
                              </button>

                              <div style={{ flex: 1, minWidth: 0 }}>
                                <div style={{ fontSize: 12, fontWeight: 900, color: '#000', display: 'flex', alignItems: 'center', gap: 4 }}>
                                  <span>🎤</span>
                                  <span>Voice message</span>
                                </div>
                              </div>

                              <button
                                type="button"
                                onClick={(e) => {
                                  e.preventDefault();
                                  e.stopPropagation();
                                  cyclePlaybackRate(msg.id);
                                }}
                                className="neo-box"
                                style={{
                                  padding: '2px 7px',
                                  fontSize: 11,
                                  fontWeight: 900,
                                  borderRadius: 6,
                                  border: '2px solid #000',
                                  boxShadow: '1.5px 1.5px 0px #000',
                                  backgroundColor: (audioPlaybackState.msgId === msg.id && audioPlaybackState.playbackRate === 1.5)
                                    ? '#FFE600'
                                    : (audioPlaybackState.msgId === msg.id && audioPlaybackState.playbackRate === 2.0)
                                    ? '#FF3366'
                                    : '#FFFFFF',
                                  color: (audioPlaybackState.msgId === msg.id && audioPlaybackState.playbackRate === 2.0) ? '#FFFFFF' : '#000000',
                                  cursor: 'pointer',
                                  lineHeight: '16px',
                                  transition: 'all 0.15s ease'
                                }}
                                title="Toggle Speed"
                              >
                                {(audioPlaybackState.msgId === msg.id ? audioPlaybackState.playbackRate : 1.0)}x
                              </button>
                            </div>

                            <AudioWaveformScrubber
                              msgId={msg.id}
                              isCurrent={audioPlaybackState.msgId === msg.id}
                              isPlaying={audioPlaybackState.msgId === msg.id && audioPlaybackState.isPlaying}
                              currentTime={audioPlaybackState.msgId === msg.id ? audioPlaybackState.currentTime : 0}
                              duration={audioPlaybackState.msgId === msg.id && audioPlaybackState.duration > 0
                                ? audioPlaybackState.duration
                                : (msg.mediaDurationMs ? msg.mediaDurationMs / 1000 : 0)}
                              waveformBars={getWaveformData(msg.id, 26)}
                              isOut={isOut}
                              onSeek={(ratio) => seekAudio(msg.id, ratio, msg.mediaData)}
                            />
                          </div>
                        )}

                        {/* Document */}
                        {msg.mediaType === 'DOCUMENT' && (
                          isAudioDoc(msg.fileName || msg.text) ? (
                            <AudioDocumentCard
                              msg={msg}
                              isOut={isOut}
                              audioPlaybackState={audioPlaybackState}
                              onTogglePlay={togglePlayAudio}
                              onSeek={seekAudio}
                              onCycleSpeed={cyclePlaybackRate}
                              onDownload={handleDownloadDocument}
                              formatFileSize={formatFileSize}
                            />
                          ) : (
                            <div
                              onClick={(e) => handleDownloadDocument(e, msg)}
                              className="neo-box"
                              style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: 12,
                                padding: '10px 14px',
                                backgroundColor: isOut ? '#E1F5FE' : '#FFFFFF',
                                borderRadius: 10,
                                marginBottom: 4,
                                border: '2px solid #000',
                                minWidth: 220,
                                maxWidth: 320,
                                cursor: 'pointer'
                              }}
                            >
                              <div style={{
                                width: 42,
                                height: 42,
                                borderRadius: 8,
                                backgroundColor: '#5E35B1',
                                color: '#fff',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                fontWeight: 900,
                                fontSize: 11,
                                flexShrink: 0
                              }}>
                                {((msg.fileName || msg.text || 'DOC').split('.').pop() || 'DOC').toUpperCase().slice(0, 4)}
                              </div>

                              <div style={{ flex: 1, minWidth: 0 }}>
                                <div
                                  style={{
                                    fontWeight: 800,
                                    fontSize: 13,
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap',
                                    color: '#000'
                                  }}
                                  title={msg.fileName || msg.text || 'Document'}
                                >
                                  {msg.fileName || msg.text || 'Document'}
                                </div>
                                <div style={{ fontSize: 11, fontWeight: 700, color: '#666', marginTop: 2 }}>
                                  {formatFileSize(msg.fileSize)}
                                  {msg.fileSize ? ' • ' : ''}
                                  Document
                                </div>
                              </div>

                              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                                <button
                                  type="button"
                                  onClick={(e) => handleDownloadDocument(e, msg)}
                                  className="neo-box"
                                  style={{
                                    width: 34,
                                    height: 34,
                                    borderRadius: '50%',
                                    backgroundColor: '#FFE600',
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    border: '2px solid #000',
                                    cursor: 'pointer',
                                    padding: 0
                                  }}
                                  title="Download"
                                >
                                  <Download size={16} color="#000" />
                                </button>
                              </div>
                            </div>
                          )
                        )}

                        {/* Text */}
                        {text && msg.mediaType !== 'DOCUMENT' ? (
                          <div style={{ fontSize: 15, fontWeight: 600, color: '#000', wordBreak: 'break-word', whiteSpace: 'pre-wrap' }}>
                            {text}
                          </div>
                        ) : null}
                      </>
                    )}

                    {/* Timestamp + ticks */}
                    <div style={{ alignSelf: 'flex-end', display: 'flex', alignItems: 'center', fontSize: 11, fontWeight: 700, color: '#555', marginTop: 2 }}>
                      {Boolean(msg.isEdited) && !isDeleted && (
                        <span style={{ fontStyle: 'italic', opacity: 0.75, marginRight: 4, fontSize: 10, color: '#2e7d32' }}>
                          Edited •
                        </span>
                      )}
                      {timeStr}
                      {isOut && renderTicks(msg.status)}
                      {isOut && !isDeleted && msg.mediaType === 'TEXT' && (Date.now() - (msg.timestamp || 0) <= 10 * 60 * 1000) && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            setEditingMessage(msg);
                            setInputText(text);
                            inputRef.current?.focus();
                          }}
                          style={{
                            background: 'none',
                            border: 'none',
                            cursor: 'pointer',
                            padding: '0 0 0 5px',
                            display: 'flex',
                            alignItems: 'center',
                            opacity: 0.6
                          }}
                          title="Edit message (10 min window)"
                        >
                          <Edit2 size={12} color="#00838f" />
                        </button>
                      )}
                      {!isDeleted && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            setSelectedMessageForDelete(msg);
                          }}
                          style={{
                            background: 'none',
                            border: 'none',
                            cursor: 'pointer',
                            padding: '0 0 0 5px',
                            display: 'flex',
                            alignItems: 'center',
                            opacity: 0.6
                          }}
                          title="Delete message"
                        >
                          <Trash2 size={12} color="#d32f2f" />
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              </SwipeableMessage>
            );
          })
        )}

        {/* Typing indicator */}
        {isTypingPeer && (
          <div style={{ alignSelf: 'flex-start', padding: '8px 14px', backgroundColor: '#fff', borderRadius: 12, border: '3px solid #000', boxShadow: '3px 3px 0 #000', fontSize: 13, fontWeight: 800, color: '#00838f', fontStyle: 'italic' }}>
            {peerName || normPeer} is typing…
          </div>
        )}

        <div ref={messagesEndRef} style={{ height: 4 }} />
      </div>

      {/* ── INPUT BAR (sticks above keyboard using visualViewport paddingBottom) ── */}
      <div style={{
        padding: '12px 20px',
        backgroundColor: '#fff',
        borderTop: '4px solid #000',
        zIndex: 20,
        flexShrink: 0,
        width: '100%',
        // Push up by keyboard height
        marginBottom: inputPaddingBottom,
        transition: 'margin-bottom 0.15s ease-out'
      }}>
        <div style={{ maxWidth: '920px', margin: '0 auto', width: '100%' }}>
          {/* Reply Preview Bar */}
          {replyingTo && (
            <div style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              backgroundColor: '#e0f7fa', border: '2px solid #00838f',
              borderRadius: 10, padding: '6px 12px', marginBottom: 8
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <CornerUpLeft size={16} color="#00838f" strokeWidth={2.5} />
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 11, fontWeight: 900, color: '#00838f' }}>
                    Replying to {replyingTo.senderLabel}
                  </div>
                  <div style={{ fontSize: 12, fontWeight: 600, color: '#444', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 260 }}>
                    {replyingTo.text}
                  </div>
                </div>
              </div>
              <button
                onClick={() => setReplyingTo(null)}
                style={{ border: 'none', background: 'none', cursor: 'pointer', flexShrink: 0, padding: 4 }}
              >
                <X size={18} color="#555" />
              </button>
            </div>
          )}

          {/* Edit Preview Bar */}
          {editingMessage && (
            <div style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              backgroundColor: '#e8f5e9', border: '2px solid #2e7d32',
              borderRadius: 10, padding: '6px 12px', marginBottom: 8
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <Edit2 size={16} color="#2e7d32" style={{ flexShrink: 0 }} />
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 11, fontWeight: 900, color: '#2e7d32' }}>
                    Editing message (10 min window)
                  </div>
                  <div style={{ fontSize: 12, fontWeight: 600, color: '#444', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 260 }}>
                    {editingMessage.text}
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={() => { setEditingMessage(null); setInputText(''); }}
                style={{ border: 'none', background: 'none', cursor: 'pointer', flexShrink: 0, padding: 4 }}
              >
                <X size={18} color="#555" />
              </button>
            </div>
          )}

          {isRecording ? (
            /* RECORDING BAR */
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', gap: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ width: 12, height: 12, borderRadius: '50%', backgroundColor: '#f44336', display: 'inline-block', animation: 'pulse 1s infinite' }} />
                <span style={{ fontWeight: 800, fontSize: 14 }}>{formatDur(recordSeconds * 1000)}</span>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button type="button" onClick={cancelRecording} className="neo-box"
                  style={{ padding: '8px 14px', backgroundColor: '#fff', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, fontWeight: 800, fontSize: 12 }}>
                  <Trash2 size={16} color="#f44336" /> Cancel
                </button>
                <button type="button" onClick={stopAndSendRecording} className="neo-box"
                  style={{ padding: '8px 18px', backgroundColor: '#00E5FF', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6, fontWeight: 900, fontSize: 13 }}>
                  <Send size={16} /> Send
                </button>
              </div>
            </div>
          ) : (
            /* TEXT / MEDIA BAR */
            <form onSubmit={handleSendText} style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%' }}>
              <input type="file" accept="image/*" ref={fileInputRef} style={{ display: 'none' }} onChange={handleImageSelected} />
              <input type="file" ref={docFileInputRef} style={{ display: 'none' }} onChange={handleDocSelected} />
              <button type="button" onClick={() => fileInputRef.current?.click()} className="neo-box"
                style={{ width: 44, height: 44, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: 'var(--accent)', cursor: 'pointer', flexShrink: 0 }}
                title="Attach Photo">
                <ImageIcon size={22} />
              </button>
              <button type="button" onClick={() => docFileInputRef.current?.click()} className="neo-box"
                style={{ width: 44, height: 44, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: '#EDE7F6', cursor: 'pointer', flexShrink: 0 }}
                title="Attach Document">
                <Paperclip size={22} color="#5E35B1" />
              </button>
              <button type="button" onClick={startRecording} className="neo-box"
                style={{ width: 44, height: 44, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: '#fff', cursor: 'pointer', flexShrink: 0 }}
                title="Record Voice Note">
                <Mic size={22} />
              </button>
              <input
                ref={inputRef}
                type="text"
                className="neo-input"
                placeholder={editingMessage ? 'Edit message...' : (replyingTo ? `Reply to ${replyingTo.senderLabel}...` : 'Encrypted message...')}
                value={inputText}
                onChange={handleInputChange}
                onKeyDown={handleKeyDown}
                style={{ flex: 1, padding: '12px 16px', fontSize: 15 }}
                // Prevent iOS from zooming in (min font-size 16px avoids that)
              />
              <button type="submit" disabled={!inputText.trim() || sending} className="neo-btn"
                style={{
                  width: 46, height: 46, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  backgroundColor: editingMessage ? '#2e7d32' : (inputText.trim() ? 'var(--primary)' : '#ccc'),
                  cursor: inputText.trim() ? 'pointer' : 'default', flexShrink: 0
                }}>
                {editingMessage ? <Check size={20} color="#fff" /> : <Send size={20} color="#fff" />}
              </button>
            </form>
          )}
        </div>
      </div>

      {/* ── IMAGE LIGHTBOX ── */}
      {selectedImageModal && (
        <div onClick={() => setSelectedImageModal(null)} style={{
          position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.9)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          padding: 20, zIndex: 1000
        }}>
          <div style={{ position: 'relative' }}>
            <button onClick={() => setSelectedImageModal(null)} className="neo-box"
              style={{ position: 'absolute', top: -16, right: -16, width: 36, height: 36, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: '#fff', cursor: 'pointer', zIndex: 10 }}>
              <X size={20} />
            </button>
            <img
              src={selectedImageModal.startsWith('data:') ? selectedImageModal : `data:image/jpeg;base64,${selectedImageModal}`}
              alt="Enlarged"
              style={{ maxWidth: '90vw', maxHeight: '85vh', objectFit: 'contain', border: '3px solid #000' }}
            />
          </div>
        </div>
      )}

      {/* ── WHATSAPP-STYLE DELETE OPTIONS MODAL ── */}
      {selectedMessageForDelete && (
        <div
          onClick={() => setSelectedMessageForDelete(null)}
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0, 0, 0, 0.6)',
            zIndex: 1000,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: 20
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="neo-box"
            style={{
              backgroundColor: '#fff',
              maxWidth: 360,
              width: '100%',
              padding: '24px 20px',
              borderRadius: 16,
              textAlign: 'center',
              boxShadow: '0 10px 25px rgba(0,0,0,0.3)'
            }}
          >
            <div style={{ fontSize: 18, fontWeight: 900, marginBottom: 8, color: '#000' }}>
              Delete message?
            </div>
            <div style={{ fontSize: 13, fontWeight: 600, color: '#666', marginBottom: 20 }}>
              {selectedMessageForDelete.isOutgoing
                ? 'You can delete this message for everyone or delete it just for yourself.'
                : 'Delete this message from your chat history.'}
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {selectedMessageForDelete.isOutgoing && (
                <button
                  type="button"
                  className="neo-box"
                  onClick={async () => {
                    const msg = selectedMessageForDelete;
                    setSelectedMessageForDelete(null);
                    try {
                      await chatRepositoryWeb.deleteMessageForEveryone(msg.id, normPeer);
                    } catch (err) {
                      alert(err.message || 'Failed to delete message');
                    }
                  }}
                  style={{
                    padding: '12px',
                    backgroundColor: '#fee2e2',
                    color: '#dc2626',
                    fontWeight: 900,
                    fontSize: 14,
                    borderRadius: 10,
                    cursor: 'pointer',
                    border: '2px solid #dc2626'
                  }}
                >
                  Delete for everyone
                </button>
              )}

              <button
                type="button"
                className="neo-box"
                onClick={() => {
                  const msg = selectedMessageForDelete;
                  setSelectedMessageForDelete(null);
                  chatRepositoryWeb.deleteMessageLocally(msg.id, normPeer);
                }}
                style={{
                  padding: '12px',
                  backgroundColor: '#f3f4f6',
                  color: '#111827',
                  fontWeight: 900,
                  fontSize: 14,
                  borderRadius: 10,
                  cursor: 'pointer',
                  border: '2px solid #000'
                }}
              >
                Delete for me
              </button>

              <button
                type="button"
                onClick={() => setSelectedMessageForDelete(null)}
                style={{
                  padding: '10px',
                  backgroundColor: 'transparent',
                  color: '#6b7280',
                  fontWeight: 800,
                  fontSize: 13,
                  border: 'none',
                  cursor: 'pointer',
                  marginTop: 4
                }}
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Slide-down animation keyframes */}
      <style>{`
        @keyframes slideDown {
          from { opacity: 0; transform: translateY(-16px); }
          to   { opacity: 1; transform: translateY(0); }
        }
        @keyframes pulse {
          0%, 100% { opacity: 1; }
          50%       { opacity: 0.4; }
        }
      `}</style>
    </div>
  );
}
