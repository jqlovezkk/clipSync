package database

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"fmt"
	"time"
)

// DeviceRepo handles device-related database operations.
type DeviceRepo struct {
	db *sql.DB
}

// NewDeviceRepo creates a new DeviceRepo.
func NewDeviceRepo(db *sql.DB) *DeviceRepo {
	return &DeviceRepo{db: db}
}

// CreateDevice registers a new device for a user.
func (r *DeviceRepo) CreateDevice(userID int64, deviceName, platform string) (*Device, error) {
	id := generateDeviceID()
	now := time.Now().UnixMilli()

	_, err := r.db.Exec(
		"INSERT INTO devices (id, user_id, device_name, platform, last_seen, created_at) VALUES (?, ?, ?, ?, ?, ?)",
		id, userID, deviceName, platform, now, now,
	)
	if err != nil {
		return nil, fmt.Errorf("insert device: %w", err)
	}

	return &Device{
		ID:         id,
		UserID:     userID,
		DeviceName: deviceName,
		Platform:   platform,
		LastSeen:   now,
		CreatedAt:  now,
	}, nil
}

// GetDevice retrieves a device by ID.
func (r *DeviceRepo) GetDevice(deviceID string) (*Device, error) {
	var d Device
	err := r.db.QueryRow(
		"SELECT id, user_id, device_name, platform, last_seen, created_at FROM devices WHERE id = ?",
		deviceID,
	).Scan(&d.ID, &d.UserID, &d.DeviceName, &d.Platform, &d.LastSeen, &d.CreatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("query device: %w", err)
	}
	return &d, nil
}

// GetDevicesByUser retrieves all devices for a user.
func (r *DeviceRepo) GetDevicesByUser(userID int64) ([]Device, error) {
	rows, err := r.db.Query(
		"SELECT id, user_id, device_name, platform, last_seen, created_at FROM devices WHERE user_id = ? ORDER BY last_seen DESC",
		userID,
	)
	if err != nil {
		return nil, fmt.Errorf("query devices: %w", err)
	}
	defer rows.Close()

	var devices []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.ID, &d.UserID, &d.DeviceName, &d.Platform, &d.LastSeen, &d.CreatedAt); err != nil {
			return nil, fmt.Errorf("scan device: %w", err)
		}
		devices = append(devices, d)
	}
	return devices, rows.Err()
}

// UpdateDeviceLastSeen updates the last_seen timestamp for a device.
func (r *DeviceRepo) UpdateDeviceLastSeen(deviceID string) error {
	now := time.Now().UnixMilli()
	_, err := r.db.Exec("UPDATE devices SET last_seen = ? WHERE id = ?", now, deviceID)
	if err != nil {
		return fmt.Errorf("update last seen: %w", err)
	}
	return nil
}

// DeleteDevice removes a device. Returns true if a row was deleted.
func (r *DeviceRepo) DeleteDevice(userID int64, deviceID string) (bool, error) {
	result, err := r.db.Exec(
		"DELETE FROM devices WHERE id = ? AND user_id = ?",
		deviceID, userID,
	)
	if err != nil {
		return false, fmt.Errorf("delete device: %w", err)
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return false, fmt.Errorf("get rows affected: %w", err)
	}
	return rows > 0, nil
}

// DeviceBelongsToUser checks if a device belongs to a user.
func (r *DeviceRepo) DeviceBelongsToUser(userID int64, deviceID string) (bool, error) {
	var count int
	err := r.db.QueryRow(
		"SELECT COUNT(*) FROM devices WHERE id = ? AND user_id = ?",
		deviceID, userID,
	).Scan(&count)
	if err != nil {
		return false, fmt.Errorf("check device ownership: %w", err)
	}
	return count > 0, nil
}

func generateDeviceID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return "dev-" + hex.EncodeToString(b)
}
