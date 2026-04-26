package auth

import "errors"

// Common sentinel errors for authentication business logic.
// Use errors.Is() to check for specific error types instead of string comparison.
var (
	ErrInvalidCredentials = errors.New("INVALID_CREDENTIALS")
	ErrUsernameExists     = errors.New("USERNAME_EXISTS")
	ErrTokenExpired       = errors.New("TOKEN_EXPIRED")
)
