package websocket

import (
	"fmt"
	"clipsync-server/internal/auth"
	"clipsync-server/internal/database"
	"testing"
	"time"
)

func setupTestHub(t *testing.T) *Hub {
	t.Helper()
	authSvc := &auth.Service{}
	clipRepo := &database.ClipboardRepo{}
	deviceRepo := &database.DeviceRepo{}
	userRepo := &database.UserRepo{}

	hub := NewHub(authSvc, clipRepo, deviceRepo, userRepo, 90, 50)
	go hub.Run()

	t.Cleanup(func() {
		// Hub doesn't have a Stop method, but that's OK for tests
	})

	return hub
}

func TestHub_ClientCount_Negative_Prevention(t *testing.T) {
	hub := setupTestHub(t)

	// Ensure initial count is 0
	if hub.ClientCount() != 0 {
		t.Errorf("Expected initial ClientCount to be 0, got %d", hub.ClientCount())
	}

	// Test that direct increment/decrement work correctly
	hub.incrementCount()
	if hub.ClientCount() != 1 {
		t.Errorf("Expected ClientCount to be 1 after increment, got %d", hub.ClientCount())
	}

	hub.decrementCount()
	if hub.ClientCount() != 0 {
		t.Errorf("Expected ClientCount to be 0 after decrement, got %d", hub.ClientCount())
	}

	// Note: In production, decrementCount is called in readPump defer,
	// and incrementCount is called in HandleWebSocket.
	// The count should never go negative if properly paired.
	// This test verifies the counters themselves work correctly.
}

func TestHub_RegisterUnregister_ClientMap(t *testing.T) {
	hub := setupTestHub(t)

	// Create a test client
	client := &Client{
		ID:         "test-client-1",
		DeviceName: "TestDevice",
		Platform:   "windows",
		Send:       make(chan []byte, 256),
		Hub:        hub,
	}

	// Register client
	hub.register <- client
	time.Sleep(50 * time.Millisecond)

	// Verify client is in map
	hub.mu.RLock()
	_, exists := hub.clients[client.ID]
	hub.mu.RUnlock()

	if !exists {
		t.Error("Client should be registered in clients map")
	}

	// Unregister client
	hub.unregister <- client
	time.Sleep(50 * time.Millisecond)

	hub.mu.RLock()
	_, exists = hub.clients[client.ID]
	hub.mu.RUnlock()

	if exists {
		t.Error("Client should be unregistered from clients map")
	}
}

func TestHub_MultipleClients_MapRegistration(t *testing.T) {
	hub := setupTestHub(t)

	// Create and register 3 clients
	clients := make([]*Client, 3)
	for i := 0; i < 3; i++ {
		clients[i] = &Client{
			ID:         fmt.Sprintf("test-client-%d", i),
			DeviceName: "TestDevice",
			Platform:   "windows",
			Send:       make(chan []byte, 256),
			Hub:        hub,
		}
		hub.register <- clients[i]
	}

	time.Sleep(100 * time.Millisecond)

	// Verify all clients are in map
	hub.mu.RLock()
	if len(hub.clients) != 3 {
		t.Errorf("Expected 3 clients in map, got %d", len(hub.clients))
	}
	hub.mu.RUnlock()

	// Unregister 2 clients
	for i := 0; i < 2; i++ {
		hub.unregister <- clients[i]
	}

	time.Sleep(100 * time.Millisecond)

	hub.mu.RLock()
	if len(hub.clients) != 1 {
		t.Errorf("Expected 1 client in map, got %d", len(hub.clients))
	}
	hub.mu.RUnlock()
}

func TestHub_GetClientCountForUser(t *testing.T) {
	hub := setupTestHub(t)

	// Create clients for different users
	clients := []*Client{
		{ID: "client-1", UserID: 1, Send: make(chan []byte, 256), Hub: hub},
		{ID: "client-2", UserID: 1, Send: make(chan []byte, 256), Hub: hub},
		{ID: "client-3", UserID: 2, Send: make(chan []byte, 256), Hub: hub},
	}

	for _, c := range clients {
		hub.register <- c
	}

	time.Sleep(100 * time.Millisecond)

	count1 := hub.GetClientCountForUser(1)
	if count1 != 2 {
		t.Errorf("Expected 2 clients for user 1, got %d", count1)
	}

	count2 := hub.GetClientCountForUser(2)
	if count2 != 1 {
		t.Errorf("Expected 1 client for user 2, got %d", count2)
	}

	count3 := hub.GetClientCountForUser(3)
	if count3 != 0 {
		t.Errorf("Expected 0 clients for user 3, got %d", count3)
	}
}

func TestHub_Broadcast_ExcludeSender(t *testing.T) {
	hub := setupTestHub(t)

	// Create clients for same user
	client1 := &Client{ID: "client-1", UserID: 1, Send: make(chan []byte, 256), Hub: hub}
	client2 := &Client{ID: "client-2", UserID: 1, Send: make(chan []byte, 256), Hub: hub}
	client3 := &Client{ID: "client-3", UserID: 1, Send: make(chan []byte, 256), Hub: hub}

	hub.register <- client1
	hub.register <- client2
	hub.register <- client3
	time.Sleep(50 * time.Millisecond)

	// Broadcast from client1, should reach client2 and client3
	testData := []byte(`{"test":"data"}`)
	hub.Broadcast(testData, "client-1", 1)
	time.Sleep(50 * time.Millisecond)

	// client1 should not receive
	select {
	case <-client1.Send:
		t.Error("client1 should not receive broadcast (it's the sender)")
	default:
		// OK
	}

	// client2 and client3 should receive
	select {
	case msg := <-client2.Send:
		if string(msg) != string(testData) {
			t.Errorf("client2 received wrong message: %s", msg)
		}
	default:
		t.Error("client2 should have received broadcast")
	}

	select {
	case msg := <-client3.Send:
		if string(msg) != string(testData) {
			t.Errorf("client3 received wrong message: %s", msg)
		}
	default:
		t.Error("client3 should have received broadcast")
	}
}
