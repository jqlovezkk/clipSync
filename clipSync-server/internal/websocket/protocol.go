package websocket

import (
	"net/http"

	"github.com/gorilla/websocket"
)

// Upgrader creates a configured websocket.Upgrader.
func NewUpgrader() websocket.Upgrader {
	return websocket.Upgrader{
		ReadBufferSize:  4096,
		WriteBufferSize: 4096,
		CheckOrigin: func(r *http.Request) bool {
			return true // Allow all origins; restrict in production
		},
	}
}

// WSHandler returns an http.HandlerFunc that handles WebSocket connections.
func (h *Hub) WSHandler() http.HandlerFunc {
	upgrader := NewUpgrader()
	return func(w http.ResponseWriter, r *http.Request) {
		h.HandleWebSocket(w, r, upgrader)
	}
}
