package websocket

import (
	"clipsync-server/pkg/protocol"
	"encoding/json"
	"log"
	"time"
)

// handleMessage routes incoming messages to the appropriate handler.
func (c *Client) handleMessage(msg protocol.WSMessage) {
	switch msg.Type {
	case protocol.TypeAuth:
		c.handleAuth(msg)
	case protocol.TypeHeartbeat:
		c.handleHeartbeat(msg)
	case protocol.TypeClipboardPush:
		c.handleClipboardPush(msg)
	case protocol.TypeClipboardPull:
		c.handleClipboardPull(msg)
	case protocol.TypeDeviceList:
		c.handleDeviceList(msg)
	case protocol.TypeDeviceUnregister:
		c.handleDeviceUnregister(msg)
	case protocol.TypePong:
		// Response to server ping, update last seen
		c.LastSeen = time.Now()
	default:
		c.sendError("INVALID_PAYLOAD", "Unknown message type: "+msg.Type)
	}
}

// handleAuth authenticates a WebSocket connection.
func (c *Client) handleAuth(msg protocol.WSMessage) {
	if c.Authed {
		c.sendError("INVALID_PAYLOAD", "Already authenticated")
		return
	}

	// Parse payload
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("INVALID_PAYLOAD", "Invalid auth payload")
		return
	}

	var authPayload protocol.AuthPayload
	if err := json.Unmarshal(payloadBytes, &authPayload); err != nil {
		c.sendError("INVALID_PAYLOAD", "Failed to parse auth payload")
		return
	}

	if authPayload.Token == "" {
		c.sendError("AUTH_FAILED", "Token required")
		return
	}

	// Validate JWT token
	claims, err := c.Hub.authService.ValidateWSAuth(authPayload.Token)
	if err != nil {
		c.sendError("AUTH_FAILED", "Invalid token")
		return
	}

	// Set client identity
	c.UserID = claims.UserID
	c.Username = claims.Username
	c.DeviceID = claims.DeviceID
	c.Authed = true

	// Stop auth timeout timer
	if c.authTimer != nil {
		c.authTimer.Stop()
		c.authTimer = nil
	}

	// Get device info
	device, err := c.Hub.deviceRepo.GetDevice(c.DeviceID)
	if err == nil && device != nil {
		c.DeviceName = device.DeviceName
		c.Platform = device.Platform
		c.Hub.deviceRepo.UpdateDeviceLastSeen(c.DeviceID)
	}

	// Override with auth payload if provided
	if authPayload.DeviceName != "" {
		c.DeviceName = authPayload.DeviceName
	}
	if authPayload.Platform != "" {
		c.Platform = authPayload.Platform
	}

	// Register with hub (use goroutine to avoid blocking auth response)
	go func() {
		c.Hub.register <- c
	}()

	// Send auth response
	c.sendJSON(protocol.WSMessage{
		Type:      protocol.TypeAuthResponse,
		Version:   protocol.Version,
		Timestamp: protocol.NowMillis(),
		DeviceID:  c.DeviceID,
		Payload: protocol.AuthResponsePayload{
			Success: true,
			DeviceID: c.DeviceID,
			Message:  "Authenticated successfully",
		},
	})

	log.Printf("[WS] Authenticated: %s (%s) [%s] user=%d", c.DeviceName, c.Platform, c.DeviceID, c.UserID)
}

// handleHeartbeat processes a client heartbeat.
func (c *Client) handleHeartbeat(msg protocol.WSMessage) {
	if !c.Authed {
		c.sendError("AUTH_FAILED", "Not authenticated")
		return
	}

	c.heartbeatSeq++

	var hbPayload protocol.HeartbeatPayload
	if msg.Payload != nil {
		payloadBytes, _ := json.Marshal(msg.Payload)
		json.Unmarshal(payloadBytes, &hbPayload)
	}

	c.sendJSON(protocol.WSMessage{
		Type:      protocol.TypeHeartbeatAck,
		Version:   protocol.Version,
		Timestamp: protocol.NowMillis(),
		Payload: protocol.HeartbeatPayload{
			Seq: c.heartbeatSeq,
		},
	})

	// Update device last seen
	if c.DeviceID != "" {
		c.Hub.deviceRepo.UpdateDeviceLastSeen(c.DeviceID)
	}
}

// handleClipboardPush processes a clipboard push from a client.
func (c *Client) handleClipboardPush(msg protocol.WSMessage) {
	if !c.Authed {
		c.sendError("AUTH_FAILED", "Not authenticated")
		return
	}

	var pushPayload protocol.ClipboardPushPayload
	payloadBytes, _ := json.Marshal(msg.Payload)
	if err := json.Unmarshal(payloadBytes, &pushPayload); err != nil {
		c.sendError("INVALID_PAYLOAD", "Invalid clipboard payload")
		return
	}

	// Validate content type
	if pushPayload.ContentType != "text" && pushPayload.ContentType != "image" && pushPayload.ContentType != "file" {
		c.sendError("INVALID_PAYLOAD", "Invalid content type: "+pushPayload.ContentType)
		return
	}

	// Check for duplicate checksum
	if pushPayload.Checksum != "" {
		isDup, err := c.Hub.clipRepo.CheckDuplicateChecksum(c.UserID, pushPayload.Checksum)
		if err != nil {
			log.Printf("[WS] Error checking duplicate: %v", err)
		}
		if isDup {
			log.Printf("[WS] Duplicate clipboard ignored: user_id=%d device_id=%s checksum=%s", c.UserID, c.DeviceID, pushPayload.Checksum)
			return
		}
	}

	// Store in database
	entry, err := c.Hub.clipRepo.AddEntry(
		c.UserID,
		pushPayload.ContentType,
		pushPayload.Content,
		pushPayload.Format,
		pushPayload.Size,
		pushPayload.Checksum,
		c.DeviceID,
		c.DeviceName,
	)
	if err != nil {
		log.Printf("[WS] Error storing clipboard: %v", err)
		c.sendError("INTERNAL_ERROR", "Failed to store clipboard")
		return
	}

	// Build sync message
	syncPayload := protocol.ClipboardSyncPayload{
		SourceDeviceID:   c.DeviceID,
		SourceDeviceName: c.DeviceName,
		ContentType:      pushPayload.ContentType,
		Content:          pushPayload.Content,
		Format:           pushPayload.Format,
		Size:             pushPayload.Size,
		Checksum:         pushPayload.Checksum,
		Encrypted:        pushPayload.Encrypted,
	}

	syncMsg := protocol.WSMessage{
		Type:      protocol.TypeClipboardSync,
		Version:   protocol.Version,
		Timestamp: protocol.NowMillis(),
		Payload:   syncPayload,
	}

	data, _ := json.Marshal(syncMsg)

	// Broadcast to other devices of the same user
	c.Hub.Broadcast(data, c.ID, c.UserID)

	// Also send back to the sender as acknowledgment
	c.sendJSON(protocol.WSMessage{
		Type:      protocol.TypeClipboardSync,
		Version:   protocol.Version,
		Timestamp: protocol.NowMillis(),
		DeviceID:  c.DeviceID,
		Payload: protocol.ClipboardSyncPayload{
			SourceDeviceID:   c.DeviceID,
			SourceDeviceName: c.DeviceName,
			ContentType:      pushPayload.ContentType,
			Content:          pushPayload.Content,
			Format:           pushPayload.Format,
			Size:             pushPayload.Size,
			Checksum:         pushPayload.Checksum,
			Encrypted:        pushPayload.Encrypted,
		},
	})

	log.Printf("[WS] Clipboard pushed from %s: type=%s, size=%d, id=%d", c.DeviceName, pushPayload.ContentType, pushPayload.Size, entry.ID)
}

// handleClipboardPull retrieves clipboard history.
func (c *Client) handleClipboardPull(msg protocol.WSMessage) {
	if !c.Authed {
		c.sendError("AUTH_FAILED", "Not authenticated")
		return
	}

	var pullPayload protocol.ClipboardPullPayload
	if msg.Payload != nil {
		payloadBytes, _ := json.Marshal(msg.Payload)
		json.Unmarshal(payloadBytes, &pullPayload)
	}

	if pullPayload.Limit <= 0 {
		pullPayload.Limit = 20
	}

	entries, total, hasMore, err := c.Hub.clipRepo.GetHistory(c.UserID, pullPayload.Limit, pullPayload.AfterID)
	if err != nil {
		log.Printf("[WS] Error fetching history: %v", err)
		c.sendError("INTERNAL_ERROR", "Failed to fetch history")
		return
	}

	items := make([]protocol.ClipboardItem, len(entries))
	for i, e := range entries {
		items[i] = protocol.ClipboardItem{
			ID:               e.ID,
			ContentType:      e.ContentType,
			Content:          e.Content,
			Format:           e.Format,
			Size:             e.Size,
			Checksum:         e.Checksum,
			SourceDeviceID:   e.SourceDeviceID,
			SourceDeviceName: e.SourceDeviceName,
			CreatedAt:        e.CreatedAt,
		}
	}

	c.sendJSON(protocol.WSMessage{
		Type:      protocol.TypeClipboardHistory,
		Version:   protocol.Version,
		Timestamp: protocol.NowMillis(),
		Payload: protocol.ClipboardHistoryPayload{
			Items:   items,
			Total:   total,
			HasMore: hasMore,
		},
	})
}

// handleDeviceList returns the list of registered devices.
func (c *Client) handleDeviceList(msg protocol.WSMessage) {
	if !c.Authed {
		c.sendError("AUTH_FAILED", "Not authenticated")
		return
	}

	devices, err := c.Hub.deviceRepo.GetDevicesByUser(c.UserID)
	if err != nil {
		log.Printf("[WS] Error fetching devices: %v", err)
		c.sendError("INTERNAL_ERROR", "Failed to fetch devices")
		return
	}

	onlineCount := c.Hub.GetClientCountForUser(c.UserID)

	deviceInfos := make([]protocol.DeviceInfo, len(devices))
	for i, d := range devices {
		deviceInfos[i] = protocol.DeviceInfo{
			DeviceID:   d.ID,
			DeviceName: d.DeviceName,
			Platform:   d.Platform,
			LastSeen:   d.LastSeen,
			IsOnline:   false, // Will be updated below
			CreatedAt:  d.CreatedAt,
		}
	}

	// Mark online devices
	c.Hub.mu.RLock()
	for _, wsClient := range c.Hub.clients {
		if wsClient.UserID == c.UserID {
			for i := range deviceInfos {
				if deviceInfos[i].DeviceID == wsClient.DeviceID {
					deviceInfos[i].IsOnline = true
					break
				}
			}
		}
	}
	c.Hub.mu.RUnlock()

	_ = onlineCount // Used for marking online status above

	c.sendJSON(protocol.WSMessage{
		Type:      protocol.TypeDeviceListResp,
		Version:   protocol.Version,
		Timestamp: protocol.NowMillis(),
		Payload: protocol.DeviceListPayload{
			Devices: deviceInfos,
		},
	})
}

// handleDeviceUnregister unregisters a device.
func (c *Client) handleDeviceUnregister(msg protocol.WSMessage) {
	if !c.Authed {
		c.sendError("AUTH_FAILED", "Not authenticated")
		return
	}

	var unregPayload protocol.DeviceUnregisterPayload
	payloadBytes, _ := json.Marshal(msg.Payload)
	if err := json.Unmarshal(payloadBytes, &unregPayload); err != nil {
		c.sendError("INVALID_PAYLOAD", "Invalid payload")
		return
	}

	if unregPayload.DeviceID == "" {
		c.sendError("INVALID_PAYLOAD", "device_id required")
		return
	}

	deleted, err := c.Hub.deviceRepo.DeleteDevice(c.UserID, unregPayload.DeviceID)
	if err != nil {
		log.Printf("[WS] Error deleting device: %v", err)
		c.sendError("INTERNAL_ERROR", "Failed to unregister device")
		return
	}

	if !deleted {
		c.sendError("DEVICE_NOT_FOUND", "Device not found")
		return
	}

	// Disconnect the device if it's currently connected
	c.Hub.mu.RLock()
	for _, client := range c.Hub.clients {
		if client.DeviceID == unregPayload.DeviceID && client.ID != c.ID {
			c.Hub.unregister <- client
			client.Conn.Close()
			break
		}
	}
	c.Hub.mu.RUnlock()

	c.sendJSON(protocol.WSMessage{
		Type:      protocol.TypeDeviceListResp,
		Version:   protocol.Version,
		Timestamp: protocol.NowMillis(),
		Payload: protocol.DeviceListPayload{
			Devices: []protocol.DeviceInfo{},
		},
	})
}
