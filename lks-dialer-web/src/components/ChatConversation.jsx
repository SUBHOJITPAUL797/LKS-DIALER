import React, { useState, useEffect, useRef } from 'react';
import { 
  ArrowLeft, Phone, Video, MoreVertical, Send, Image as ImageIcon, 
  Mic, Square, Trash2, Check, CheckCheck, Play, Pause, X, Shield, Ban 
} from 'lucide-react';
import { chatRepositoryWeb, normalizePhoneNumber, numbersMatch } from '../lib/ChatRepositoryWeb';
import { webRtcEngine } from '../lib/WebRtcEngine';
import { formatAvatarUrl } from '../lib/ImageUtils';

export default function ChatConversation({ peerNumber, peerName, peerAvatar, onBack, onStartCall }) {
  const normPeer = normalizePhoneNumber(peerNumber);
  const [messages, setMessages] = useState([]);
  const [inputText, setInputText] = useState('');
  const [isTypingPeer, setIsTypingPeer] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [selectedImageModal, setSelectedImageModal] = useState(null);
  const [sending, setSending] = useState(false);

  // Audio recording state
  const [isRecording, setIsRecording] = useState(false);
  const [recordSeconds, setRecordSeconds] = useState(0);
  const mediaRecorderRef = useRef(null);
  const audioChunksRef = useRef([]);
  const recordingTimerRef = useRef(null);

  // Audio message playback state: { [messageId]: { isPlaying: boolean, currentTime: number, duration: number } }
  const [playingAudioId, setPlayingAudioId] = useState(null);
  const audioElementRef = useRef(null);

  const messagesEndRef = useRef(null);
  const fileInputRef = useRef(null);

  const scrollToBottom = (behavior = 'smooth') => {
    messagesEndRef.current?.scrollIntoView({ behavior });
  };

  useEffect(() => {
    // Set active chat peer (resets unread and sends READ ack)
    chatRepositoryWeb.setActiveChatPeer(normPeer);

    const updateMessages = () => {
      const msgs = chatRepositoryWeb.getMessages(normPeer);
      setMessages(msgs);
      setIsTypingPeer(Boolean(chatRepositoryWeb.typingStatus[normPeer]));
    };

    updateMessages();
    setTimeout(() => scrollToBottom('auto'), 100);

    const unsubscribe = chatRepositoryWeb.subscribe(updateMessages);

    return () => {
      chatRepositoryWeb.setActiveChatPeer(null);
      chatRepositoryWeb.setTyping(normPeer, false);
      unsubscribe();
      if (audioElementRef.current) {
        audioElementRef.current.pause();
      }
      if (recordingTimerRef.current) {
        clearInterval(recordingTimerRef.current);
      }
      if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
        try { mediaRecorderRef.current.stop(); } catch {}
      }
    };
  }, [normPeer]);

  useEffect(() => {
    scrollToBottom('smooth');
  }, [messages.length, isTypingPeer]);

  // Handle typing input
  const handleInputChange = (e) => {
    const text = e.target.value;
    setInputText(text);
    chatRepositoryWeb.setTyping(normPeer, text.length > 0);
  };

  // SEND TEXT MESSAGE
  const handleSendText = async (e) => {
    if (e) e.preventDefault();
    const trimmed = inputText.trim();
    if (!trimmed || sending) return;

    setSending(true);
    setInputText('');
    chatRepositoryWeb.setTyping(normPeer, false);

    try {
      await chatRepositoryWeb.sendMessage(
        normPeer,
        peerName || normPeer,
        trimmed,
        'TEXT'
      );
    } catch (err) {
      alert(err.message || 'Failed to send message');
    } finally {
      setSending(false);
      setTimeout(() => scrollToBottom('smooth'), 50);
    }
  };

  // SEND IMAGE
  const handleImageSelected = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const img = new Image();
      img.onload = async () => {
        // Compress image using canvas
        const canvas = document.createElement('canvas');
        const MAX_WIDTH = 1200;
        const MAX_HEIGHT = 1200;
        let width = img.width;
        let height = img.height;

        if (width > height) {
          if (width > MAX_WIDTH) {
            height = Math.round((height * MAX_WIDTH) / width);
            width = MAX_WIDTH;
          }
        } else {
          if (height > MAX_HEIGHT) {
            width = Math.round((width * MAX_HEIGHT) / height);
            height = MAX_HEIGHT;
          }
        }

        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, width, height);

        // Quality 0.75 JPEG
        const base64Data = canvas.toDataURL('image/jpeg', 0.75);
        // Strip data prefix if sending raw bytes (matches Android Base64)
        const rawBase64 = base64Data.replace(/^data:image\/[a-z]+;base64,/, '');

        try {
          await chatRepositoryWeb.sendMessage(
            normPeer,
            peerName || normPeer,
            '',
            'IMAGE',
            rawBase64
          );
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

  // START AUDIO RECORDING
  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioChunksRef.current = [];
      const mediaRecorder = new MediaRecorder(stream);
      mediaRecorderRef.current = mediaRecorder;

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          audioChunksRef.current.push(event.data);
        }
      };

      mediaRecorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
      };

      mediaRecorder.start();
      setIsRecording(true);
      setRecordSeconds(0);
      recordingTimerRef.current = setInterval(() => {
        setRecordSeconds(s => s + 1);
      }, 1000);
    } catch (err) {
      console.error('Microphone access denied:', err);
      alert('Microphone permission is required to record voice messages.');
    }
  };

  // STOP AND SEND AUDIO RECORDING
  const stopAndSendRecording = () => {
    if (!mediaRecorderRef.current || !isRecording) return;
    const durationMs = recordSeconds * 1000;

    clearInterval(recordingTimerRef.current);
    const recorder = mediaRecorderRef.current;

    recorder.onstop = async () => {
      const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/webm' });
      const reader = new FileReader();
      reader.onload = async () => {
        const base64Data = reader.result.replace(/^data:audio\/[a-z0-9]+;base64,/, '');
        try {
          await chatRepositoryWeb.sendMessage(
            normPeer,
            peerName || normPeer,
            'Voice message',
            'AUDIO',
            base64Data,
            durationMs
          );
        } catch (err) {
          alert(err.message || 'Failed to send voice message');
        }
      };
      reader.readAsDataURL(audioBlob);
    };

    recorder.stop();
    setIsRecording(false);
    setRecordSeconds(0);
  };

  // CANCEL AUDIO RECORDING
  const cancelRecording = () => {
    if (recordingTimerRef.current) clearInterval(recordingTimerRef.current);
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
      mediaRecorderRef.current.stop();
    }
    audioChunksRef.current = [];
    setIsRecording(false);
    setRecordSeconds(0);
  };

  // PLAY AUDIO MESSAGE
  const togglePlayAudio = (msgId, base64Audio) => {
    if (playingAudioId === msgId) {
      audioElementRef.current?.pause();
      setPlayingAudioId(null);
      return;
    }

    if (audioElementRef.current) {
      audioElementRef.current.pause();
    }

    const audioSrc = base64Audio.startsWith('data:') 
      ? base64Audio 
      : `data:audio/mp4;base64,${base64Audio}`;

    const audio = new Audio(audioSrc);
    audioElementRef.current = audio;

    audio.onended = () => {
      setPlayingAudioId(null);
    };

    audio.onerror = () => {
      // Fallback to webm mime type
      const webmSrc = `data:audio/webm;base64,${base64Audio}`;
      const fallbackAudio = new Audio(webmSrc);
      audioElementRef.current = fallbackAudio;
      fallbackAudio.onended = () => setPlayingAudioId(null);
      fallbackAudio.play().catch(e => {
        console.error('Audio playback failed:', e);
        setPlayingAudioId(null);
      });
    };

    audio.play().then(() => {
      setPlayingAudioId(msgId);
    }).catch(e => {
      console.error('Audio play error:', e);
      setPlayingAudioId(null);
    });
  };

  const formatAudioDuration = (ms) => {
    const totalSecs = Math.max(1, Math.round(ms / 1000));
    const mins = Math.floor(totalSecs / 60);
    const secs = totalSecs % 60;
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
  };

  const renderStatusTicks = (status) => {
    switch (status) {
      case 'READ':
        return <CheckCheck size={14} color="#00C9FF" strokeWidth={2.5} style={{ marginLeft: '4px' }} />;
      case 'DELIVERED':
        return <CheckCheck size={14} color="#555" strokeWidth={2} style={{ marginLeft: '4px' }} />;
      case 'SENT':
        return <Check size={14} color="#555" strokeWidth={2} style={{ marginLeft: '4px' }} />;
      case 'FAILED':
        return <span style={{ color: '#ff3366', fontWeight: '900', marginLeft: '4px', fontSize: '11px' }}>!</span>;
      default:
        return null;
    }
  };

  const avatarUrl = formatAvatarUrl(peerAvatar);
  const initial = (peerName || normPeer || '?')[0]?.toUpperCase() || '?';

  const isPeerBlocked = webRtcEngine.isNumberBlocked ? webRtcEngine.isNumberBlocked(normPeer) : false;

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', height: '100%',
      backgroundColor: 'var(--bg-color)', position: 'relative'
    }}>
      {/* CONVERSATION TOP BAR */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '10px 14px', backgroundColor: '#fff',
        borderBottom: '4px solid #000', zIndex: 10
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flex: 1, minWidth: 0 }}>
          <button
            onClick={onBack}
            className="neo-box"
            style={{
              width: '36px', height: '36px', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer', backgroundColor: 'var(--accent)'
            }}
          >
            <ArrowLeft size={20} color="#000" strokeWidth={3} />
          </button>

          {/* Peer Avatar */}
          <div style={{
            width: '42px', height: '42px', borderRadius: '50%',
            backgroundColor: 'var(--secondary)', border: '2px solid #000',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontWeight: '900', fontSize: '18px', overflow: 'hidden', flexShrink: 0
          }}>
            {avatarUrl ? (
              <img src={avatarUrl} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            ) : initial}
          </div>

          {/* Name & Status */}
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{
              fontSize: '16px', fontWeight: '900',
              whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'
            }}>
              {peerName || normPeer}
            </div>
            <div style={{ fontSize: '12px', fontWeight: '700' }}>
              {isTypingPeer ? (
                <span style={{ color: '#00838f', fontStyle: 'italic' }}>typing...</span>
              ) : (
                <span style={{ color: '#666', display: 'flex', alignItems: 'center', gap: '4px' }}>
                  <Shield size={11} color="#00b4d8" /> E2E Encrypted
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Action Buttons */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <button
            onClick={() => onStartCall && onStartCall(normPeer, 'AUDIO')}
            className="neo-box"
            style={{
              width: '36px', height: '36px', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              backgroundColor: 'var(--accent)', cursor: 'pointer'
            }}
            title="Audio Call"
          >
            <Phone size={18} color="#000" />
          </button>
          <button
            onClick={() => onStartCall && onStartCall(normPeer, 'VIDEO')}
            className="neo-box"
            style={{
              width: '36px', height: '36px', padding: 0,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              backgroundColor: 'var(--primary)', cursor: 'pointer'
            }}
            title="Video Call"
          >
            <Video size={18} color="#fff" />
          </button>

          {/* Options Menu Toggle */}
          <div style={{ position: 'relative' }}>
            <button
              onClick={() => setMenuOpen(!menuOpen)}
              className="neo-box"
              style={{
                width: '36px', height: '36px', padding: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                backgroundColor: '#fff', cursor: 'pointer'
              }}
            >
              <MoreVertical size={18} color="#000" />
            </button>

            {menuOpen && (
              <div className="neo-box" style={{
                position: 'absolute', right: 0, top: '44px', width: '160px',
                backgroundColor: '#fff', padding: '6px', zIndex: 50,
                display: 'flex', flexDirection: 'column', gap: '4px'
              }}>
                <button
                  onClick={() => {
                    setMenuOpen(false);
                    if (confirm('Clear all messages for this chat? This cannot be undone.')) {
                      chatRepositoryWeb.clearChat(normPeer);
                    }
                  }}
                  style={{
                    border: 'none', background: 'none', padding: '8px 10px',
                    textAlign: 'left', fontWeight: '800', fontSize: '13px',
                    cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px'
                  }}
                >
                  <Trash2 size={16} /> Clear Chat
                </button>
                <button
                  onClick={async () => {
                    setMenuOpen(false);
                    if (isPeerBlocked) {
                      await webRtcEngine.unblockNumber(normPeer);
                      alert(`Unblocked ${normPeer}`);
                    } else {
                      if (confirm(`Block ${normPeer}? Calls and messages from this number will be rejected.`)) {
                        await webRtcEngine.blockNumber(normPeer);
                        alert(`Blocked ${normPeer}`);
                      }
                    }
                  }}
                  style={{
                    border: 'none', background: 'none', padding: '8px 10px',
                    textAlign: 'left', fontWeight: '800', fontSize: '13px',
                    cursor: 'pointer', color: '#ff3366', display: 'flex', alignItems: 'center', gap: '8px'
                  }}
                >
                  <Ban size={16} /> {isPeerBlocked ? 'Unblock Contact' : 'Block Contact'}
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* MESSAGES SCROLLABLE CONTAINER */}
      <div style={{
        flex: 1, overflowY: 'auto', padding: '16px',
        display: 'flex', flexDirection: 'column', gap: '10px'
      }}>
        {/* Security E2EE Notice Banner */}
        <div style={{
          backgroundColor: '#fffbe6', border: '2px dashed #000',
          borderRadius: '8px', padding: '8px 12px', textAlign: 'center',
          fontSize: '11px', fontWeight: '700', color: '#555', margin: '0 auto 8px auto',
          maxWidth: '380px'
        }}>
          🔒 Messages & calls are end-to-end encrypted. Ephemeral relay automatically deletes messages from server after delivery.
        </div>

        {messages.length === 0 ? (
          <div style={{ textAlign: 'center', margin: 'auto 0', color: '#888', fontWeight: '700', fontSize: '14px' }}>
            Say hello! Send an encrypted message below.
          </div>
        ) : (
          messages.map((msg) => {
            const isOut = Boolean(msg.isOutgoing);
            const timeStr = msg.timestamp ? new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';

            return (
              <div
                key={msg.id}
                style={{
                  alignSelf: isOut ? 'flex-end' : 'flex-start',
                  maxWidth: '82%',
                  display: 'flex',
                  flexDirection: 'column'
                }}
              >
                <div
                  className="neo-box"
                  style={{
                    backgroundColor: isOut ? '#d4fcd4' : '#ffffff',
                    padding: '8px 12px',
                    borderRadius: '12px',
                    boxShadow: '3px 3px 0 #000',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '4px'
                  }}
                >
                  {/* Image Attachment */}
                  {msg.mediaType === 'IMAGE' && msg.mediaData && (
                    <div style={{ marginBottom: '4px' }}>
                      <img
                        src={msg.mediaData.startsWith('data:') ? msg.mediaData : `data:image/jpeg;base64,${msg.mediaData}`}
                        alt="Photo"
                        onClick={() => setSelectedImageModal(msg.mediaData)}
                        style={{
                          width: '100%', maxHeight: '240px', objectFit: 'cover',
                          borderRadius: '8px', border: '2px solid #000', cursor: 'pointer'
                        }}
                      />
                    </div>
                  )}

                  {/* Audio Message (Voice Note) */}
                  {msg.mediaType === 'AUDIO' && msg.mediaData && (
                    <div style={{
                      display: 'flex', alignItems: 'center', gap: '10px',
                      padding: '4px 0', minWidth: '180px'
                    }}>
                      <button
                        onClick={() => togglePlayAudio(msg.id, msg.mediaData)}
                        className="neo-box"
                        style={{
                          width: '36px', height: '36px', padding: 0,
                          display: 'flex', alignItems: 'center', justifyContent: 'center',
                          backgroundColor: playingAudioId === msg.id ? 'var(--primary)' : 'var(--accent)',
                          cursor: 'pointer', borderRadius: '50%'
                        }}
                      >
                        {playingAudioId === msg.id ? (
                          <Pause size={18} color="#000" />
                        ) : (
                          <Play size={18} color="#000" style={{ marginLeft: '2px' }} />
                        )}
                      </button>
                      <div style={{ flex: 1 }}>
                        <div style={{
                          height: '6px', backgroundColor: '#ddd', borderRadius: '3px',
                          border: '1px solid #000', overflow: 'hidden'
                        }}>
                          <div style={{
                            height: '100%',
                            backgroundColor: playingAudioId === msg.id ? 'var(--primary)' : '#00E5FF',
                            width: playingAudioId === msg.id ? '100%' : '0%',
                            transition: playingAudioId === msg.id ? 'width 10s linear' : 'none'
                          }} />
                        </div>
                        <div style={{ fontSize: '11px', fontWeight: '800', marginTop: '2px', color: '#555' }}>
                          🎤 Voice message • {formatAudioDuration(msg.mediaDurationMs || 0)}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Text Content */}
                  {msg.text && (
                    <div style={{
                      fontSize: '15px', fontWeight: '600', color: '#000',
                      wordBreak: 'break-word', whiteSpace: 'pre-wrap'
                    }}>
                      {msg.text}
                    </div>
                  )}

                  {/* Bubble Footer: Timestamp & Ticks */}
                  <div style={{
                    alignSelf: 'flex-end', display: 'flex', alignItems: 'center',
                    fontSize: '11px', fontWeight: '700', color: '#555', marginTop: '2px'
                  }}>
                    <span>{timeStr}</span>
                    {isOut && renderStatusTicks(msg.status)}
                  </div>
                </div>
              </div>
            );
          })
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* INPUT / RECORDING BAR */}
      <div style={{
        padding: '12px 14px', backgroundColor: '#fff',
        borderTop: '4px solid #000', zIndex: 10
      }}>
        {isRecording ? (
          /* ACTIVE VOICE RECORDING BAR */
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <div style={{
                width: '12px', height: '12px', borderRadius: '50%',
                backgroundColor: '#ff3366', animation: 'pulse 1s infinite'
              }} />
              <span style={{ fontWeight: '900', fontSize: '15px', color: '#ff3366' }}>
                Recording... {Math.floor(recordSeconds / 60)}:{(recordSeconds % 60) < 10 ? '0' : ''}{recordSeconds % 60}
              </span>
            </div>

            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                onClick={cancelRecording}
                className="neo-box"
                style={{
                  padding: '8px 12px', backgroundColor: '#fff',
                  cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '6px',
                  fontWeight: '800', fontSize: '13px'
                }}
              >
                <Trash2 size={16} color="#ff3366" /> Cancel
              </button>
              <button
                onClick={stopAndSendRecording}
                className="neo-box"
                style={{
                  padding: '8px 16px', backgroundColor: '#00E5FF',
                  cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '6px',
                  fontWeight: '900', fontSize: '13px'
                }}
              >
                <Send size={16} color="#000" /> Send
              </button>
            </div>
          </div>
        ) : (
          /* STANDARD TEXT / MEDIA INPUT BAR */
          <form onSubmit={handleSendText} style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            {/* Hidden image input */}
            <input
              type="file"
              accept="image/*"
              ref={fileInputRef}
              style={{ display: 'none' }}
              onChange={handleImageSelected}
            />

            {/* Photo Attachment Button */}
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="neo-box"
              style={{
                width: '42px', height: '42px', padding: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                backgroundColor: 'var(--accent)', cursor: 'pointer', flexShrink: 0
              }}
              title="Attach Photo"
            >
              <ImageIcon size={20} color="#000" />
            </button>

            {/* Voice Note Record Button */}
            <button
              type="button"
              onClick={startRecording}
              className="neo-box"
              style={{
                width: '42px', height: '42px', padding: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                backgroundColor: '#fff', cursor: 'pointer', flexShrink: 0
              }}
              title="Record Voice Note"
            >
              <Mic size={20} color="#000" />
            </button>

            {/* Message Input */}
            <input
              type="text"
              className="neo-input"
              placeholder="Encrypted message..."
              value={inputText}
              onChange={handleInputChange}
              style={{ flex: 1, padding: '10px 14px', fontSize: '15px' }}
            />

            {/* Send Button */}
            <button
              type="submit"
              disabled={!inputText.trim() || sending}
              className="neo-btn"
              style={{
                width: '44px', height: '44px', padding: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                backgroundColor: inputText.trim() ? 'var(--primary)' : '#ccc',
                cursor: inputText.trim() ? 'pointer' : 'default',
                flexShrink: 0
              }}
            >
              <Send size={18} color="#fff" />
            </button>
          </form>
        )}
      </div>

      {/* FULLSCREEN IMAGE LIGHTBOX MODAL */}
      {selectedImageModal && (
        <div
          onClick={() => setSelectedImageModal(null)}
          style={{
            position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.9)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            zIndex: 1000, padding: '20px'
          }}
        >
          <div style={{ position: 'relative', maxWidth: '90vw', maxHeight: '90vh' }}>
            <button
              onClick={() => setSelectedImageModal(null)}
              className="neo-box"
              style={{
                position: 'absolute', top: '-16px', right: '-16px',
                width: '36px', height: '36px', padding: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                backgroundColor: '#fff', cursor: 'pointer', zIndex: 10
              }}
            >
              <X size={20} />
            </button>
            <img
              src={selectedImageModal.startsWith('data:') ? selectedImageModal : `data:image/jpeg;base64,${selectedImageModal}`}
              alt="Enlarged view"
              style={{ maxWidth: '100%', maxHeight: '85vh', objectFit: 'contain', border: '3px solid #000' }}
            />
          </div>
        </div>
      )}
    </div>
  );
}
