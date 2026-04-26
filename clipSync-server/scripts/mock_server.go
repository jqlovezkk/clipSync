// Mock Server for ClipSync Client Development
// Provides WebSocket (8080) and HTTP (8081) endpoints
// Simulates all server responses without requiring a real database
//
// Usage:
//   go run mock_server.go                    # Default settings
//   go run mock_server.go --latency=100ms    # Add simulated latency
//   go run mock_server.go --error-rate=0.1   # 10% error injection
//
// Features:
// - Accepts any login credentials
// - Simulates multi-device sync (echoes messages to other clients)
// - Heartbeat responses
// - Device list simulation
// - Configurable latency and error injection
// - No external dependencies (standard library only)

package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	mrand "math/rand"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Configuration
type Config struct {
	HTTPPort    int
	WSPort      int
	Latency     time.Duration
	ErrorRate   float64
	MaxDevices  int
}

// Protocol Messages
type WSMessage struct {
	Type      string      `json:"type"`
	Version   int         `json:"version"`
	Timestamp int64       `json:"timestamp"`
	DeviceID  string      `json:"device_id,omitempty"`
	Payload   interface{} `json:"payload"`
}

// Client represents a connected WebSocket client
type Client struct {
	ID         string
	Name       string
	Platform   string
	Conn       *websocket.Conn
	Send       chan []byte
	LastSeen   time.Time
	HeartbeatSeq int
}

// Hub manages all connected clients
type Hub struct {
	Clients    map[string]*Client
	Register   chan *Client
	Unregister chan *Client
	Broadcast  chan []byte
	Mu         sync.RWMutex
}

func NewHub() *Hub {
	return &Hub{
		Clients:    make(map[string]*Client),
		Register:   make(chan *Client),
		Unregister: make(chan *Client),
		Broadcast:  make(chan []byte, 256),
	}
}

func (h *Hub) Run() {
	for {
		select {
		case client := <-h.Register:
			h.Mu.Lock()
			h.Clients[client.ID] = client
			h.Mu.Unlock()
			log.Printf("Client connected: %s (%s) - Total: %d", client.Name, client.Platform, len(h.Clients))

		case client := <-h.Unregister:
			h.Mu.Lock()
			if _, ok := h.Clients[client.ID]; ok {
				delete(h.Clients, client.ID)
				close(client.Send)
			}
			h.Mu.Unlock()
			log.Printf("Client disconnected: %s - Total: %d", client.Name, len(h.Clients))

		case message := <-h.Broadcast:
			h.Mu.RLock()
			for _, client := range h.Clients {
				select {
				case client.Send <- message:
				default:
					close(client.Send)
					delete(h.Clients, client.ID)
				}
			}
			h.Mu.RUnlock()
		}
	}
}

func (h *Hub) GetDeviceList() []map[string]interface{} {
	h.Mu.RLock()
	defer h.Mu.RUnlock()

	devices := make([]map[string]interface{}, 0, len(h.Clients))
	for _, client := range h.Clients {
		devices = append(devices, map[string]interface{}{
			"device_id":   client.ID,
			"device_name": client.Name,
			"platform":    client.Platform,
			"last_seen":   client.LastSeen.UnixMilli(),
			"is_online":   true,
		})
	}

	// Add some simulated offline devices
	devices = append(devices, map[string]interface{}{
		"device_id":   "dev-simulated-001",
		"device_name": "MacBook-Pro",
		"platform":    "macos",
		"last_seen":   time.Now().Add(-5 * time.Minute).UnixMilli(),
		"is_online":   false,
	})

	return devices
}

var (
	config Config
	hub    *Hub
	upgrader = websocket.Upgrader{
		ReadBufferSize:  4096,
		WriteBufferSize: 4096,
		CheckOrigin: func(r *http.Request) bool {
			return true // Allow all origins for development
		},
	}
)

func generateID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return "dev-" + hex.EncodeToString(b)
}

func simulateLatency() {
	if config.Latency > 0 {
		jitter := time.Duration(float64(config.Latency) * 0.5)
		actual := config.Latency + jitter - time.Duration(mrand.Int63n(int64(jitter*2)))
		time.Sleep(actual)
	}
}

func shouldInjectError() bool {
	if config.ErrorRate <= 0 {
		return false
	}
	b := make([]byte, 1)
	rand.Read(b)
	return float64(b[0])/255.0 < config.ErrorRate
}

func sendError(conn *websocket.Conn, code, message string) {
	errMsg := WSMessage{
		Type:      "error",
		Version:   1,
		Timestamp: time.Now().UnixMilli(),
		Payload: map[string]interface{}{
			"code":    code,
			"message": message,
		},
	}
	data, _ := json.Marshal(errMsg)
	conn.WriteMessage(websocket.TextMessage, data)
}

func sendJSON(conn *websocket.Conn, msg WSMessage) {
	data, _ := json.Marshal(msg)
	conn.WriteMessage(websocket.TextMessage, data)
}

// WebSocket Handler
func wsHandler(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket upgrade failed: %v", err)
		return
	}

	client := &Client{
		ID:       generateID(),
		Name:     "Dev-Client",
		Platform: "unknown",
		Conn:     conn,
		Send:     make(chan []byte, 256),
		LastSeen: time.Now(),
	}

	hub.Register <- client

	go client.writePump()
	go client.readPump()
}

func (c *Client) readPump() {
	defer func() {
		hub.Unregister <- c
		c.Conn.Close()
	}()

	for {
		_, message, err := c.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseNormalClosure) {
				log.Printf("WebSocket error: %v", err)
			}
			break
		}

		c.LastSeen = time.Now()

		var msg WSMessage
		if err := json.Unmarshal(message, &msg); err != nil {
			sendError(c.Conn, "INVALID_PAYLOAD", "Failed to parse message")
			continue
		}

		// Inject errors if configured
		if shouldInjectError() {
			sendError(c.Conn, "INTERNAL_ERROR", "Simulated error for testing")
			continue
		}

		simulateLatency()
		c.handleMessage(msg)
	}
}

func (c *Client) writePump() {
	defer func() {
		c.Conn.Close()
	}()

	for {
		message, ok := <-c.Send
		if !ok {
			c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
			return
		}

		w, err := c.Conn.NextWriter(websocket.TextMessage)
		if err != nil {
			return
		}
		w.Write(message)

		// Pump additional messages if available
		n := len(c.Send)
		for i := 0; i < n; i++ {
			w.Write([]byte{'\n'})
			w.Write(<-c.Send)
		}

		if err := w.Close(); err != nil {
			return
		}
	}
}

func (c *Client) handleMessage(msg WSMessage) {
	switch msg.Type {
	case "auth":
		c.handleAuth(msg)
	case "heartbeat":
		c.handleHeartbeat(msg)
	case "clipboard_push":
		c.handleClipboardPush(msg)
	case "clipboard_pull":
		c.handleClipboardPull(msg)
	case "device_list":
		c.handleDeviceList(msg)
	case "pong":
		// Server initiated ping response
	case "ping":
		// Respond to server ping
		sendJSON(c.Conn, WSMessage{
			Type:      "pong",
			Version:   1,
			Timestamp: time.Now().UnixMilli(),
		})
	default:
		sendError(c.Conn, "INVALID_PAYLOAD", fmt.Sprintf("Unknown message type: %s", msg.Type))
	}
}

func (c *Client) handleAuth(msg WSMessage) {
	simulateLatency()

	payload, _ := msg.Payload.(map[string]interface{})
	token, _ := payload["token"].(string)

	if token == "" {
		sendError(c.Conn, "AUTH_FAILED", "Token required")
		return
	}

	// Accept any token in mock mode
	if name, ok := payload["device_name"].(string); ok {
		c.Name = name
	}
	if platform, ok := payload["platform"].(string); ok {
		c.Platform = platform
	}

	sendJSON(c.Conn, WSMessage{
		Type:      "auth_response",
		Version:   1,
		Timestamp: time.Now().UnixMilli(),
		DeviceID:  c.ID,
		Payload: map[string]interface{}{
			"success": true,
			"device_id": c.ID,
			"message":   "Authenticated (mock mode)",
		},
	})

	log.Printf("Client authenticated: %s (%s) [%s]", c.Name, c.Platform, c.ID)
}

func (c *Client) handleHeartbeat(msg WSMessage) {
	c.HeartbeatSeq++
	sendJSON(c.Conn, WSMessage{
		Type:      "heartbeat_ack",
		Version:   1,
		Timestamp: time.Now().UnixMilli(),
		Payload: map[string]interface{}{
			"seq": c.HeartbeatSeq,
		},
	})
}

func (c *Client) handleClipboardPush(msg WSMessage) {
	payload, _ := msg.Payload.(map[string]interface{})

	// Echo to all other connected clients (simulating sync)
	hub.Mu.RLock()
	for id, client := range hub.Clients {
		if id != c.ID {
			syncMsg := WSMessage{
				Type:      "clipboard_sync",
				Version:   1,
				Timestamp: time.Now().UnixMilli(),
				Payload: map[string]interface{}{
					"source_device_id":   c.ID,
					"source_device_name": c.Name,
					"content_type":       payload["content_type"],
					"content":            payload["content"],
					"format":             payload["format"],
					"size":               payload["size"],
					"checksum":           payload["checksum"],
					"encrypted":          false,
				},
			}
			data, _ := json.Marshal(syncMsg)
			select {
			case client.Send <- data:
			default:
			}
		}
	}
	hub.Mu.RUnlock()

	// Acknowledge to sender
	sendJSON(c.Conn, WSMessage{
		Type:      "clipboard_sync",
		Version:   1,
		Timestamp: time.Now().UnixMilli(),
		Payload: map[string]interface{}{
			"source_device_id":   c.ID,
			"source_device_name": c.Name,
			"content_type":       payload["content_type"],
			"content":            payload["content"],
			"format":             payload["format"],
			"size":               payload["size"],
			"checksum":           payload["checksum"],
			"encrypted":          false,
		},
	})

	log.Printf("Clipboard pushed from %s: type=%s, size=%v", c.Name, payload["content_type"], payload["size"])
}

func (c *Client) handleClipboardPull(msg WSMessage) {
	// Return simulated clipboard history
	items := []map[string]interface{}{
		{
			"id":               1,
			"content_type":     "text",
			"content":          "Hello from mock server! This is a simulated clipboard item.",
			"format":           "text/plain",
			"size":             58,
			"checksum":         "simulated-checksum-001",
			"source_device_id": "dev-simulated-001",
			"source_device_name": "MacBook-Pro",
			"created_at":       time.Now().Add(-10 * time.Minute).UnixMilli(),
		},
		{
			"id":               2,
			"content_type":     "text",
			"content":          "npm install clipsync-client",
			"format":           "text/plain",
			"size":             28,
			"checksum":         "simulated-checksum-002",
			"source_device_id": "dev-simulated-002",
			"source_device_name": "Windows-PC",
			"created_at":       time.Now().Add(-5 * time.Minute).UnixMilli(),
		},
	}

	sendJSON(c.Conn, WSMessage{
		Type:      "clipboard_history",
		Version:   1,
		Timestamp: time.Now().UnixMilli(),
		Payload: map[string]interface{}{
			"items":    items,
			"total":    len(items),
			"has_more": false,
		},
	})
}

func (c *Client) handleDeviceList(msg WSMessage) {
	devices := hub.GetDeviceList()

	sendJSON(c.Conn, WSMessage{
		Type:      "device_list_response",
		Version:   1,
		Timestamp: time.Now().UnixMilli(),
		Payload: map[string]interface{}{
			"devices": devices,
		},
	})
}

// HTTP Handlers
type AuthRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceName string `json:"device_name"`
	Platform   string `json:"platform"`
}

type AuthResponse struct {
	Success   bool   `json:"success"`
	Token     string `json:"token,omitempty"`
	DeviceID  string `json:"device_id,omitempty"`
	ExpiresAt int64  `json:"expires_at,omitempty"`
	Error     string `json:"error,omitempty"`
}

func loginHandler(w http.ResponseWriter, r *http.Request) {
	simulateLatency()

	if shouldInjectError() {
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"success": false,
			"error":   "INTERNAL_ERROR",
		})
		return
	}

	var req AuthRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
		})
		return
	}

	// Accept any credentials in mock mode
	token := generateID()
	deviceID := generateID()

	writeJSON(w, http.StatusOK, AuthResponse{
		Success:   true,
		Token:     token,
		DeviceID:  deviceID,
		ExpiresAt: time.Now().Add(24 * time.Hour).UnixMilli(),
	})

	log.Printf("Login successful: %s (%s)", req.DeviceName, req.Platform)
}

func registerHandler(w http.ResponseWriter, r *http.Request) {
	simulateLatency()

	var req AuthRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
		})
		return
	}

	// Accept any registration in mock mode
	token := generateID()
	deviceID := generateID()

	writeJSON(w, http.StatusCreated, AuthResponse{
		Success:   true,
		Token:     token,
		DeviceID:  deviceID,
		ExpiresAt: time.Now().Add(24 * time.Hour).UnixMilli(),
	})

	log.Printf("Registration successful: %s (%s)", req.DeviceName, req.Platform)
}

func refreshHandler(w http.ResponseWriter, r *http.Request) {
	simulateLatency()

	authHeader := r.Header.Get("Authorization")
	if !strings.HasPrefix(authHeader, "Bearer ") {
		writeJSON(w, http.StatusUnauthorized, map[string]interface{}{
			"success": false,
			"error":   "AUTH_FAILED",
		})
		return
	}

	writeJSON(w, http.StatusOK, AuthResponse{
		Success:   true,
		Token:     generateID(),
		ExpiresAt: time.Now().Add(24 * time.Hour).UnixMilli(),
	})
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	hub.Mu.RLock()
	clientCount := len(hub.Clients)
	hub.Mu.RUnlock()

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status":             "ok",
		"version":            "1.0.0-mock",
		"uptime":             time.Since(startTime).Seconds(),
		"connected_clients":  clientCount,
		"mode":               "mock",
	})
}

func devicesHandler(w http.ResponseWriter, r *http.Request) {
	simulateLatency()

	devices := hub.GetDeviceList()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"devices": devices,
	})
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

var startTime time.Time

func setupHTTPRoutes() {
	// HTTP API routes
	http.HandleFunc("/api/v1/auth/login", loginHandler)
	http.HandleFunc("/api/v1/auth/register", registerHandler)
	http.HandleFunc("/api/v1/auth/refresh", refreshHandler)
	http.HandleFunc("/api/v1/health", healthHandler)
	http.HandleFunc("/api/v1/devices", devicesHandler)
}

func setupWSRoutes() {
	// WebSocket routes
	http.HandleFunc("/ws", wsHandler)
}

func main() {
	// Parse flags
	flag.IntVar(&config.HTTPPort, "http-port", 8081, "HTTP API port")
	flag.IntVar(&config.WSPort, "ws-port", 8080, "WebSocket port")
	flag.DurationVar(&config.Latency, "latency", 50*time.Millisecond, "Simulated network latency")
	flag.Float64Var(&config.ErrorRate, "error-rate", 0.0, "Error injection rate (0.0-1.0)")
	flag.IntVar(&config.MaxDevices, "max-devices", 10, "Maximum connected devices")
	flag.Parse()

	startTime = time.Now()
	hub = NewHub()
	go hub.Run()

	// HTTP API server (separate mux)
	httpAPIMux := http.NewServeMux()
	httpAPIMux.HandleFunc("/api/v1/auth/login", loginHandler)
	httpAPIMux.HandleFunc("/api/v1/auth/register", registerHandler)
	httpAPIMux.HandleFunc("/api/v1/auth/refresh", refreshHandler)
	httpAPIMux.HandleFunc("/api/v1/health", healthHandler)
	httpAPIMux.HandleFunc("/api/v1/devices", devicesHandler)

	wsMux := http.NewServeMux()
	wsMux.HandleFunc("/ws", wsHandler)

	fmt.Println("╔══════════════════════════════════════════════════════════╗")
	fmt.Println("║           ClipSync Mock Server - Development Mode        ║")
	fmt.Println("╠══════════════════════════════════════════════════════════╣")
	fmt.Printf("║  WebSocket:    ws://localhost:%d/ws                      ║\n", config.WSPort)
	fmt.Printf("║  HTTP API:     http://localhost:%d/api/v1/...            ║\n", config.HTTPPort)
	fmt.Printf("║  Health Check: http://localhost:%d/api/v1/health         ║\n", config.HTTPPort)
	fmt.Printf("║  Latency:      %v                                        ║\n", config.Latency)
	fmt.Printf("║  Error Rate:   %.1f%%                                      ║\n", config.ErrorRate*100)
	fmt.Println("╠══════════════════════════════════════════════════════════╣")
	fmt.Println("║  Features:                                                ║")
	fmt.Println("║  ✓ Accepts any login credentials                         ║")
	fmt.Println("║  ✓ Simulates multi-device sync                           ║")
	fmt.Println("║  ✓ Heartbeat responses                                   ║")
	fmt.Println("║  ✓ Device list simulation                                ║")
	fmt.Println("║  ✓ Clipboard history simulation                          ║")
	fmt.Println("║  ✓ Configurable latency & error injection                ║")
	fmt.Println("╚══════════════════════════════════════════════════════════╝")
	fmt.Println()
	log.Printf("Mock server starting: HTTP on :%d, WS on :%d", config.HTTPPort, config.WSPort)

	// Start HTTP API server
	go func() {
		httpServer := &http.Server{
			Addr:    fmt.Sprintf(":%d", config.HTTPPort),
			Handler: httpAPIMux,
		}
		if err := httpServer.ListenAndServe(); err != nil {
			log.Fatalf("HTTP server failed: %v", err)
		}
	}()

	// Start WebSocket server on separate port
	wsServer := &http.Server{
		Addr:    fmt.Sprintf(":%d", config.WSPort),
		Handler: wsMux,
	}
	if err := wsServer.ListenAndServe(); err != nil {
		log.Fatalf("WebSocket server failed: %v", err)
	}
}
