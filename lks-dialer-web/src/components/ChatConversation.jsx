import React, { useState, useEffect, useRef, useCallback } from 'react';
import { 
  ArrowLeft, Phone, Video, MoreVertical, Send, Image as ImageIcon, 
  Mic, Trash2, Check, CheckCheck, Play, Pause, X, Shield, Ban, CornerUpLeft, Reply, Edit2
} from 'lucide-react';
import { chatRepositoryWeb, normalizePhoneNumber } from '../lib/ChatRepositoryWeb';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { formatAvatarUrl } from '../lib/ImageUtils';

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
  const [menuOpen, setMenuOpen] = useState(false);
  const [selectedImageModal, setSelectedImageModal] = useState(null);
  const [sending, setSending] = useState(false);

  // Swipe-to-reply state
  const [replyingTo, setReplyingTo] = useState(null); // { id, text, isOutgoing }

  // Message edit state (10 min window)
  const [editingMessage, setEditingMessage] = useState(null); // msg object

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

  // Audio playback
  const [playingAudioId, setPlayingAudioId] = useState(null);
  const audioElementRef = useRef(null);

  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);
  const fileInputRef = useRef(null);

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
      if (audioElementRef.current) audioElementRef.current.pause();
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
  const togglePlayAudio = (msgId, base64Audio) => {
    if (playingAudioId === msgId) {
      audioElementRef.current?.pause();
      setPlayingAudioId(null);
      return;
    }
    if (audioElementRef.current) audioElementRef.current.pause();
    const tryPlay = (src) => {
      const audio = new Audio(src);
      audioElementRef.current = audio;
      audio.onended = () => setPlayingAudioId(null);
      audio.play().then(() => setPlayingAudioId(msgId)).catch(() => setPlayingAudioId(null));
    };
    const src = base64Audio.startsWith('data:') ? base64Audio : `data:audio/mp4;base64,${base64Audio}`;
    const a = new Audio(src);
    audioElementRef.current = a;
    a.onended = () => setPlayingAudioId(null);
    a.onerror = () => tryPlay(`data:audio/webm;base64,${base64Audio}`);
    a.play().then(() => setPlayingAudioId(msgId)).catch(() => tryPlay(`data:audio/webm;base64,${base64Audio}`));
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

  const avatarUrl = formatAvatarUrl(peerAvatar);
  const initial = (peerName || normPeer || '?')[0]?.toUpperCase() || '?';
  const isBlocked = webRtcEngine.isNumberBlocked ? webRtcEngine.isNumberBlocked(normPeer) : false;

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
          <div style={{
            width: 42, height: 42, borderRadius: '50%',
            backgroundColor: 'var(--secondary)', border: '2px solid #000',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontWeight: 900, fontSize: 18, overflow: 'hidden', flexShrink: 0
          }}>
            {avatarUrl ? (
              <img src={avatarUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            ) : initial}
          </div>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 900, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {peerName || normPeer}
            </div>
            <div style={{ fontSize: 12, fontWeight: 700 }}>
              {isTypingPeer
                ? <span style={{ color: '#00838f', fontStyle: 'italic' }}>typing...</span>
                : <span style={{ color: '#666', display: 'flex', alignItems: 'center', gap: 4 }}>
                    <Shield size={11} color="#00b4d8" /> E2E Encrypted
                  </span>}
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

                        {/* Audio */}
                        {msg.mediaType === 'AUDIO' && msg.mediaData && (
                          <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 180 }}>
                            <button onClick={() => togglePlayAudio(msg.id, msg.mediaData)} className="neo-box"
                              style={{ width: 36, height: 36, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
                                backgroundColor: playingAudioId === msg.id ? 'var(--primary)' : 'var(--accent)', cursor: 'pointer', borderRadius: '50%' }}>
                              {playingAudioId === msg.id ? <Pause size={18} /> : <Play size={18} style={{ marginLeft: 2 }} />}
                            </button>
                            <div style={{ flex: 1 }}>
                              <div style={{ height: 6, backgroundColor: '#ddd', borderRadius: 3, border: '1px solid #000', overflow: 'hidden' }}>
                                <div style={{
                                  height: '100%',
                                  backgroundColor: playingAudioId === msg.id ? 'var(--primary)' : '#00E5FF',
                                  width: playingAudioId === msg.id ? '100%' : '0%',
                                  transition: playingAudioId === msg.id ? 'width 10s linear' : 'none'
                                }} />
                              </div>
                              <div style={{ fontSize: 11, fontWeight: 800, marginTop: 2, color: '#555' }}>
                                🎤 {formatDur(msg.mediaDurationMs)}
                              </div>
                            </div>
                          </div>
                        )}

                        {/* Document */}
                        {msg.mediaType === 'DOCUMENT' && (
                          <a
                            href={msg.mediaData ? (msg.mediaData.startsWith('data:') ? msg.mediaData : `data:application/octet-stream;base64,${msg.mediaData}`) : '#'}
                            download={msg.text || 'document'}
                            className="neo-box"
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: 10,
                              padding: '8px 12px',
                              backgroundColor: '#fff',
                              textDecoration: 'none',
                              color: '#000',
                              borderRadius: 8,
                              marginBottom: 4,
                              border: '2px solid #000'
                            }}
                          >
                            <div style={{ width: 36, height: 36, borderRadius: 6, backgroundColor: '#5E35B1', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 800, fontSize: 11 }}>
                              {(msg.text || 'DOC').split('.').pop().toUpperCase().slice(0, 4)}
                            </div>
                            <div style={{ flex: 1, minWidth: 0 }}>
                              <div style={{ fontWeight: 800, fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                {msg.text || 'Document'}
                              </div>
                              <div style={{ fontSize: 10, fontWeight: 700, color: '#666' }}>Tap to download</div>
                            </div>
                          </a>
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
                      {isOut && !isDeleted && (
                        <button
                          type="button"
                          onClick={async (e) => {
                            e.stopPropagation();
                            if (window.confirm('Delete this message for everyone?')) {
                              try {
                                await chatRepositoryWeb.deleteMessageForEveryone(msg.id, normPeer);
                              } catch (err) {
                                alert(err.message || 'Failed to delete message');
                              }
                            }
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
                          title="Delete for everyone"
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
              <button type="button" onClick={() => fileInputRef.current?.click()} className="neo-box"
                style={{ width: 44, height: 44, padding: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: 'var(--accent)', cursor: 'pointer', flexShrink: 0 }}
                title="Attach Photo">
                <ImageIcon size={22} />
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
