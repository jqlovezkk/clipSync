package httpserver

import (
	"clipsync-server/internal/auth"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"database/sql"
)

// UploadHandler handles file upload/download HTTP endpoints.
type UploadHandler struct {
	db           *sql.DB
	storagePath  string
	maxFileSize  int64
}

// NewUploadHandler creates a new upload handler.
func NewUploadHandler(db *sql.DB, storagePath string, maxFileSizeMB int) *UploadHandler {
	os.MkdirAll(storagePath, 0755)
	return &UploadHandler{
		db:           db,
		storagePath:  storagePath,
		maxFileSize:  int64(maxFileSizeMB) * 1024 * 1024,
	}
}

// Upload handles POST /api/v1/upload.
func (h *UploadHandler) Upload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}

	userID := auth.GetUserID(r)
	if userID == 0 {
		writeJSON(w, http.StatusUnauthorized, map[string]interface{}{
			"success": false,
			"error":   "AUTH_FAILED",
		})
		return
	}

	// Limit request body size
	r.Body = http.MaxBytesReader(w, r.Body, h.maxFileSize)

	if err := r.ParseMultipartForm(h.maxFileSize); err != nil {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]interface{}{
			"success": false,
			"error":   "CONTENT_TOO_LARGE",
		})
		return
	}

	file, header, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
		})
		return
	}
	defer file.Close()

	clientChecksum := r.FormValue("checksum")

	// Generate unique file ID
	fileID := generateFileID()

	// Create user-specific subdirectory
	userDir := filepath.Join(h.storagePath, fmt.Sprintf("%d", userID))
	if err := os.MkdirAll(userDir, 0755); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"success": false,
			"error":   "INTERNAL_ERROR",
		})
		return
	}

	// Save file and compute checksum simultaneously
	filePath := filepath.Join(userDir, fileID)
	dst, err := os.Create(filePath)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"success": false,
			"error":   "INTERNAL_ERROR",
		})
		return
	}
	defer dst.Close()

	hasher := sha256.New()
	multiWriter := io.MultiWriter(dst, hasher)

	written, err := io.Copy(multiWriter, file)
	if err != nil {
		os.Remove(filePath)
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"success": false,
			"error":   "INTERNAL_ERROR",
		})
		return
	}

	// Verify checksum if provided
	computedChecksum := hex.EncodeToString(hasher.Sum(nil))
	if clientChecksum != "" && clientChecksum != computedChecksum {
		os.Remove(filePath)
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "CHECKSUM_MISMATCH",
			"message": "File checksum does not match",
		})
		return
	}

	// Record file in database
	now := time.Now().UnixMilli()
	mimeType := header.Header.Get("Content-Type")
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}
	_, err = h.db.Exec(
		`INSERT INTO uploaded_files (id, user_id, filename, mime_type, size, checksum, file_path, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		fileID, userID, header.Filename, mimeType, written, computedChecksum, filePath, now,
	)
	if err != nil {
		os.Remove(filePath)
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"success": false,
			"error":   "INTERNAL_ERROR",
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success":      true,
		"file_id":      fileID,
		"download_url": fmt.Sprintf("/api/v1/download/%s", fileID),
	})
}

// Download handles GET /api/v1/download/{file_id}.
func (h *UploadHandler) Download(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}

	userID := auth.GetUserID(r)
	if userID == 0 {
		writeJSON(w, http.StatusUnauthorized, map[string]interface{}{
			"success": false,
			"error":   "AUTH_FAILED",
		})
		return
	}

	// Extract file_id from URL path
	fileID := r.PathValue("file_id")
	if fileID == "" {
		// Fallback for older Go versions
		fileID = filepath.Base(r.URL.Path)
	}

	// Security: validate file_id does not contain path traversal characters
	if strings.Contains(fileID, "/") || strings.Contains(fileID, "\\") || strings.Contains(fileID, "..") {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_FILE_ID",
		})
		return
	}

	// Verify file belongs to this user via database
	var fileUserID int64
	err := h.db.QueryRow("SELECT user_id FROM uploaded_files WHERE id = ?", fileID).Scan(&fileUserID)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{
			"success": false,
			"error":   "FILE_NOT_FOUND",
		})
		return
	}
	if fileUserID != userID {
		writeJSON(w, http.StatusForbidden, map[string]interface{}{
			"success": false,
			"error":   "ACCESS_DENIED",
		})
		return
	}

	filePath := filepath.Join(h.storagePath, fmt.Sprintf("%d", userID), fileID)

	// Verify file exists
	info, err := os.Stat(filePath)
	if err != nil || info.IsDir() {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{
			"error": "FILE_NOT_FOUND",
		})
		return
	}

	http.ServeFile(w, r, filePath)
}

func generateFileID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b) + time.Now().Format("20060102")
}
