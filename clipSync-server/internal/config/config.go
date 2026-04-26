package config

import (
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

// Config holds all server configuration.
type Config struct {
	WSPort                   int    `yaml:"ws_port"`
	HTTPPort                 int    `yaml:"http_port"`
	DBPath                   string `yaml:"db_path"`
	JWTSecret                string `yaml:"jwt_secret"`
	JWTExpiryHours           int    `yaml:"jwt_expiry_hours"`
	FileStoragePath          string `yaml:"file_storage_path"`
	MaxFileSizeMB            int    `yaml:"max_file_size_mb"`
	ClipboardHistoryLimit    int    `yaml:"clipboard_history_limit"`
	HeartbeatTimeoutSec      int    `yaml:"heartbeat_timeout_seconds"`
	WSMaxMessageSizeMB       int    `yaml:"ws_max_message_size_mb"`
	FileReferenceThresholdKB int    `yaml:"file_reference_threshold_kb"`
}

// DefaultConfig returns a Config with sensible defaults.
func DefaultConfig() Config {
	return Config{
		WSPort:                   8080,
		HTTPPort:                 8081,
		DBPath:                   "./data/clipsync.db",
		JWTSecret:                "clipsync-secret-change-in-production",
		JWTExpiryHours:           720,
		FileStoragePath:          "./data/files",
		MaxFileSizeMB:            50,
		ClipboardHistoryLimit:    50,
		HeartbeatTimeoutSec:      90,
		WSMaxMessageSizeMB:       1,
		FileReferenceThresholdKB: 512,
	}
}

// Load reads configuration from a YAML file, falling back to defaults.
func Load(path string) (Config, error) {
	cfg := DefaultConfig()

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return cfg, nil
		}
		return cfg, fmt.Errorf("read config file: %w", err)
	}

	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return cfg, fmt.Errorf("parse config file: %w", err)
	}

	return cfg, nil
}

// Validate checks that the configuration is safe for production use.
// Returns warnings for insecure defaults.
func (c *Config) Validate() []string {
	var warnings []string

	if c.JWTSecret == "clipsync-secret-change-in-production" {
		warnings = append(warnings, "JWT_SECRET is set to the default value. Change it in production!")
	}

	if c.JWTExpiryHours > 720 {
		warnings = append(warnings, fmt.Sprintf("JWT expiry is %d hours (>30 days). Consider using a shorter expiry.", c.JWTExpiryHours))
	}

	return warnings
}
