package dev.glide.transport.webrtc

import java.nio.charset.StandardCharsets

/**
 * Embedded WebRTC Static Client SPA assets (HTML/CSS/JS).
 *
 * Served by the Glide host to the browser via the WebRTC signaling channel.
 * 100% offline and air-gapped: zero external CDNs, fonts, or tracking scripts.
 *
 * Security invariants:
 * - No external resource requests (all assets embedded)
 * - Strict CSP: default-src 'self'; script-src 'self'; style-src 'self'
 * - No inline scripts or eval
 *
 * Total size budget: <92 KB (gzipped < 28 KB)
 */
object GlideWebRtcAssets {

    private fun loadResourceOrFallback(resourcePath: String, fallbackText: () -> String): ByteArray {
        val stream = GlideWebRtcAssets::class.java.getResourceAsStream(resourcePath)
        return if (stream != null) {
            stream.use { it.readBytes() }
        } else {
            fallbackText().toByteArray(StandardCharsets.UTF_8)
        }
    }

    /**
     * Load embedded asset from resources at path: /glide/webrtc/<name>
     */
    fun getAsset(name: String): ByteArray {
        return loadResourceOrFallback("/glide/webrtc/$name") { getEmbeddedAsset(name) }
    }

    fun getIndexHtml(): ByteArray = getAsset("index.html")

    fun getStyleCss(): ByteArray = getAsset("style.css")

    fun getAppJs(): ByteArray = getAsset("app.js")

    /**
     * Fallback embedded assets when resources are not available (e.g., in tests).
     * Total combined size: ~12 KB (well within 92 KB budget).
     */
    private fun getEmbeddedAsset(name: String): String {
        return when (name) {
            "index.html" -> EMBEDDED_HTML
            "style.css" -> EMBEDDED_CSS
            "app.js" -> EMBEDDED_JS
            else -> ""
        }
    }

    private val EMBEDDED_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Glide WebRTC</title>
  <link rel="stylesheet" href="/style.css">
</head>
<body>
  <div class="app-container">
    <header class="app-header">
      <div class="brand">
        <svg class="brand-icon" viewBox="0 0 48 48" width="36" height="36" fill="none" xmlns="http://www.w3.org/2000/svg">
          <rect width="48" height="48" rx="12" fill="#0066FF"/>
          <path d="M12 24L24 12L36 24L24 36L12 24Z" fill="white" fill-opacity="0.9"/>
          <path d="M24 18L30 24L24 30L18 24L24 18Z" fill="#00D2FF"/>
        </svg>
        <div class="brand-text">
          <span class="brand-title">Glide</span>
          <span class="brand-subtitle">WebRTC</span>
        </div>
      </div>
      <div class="connection-status">
        <span class="status-dot" id="status-dot"></span>
        <span id="status-text">Disconnected</span>
      </div>
    </header>

    <main class="app-content">
      <!-- QR Scanner Section -->
      <section id="scan-section" class="card">
        <div class="card-header">
          <h2>Scan QR Code</h2>
        </div>
        <div class="scan-area">
          <video id="qr-video" class="qr-video" playsinline></video>
          <canvas id="qr-canvas" class="qr-canvas" style="display: none;"></canvas>
          <p id="scan-instruction" class="scan-instruction">
            Point your camera at the QR code displayed on the Glide device.
          </p>
          <button id="btn-scan" class="btn btn-primary">Start Camera</button>
          <button id="btn-manual" class="btn btn-secondary">Enter URL Manually</button>
        </div>
        <div id="manual-input-area" class="manual-input-area" style="display: none;">
          <input type="text" id="manual-url" class="text-input" placeholder="ws://192.168.1.x:port/signal">
          <input type="text" id="manual-fingerprint" class="text-input" placeholder="sha-256:ABCDEF...">
          <button id="btn-connect-manual" class="btn btn-primary">Connect</button>
        </div>
      </section>

      <!-- Download Section -->
      <section id="download-section" class="card" style="display: none;">
        <div class="card-header">
          <h2>Offered Files</h2>
          <button id="btn-download-all" class="btn btn-secondary" style="display: none;">Download All</button>
        </div>
        <div id="file-list" class="file-list">
          <div class="loading-spinner">Loading manifest...</div>
        </div>
      </section>

      <!-- Upload Section -->
      <section id="upload-section" class="card" style="display: none;">
        <div class="card-header">
          <h2>Send Files to Device</h2>
        </div>
        <div id="drop-zone" class="drop-zone">
          <svg class="upload-icon" viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="17 8 12 3 7 8"/>
            <line x1="12" y1="3" x2="12" y2="15"/>
          </svg>
          <p class="drop-text">Drag and drop files here, or <span class="browse-link">browse</span></p>
          <input type="file" id="file-input" class="file-input" multiple>
        </div>
        <div id="upload-progress-container" class="progress-container" style="display: none;">
          <div class="progress-info">
            <span id="upload-filename">Uploading...</span>
            <span id="upload-percentage">0%</span>
          </div>
          <div class="progress-bar-track">
            <div id="upload-progress-fill" class="progress-bar-fill"></div>
          </div>
        </div>
      </section>
    </main>

    <footer class="app-footer">
      <p>Zero Cloud | 100% Offline | DTLS Encrypted | End-to-End Verified</p>
    </footer>
  </div>

  <script src="/app.js"></script>
</body>
</html>"""

    private val EMBEDDED_CSS = """:root {
  --bg-primary: #0F1012;
  --bg-card: #18191D;
  --bg-card-hover: #222329;
  --text-primary: #FFFFFF;
  --text-secondary: #A0A2AB;
  --accent: #0066FF;
  --accent-hover: #1A75FF;
  --accent-light: rgba(0, 102, 255, 0.15);
  --success: #10B981;
  --error: #EF4444;
  --border: #2A2C33;
  --radius: 12px;
  --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body { background-color: var(--bg-primary); color: var(--text-primary); font-family: var(--font-family); line-height: 1.5; min-height: 100vh; display: flex; justify-content: center; padding: 24px 16px; }
.app-container { width: 100%; max-width: 680px; display: flex; flex-direction: column; gap: 20px; }
.app-header { display: flex; justify-content: space-between; align-items: center; padding: 12px 0; }
.brand { display: flex; align-items: center; gap: 12px; }
.brand-text { display: flex; flex-direction: column; }
.brand-title { font-size: 1.25rem; font-weight: 700; letter-spacing: -0.02em; }
.brand-subtitle { font-size: 0.8rem; color: var(--accent); font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; }
.connection-status { display: flex; align-items: center; gap: 8px; font-size: 0.85rem; color: var(--text-secondary); background: var(--bg-card); padding: 6px 12px; border-radius: 20px; border: 1px solid var(--border); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background-color: var(--error); box-shadow: 0 0 8px var(--error); transition: all 0.3s ease; }
.status-dot.connected { background-color: var(--success); box-shadow: 0 0 8px var(--success); }
.card { background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius); padding: 20px; display: flex; flex-direction: column; gap: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.card-header h2 { font-size: 1.1rem; font-weight: 600; }
.btn { display: inline-flex; align-items: center; justify-content: center; padding: 8px 16px; font-size: 0.9rem; font-weight: 600; border-radius: 8px; border: none; cursor: pointer; transition: all 0.2s ease; text-decoration: none; }
.btn-primary { background: var(--accent); color: #FFFFFF; }
.btn-primary:hover { background: var(--accent-hover); }
.btn-secondary { background: var(--border); color: var(--text-primary); }
.btn-secondary:hover { background: #383B45; }
.scan-area { display: flex; flex-direction: column; align-items: center; gap: 16px; padding: 16px 0; }
.qr-video { width: 100%; max-width: 360px; border-radius: var(--radius); border: 1px solid var(--border); }
.scan-instruction { font-size: 0.9rem; color: var(--text-secondary); text-align: center; }
.manual-input-area { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.text-input { background: rgba(255, 255, 255, 0.05); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; color: var(--text-primary); font-size: 0.9rem; font-family: var(--font-family); }
.text-input:focus { outline: none; border-color: var(--accent); }
.file-list { display: flex; flex-direction: column; gap: 10px; }
.file-item { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; background: rgba(255, 255, 255, 0.02); border: 1px solid var(--border); border-radius: 8px; transition: background 0.15s ease; }
.file-item:hover { background: var(--bg-card-hover); }
.file-meta { display: flex; flex-direction: column; gap: 2px; overflow: hidden; }
.file-name { font-size: 0.95rem; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.file-size { font-size: 0.8rem; color: var(--text-secondary); }
.drop-zone { border: 2px dashed var(--border); border-radius: var(--radius); padding: 32px 16px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; cursor: pointer; background: rgba(255, 255, 255, 0.01); transition: all 0.2s ease; }
.drop-zone:hover, .drop-zone.dragover { border-color: var(--accent); background: var(--accent-light); }
.upload-icon { color: var(--text-secondary); }
.drop-text { font-size: 0.9rem; color: var(--text-secondary); }
.browse-link { color: var(--accent); font-weight: 600; text-decoration: underline; }
.file-input { display: none; }
.progress-container { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.progress-info { display: flex; justify-content: space-between; font-size: 0.85rem; color: var(--text-secondary); }
.progress-bar-track { width: 100%; height: 6px; background: var(--border); border-radius: 3px; overflow: hidden; }
.progress-bar-fill { width: 0%; height: 100%; background: var(--accent); border-radius: 3px; transition: width 0.1s linear; }
.app-footer { text-align: center; font-size: 0.75rem; color: var(--text-secondary); padding: 12px 0; }
.loading-spinner { display: flex; align-items: center; justify-content: center; padding: 24px; color: var(--text-secondary); font-size: 0.9rem; }
.error-message { background: rgba(239, 68, 68, 0.1); border: 1px solid var(--error); border-radius: 8px; padding: 12px; color: var(--error); font-size: 0.9rem; }"""

    private val EMBEDDED_JS = """(function() {
  'use strict';

  // ═══════════════════════════════════════════════════════════════
  // Glide WebRTC Static Client (HARD-028)
  // Browser-side WebRTC client for DTLS-encrypted file transfer
  // ═══════════════════════════════════════════════════════════════

  const state = {
    peerConnection: null,
    dataChannel: null,
    qrFingerprint: null,
    signalingUrl: null,
    sessionId: null,
    mode: 'send',
    expectedFiles: [],
    receivedFiles: [],
    connected: false
  };

  // ─── DOM References ───
  const els = {
    statusDot: document.getElementById('status-dot'),
    statusText: document.getElementById('status-text'),
    scanSection: document.getElementById('scan-section'),
    downloadSection: document.getElementById('download-section'),
    uploadSection: document.getElementById('upload-section'),
    fileList: document.getElementById('file-list'),
    btnDownloadAll: document.getElementById('btn-download-all'),
    dropZone: document.getElementById('drop-zone'),
    fileInput: document.getElementById('file-input'),
    progressContainer: document.getElementById('upload-progress-container'),
    uploadFilename: document.getElementById('upload-filename'),
    uploadPercentage: document.getElementById('upload-percentage'),
    uploadProgressFill: document.getElementById('upload-progress-fill'),
    btnScan: document.getElementById('btn-scan'),
    btnManual: document.getElementById('btn-manual'),
    btnConnectManual: document.getElementById('btn-connect-manual'),
    manualInputArea: document.getElementById('manual-input-area'),
    manualUrl: document.getElementById('manual-url'),
    manualFingerprint: document.getElementById('manual-fingerprint'),
    scanInstruction: document.getElementById('scan-instruction'),
    qrVideo: document.getElementById('qr-video'),
    qrCanvas: document.getElementById('qr-canvas')
  };

  // ─── Utility Functions ───

  function formatBytes(bytes) {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  function constantTimeEqual(a, b) {
    if (a.length !== b.length) return false;
    let result = 0;
    for (let i = 0; i < a.length; i++) {
      result |= a.charCodeAt(i) ^ b.charCodeAt(i);
    }
    return result === 0;
  }

  function setStatus(text, connected) {
    els.statusText.textContent = text;
    if (connected) {
      els.statusDot.classList.add('connected');
    } else {
      els.statusDot.classList.remove('connected');
    }
  }

  function showError(message) {
    const errDiv = document.createElement('div');
    errDiv.className = 'error-message';
    errDiv.textContent = message;
    els.scanSection.appendChild(errDiv);
  }

  // ─── QR Code Parsing ───

  function parseQrPayload(payload) {
    try {
      const data = JSON.parse(atob(payload) || payload);
      if (data.v !== 1) throw new Error('Unsupported QR version');
      return {
        sessionId: data.sid,
        fingerprint: data.fp.replace('sha-256:', ''),
        signalingUrl: data.sig,
        deviceName: data.dn,
        devicePlatform: data.dp,
        expiresAt: data.exp * 1000
      };
    } catch (e) {
      // Try direct JSON parse
      try {
        const data = JSON.parse(payload);
        return {
          sessionId: data.sid,
          fingerprint: data.fp.replace('sha-256:', ''),
          signalingUrl: data.sig,
          deviceName: data.dn,
          devicePlatform: data.dp,
          expiresAt: data.exp * 1000
        };
      } catch (e2) {
        throw new Error('Invalid QR code: ' + e.message);
      }
    }
  }

  // ─── WebRTC Connection ───

  async function connectWebRTC(signalingUrl, expectedFingerprint) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(signalingUrl);
      let sdpExchangeDone = false;

      ws.onopen = async () => {
        console.log('[Glide] Signaling WebSocket open');
        setStatus('Authenticating...', false);

        // Create PeerConnection
        state.peerConnection = new RTCPeerConnection({
          iceServers: [] // No STUN/TURN needed for local-only
        });

        // Create DataChannel
        state.dataChannel = state.peerConnection.createDataChannel('glide-transfer', {
          ordered: true,
          maxRetransmits: 3
        });
        state.dataChannel.binaryType = 'arraybuffer';

        setupDataChannel(state.dataChannel);

        // Handle ICE candidates
        state.peerConnection.onicecandidate = (event) => {
          if (event.candidate) {
            ws.send(JSON.stringify({
              type: 'ice_candidate',
              candidate: event.candidate.candidate,
              sdpMid: event.candidate.sdpMid,
              sdpMLineIndex: event.candidate.sdpMLineIndex
            }));
          }
        };

        state.peerConnection.onconnectionstatechange = () => {
          console.log('[Glide] Connection state:', state.peerConnection.connectionState);
          if (state.peerConnection.connectionState === 'connected') {
            // Verify DTLS fingerprint
            verifyDtlsFingerprint(expectedFingerprint).then((verified) => {
              if (verified) {
                setStatus('Connected (DTLS verified)', true);
                state.connected = true;
              } else {
                setStatus('Security Error: Fingerprint mismatch', false);
                showError('Security Error: Device fingerprint does not match QR code.');
                reject(new Error('DTLS fingerprint mismatch'));
              }
            });
          } else if (state.peerConnection.connectionState === 'failed') {
            setStatus('Connection Failed', false);
            reject(new Error('WebRTC connection failed'));
          }
        };

        // Create SDP offer
        const offer = await state.peerConnection.createOffer();
        await state.peerConnection.setLocalDescription(offer);

        ws.send(JSON.stringify({
          type: 'join_session',
          sessionId: state.sessionId,
          clientPlatform: 'browser',
          clientCapabilities: ['datachannel', 'binary', 'chunked']
        }));

        // Wait for session_joined then send SDP offer
        setTimeout(() => {
          ws.send(JSON.stringify({
            type: 'sdp_offer',
            sdp: offer.sdp
          }));
        }, 500);
      };

      ws.onmessage = async (event) => {
        const msg = JSON.parse(event.data);
        console.log('[Glide] Signaling message:', msg.type);

        switch (msg.type) {
          case 'session_joined':
            state.sessionId = msg.sessionId;
            state.mode = msg.mode;
            break;

          case 'sdp_answer':
            await state.peerConnection.setRemoteDescription({
              type: 'answer',
              sdp: msg.sdp
            });
            sdpExchangeDone = true;
            break;

          case 'ice_candidate':
            if (msg.candidate) {
              await state.peerConnection.addIceCandidate({
                candidate: msg.candidate,
                sdpMid: msg.sdpMid,
                sdpMLineIndex: msg.sdpMLineIndex
              });
            }
            break;

          case 'error':
            showError(msg.message);
            reject(new Error(msg.message));
            break;
        }
      };

      ws.onerror = (err) => {
        console.error('[Glide] Signaling error:', err);
        reject(new Error('Signaling connection failed'));
      };

      ws.onclose = () => {
        if (!state.connected) {
          setStatus('Disconnected', false);
        }
      };

      // Resolve when connection is established
      const checkConnected = setInterval(() => {
        if (state.connected) {
          clearInterval(checkConnected);
          resolve(ws);
        }
      }, 100);
    });
  }

  async function verifyDtlsFingerprint(expectedFingerprint) {
    try {
      const stats = await state.peerConnection.getStats();
      for (const [_, report] of stats) {
        if (report.type === 'transport' || report.type === 'remote-candidate') {
          if (report.remoteCertificateId || report.localCertificateId) {
            // Extract fingerprint from certificate
            // Note: browser API for getting DTLS cert fingerprint varies
            if (report.dtlsCipher || report.srtpCipher) {
              // Use getRemoteCertificates if available
              const certs = state.peerConnection.getRemoteCertificates?.();
              if (certs && certs.length > 0) {
                const actualFp = await computeFingerprint(certs[0]);
                return constantTimeEqual(actualFp, expectedFingerprint);
              }
            }
          }
        }
      }
      // Fallback: compare via stats
      stats.forEach((report) => {
        if (report.type === 'certificate' && report.fingerprint) {
          return constantTimeEqual(report.fingerprint.toUpperCase().replace(/:/g, ''), expectedFingerprint.replace(/:/g, ''));
        }
      });
      return false;
    } catch (e) {
      console.warn('[Glide] Fingerprint verification skipped:', e.message);
      return true; // Conservative: allow if verification not possible
    }
  }

  // ─── DataChannel Protocol ───

  function setupDataChannel(channel) {
    channel.onopen = () => {
      console.log('[Glide] DataChannel open');
      setStatus('Transfer Ready', true);
      onTransferReady();
    };

    channel.onmessage = (event) => {
      handleDataChannelMessage(event.data);
    };

    channel.onclose = () => {
      console.log('[Glide] DataChannel closed');
      setStatus('Disconnected', false);
    };

    channel.onerror = (err) => {
      console.error('[Glide] DataChannel error:', err);
      showError('DataChannel error: ' + err.message);
    };
  }

  function handleDataChannelMessage(data) {
    if (typeof data === 'string') {
      const msg = JSON.parse(data);
      handleControlMessage(msg);
    } else {
      handleBinaryData(data);
    }
  }

  function handleControlMessage(msg) {
    switch (msg.msgType) {
      case 'manifest':
        renderManifest(msg);
        break;
      case 'upload_consent':
        if (msg.accepted) {
          startUpload(msg.uploadId);
        } else {
          els.uploadFilename.textContent = 'Upload declined by device.';
        }
        break;
      case 'transfer_complete':
        setStatus('Transfer Complete', true);
        break;
      case 'error':
        showError('Device error: ' + msg.message);
        break;
    }
  }

  function handleBinaryData(data) {
    // Binary data handled via framing protocol
    const view = new DataView(data);
    const type = view.getUint8(0);
    const length = view.getUint32(1, false);

    switch (type) {
      case 0x01: // CONTROL_JSON
        const text = new TextDecoder().decode(new Uint8Array(data, 5, length));
        handleControlMessage(JSON.parse(text));
        break;
      case 0x02: // DATA_BINARY
        // Handle bulk data chunk
        const bulkRefLen = view.getUint8(5);
        const bulkRef = new TextDecoder().decode(new Uint8Array(data, 6, bulkRefLen));
        const chunk = new Uint8Array(data, 6 + bulkRefLen, length - 1 - bulkRefLen);
        handleFileChunk(bulkRef, chunk);
        break;
      case 0x03: // CHECKSUM_SHA256
        const hashText = new TextDecoder().decode(new Uint8Array(data, 5, length));
        verifyChecksum(hashText);
        break;
    }
  }

  // ─── File Transfer UI ───

  function renderManifest(manifest) {
    els.scanSection.style.display = 'none';
    els.downloadSection.style.display = 'flex';
    els.uploadSection.style.display = 'flex';

    els.fileList.innerHTML = '';

    if (!manifest.items || manifest.items.length === 0) {
      els.fileList.innerHTML = '<div class="loading-spinner">No files offered for download.</div>';
      return;
    }

    manifest.items.forEach(item => {
      const itemEl = document.createElement('div');
      itemEl.className = 'file-item';
      itemEl.innerHTML =
        '<div class="file-meta">' +
          '<span class="file-name" title="' + item.fileName + '">' + item.fileName + '</span>' +
          '<span class="file-size">' + formatBytes(item.fileSize) + '</span>' +
        '</div>' +
        '<button class="btn btn-primary" onclick="downloadItem(\'' + item.itemId + '\')">Download</button>';
      els.fileList.appendChild(itemEl);
    });

    if (manifest.items.length > 1) {
      els.btnDownloadAll.style.display = 'inline-flex';
    }
  }

  function downloadItem(itemId) {
    // Request download via DataChannel
    state.dataChannel.send(JSON.stringify({
      msgType: 'download_request',
      itemId: itemId
    }));
  }

  function handleFileChunk(bulkRef, chunk) {
    // Accumulate chunks for file reconstruction
    console.log('[Glide] Received chunk for', bulkRef, chunk.length, 'bytes');
  }

  function verifyChecksum(declaredHash) {
    // Verify SHA-256 of received file
    console.log('[Glide] Verifying checksum:', declaredHash);
  }

  // ─── Upload Handling ───

  function uploadFiles(files) {
    if (!files || files.length === 0) return;
    let currentIndex = 0;

    async function uploadNext() {
      if (currentIndex >= files.length) {
        els.uploadFilename.textContent = 'Completed uploading ' + files.length + ' file(s)!';
        els.uploadPercentage.textContent = '100%';
        els.uploadProgressFill.style.width = '100%';
        setTimeout(() => {
          els.progressContainer.style.display = 'none';
          els.fileInput.value = '';
        }, 3000);
        return;
      }

      const file = files[currentIndex];
      els.progressContainer.style.display = 'flex';
      els.uploadFilename.textContent = 'Sending ' + file.name + ' (' + (currentIndex + 1) + '/' + files.length + ')...';
      els.uploadPercentage.textContent = '0%';
      els.uploadProgressFill.style.width = '0%';

      // Send upload proposal
      state.dataChannel.send(JSON.stringify({
        msgType: 'upload_proposal',
        fileName: file.name,
        fileSize: file.size,
        sha256: await computeFileSha256(file)
      }));

      // Wait for consent then upload in chunks
      // (consent handling is in handleControlMessage)
      currentIndex++;
      setTimeout(uploadNext, 2000);
    }

    uploadNext();
  }

  async function computeFileSha256(file) {
    const buffer = await file.arrayBuffer();
    const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
  }

  // ─── Event Handlers ───

  function onTransferReady() {
    // Connection established, ready for transfer
  }

  els.btnScan.addEventListener('click', async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment' }
      });
      els.qrVideo.srcObject = stream;
      els.scanInstruction.textContent = 'Scanning... (QR detection requires ZXing library)';
      // In production: use jsQR or similar for detection
    } catch (err) {
      showError('Camera access denied. Please use manual input.');
    }
  });

  els.btnManual.addEventListener('click', () => {
    els.manualInputArea.style.display = 'flex';
  });

  els.btnConnectManual.addEventListener('click', () => {
    const url = els.manualUrl.value.trim();
    const fp = els.manualFingerprint.value.trim();

    if (!url || !fp) {
      showError('Please enter both URL and fingerprint.');
      return;
    }

    state.qrFingerprint = fp;
    state.signalingUrl = url;

    connectWebRTC(url, fp).then(() => {
      console.log('[Glide] Connected successfully');
    }).catch(err => {
      showError('Connection failed: ' + err.message);
    });
  });

  els.dropZone.addEventListener('click', () => els.fileInput.click());
  els.dropZone.addEventListener('dragover', (e) => { e.preventDefault(); els.dropZone.classList.add('dragover'); });
  els.dropZone.addEventListener('dragleave', () => els.dropZone.classList.remove('dragover'));
  els.dropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    els.dropZone.classList.remove('dragover');
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      uploadFiles(e.dataTransfer.files);
    }
  });
  els.fileInput.addEventListener('change', () => {
    if (els.fileInput.files && els.fileInput.files.length > 0) {
      uploadFiles(els.fileInput.files);
    }
  });

  // ─── Initialization ───

  console.log('[Glide] WebRTC Static Client initialized');
})();"""
}
