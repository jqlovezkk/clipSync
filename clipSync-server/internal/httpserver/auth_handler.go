package httpserver

import (
	"clipsync-server/internal/auth"
	"encoding/json"
	"errors"
	"net/http"
	"unicode"
)

// AuthHandler handles authentication HTTP endpoints.
type AuthHandler struct {
	authService *auth.Service
}

// NewAuthHandler creates a new auth handler.
func NewAuthHandler(authService *auth.Service) *AuthHandler {
	return &AuthHandler{authService: authService}
}

// LoginRequest represents the login/register request body.
type LoginRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceName string `json:"device_name"`
	Platform   string `json:"platform"`
}

// validatePassword checks that a password meets minimum strength requirements.
func validatePassword(password string) (bool, string) {
	if len(password) < 8 {
		return false, "Password must be at least 8 characters"
	}
	var hasLetter, hasDigit bool
	for _, c := range password {
		if unicode.IsLetter(c) {
			hasLetter = true
		}
		if unicode.IsDigit(c) {
			hasDigit = true
		}
	}
	if !hasLetter {
		return false, "Password must contain at least one letter"
	}
	if !hasDigit {
		return false, "Password must contain at least one digit"
	}
	return true, ""
}

// validateUsername checks that a username meets minimum requirements.
func validateUsername(username string) (bool, string) {
	if len(username) < 3 {
		return false, "Username must be at least 3 characters"
	}
	if len(username) > 32 {
		return false, "Username must be at most 32 characters"
	}
	return true, ""
}

// Login handles POST /api/v1/auth/login.
func (h *AuthHandler) Login(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}

	var req LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
		})
		return
	}

	if req.Username == "" || req.Password == "" || req.DeviceName == "" || req.Platform == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
		})
		return
	}

	result, err := h.authService.Login(req.Username, req.Password, req.DeviceName, req.Platform)
	if err != nil {
		if errors.Is(err, auth.ErrInvalidCredentials) {
			writeJSON(w, http.StatusUnauthorized, map[string]interface{}{
				"success": false,
				"error":   "INVALID_CREDENTIALS",
			})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"success": false,
			"error":   "INTERNAL_ERROR",
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success":    true,
		"token":      result.Token,
		"device_id":  result.DeviceID,
		"expires_at": result.ExpiresAt,
	})
}

// Register handles POST /api/v1/auth/register.
func (h *AuthHandler) Register(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}

	var req LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
		})
		return
	}

	if req.Username == "" || req.Password == "" || req.DeviceName == "" || req.Platform == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
		})
		return
	}

	if ok, msg := validateUsername(req.Username); !ok {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
			"message": msg,
		})
		return
	}

	if ok, msg := validatePassword(req.Password); !ok {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"success": false,
			"error":   "INVALID_PAYLOAD",
			"message": msg,
		})
		return
	}

	result, err := h.authService.Register(req.Username, req.Password, req.DeviceName, req.Platform)
	if err != nil {
		if errors.Is(err, auth.ErrUsernameExists) {
			writeJSON(w, http.StatusConflict, map[string]interface{}{
				"success": false,
				"error":   "USERNAME_EXISTS",
			})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]interface{}{
			"success": false,
			"error":   "INTERNAL_ERROR",
		})
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"success":    true,
		"token":      result.Token,
		"device_id":  result.DeviceID,
		"expires_at": result.ExpiresAt,
	})
}

// Refresh handles POST /api/v1/auth/refresh.
func (h *AuthHandler) Refresh(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "METHOD_NOT_ALLOWED"})
		return
	}

	authHeader := r.Header.Get("Authorization")
	if authHeader == "" || len(authHeader) < 7 || authHeader[:7] != "Bearer " {
		writeJSON(w, http.StatusUnauthorized, map[string]interface{}{
			"success": false,
			"error":   "AUTH_FAILED",
		})
		return
	}

	tokenString := authHeader[7:]
	newToken, expiresAt, err := h.authService.Refresh(tokenString)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]interface{}{
			"success": false,
			"error":   "TOKEN_EXPIRED",
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"success":    true,
		"token":      newToken,
		"expires_at": expiresAt,
	})
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}
