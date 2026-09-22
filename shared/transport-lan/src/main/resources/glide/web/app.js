document.addEventListener("DOMContentLoaded", () => {
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

  // Load manifest
  async function loadManifest() {
    try {
      const response = await fetch("/api/v1/manifest");
      if (!response.ok) {
        throw new Error(`Failed to load manifest: ${response.status}`);
      }
      const data = await response.json();

      if (data.device && data.device.name) {
        deviceNameEl.textContent = `${data.device.name} (${data.device.platform || "unknown"})`;
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
            <span class="file-name" title="${item.fileName}">${item.fileName}</span>
            <span class="file-size">${formatBytes(item.fileSize)}</span>
          </div>
          <a href="/api/v1/download/${item.itemId}" download="${item.fileName}" class="btn btn-primary">Download</a>
        `;
        fileListEl.appendChild(itemEl);
      });

      if (data.items.length > 1) {
        btnDownloadAll.style.display = "inline-flex";
        btnDownloadAll.onclick = () => {
          data.items.forEach((item, index) => {
            setTimeout(() => {
              const a = document.createElement("a");
              a.href = `/api/v1/download/${item.itemId}`;
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
      fileListEl.innerHTML = `<div style="color: #EF4444; text-align: center; padding: 16px;">Error: ${err.message}</div>`;
    }
  }

  // File Upload Handlers
  dropZone.addEventListener("click", () => fileInput.click());

  dropZone.addEventListener("dragover", (e) => {
    e.preventDefault();
    dropZone.classList.add("dragover");
  });

  dropZone.addEventListener("dragleave", () => {
    dropZone.classList.remove("dragover");
  });

  dropZone.addEventListener("drop", (e) => {
    e.preventDefault();
    dropZone.classList.remove("dragover");
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      uploadFiles(e.dataTransfer.files);
    }
  });

  fileInput.addEventListener("change", () => {
    if (fileInput.files && fileInput.files.length > 0) {
      uploadFiles(fileInput.files);
    }
  });

  function uploadFiles(files) {
    if (!files || files.length === 0) return;

    let currentIndex = 0;

    async function uploadNext() {
      if (currentIndex >= files.length) {
        uploadFilenameEl.textContent = `Completed uploading ${files.length} file(s)!`;
        uploadPercentageEl.textContent = "100%";
        uploadProgressFill.style.width = "100%";
        setTimeout(() => {
          progressContainer.style.display = "none";
          fileInput.value = "";
        }, 3000);
        return;
      }

      const file = files[currentIndex];
      progressContainer.style.display = "flex";
      uploadFilenameEl.textContent = `Proposing upload for ${file.name} (${currentIndex + 1}/${files.length})...`;
      uploadPercentageEl.textContent = "0%";
      uploadProgressFill.style.width = "0%";

      try {
        const proposalResp = await fetch("/api/v1/upload/proposal", {
          method: "POST",
          headers: {
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            fileName: file.name,
            fileSize: file.size,
            mimeType: file.type || "application/octet-stream"
          })
        });

        if (proposalResp.status === 507) {
          uploadFilenameEl.textContent = `Declined ${file.name}: Insufficient storage space on device.`;
          return;
        }

        if (!proposalResp.ok) {
          uploadFilenameEl.textContent = `Proposal failed for ${file.name}: ${proposalResp.statusText}`;
          return;
        }

        const proposalData = await proposalResp.json();
        const uploadId = proposalData.uploadId || "";

        uploadFilenameEl.textContent = `Uploading ${file.name} (${currentIndex + 1}/${files.length})...`;

        const xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/v1/upload", true);
        xhr.setRequestHeader("X-File-Name", encodeURIComponent(file.name));
        xhr.setRequestHeader("X-File-Size", file.size.toString());
        if (uploadId) {
          xhr.setRequestHeader("X-Upload-Id", uploadId);
        }
        xhr.setRequestHeader("Content-Type", file.type || "application/octet-stream");

        xhr.upload.onprogress = (e) => {
          if (e.lengthComputable) {
            const percent = Math.round((e.loaded / e.total) * 100);
            uploadPercentageEl.textContent = `${percent}%`;
            uploadProgressFill.style.width = `${percent}%`;
          }
        };

        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            currentIndex++;
            uploadNext();
          } else if (xhr.status === 422) {
            uploadFilenameEl.textContent = `Verification failed for ${file.name}: Checksum integrity mismatch.`;
          } else if (xhr.status === 507) {
            uploadFilenameEl.textContent = `Failed uploading ${file.name}: Insufficient storage on device.`;
          } else {
            uploadFilenameEl.textContent = `Failed uploading ${file.name}: ${xhr.statusText || xhr.status}`;
          }
        };

        xhr.onerror = () => {
          uploadFilenameEl.textContent = `Network error uploading ${file.name}`;
        };

        xhr.send(file);
      } catch (err) {
        uploadFilenameEl.textContent = `Error initiating upload for ${file.name}: ${err.message}`;
      }
    }

    uploadNext();
  }

  loadManifest();
});
