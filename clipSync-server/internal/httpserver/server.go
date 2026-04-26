package httpserver

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"time"
)

// Server wraps the HTTP server configuration and handlers.
type Server struct {
	port    int
	handler http.Handler
	srv     *http.Server
}

// New creates a new HTTP server with all routes configured.
func New(port int, handler http.Handler) *Server {
	return &Server{
		port:    port,
		handler: handler,
	}
}

// Start begins listening on the configured port.
func (s *Server) Start() error {
	s.srv = &http.Server{
		Addr:         fmt.Sprintf(":%d", s.port),
		Handler:      s.handler,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	log.Printf("[HTTP] Server starting on port %d", s.port)
	if err := s.srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		return fmt.Errorf("HTTP server: %w", err)
	}
	return nil
}

// Shutdown gracefully stops the HTTP server with a timeout.
func (s *Server) Shutdown(ctx context.Context) error {
	if s.srv != nil {
		return s.srv.Shutdown(ctx)
	}
	return nil
}
