import React, { useState, useMemo } from 'react';
import { X, Image as ImageIcon, FileText, ExternalLink, Download, Play, Calendar, Search } from 'lucide-react';
import { extractGifUrlWeb } from '../lib/ChatRepositoryWeb';

function formatFileSize(bytes) {
  if (!bytes || bytes <= 0) return '';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

function formatDate(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  return d.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
}

function isVideoFile(fileName) {
  if (!fileName) return false;
  const ext = fileName.toLowerCase().split('.').pop();
  return ['mp4', 'mov', 'webm', 'mkv', 'avi', '3gp'].includes(ext);
}

function extractLinksFromText(text) {
  if (!text) return [];
  const urlRegex = /(https?:\/\/[^\s]+)/gi;
  const matches = text.match(urlRegex);
  return matches || [];
}

export default function ChatMediaGalleryModal({
  isOpen,
  onClose,
  messages = [],
  peerName = 'Contact',
  onSelectImage,
  onOpenVideo,
  onDownloadDocument
}) {
  const [activeTab, setActiveTab] = useState('media'); // 'media' | 'docs' | 'links'
  const [filterQuery, setFilterQuery] = useState('');

  // 1. Media (Images, GIFs, Videos)
  const mediaItems = useMemo(() => {
    return messages.filter(m => {
      if (m.isDeleted) return false;
      if (m.mediaType === 'IMAGE') return true;
      if (m.mediaType === 'DOCUMENT' && isVideoFile(m.fileName || m.text)) return true;
      if (m.text && (m.text.startsWith('[gif:') || (m.text.startsWith('http') && (m.text.includes('.gif') || m.text.includes('.png') || m.text.includes('.jpg'))))) return true;
      return false;
    });
  }, [messages]);

  // 2. Documents
  const docItems = useMemo(() => {
    return messages.filter(m => {
      if (m.isDeleted) return false;
      if (m.mediaType === 'DOCUMENT' && !isVideoFile(m.fileName || m.text)) return true;
      return false;
    });
  }, [messages]);

  // 3. Links
  const linkItems = useMemo(() => {
    const list = [];
    messages.forEach(m => {
      if (m.isDeleted) return;
      // Skip pure GIF markers
      if (m.text && m.text.startsWith('[gif:')) return;
      const urls = extractLinksFromText(m.text);
      urls.forEach(url => {
        let domain = '';
        try {
          domain = new URL(url).hostname.replace('www.', '');
        } catch {
          domain = url;
        }
        list.push({
          id: `${m.id}-${url}`,
          url,
          domain,
          text: m.text,
          timestamp: m.timestamp,
          isOutgoing: m.isOutgoing
        });
      });
    });
    return list;
  }, [messages]);

  if (!isOpen) return null;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.65)',
        backdropFilter: 'blur(5px)',
        zIndex: 9999,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '16px',
        animation: 'fadeIn 0.2s ease-out'
      }}
      onClick={onClose}
    >
      <div
        className="neo-box"
        style={{
          width: '100%',
          maxWidth: 680,
          maxHeight: '85vh',
          backgroundColor: '#fff',
          borderRadius: 16,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          boxShadow: '8px 8px 0px #000',
          border: '4px solid #000'
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Modal Header */}
        <div style={{
          padding: '14px 20px',
          borderBottom: '3px solid #000',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          backgroundColor: '#f8fafc'
        }}>
          <div>
            <h3 style={{ margin: 0, fontSize: 18, fontWeight: 900, color: '#0f172a' }}>
              Media, Links and Docs
            </h3>
            <span style={{ fontSize: 13, fontWeight: 700, color: '#64748b' }}>
              {peerName}
            </span>
          </div>
          <button
            onClick={onClose}
            className="neo-box"
            style={{
              width: 36,
              height: 36,
              padding: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: '#fee2e2',
              cursor: 'pointer'
            }}
            title="Close"
          >
            <X size={18} color="#ef4444" />
          </button>
        </div>

        {/* Tabs Bar */}
        <div style={{
          display: 'flex',
          borderBottom: '3px solid #000',
          backgroundColor: '#f1f5f9'
        }}>
          <button
            onClick={() => setActiveTab('media')}
            style={{
              flex: 1,
              padding: '12px 16px',
              border: 'none',
              borderBottom: activeTab === 'media' ? '4px solid #00C9FF' : '4px solid transparent',
              backgroundColor: activeTab === 'media' ? '#fff' : 'transparent',
              fontWeight: 900,
              fontSize: 14,
              color: activeTab === 'media' ? '#0284c7' : '#64748b',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
              transition: 'all 0.15s ease'
            }}
          >
            <ImageIcon size={17} />
            <span>Media ({mediaItems.length})</span>
          </button>
          <button
            onClick={() => setActiveTab('docs')}
            style={{
              flex: 1,
              padding: '12px 16px',
              border: 'none',
              borderBottom: activeTab === 'docs' ? '4px solid #8B5CF6' : '4px solid transparent',
              backgroundColor: activeTab === 'docs' ? '#fff' : 'transparent',
              fontWeight: 900,
              fontSize: 14,
              color: activeTab === 'docs' ? '#7c3aed' : '#64748b',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
              transition: 'all 0.15s ease'
            }}
          >
            <FileText size={17} />
            <span>Docs ({docItems.length})</span>
          </button>
          <button
            onClick={() => setActiveTab('links')}
            style={{
              flex: 1,
              padding: '12px 16px',
              border: 'none',
              borderBottom: activeTab === 'links' ? '4px solid #10B981' : '4px solid transparent',
              backgroundColor: activeTab === 'links' ? '#fff' : 'transparent',
              fontWeight: 900,
              fontSize: 14,
              color: activeTab === 'links' ? '#059669' : '#64748b',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 8,
              transition: 'all 0.15s ease'
            }}
          >
            <ExternalLink size={17} />
            <span>Links ({linkItems.length})</span>
          </button>
        </div>

        {/* Tab Content Body */}
        <div style={{
          flex: 1,
          overflowY: 'auto',
          padding: 16,
          minHeight: 320,
          backgroundColor: '#fafafa'
        }}>
          {/* ─── TAB 1: MEDIA ─── */}
          {activeTab === 'media' && (
            <div>
              {mediaItems.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '60px 20px', color: '#94a3b8' }}>
                  <ImageIcon size={48} strokeWidth={1.5} style={{ margin: '0 auto 12px', opacity: 0.6 }} />
                  <div style={{ fontWeight: 800, fontSize: 16 }}>No photos or videos yet</div>
                  <div style={{ fontSize: 13, marginTop: 4 }}>Photos and videos shared in this chat will appear here.</div>
                </div>
              ) : (
                <div style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fill, minmax(130px, 1fr))',
                  gap: 10
                }}>
                  {mediaItems.map((msg) => {
                    let src = msg.mediaUrl || (msg.mediaData?.startsWith('data:') ? msg.mediaData : null);
                    if (!src && msg.text) src = extractGifUrlWeb(msg.text);
                    if (!src && msg.mediaData) src = `data:image/jpeg;base64,${msg.mediaData}`;
                    const isVid = isVideoFile(msg.fileName || msg.text);

                    return (
                      <div
                        key={msg.id}
                        onClick={() => {
                          if (isVid) {
                            onOpenVideo?.(null, msg);
                          } else if (src) {
                            onSelectImage?.(src);
                          }
                        }}
                        className="neo-box"
                        style={{
                          aspectRatio: '1 / 1',
                          borderRadius: 10,
                          overflow: 'hidden',
                          cursor: 'pointer',
                          position: 'relative',
                          backgroundColor: '#e2e8f0',
                          border: '2px solid #000'
                        }}
                        title={msg.fileName || msg.text || 'View media'}
                      >
                        {src && !isVid ? (
                          <img
                            src={src}
                            alt=""
                            style={{
                              width: '100%',
                              height: '100%',
                              objectFit: 'cover',
                              display: 'block'
                            }}
                            loading="lazy"
                          />
                        ) : (
                          <div style={{
                            width: '100%',
                            height: '100%',
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'center',
                            justifyContent: 'center',
                            backgroundColor: '#1e293b',
                            color: '#fff'
                          }}>
                            <Play size={28} fill="#fff" />
                            <span style={{ fontSize: 11, fontWeight: 800, marginTop: 4 }}>Video</span>
                          </div>
                        )}
                        {/* Overlay with date */}
                        <div style={{
                          position: 'absolute',
                          bottom: 0,
                          left: 0,
                          right: 0,
                          padding: '4px 6px',
                          background: 'linear-gradient(transparent, rgba(0,0,0,0.75))',
                          color: '#fff',
                          fontSize: 10,
                          fontWeight: 700,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between'
                        }}>
                          <span>{formatDate(msg.timestamp)}</span>
                          {isVid && <Play size={10} fill="#fff" />}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {/* ─── TAB 2: DOCS ─── */}
          {activeTab === 'docs' && (
            <div>
              {docItems.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '60px 20px', color: '#94a3b8' }}>
                  <FileText size={48} strokeWidth={1.5} style={{ margin: '0 auto 12px', opacity: 0.6 }} />
                  <div style={{ fontWeight: 800, fontSize: 16 }}>No documents yet</div>
                  <div style={{ fontSize: 13, marginTop: 4 }}>PDFs, spreadsheets, and files shared in this chat will appear here.</div>
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {docItems.map((msg) => {
                    const ext = ((msg.fileName || msg.text || 'DOC').split('.').pop() || 'DOC').toUpperCase().slice(0, 4);
                    return (
                      <div
                        key={msg.id}
                        onClick={(e) => onDownloadDocument?.(e, msg)}
                        className="neo-box"
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 12,
                          padding: '12px 14px',
                          backgroundColor: '#fff',
                          borderRadius: 10,
                          border: '2px solid #000',
                          cursor: 'pointer'
                        }}
                      >
                        <div style={{
                          width: 44,
                          height: 44,
                          borderRadius: 8,
                          backgroundColor: '#5E35B1',
                          color: '#fff',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontWeight: 900,
                          fontSize: 12,
                          flexShrink: 0
                        }}>
                          {ext}
                        </div>
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{
                            fontWeight: 800,
                            fontSize: 14,
                            color: '#0f172a',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap'
                          }}>
                            {msg.fileName || msg.text || 'Document'}
                          </div>
                          <div style={{ fontSize: 12, fontWeight: 700, color: '#64748b', marginTop: 2 }}>
                            {formatFileSize(msg.fileSize)}
                            {msg.fileSize ? ' • ' : ''}
                            {formatDate(msg.timestamp)}
                          </div>
                        </div>
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            onDownloadDocument?.(e, msg);
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
                            cursor: 'pointer',
                            padding: 0,
                            flexShrink: 0
                          }}
                          title="Download document"
                        >
                          <Download size={16} color="#000" />
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {/* ─── TAB 3: LINKS ─── */}
          {activeTab === 'links' && (
            <div>
              {linkItems.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '60px 20px', color: '#94a3b8' }}>
                  <ExternalLink size={48} strokeWidth={1.5} style={{ margin: '0 auto 12px', opacity: 0.6 }} />
                  <div style={{ fontWeight: 800, fontSize: 16 }}>No links shared yet</div>
                  <div style={{ fontSize: 13, marginTop: 4 }}>Web links shared in messages will be gathered here.</div>
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {linkItems.map((link) => (
                    <a
                      key={link.id}
                      href={link.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="neo-box"
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 12,
                        padding: '12px 14px',
                        backgroundColor: '#fff',
                        borderRadius: 10,
                        border: '2px solid #000',
                        textDecoration: 'none',
                        color: 'inherit'
                      }}
                    >
                      <div style={{
                        width: 44,
                        height: 44,
                        borderRadius: 8,
                        backgroundColor: '#ecfdf5',
                        border: '2px solid #10b981',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        flexShrink: 0
                      }}>
                        <ExternalLink size={20} color="#059669" />
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{
                          fontWeight: 900,
                          fontSize: 13,
                          color: '#0284c7',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap'
                        }}>
                          {link.url}
                        </div>
                        <div style={{
                          fontSize: 12,
                          fontWeight: 700,
                          color: '#64748b',
                          marginTop: 3,
                          display: 'flex',
                          alignItems: 'center',
                          gap: 6
                        }}>
                          <span style={{
                            backgroundColor: '#f1f5f9',
                            padding: '1px 6px',
                            borderRadius: 4,
                            border: '1px solid #cbd5e1'
                          }}>
                            {link.domain}
                          </span>
                          <span>•</span>
                          <span>{formatDate(link.timestamp)}</span>
                        </div>
                      </div>
                    </a>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
