package httpserver

import (
	"clipsync-server/internal/websocket"
	"database/sql"
	"net/http"
	"time"
)

// HealthHandler handles the health check endpoint.
type HealthHandler struct {
	hub       *websocket.Hub
	db        *sql.DB
	startTime time.Time
	version   string
}

// NewHealthHandler creates a new health handler.
func NewHealthHandler(hub *websocket.Hub, db *sql.DB, version string) *HealthHandler {
	return &HealthHandler{
		hub:       hub,
		db:        db,
		startTime: time.Now(),
		version:   version,
	}
}

// Health handles GET /api/v1/health.
func (h *HealthHandler) Health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}

	uptime := time.Since(h.startTime).Seconds()

	// Check database connectivity
	dbStatus := "ok"
	if h.db != nil {
		if err := h.db.Ping(); err != nil {
			dbStatus = "error: " + err.Error()
		}
	} else {
		dbStatus = "not configured"
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":            "ok",
		"version":           h.version,
		"uptime":            uptime,
		"connected_clients": h.hub.ClientCount(),
		"database":          dbStatus,
	})
}
