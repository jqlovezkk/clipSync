package database

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"

	_ "github.com/mattn/go-sqlite3"
)

// DB wraps the SQLite database connection.
type DB struct {
	*sql.DB
}

// New opens a SQLite database with WAL mode and returns a wrapped connection.
func New(dbPath string) (*DB, error) {
	dir := filepath.Dir(dbPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("create database directory: %w", err)
	}

	db, err := sql.Open("sqlite3", dbPath+"?_journal_mode=WAL&_busy_timeout=5000&_foreign_keys=ON")
	if err != nil {
		return nil, fmt.Errorf("open sqlite database: %w", err)
	}

	// Connection pool settings optimized for 2-core 2G server
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(2)

	// Enable WAL mode
	if _, err := db.Exec("PRAGMA journal_mode=WAL"); err != nil {
		return nil, fmt.Errorf("enable WAL mode: %w", err)
	}

	// Optimize for concurrent reads
	if _, err := db.Exec("PRAGMA synchronous=NORMAL"); err != nil {
		return nil, fmt.Errorf("set synchronous: %w", err)
	}

	if _, err := db.Exec("PRAGMA cache_size=-2000"); err != nil {
		return nil, fmt.Errorf("set cache size: %w", err)
	}

	if _, err := db.Exec("PRAGMA temp_store=MEMORY"); err != nil {
		return nil, fmt.Errorf("set temp store: %w", err)
	}

	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping database: %w", err)
	}

	return &DB{db}, nil
}

// Close closes the database connection.
func (d *DB) Close() error {
	return d.DB.Close()
}
