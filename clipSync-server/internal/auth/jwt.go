package auth

import (
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Claims holds the JWT token claims.
type Claims struct {
	UserID   int64  `json:"user_id"`
	Username string `json:"username"`
	DeviceID string `json:"device_id"`
	jwt.RegisteredClaims
}

// JWTManager handles JWT token generation and validation.
type JWTManager struct {
	secret     []byte
	expiryHours int
}

// NewJWTManager creates a new JWT manager.
func NewJWTManager(secret string, expiryHours int) *JWTManager {
	return &JWTManager{
		secret:      []byte(secret),
		expiryHours: expiryHours,
	}
}

// GenerateToken creates a new JWT token for a user and device.
func (m *JWTManager) GenerateToken(userID int64, username, deviceID string) (string, int64, error) {
	now := time.Now()
	expiresAt := now.Add(time.Duration(m.expiryHours) * time.Hour)

	claims := Claims{
		UserID:   userID,
		Username: username,
		DeviceID: deviceID,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(expiresAt),
			IssuedAt:  jwt.NewNumericDate(now),
			Issuer:    "clipsync-server",
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := token.SignedString(m.secret)
	if err != nil {
		return "", 0, fmt.Errorf("sign token: %w", err)
	}

	return signed, expiresAt.UnixMilli(), nil
}

// ValidateToken parses and validates a JWT token.
func (m *JWTManager) ValidateToken(tokenString string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(tokenString, &Claims{}, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return m.secret, nil
	})
	if err != nil {
		return nil, fmt.Errorf("parse token: %w", err)
	}

	claims, ok := token.Claims.(*Claims)
	if !ok || !token.Valid {
		return nil, fmt.Errorf("invalid token")
	}

	return claims, nil
}
