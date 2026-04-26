package websocket

import (
	"clipsync-server/internal/auth"
	"clipsync-server/internal/database"
	"clipsync-server/pkg/protocol"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Hub manages all connected WebSocket clients.
type Hub struct {
	clients    map[string]*Client
	register   chan *Client
	unregister chan *Client
	broadcast  chan *BroadcastMessage
	mu         sync.RWMutex

	authService *auth.Service
	clipRepo    *database.ClipboardRepo
	deviceRepo  *database.DeviceRepo
	userRepo    *database.UserRepo

	heartbeatTimeout time.Duration
	historyLimit     int
	clientCount      int
	countMu          sync.RWMutex
}

// BroadcastMessage represents a message to broadcast to specific clients.
type BroadcastMessage struct {
	Data          []byte
	ExcludeClient string // client ID to exclude (the sender)
	UserID        int64  // only broadcast to clients of this user
}

// NewHub creates a new WebSocket hub.
func NewHub(authSvc *auth.Service, clipRepo *database.ClipboardRepo, deviceRepo *database.DeviceRepo, userRepo *database.UserRepo, heartbeatTimeoutSec, historyLimit int) *Hub {
	return &Hub{
		clients:          make(map[string]*Client),
		register:         make(chan *Client),
		unregister:       make(chan *Client),
		broadcast:        make(chan *BroadcastMessage, 256),
		authService:      authSvc,
		clipRepo:         clipRepo,
		deviceRepo:       deviceRepo,
		userRepo:         userRepo,
		heartbeatTimeout: time.Duration(heartbeatTimeoutSec) * time.Second,
		historyLimit:     historyLimit,
	}
}

// Run starts the hub's main loop.
func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.mu.Lock()
			h.clients[client.ID] = client
			h.mu.Unlock()
			h.incrementCount()
			log.Printf("[WS] Client connected: %s (%s) - Total: %d", client.DeviceName, client.Platform, h.ClientCount())

		case client := <-h.unregister:
			h.mu.Lock()
			if _, ok := h.clients[client.ID]; ok {
				delete(h.clients, client.ID)
				close(client.Send)
			}
			h.mu.Unlock()
			h.decrementCount()
			log.Printf("[WS] Client disconnected: %s - Total: %d", client.DeviceName, h.ClientCount())

		case msg := <-h.broadcast:
			h.mu.RLock()
			var disconnected []*Client
			for _, client := range h.clients {
				if client.UserID != msg.UserID {
					continue
				}
				if client.ID == msg.ExcludeClient {
					continue
				}
				select {
				case client.Send <- msg.Data:
				default:
					// Client send buffer full, mark for disconnect
					disconnected = append(disconnected, client)
				}
			}
			h.mu.RUnlock()

			// Disconnect clients with full buffers outside of read lock
			for _, client := range disconnected {
				h.mu.Lock()
				if _, ok := h.clients[client.ID]; ok {
					delete(h.clients, client.ID)
					close(client.Send)
					h.decrementCount()
				}
				h.mu.Unlock()
			}
		}
	}
}

// Broadcast sends a message to all clients of a specific user, excluding one.
func (h *Hub) Broadcast(data []byte, excludeClientID string, userID int64) {
	h.broadcast <- &BroadcastMessage{
		Data:          data,
		ExcludeClient: excludeClientID,
		UserID:        userID,
	}
}

// GetClientCountForUser returns the number of connected clients for a user.
func (h *Hub) GetClientCountForUser(userID int64) int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	count := 0
	for _, c := range h.clients {
		if c.UserID == userID {
			count++
		}
	}
	return count
}

// ClientCount returns the total number of connected clients.
func (h *Hub) ClientCount() int {
	h.countMu.RLock()
	defer h.countMu.RUnlock()
	return h.clientCount
}

func (h *Hub) incrementCount() {
	h.countMu.Lock()
	defer h.countMu.Unlock()
	h.clientCount++
}

func (h *Hub) decrementCount() {
	h.countMu.Lock()
	defer h.countMu.Unlock()
	h.clientCount--
}

// DisconnectDevice closes the WebSocket connection for a specific device ID.
func (h *Hub) DisconnectDevice(deviceID string) {
	h.mu.RLock()
	for _, client := range h.clients {
		if client.DeviceID == deviceID {
			h.unregister <- client
			client.Conn.Close()
			break
		}
	}
	h.mu.RUnlock()
}

// GetOnlineDevices returns a map of device IDs that are currently connected for a user.
func (h *Hub) GetOnlineDevices(userID int64) map[string]bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	online := make(map[string]bool)
	for _, client := range h.clients {
		if client.UserID == userID {
			online[client.DeviceID] = true
		}
	}
	return online
}

// HandleWebSocket upgrades an HTTP connection to WebSocket and starts client handling.
func (h *Hub) HandleWebSocket(w http.ResponseWriter, r *http.Request, upgrader websocket.Upgrader) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[WS] Upgrade failed: %v", err)
		return
	}

	client := &Client{
		ID:       generateClientID(),
		Conn:     conn,
		Send:     make(chan []byte, 256),
		Hub:      h,
		LastSeen: time.Now(),
	}

	// Set auth timeout: disconnect if not authenticated within 30 seconds
	client.authTimer = time.AfterFunc(30*time.Second, func() {
		if !client.Authed {
			log.Printf("[WS] Client %s auth timeout, disconnecting", client.ID)
			// Send error via channel to avoid concurrent write panic
			errMsg := protocol.WSMessage{
				Type:      protocol.TypeError,
				Version:   protocol.Version,
				Timestamp: protocol.NowMillis(),
				Payload: protocol.ErrorPayload{
					Code:    "AUTH_TIMEOUT",
					Message: "Authentication required within 30 seconds",
				},
			}
			if data, err := json.Marshal(errMsg); err == nil {
				select {
				case client.Send <- data:
				default:
				}
			}
			conn.Close()
		}
	})

	go client.writePump()
	go client.readPump()
}

func generateClientID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return "ws-" + hex.EncodeToString(b)
}
