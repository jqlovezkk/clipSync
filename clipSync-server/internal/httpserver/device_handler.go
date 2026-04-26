package httpserver

import (
	"clipsync-server/internal/auth"
	"clipsync-server/internal/database"
	"clipsync-server/internal/websocket"
	"net/http"
	"strings"
)

// DeviceHandler handles device management HTTP endpoints.
type DeviceHandler struct {
	deviceRepo *database.DeviceRepo
	hub        *websocket.Hub
}

// NewDeviceHandler creates a new device handler.
func NewDeviceHandler(deviceRepo *database.DeviceRepo, hub *websocket.Hub) *DeviceHandler {
	return &DeviceHandler{
		deviceRepo: deviceRepo,
		hub:        hub,
	}
}

// ListDevices handles GET /api/v1/devices.
func (h *DeviceHandler) ListDevices(w http.ResponseWriter, r *http.Request) {
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

	devices, err := h.deviceRepo.GetDevicesByUser(userID)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"error": "INTERNAL_ERROR",
		})
		return
	}

	// Build response with online status
	type DeviceResponse struct {
		DeviceID   string `json:"device_id"`
		DeviceName string `json:"device_name"`
		Platform   string `json:"platform"`
		LastSeen   int64  `json:"last_seen"`
		IsOnline   bool   `json:"is_online"`
		CreatedAt  int64  `json:"created_at"`
	}

	deviceResponses := make([]DeviceResponse, len(devices))
	for i, d := range devices {
		deviceResponses[i] = DeviceResponse{
			DeviceID:   d.ID,
			DeviceName: d.DeviceName,
			Platform:   d.Platform,
			LastSeen:   d.LastSeen,
			IsOnline:   false,
			CreatedAt:  d.CreatedAt,
		}
	}

	// Mark online devices
	onlineDevices := h.hub.GetOnlineDevices(userID)
	for i := range deviceResponses {
		if onlineDevices[deviceResponses[i].DeviceID] {
			deviceResponses[i].IsOnline = true
		}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"devices": deviceResponses,
	})
}

// DeleteDevice handles DELETE /api/v1/devices/{device_id}.
func (h *DeviceHandler) DeleteDevice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
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

	// Extract device_id from URL path
	deviceID := r.PathValue("device_id")
	if deviceID == "" {
		// Fallback: extract from URL path manually
		path := strings.TrimPrefix(r.URL.Path, "/api/v1/devices/")
		deviceID = path
	}

	if deviceID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"error": "INVALID_PAYLOAD",
		})
		return
	}

	deleted, err := h.deviceRepo.DeleteDevice(userID, deviceID)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"error": "INTERNAL_ERROR",
		})
		return
	}

	if !deleted {
		writeJSON(w, http.StatusNotFound, map[string]interface{}{
			"error": "DEVICE_NOT_FOUND",
		})
		return
	}

	// Disconnect the device if it's currently connected
	h.hub.DisconnectDevice(deviceID)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success": true,
	})
}
