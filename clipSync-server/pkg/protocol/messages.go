package protocol

import "time"

// WSMessage is the envelope for all WebSocket messages.
type WSMessage struct {
	Type      string      `json:"type"`
	Version   int         `json:"version"`
	Timestamp int64       `json:"timestamp"`
	DeviceID  string      `json:"device_id,omitempty"`
	Payload   interface{} `json:"payload"`
}

// AuthPayload is the payload for auth messages.
type AuthPayload struct {
	Token      string `json:"token"`
	DeviceName string `json:"device_name,omitempty"`
	Platform   string `json:"platform,omitempty"`
}

// AuthResponsePayload is the payload for auth_response messages.
type AuthResponsePayload struct {
	Success bool   `json:"success"`
	DeviceID string `json:"device_id,omitempty"`
	Message  string `json:"message,omitempty"`
}

// HeartbeatPayload is the payload for heartbeat messages.
type HeartbeatPayload struct {
	Seq int `json:"seq"`
}

// ClipboardPushPayload is the payload for clipboard_push messages.
type ClipboardPushPayload struct {
	ContentType string `json:"content_type"`
	Content     string `json:"content"`
	Format      string `json:"format"`
	Size        int64  `json:"size"`
	Checksum    string `json:"checksum"`
	Encrypted   bool   `json:"encrypted"`
}

// ClipboardSyncPayload is the payload for clipboard_sync messages.
type ClipboardSyncPayload struct {
	SourceDeviceID   string `json:"source_device_id"`
	SourceDeviceName string `json:"source_device_name"`
	ContentType      string `json:"content_type"`
	Content          string `json:"content"`
	Format           string `json:"format"`
	Size             int64  `json:"size"`
	Checksum         string `json:"checksum"`
	Encrypted        bool   `json:"encrypted"`
}

// ClipboardPullPayload is the payload for clipboard_pull messages.
type ClipboardPullPayload struct {
	Limit   int `json:"limit"`
	AfterID int `json:"after_id"`
}

// ClipboardItem represents a single clipboard history item.
type ClipboardItem struct {
	ID               int64  `json:"id"`
	ContentType      string `json:"content_type"`
	Content          string `json:"content"`
	Format           string `json:"format"`
	Size             int64  `json:"size"`
	Checksum         string `json:"checksum"`
	SourceDeviceID   string `json:"source_device_id"`
	SourceDeviceName string `json:"source_device_name"`
	CreatedAt        int64  `json:"created_at"`
}

// ClipboardHistoryPayload is the payload for clipboard_history messages.
type ClipboardHistoryPayload struct {
	Items   []ClipboardItem `json:"items"`
	Total   int             `json:"total"`
	HasMore bool            `json:"has_more"`
}

// DeviceInfo represents a registered device.
type DeviceInfo struct {
	DeviceID   string `json:"device_id"`
	DeviceName string `json:"device_name"`
	Platform   string `json:"platform"`
	LastSeen   int64  `json:"last_seen"`
	IsOnline   bool   `json:"is_online"`
	CreatedAt  int64  `json:"created_at,omitempty"`
}

// DeviceListPayload is the payload for device_list_response messages.
type DeviceListPayload struct {
	Devices []DeviceInfo `json:"devices"`
}

// DeviceUnregisterPayload is the payload for device_unregister messages.
type DeviceUnregisterPayload struct {
	DeviceID string `json:"device_id"`
}

// ErrorPayload is the payload for error messages.
type ErrorPayload struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Message type constants.
const (
	TypeAuth             = "auth"
	TypeAuthResponse     = "auth_response"
	TypeHeartbeat        = "heartbeat"
	TypeHeartbeatAck     = "heartbeat_ack"
	TypeClipboardPush    = "clipboard_push"
	TypeClipboardSync    = "clipboard_sync"
	TypeClipboardPull    = "clipboard_pull"
	TypeClipboardHistory = "clipboard_history"
	TypeDeviceList       = "device_list"
	TypeDeviceListResp   = "device_list_response"
	TypeDeviceUnregister = "device_unregister"
	TypeError            = "error"
	TypePing             = "ping"
	TypePong             = "pong"
)

// Protocol version.
const Version = 1

// NowMillis returns the current time in Unix milliseconds.
func NowMillis() int64 {
	return time.Now().UnixMilli()
}
