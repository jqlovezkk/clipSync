package main

import (
	"clipsync-server/internal/auth"
	"clipsync-server/internal/config"
	"clipsync-server/internal/database"
	"clipsync-server/internal/httpserver"
	ws "clipsync-server/internal/websocket"
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"
)

const version = "1.0.0"

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lshortfile)
	log.Printf("ClipSync Server v%s starting...", version)

	// Load configuration
	configPath := "configs/config.yaml"
	if envPath := os.Getenv("CLIPSYNC_CONFIG"); envPath != "" {
		configPath = envPath
	}

	cfg, err := config.Load(configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	log.Printf("Config loaded: WS=%d, HTTP=%d, DB=%s", cfg.WSPort, cfg.HTTPPort, cfg.DBPath)

	// Validate configuration and warn about insecure defaults
	for _, warning := range cfg.Validate() {
		log.Printf("WARNING: %s", warning)
	}

	// Initialize database
	db, err := database.New(cfg.DBPath)
	if err != nil {
		log.Fatalf("Failed to open database: %v", err)
	}
	defer db.Close()

	// Run migrations
	if err := database.RunMigrations(db.DB); err != nil {
		log.Fatalf("Failed to run migrations: %v", err)
	}
	log.Println("Database migrations complete")

	// Initialize repositories
	userRepo := database.NewUserRepo(db.DB)
	deviceRepo := database.NewDeviceRepo(db.DB)
	clipRepo := database.NewClipboardRepo(db.DB, cfg.ClipboardHistoryLimit)

	// Initialize JWT manager
	jwtMgr := auth.NewJWTManager(cfg.JWTSecret, cfg.JWTExpiryHours)

	// Initialize auth service
	authService := auth.NewService(userRepo, deviceRepo, jwtMgr)

	// Initialize WebSocket hub
	hub := ws.NewHub(authService, clipRepo, deviceRepo, userRepo, cfg.HeartbeatTimeoutSec, cfg.ClipboardHistoryLimit)
	go hub.Run()

	// Initialize auth middleware
	authMiddleware := auth.NewMiddleware(jwtMgr)

	// Build HTTP router
	mux := http.NewServeMux()

	// Rate limiter for auth endpoints: 10 requests per minute per IP
	authLimiter := httpserver.NewRateLimiter(10, time.Minute)

	// Auth handler
	authHandler := httpserver.NewAuthHandler(authService)
	mux.HandleFunc("/api/v1/auth/login", httpserver.RateLimitMiddleware(authLimiter, authHandler.Login))
	mux.HandleFunc("/api/v1/auth/register", httpserver.RateLimitMiddleware(authLimiter, authHandler.Register))
	mux.HandleFunc("/api/v1/auth/refresh", httpserver.RateLimitMiddleware(authLimiter, authHandler.Refresh))

	// Health handler
	healthHandler := httpserver.NewHealthHandler(hub, db.DB, version)
	mux.HandleFunc("/api/v1/health", healthHandler.Health)

	// Device handler (auth required)
	deviceHandler := httpserver.NewDeviceHandler(deviceRepo, hub)
	mux.HandleFunc("/api/v1/devices", authMiddleware.RequireAuth(deviceHandler.ListDevices))
	mux.HandleFunc("/api/v1/devices/{device_id}", authMiddleware.RequireAuth(deviceHandler.DeleteDevice))

	// Upload/download handlers (auth required)
	uploadHandler := httpserver.NewUploadHandler(db.DB, cfg.FileStoragePath, cfg.MaxFileSizeMB)
	mux.HandleFunc("/api/v1/upload", authMiddleware.RequireAuth(uploadHandler.Upload))
	mux.HandleFunc("/api/v1/download/{file_id}", authMiddleware.RequireAuth(uploadHandler.Download))

	// Start HTTP server
	httpSrv := httpserver.New(cfg.HTTPPort, mux)
	go func() {
		if err := httpSrv.Start(); err != nil {
			log.Printf("HTTP server error: %v", err)
		}
	}()

	// Start WebSocket server on separate port
	wsMux := http.NewServeMux()
	wsMux.HandleFunc("/ws", hub.WSHandler())

	wsserver := &http.Server{
		Addr:         ":" + strconv.Itoa(cfg.WSPort),
		Handler:      wsMux,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		log.Printf("[WS] WebSocket server starting on port %d", cfg.WSPort)
		if err := wsserver.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("WebSocket server error: %v", err)
		}
	}()

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("Shutting down...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := httpSrv.Shutdown(ctx); err != nil {
		log.Printf("HTTP server shutdown error: %v", err)
	}
	if err := wsserver.Shutdown(ctx); err != nil {
		log.Printf("WebSocket server shutdown error: %v", err)
	}

	log.Println("Server stopped")
}
