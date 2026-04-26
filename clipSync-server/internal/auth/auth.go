package auth

import (
	"clipsync-server/internal/database"
	"fmt"
)

// Service handles authentication business logic.
type Service struct {
	userRepo  *database.UserRepo
	deviceRepo *database.DeviceRepo
	jwtMgr    *JWTManager
}

// NewService creates a new auth service.
func NewService(userRepo *database.UserRepo, deviceRepo *database.DeviceRepo, jwtMgr *JWTManager) *Service {
	return &Service{
		userRepo:   userRepo,
		deviceRepo: deviceRepo,
		jwtMgr:     jwtMgr,
	}
}

// LoginResult holds the result of a successful login.
type LoginResult struct {
	Token     string `json:"token"`
	DeviceID  string `json:"device_id"`
	ExpiresAt int64  `json:"expires_at"`
}

// Register creates a new user and device, returns auth token.
func (s *Service) Register(username, password, deviceName, platform string) (*LoginResult, error) {
	// Check if username exists
	exists, err := s.userRepo.UserExists(username)
	if err != nil {
		return nil, fmt.Errorf("check username: %w", err)
	}
	if exists {
		return nil, fmt.Errorf("%w: username already taken", ErrUsernameExists)
	}

	// Create user
	user, err := s.userRepo.CreateUser(username, password)
	if err != nil {
		return nil, fmt.Errorf("create user: %w", err)
	}

	// Create device
	device, err := s.deviceRepo.CreateDevice(user.ID, deviceName, platform)
	if err != nil {
		return nil, fmt.Errorf("create device: %w", err)
	}

	// Generate token
	token, expiresAt, err := s.jwtMgr.GenerateToken(user.ID, user.Username, device.ID)
	if err != nil {
		return nil, fmt.Errorf("generate token: %w", err)
	}

	return &LoginResult{
		Token:     token,
		DeviceID:  device.ID,
		ExpiresAt: expiresAt,
	}, nil
}

// Login authenticates a user and returns a token.
func (s *Service) Login(username, password, deviceName, platform string) (*LoginResult, error) {
	// Verify credentials
	user, err := s.userRepo.VerifyPassword(username, password)
	if err != nil {
		return nil, fmt.Errorf("verify password: %w", err)
	}
	if user == nil {
		return nil, fmt.Errorf("%w: invalid username or password", ErrInvalidCredentials)
	}

	// Check if device already exists for this user
	devices, err := s.deviceRepo.GetDevicesByUser(user.ID)
	if err != nil {
		return nil, fmt.Errorf("get devices: %w", err)
	}

	var deviceID string
	for _, d := range devices {
		if d.DeviceName == deviceName && d.Platform == platform {
			deviceID = d.ID
			// Update last seen
			if err := s.deviceRepo.UpdateDeviceLastSeen(d.ID); err != nil {
				return nil, fmt.Errorf("update last seen: %w", err)
			}
			break
		}
	}

	// Create new device if not found
	if deviceID == "" {
		device, err := s.deviceRepo.CreateDevice(user.ID, deviceName, platform)
		if err != nil {
			return nil, fmt.Errorf("create device: %w", err)
		}
		deviceID = device.ID
	}

	// Generate token
	token, expiresAt, err := s.jwtMgr.GenerateToken(user.ID, user.Username, deviceID)
	if err != nil {
		return nil, fmt.Errorf("generate token: %w", err)
	}

	return &LoginResult{
		Token:     token,
		DeviceID:  deviceID,
		ExpiresAt: expiresAt,
	}, nil
}

// Refresh generates a new token for an existing valid token.
func (s *Service) Refresh(oldToken string) (string, int64, error) {
	claims, err := s.jwtMgr.ValidateToken(oldToken)
	if err != nil {
		return "", 0, fmt.Errorf("%w: token is no longer valid", ErrTokenExpired)
	}

	token, expiresAt, err := s.jwtMgr.GenerateToken(claims.UserID, claims.Username, claims.DeviceID)
	if err != nil {
		return "", 0, fmt.Errorf("generate token: %w", err)
	}

	return token, expiresAt, nil
}

// ValidateWSAuth validates a JWT token for WebSocket authentication.
func (s *Service) ValidateWSAuth(tokenString string) (*Claims, error) {
	return s.jwtMgr.ValidateToken(tokenString)
}
