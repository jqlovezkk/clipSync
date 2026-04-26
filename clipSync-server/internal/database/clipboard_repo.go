package database

import (
	"database/sql"
	"fmt"
	"time"
)

// ClipboardRepo handles clipboard-related database operations.
type ClipboardRepo struct {
	db    *sql.DB
	limit int
}

// NewClipboardRepo creates a new ClipboardRepo.
func NewClipboardRepo(db *sql.DB, historyLimit int) *ClipboardRepo {
	return &ClipboardRepo{db: db, limit: historyLimit}
}

// AddEntry stores a new clipboard entry.
func (r *ClipboardRepo) AddEntry(userID int64, contentType, content, format string, size int64, checksum, sourceDeviceID, sourceDeviceName string) (*ClipboardEntry, error) {
	now := time.Now().UnixMilli()

	result, err := r.db.Exec(
		`INSERT INTO clipboard_history 
		 (user_id, content_type, content, format, size, checksum, source_device_id, source_device_name, created_at) 
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		userID, contentType, content, format, size, checksum, sourceDeviceID, sourceDeviceName, now,
	)
	if err != nil {
		return nil, fmt.Errorf("insert clipboard entry: %w", err)
	}

	id, err := result.LastInsertId()
	if err != nil {
		return nil, fmt.Errorf("get last insert id: %w", err)
	}

	// Enforce history limit - delete oldest entries beyond limit
	if _, err := r.db.Exec(
		`DELETE FROM clipboard_history WHERE id IN (
			SELECT id FROM clipboard_history 
			WHERE user_id = ? 
			ORDER BY created_at DESC 
			LIMIT -1 OFFSET ?
		)`,
		userID, r.limit,
	); err != nil {
		// Non-critical error, log but don't fail
	}

	return &ClipboardEntry{
		ID:               id,
		UserID:           userID,
		ContentType:      contentType,
		Content:          content,
		Format:           format,
		Size:             size,
		Checksum:         checksum,
		SourceDeviceID:   sourceDeviceID,
		SourceDeviceName: sourceDeviceName,
		CreatedAt:        now,
	}, nil
}

// GetHistory retrieves clipboard history for a user.
func (r *ClipboardRepo) GetHistory(userID int64, limit, afterID int) ([]ClipboardEntry, int, bool, error) {
	if limit <= 0 || limit > r.limit {
		limit = r.limit
	}

	// Get total count
	var total int
	countQuery := "SELECT COUNT(*) FROM clipboard_history WHERE user_id = ?"
	if err := r.db.QueryRow(countQuery, userID).Scan(&total); err != nil {
		return nil, 0, false, fmt.Errorf("count entries: %w", err)
	}

	// Build query based on after_id
	var query string
	var args []interface{}
	if afterID > 0 {
		query = "SELECT id, user_id, content_type, content, format, size, checksum, source_device_id, source_device_name, created_at FROM clipboard_history WHERE user_id = ? AND id > ? ORDER BY created_at DESC LIMIT ?"
		args = []interface{}{userID, afterID, limit}
	} else {
		query = "SELECT id, user_id, content_type, content, format, size, checksum, source_device_id, source_device_name, created_at FROM clipboard_history WHERE user_id = ? ORDER BY created_at DESC LIMIT ?"
		args = []interface{}{userID, limit}
	}

	rows, err := r.db.Query(query, args...)
	if err != nil {
		return nil, 0, false, fmt.Errorf("query history: %w", err)
	}
	defer rows.Close()

	var entries []ClipboardEntry
	for rows.Next() {
		var e ClipboardEntry
		if err := rows.Scan(&e.ID, &e.UserID, &e.ContentType, &e.Content, &e.Format, &e.Size, &e.Checksum, &e.SourceDeviceID, &e.SourceDeviceName, &e.CreatedAt); err != nil {
			return nil, 0, false, fmt.Errorf("scan entry: %w", err)
		}
		entries = append(entries, e)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, false, fmt.Errorf("iterate rows: %w", err)
	}

	hasMore := total > (afterID + len(entries))
	return entries, total, hasMore, nil
}

// GetLatestByUser retrieves the most recent clipboard entry for a user.
func (r *ClipboardRepo) GetLatestByUser(userID int64) (*ClipboardEntry, error) {
	var e ClipboardEntry
	err := r.db.QueryRow(
		"SELECT id, user_id, content_type, content, format, size, checksum, source_device_id, source_device_name, created_at FROM clipboard_history WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
		userID,
	).Scan(&e.ID, &e.UserID, &e.ContentType, &e.Content, &e.Format, &e.Size, &e.Checksum, &e.SourceDeviceID, &e.SourceDeviceName, &e.CreatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("query latest entry: %w", err)
	}
	return &e, nil
}

// CheckDuplicateChecksum checks if a checksum already exists for a user.
func (r *ClipboardRepo) CheckDuplicateChecksum(userID int64, checksum string) (bool, error) {
	var count int
	err := r.db.QueryRow(
		"SELECT COUNT(*) FROM clipboard_history WHERE user_id = ? AND checksum = ?",
		userID, checksum,
	).Scan(&count)
	if err != nil {
		return false, fmt.Errorf("check duplicate: %w", err)
	}
	return count > 0, nil
}
