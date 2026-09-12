/**
 * MediaStorageWeb.js
 *
 * High-capacity client-side binary storage powered by IndexedDB.
 * Handles documents (MP3s, PDFs, ZIPs, APKs) and large media up to hundreds of megabytes
 * without hitting the strict 5MB localStorage / sessionStorage browser quotas.
 */

const DB_NAME = 'lks_dialer_media_db';
const DB_VERSION = 1;
const STORE_MEDIA = 'media';
const STORE_CHUNKS = 'chunks';

class MediaStorageWeb {
  constructor() {
    this.dbPromise = this._initDb();
    // In-memory RAM buffer for lightning-fast chunk aggregation during active sessions
    this.chunkRamCache = new Map(); // key: parentMessageId -> Map(chunkIndex -> bytes)
    this.blobUrlCache = new Map(); // key: messageId -> blobUrl
  }

  _initDb() {
    if (typeof window === 'undefined' || !window.indexedDB) {
      return Promise.resolve(null);
    }
    return new Promise((resolve) => {
      try {
        const request = window.indexedDB.open(DB_NAME, DB_VERSION);
        request.onupgradeneeded = (e) => {
          const db = e.target.result;
          if (!db.objectStoreNames.contains(STORE_MEDIA)) {
            db.createObjectStore(STORE_MEDIA, { keyPath: 'id' });
          }
          if (!db.objectStoreNames.contains(STORE_CHUNKS)) {
            const chunkStore = db.createObjectStore(STORE_CHUNKS, { keyPath: 'key' });
            chunkStore.createIndex('parentMessageId', 'parentMessageId', { unique: false });
          }
        };
        request.onsuccess = (e) => resolve(e.target.result);
        request.onerror = (e) => {
          console.warn('[MediaStorageWeb] IndexedDB open error:', e);
          resolve(null);
        };
      } catch (err) {
        console.warn('[MediaStorageWeb] IndexedDB initialization failed:', err);
        resolve(null);
      }
    });
  }

  /**
   * Save an assembled media Blob (or ArrayBuffer/Base64)
   */
  async saveMedia(id, blobOrData, fileName = '', mimeType = 'application/octet-stream') {
    let blob = blobOrData;
    if (typeof blobOrData === 'string') {
      // Base64 string
      const clean = blobOrData.replace(/^data:.*?;base64,/, '').replace(/\s/g, '');
      const binary = atob(clean);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i++) {
        bytes[i] = binary.charCodeAt(i);
      }
      blob = new Blob([bytes], { type: mimeType });
    } else if (blobOrData instanceof ArrayBuffer || blobOrData instanceof Uint8Array) {
      blob = new Blob([blobOrData], { type: mimeType });
    }

    // Cache Blob URL
    if (blob instanceof Blob) {
      const url = URL.createObjectURL(blob);
      this.blobUrlCache.set(id, url);
    }

    const db = await this.dbPromise;
    if (!db) return blob;

    return new Promise((resolve) => {
      try {
        const tx = db.transaction(STORE_MEDIA, 'readwrite');
        const store = tx.objectStore(STORE_MEDIA);
        store.put({
          id,
          blob,
          fileName,
          mimeType,
          fileSize: blob.size,
          timestamp: Date.now()
        });
        tx.oncomplete = () => resolve(blob);
        tx.onerror = () => resolve(blob);
      } catch {
        resolve(blob);
      }
    });
  }

  /**
   * Retrieve saved media by id. Returns { blob, url, fileName, mimeType, fileSize }
   */
  async getMedia(id) {
    if (!id) return null;
    const cleanId = id.replace(/^idb:/, '');

    // Check memory URL cache first
    if (this.blobUrlCache.has(cleanId)) {
      const url = this.blobUrlCache.get(cleanId);
      return { url };
    }

    const db = await this.dbPromise;
    if (!db) return null;

    return new Promise((resolve) => {
      try {
        const tx = db.transaction(STORE_MEDIA, 'readonly');
        const store = tx.objectStore(STORE_MEDIA);
        const req = store.get(cleanId);
        req.onsuccess = () => {
          const item = req.result;
          if (item && item.blob) {
            const url = URL.createObjectURL(item.blob);
            this.blobUrlCache.set(cleanId, url);
            resolve({
              blob: item.blob,
              url,
              fileName: item.fileName,
              mimeType: item.mimeType,
              fileSize: item.fileSize
            });
          } else {
            resolve(null);
          }
        };
        req.onerror = () => resolve(null);
      } catch {
        resolve(null);
      }
    });
  }

  /**
   * Get a playable / downloadable Object URL for a message id
   */
  async getMediaUrl(id) {
    if (!id) return null;
    const cleanId = id.replace(/^idb:/, '');
    if (this.blobUrlCache.has(cleanId)) {
      return this.blobUrlCache.get(cleanId);
    }
    const media = await this.getMedia(cleanId);
    return media?.url || null;
  }

  /**
   * Store an incoming chunk (both in RAM buffer and IndexedDB)
   */
  async saveChunk(parentMessageId, chunkIndex, totalChunks, bytesBase64) {
    // 1. Save to RAM Map
    if (!this.chunkRamCache.has(parentMessageId)) {
      this.chunkRamCache.set(parentMessageId, new Map());
    }
    const ramMap = this.chunkRamCache.get(parentMessageId);
    ramMap.set(chunkIndex, bytesBase64);

    // 2. Persist to IndexedDB
    const db = await this.dbPromise;
    if (db) {
      try {
        const tx = db.transaction(STORE_CHUNKS, 'readwrite');
        const store = tx.objectStore(STORE_CHUNKS);
        store.put({
          key: `${parentMessageId}_${chunkIndex}`,
          parentMessageId,
          chunkIndex,
          totalChunks,
          bytes: bytesBase64,
          timestamp: Date.now()
        });
      } catch (e) {
        console.warn('[MediaStorageWeb] Chunk persist error:', e);
      }
    }
  }

  /**
   * Checks if all chunks have arrived. If yes, returns assembled Uint8Array.
   */
  async checkAndAssembleChunks(parentMessageId, totalChunks) {
    // 1. Check RAM buffer first
    let ramMap = this.chunkRamCache.get(parentMessageId);
    let allInRam = true;
    if (!ramMap || ramMap.size < totalChunks) {
      allInRam = false;
    } else {
      for (let i = 0; i < totalChunks; i++) {
        if (!ramMap.has(i)) {
          allInRam = false;
          break;
        }
      }
    }

    if (allInRam) {
      return this._assembleFromMap(ramMap, totalChunks);
    }

    // 2. Fallback to IndexedDB (in case of page reload or multi-tab)
    const db = await this.dbPromise;
    if (!db) return null;

    return new Promise((resolve) => {
      try {
        const tx = db.transaction(STORE_CHUNKS, 'readonly');
        const store = tx.objectStore(STORE_CHUNKS);
        const index = store.index('parentMessageId');
        const req = index.getAll(parentMessageId);
        req.onsuccess = () => {
          const records = req.result || [];
          const idbMap = new Map();
          records.forEach(r => idbMap.set(r.chunkIndex, r.bytes));

          for (let i = 0; i < totalChunks; i++) {
            if (!idbMap.has(i)) {
              resolve(null);
              return;
            }
          }
          resolve(this._assembleFromMap(idbMap, totalChunks));
        };
        req.onerror = () => resolve(null);
      } catch {
        resolve(null);
      }
    });
  }

  _assembleFromMap(map, totalChunks) {
    const byteArrays = [];
    let totalLength = 0;

    for (let i = 0; i < totalChunks; i++) {
      const b64 = map.get(i);
      const clean = b64.replace(/\s/g, '');
      const binary = atob(clean);
      const len = binary.length;
      const u8 = new Uint8Array(len);
      for (let j = 0; j < len; j++) {
        u8[j] = binary.charCodeAt(j);
      }
      byteArrays.push(u8);
      totalLength += len;
    }

    // Concatenate into single contiguous Uint8Array
    const result = new Uint8Array(totalLength);
    let offset = 0;
    for (const arr of byteArrays) {
      result.set(arr, offset);
      offset += arr.length;
    }
    return result;
  }

  /**
   * Delete temporary chunks once assembly is complete
   */
  async clearChunks(parentMessageId) {
    this.chunkRamCache.delete(parentMessageId);
    const db = await this.dbPromise;
    if (!db) return;

    try {
      const tx = db.transaction(STORE_CHUNKS, 'readwrite');
      const store = tx.objectStore(STORE_CHUNKS);
      const index = store.index('parentMessageId');
      const req = index.getAllKeys(parentMessageId);
      req.onsuccess = () => {
        const keys = req.result || [];
        keys.forEach(k => store.delete(k));
      };
    } catch {}
  }
}

export const mediaStorageWeb = new MediaStorageWeb();
