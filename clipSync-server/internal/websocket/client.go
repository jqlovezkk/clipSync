package websocket

import (
	"clipsync-server/pkg/protocol"
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Client represents a single WebSocket connection.
type Client struct {
	ID         string
	UserID     int64
	Username   string
	DeviceID   string
	DeviceName string
	Platform   string
	Conn       *websocket.Conn
	Send       chan []byte
	Hub        *Hub
	LastSeen   time.Time
	Authed     bool
	mu         sync.Mutex

	heartbeatSeq   int
	heartbeatTimer *time.Timer
	authTimer      *time.Timer
}

// readPump pumps messages from the WebSocket connection to the client.
func (c *Client) readPump() {
	defer func() {
		c.Hub.unregister <- c
		c.Hub.decrementCount()
		log.Printf("[WS] Connection closed: %s - Total: %d", c.ID, c.Hub.ClientCount())
		c.Conn.Close()
	}()

	c.Conn.SetReadLimit(1 * 1024 * 1024) // 1MB max message size for image/file content
	c.Conn.SetReadDeadline(time.Now().Add(c.Hub.heartbeatTimeout))
	c.Conn.SetPongHandler(func(string) error {
		c.Conn.SetReadDeadline(time.Now().Add(c.Hub.heartbeatTimeout))
		return nil
	})

	for {
		_, message, err := c.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseNormalClosure, websocket.CloseAbnormalClosure) {
				log.Printf("[WS] Error reading from %s: %v", c.ID, err)
			}
			break
		}

		c.LastSeen = time.Now()
		c.Conn.SetReadDeadline(time.Now().Add(c.Hub.heartbeatTimeout))

		var msg protocol.WSMessage
		if err := json.Unmarshal(message, &msg); err != nil {
			c.sendError("INVALID_PAYLOAD", "Failed to parse message")
			continue
		}

		c.handleMessage(msg)
	}
}

// writePump pumps messages from the client's Send channel to the WebSocket connection.
func (c *Client) writePump() {
	ticker := time.NewTicker(30 * time.Second)
	defer func() {
		ticker.Stop()
		c.Conn.Close()
	}()

	for {
		select {
		case message, ok := <-c.Send:
			if !ok {
				c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			c.mu.Lock()
			w, err := c.Conn.NextWriter(websocket.TextMessage)
			if err != nil {
				c.mu.Unlock()
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
				c.mu.Unlock()
				return
			}
			c.mu.Unlock()

		case <-ticker.C:
			// Server-initiated ping
			c.mu.Lock()
			c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				c.mu.Unlock()
				return
			}
			c.mu.Unlock()
		}
	}
}

// sendError sends an error message to this client.
func (c *Client) sendError(code, message string) {
	msg := protocol.WSMessage{
		Type:      protocol.TypeError,
		Version:   protocol.Version,
		Timestamp: protocol.NowMillis(),
		Payload: protocol.ErrorPayload{
			Code:    code,
			Message: message,
		},
	}
	data, _ := json.Marshal(msg)
	select {
	case c.Send <- data:
	default:
	}
}

// sendJSON sends a protocol message to this client.
func (c *Client) sendJSON(msg protocol.WSMessage) {
	data, err := json.Marshal(msg)
	if err != nil {
		log.Printf("[WS] Failed to marshal message: %v", err)
		return
	}
	select {
	case c.Send <- data:
	default:
		log.Printf("[WS] Send buffer full for client %s", c.ID)
	}
}
