package dev.glide.transport.lan.web

import java.nio.charset.StandardCharsets

/**
 * Embedded Single-Page Application assets (HTML5, CSS3, ES2020 JavaScript)
 * served by the zero-dependency Glide Web Transfer Gateway (ADR-0019).
 *
 * 100% offline and air-gapped: zero external CDNs, fonts, or tracking scripts.
 */
object GlideWebAssets {

    private fun loadResourceOrFallback(resourcePath: String, fallbackText: () -> String): ByteArray {
        val stream = GlideWebAssets::class.java.getResourceAsStream(resourcePath)
        return if (stream != null) {
            stream.use { it.readBytes() }
        } else {
            fallbackText().toByteArray(StandardCharsets.UTF_8)
        }
    }

    fun getIndexHtml(): ByteArray = loadResourceOrFallback("/glide/web/index.html") { FALLBACK_HTML }

    fun getStyleCss(): ByteArray = loadResourceOrFallback("/glide/web/style.css") { FALLBACK_CSS }

    fun getAppJs(): ByteArray = loadResourceOrFallback("/glide/web/app.js") { FALLBACK_JS }

    private const val FALLBACK_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Glide Web Share</title>
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
          <span class="brand-subtitle">Web Share</span>
        </div>
      </div>
      <div class="device-status">
        <span class="status-dot"></span>
        <span id="device-name">Connected Device</span>
      </div>
    </header>

    <main class="app-content">
      <section id="download-section" class="card">
        <div class="card-header">
          <h2>Offered Files</h2>
          <button id="btn-download-all" class="btn btn-secondary" style="display: none;">Download All</button>
        </div>
        <div id="file-list" class="file-list">
          <div class="loading-spinner">Loading manifest...</div>
        </div>
      </section>

      <section id="upload-section" class="card">
        <div class="card-header">
          <h2>Send Files Back to Device</h2>
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
      <p>Zero Cloud • 100% Offline Local Network Transfer • Private & End-to-End Verified</p>
    </footer>
  </div>

  <script src="/app.js"></script>
</body>
</html>"""

    private const val FALLBACK_CSS = """:root {
  --bg-primary: #0F1012;
  --bg-card: #18191D;
  --bg-card-hover: #222329;
  --text-primary: #FFFFFF;
  --text-secondary: #A0A2AB;
  --accent: #0066FF;
  --accent-hover: #1A75FF;
  --accent-light: rgba(0, 102, 255, 0.15);
  --success: #10B981;
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
.device-status { display: flex; align-items: center; gap: 8px; font-size: 0.85rem; color: var(--text-secondary); background: var(--bg-card); padding: 6px 12px; border-radius: 20px; border: 1px solid var(--border); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background-color: var(--success); box-shadow: 0 0 8px var(--success); }
.card { background: var(--bg-card); border: 1px solid var(--border); border-radius: var(--radius); padding: 20px; display: flex; flex-direction: column; gap: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.card-header h2 { font-size: 1.1rem; font-weight: 600; }
.btn { display: inline-flex; align-items: center; justify-content: center; padding: 8px 16px; font-size: 0.9rem; font-weight: 600; border-radius: 8px; border: none; cursor: pointer; transition: all 0.2s ease; text-decoration: none; }
.btn-primary { background: var(--accent); color: #FFFFFF; }
.btn-primary:hover { background: var(--accent-hover); }
.btn-secondary { background: var(--border); color: var(--text-primary); }
.btn-secondary:hover { background: #383B45; }
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
.app-footer { text-align: center; font-size: 0.75rem; color: var(--text-secondary); padding: 12px 0; }"""

    private const val FALLBACK_JS = """document.addEventListener("DOMContentLoaded", () => {
  const deviceNameEl = document.getElementById("device-name");
  const fileListEl = document.getElementById("file-list");
  const btnDownloadAll = document.getElementById("btn-download-all");
  const dropZone = document.getElementById("drop-zone");
  const fileInput = document.getElementById("file-input");
  const progressContainer = document.getElementById("upload-progress-container");
  const uploadFilenameEl = document.getElementById("upload-filename");
  const uploadPercentageEl = document.getElementById("upload-percentage");
  const uploadProgressFill = document.getElementById("upload-progress-fill");

  function formatBytes(bytes) {
    if (bytes === 0) return "0 Bytes";
    const k = 1024;
    const sizes = ["Bytes", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
  }

  async function loadManifest() {
    try {
      const response = await fetch("/api/v1/manifest");
      if (!response.ok) throw new Error("Status " + response.status);
      const data = await response.json();
      if (data.device && data.device.name) {
        deviceNameEl.textContent = data.device.name + " (" + (data.device.platform || "android") + ")";
      }
      fileListEl.innerHTML = "";
      if (!data.items || data.items.length === 0) {
        fileListEl.innerHTML = '<div style="color: var(--text-secondary); text-align: center; padding: 16px;">No files currently offered for download.</div>';
        btnDownloadAll.style.display = "none";
        return;
      }
      data.items.forEach(item => {
        const itemEl = document.createElement("div");
        itemEl.className = "file-item";
        itemEl.innerHTML = `
          <div class="file-meta">
            <span class="file-name" title="${'$'}{item.fileName}">${'$'}{item.fileName}</span>
            <span class="file-size">${'$'}{formatBytes(item.fileSize)}</span>
          </div>
          <a href="/api/v1/download/${'$'}{item.itemId}" download="${'$'}{item.fileName}" class="btn btn-primary">Download</a>
        `;
        fileListEl.appendChild(itemEl);
      });
      if (data.items.length > 1) {
        btnDownloadAll.style.display = "inline-flex";
        btnDownloadAll.onclick = () => {
          data.items.forEach((item, index) => {
            setTimeout(() => {
              const a = document.createElement("a");
              a.href = "/api/v1/download/" + item.itemId;
              a.download = item.fileName;
              document.body.appendChild(a);
              a.click();
              document.body.removeChild(a);
            }, index * 300);
          });
        };
      } else {
        btnDownloadAll.style.display = "none";
      }
    } catch (err) {
      fileListEl.innerHTML = '<div style="color: #EF4444; text-align: center; padding: 16px;">Error: ' + err.message + '</div>';
    }
  }

  dropZone.addEventListener("click", () => fileInput.click());
  dropZone.addEventListener("dragover", (e) => { e.preventDefault(); dropZone.classList.add("dragover"); });
  dropZone.addEventListener("dragleave", () => { dropZone.classList.remove("dragover"); });
  dropZone.addEventListener("drop", (e) => {
    e.preventDefault();
    dropZone.classList.remove("dragover");
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) uploadFiles(e.dataTransfer.files);
  });
  fileInput.addEventListener("change", () => {
    if (fileInput.files && fileInput.files.length > 0) uploadFiles(fileInput.files);
  });

  function uploadFiles(files) {
    if (!files || files.length === 0) return;
    let currentIndex = 0;
    function uploadNext() {
      if (currentIndex >= files.length) {
        uploadFilenameEl.textContent = "Completed uploading " + files.length + " file(s)!";
        uploadPercentageEl.textContent = "100%";
        uploadProgressFill.style.width = "100%";
        setTimeout(() => { progressContainer.style.display = "none"; fileInput.value = ""; }, 3000);
        return;
      }
      const file = files[currentIndex];
      progressContainer.style.display = "flex";
      uploadFilenameEl.textContent = "Uploading " + file.name + " (" + (currentIndex + 1) + "/" + files.length + ")...";
      uploadPercentageEl.textContent = "0%";
      uploadProgressFill.style.width = "0%";

      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/v1/upload", true);
      xhr.setRequestHeader("X-File-Name", encodeURIComponent(file.name));
      xhr.setRequestHeader("Content-Type", file.type || "application/octet-stream");
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
          const percent = Math.round((e.loaded / e.total) * 100);
          uploadPercentageEl.textContent = percent + "%";
          uploadProgressFill.style.width = percent + "%";
        }
      };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) { currentIndex++; uploadNext(); }
        else { uploadFilenameEl.textContent = "Failed uploading " + file.name + ": " + xhr.statusText; }
      };
      xhr.onerror = () => { uploadFilenameEl.textContent = "Network error uploading " + file.name; };
      xhr.send(file);
    }
    uploadNext();
  }
  loadManifest();
});"""
}
