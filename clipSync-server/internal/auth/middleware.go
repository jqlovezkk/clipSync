package auth

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
)

// contextKey is a custom type for context keys.
type contextKey string

const (
	// ContextKeyUserID is the context key for user ID.
	ContextKeyUserID contextKey = "user_id"
	// ContextKeyUsername is the context key for username.
	ContextKeyUsername contextKey = "username"
	// ContextKeyDeviceID is the context key for device ID.
	ContextKeyDeviceID contextKey = "device_id"
)

// Middleware wraps HTTP handlers with JWT authentication.
type Middleware struct {
	jwtMgr *JWTManager
}

// NewMiddleware creates a new auth middleware.
func NewMiddleware(jwtMgr *JWTManager) *Middleware {
	return &Middleware{jwtMgr: jwtMgr}
}

// RequireAuth is an HTTP middleware that requires a valid Bearer token.
func (m *Middleware) RequireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		if authHeader == "" {
			writeJSONError(w, http.StatusUnauthorized, "AUTH_FAILED", "Authorization header required")
			return
		}

		if !strings.HasPrefix(authHeader, "Bearer ") {
			writeJSONError(w, http.StatusUnauthorized, "AUTH_FAILED", "Invalid authorization format")
			return
		}

		tokenString := strings.TrimPrefix(authHeader, "Bearer ")
		claims, err := m.jwtMgr.ValidateToken(tokenString)
		if err != nil {
			writeJSONError(w, http.StatusUnauthorized, "TOKEN_EXPIRED", "Invalid or expired token")
			return
		}

		// Add claims to request context
		ctx := r.Context()
		ctx = context.WithValue(ctx, ContextKeyUserID, claims.UserID)
		ctx = context.WithValue(ctx, ContextKeyUsername, claims.Username)
		ctx = context.WithValue(ctx, ContextKeyDeviceID, claims.DeviceID)

		next(w, r.WithContext(ctx))
	}
}

// GetUserID extracts user ID from request context.
func GetUserID(r *http.Request) int64 {
	v := r.Context().Value(ContextKeyUserID)
	if v == nil {
		return 0
	}
	id, ok := v.(int64)
	if !ok {
		return 0
	}
	return id
}

// GetUsername extracts username from request context.
func GetUsername(r *http.Request) string {
	v := r.Context().Value(ContextKeyUsername)
	if v == nil {
		return ""
	}
	s, ok := v.(string)
	if !ok {
		return ""
	}
	return s
}

// GetDeviceID extracts device ID from request context.
func GetDeviceID(r *http.Request) string {
	v := r.Context().Value(ContextKeyDeviceID)
	if v == nil {
		return ""
	}
	s, ok := v.(string)
	if !ok {
		return ""
	}
	return s
}

func writeJSONError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"success": false,
		"error":   code,
		"message": message,
	})
}
