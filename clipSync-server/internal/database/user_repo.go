package database

import (
	"database/sql"
	"fmt"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// UserRepo handles user-related database operations.
type UserRepo struct {
	db *sql.DB
}

// NewUserRepo creates a new UserRepo.
func NewUserRepo(db *sql.DB) *UserRepo {
	return &UserRepo{db: db}
}

// CreateUser creates a new user with a hashed password.
func (r *UserRepo) CreateUser(username, password string) (*User, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("hash password: %w", err)
	}

	now := time.Now().UnixMilli()
	result, err := r.db.Exec(
		"INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
		username, string(hash), now,
	)
	if err != nil {
		return nil, fmt.Errorf("insert user: %w", err)
	}

	id, err := result.LastInsertId()
	if err != nil {
		return nil, fmt.Errorf("get last insert id: %w", err)
	}

	return &User{
		ID:        id,
		Username:  username,
		CreatedAt: now,
	}, nil
}

// GetUserByUsername retrieves a user by username.
func (r *UserRepo) GetUserByUsername(username string) (*User, error) {
	var u User
	err := r.db.QueryRow(
		"SELECT id, username, password_hash, created_at FROM users WHERE username = ?",
		username,
	).Scan(&u.ID, &u.Username, &u.Password, &u.CreatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("query user: %w", err)
	}
	return &u, nil
}

// VerifyPassword checks if the provided password matches the stored hash.
func (r *UserRepo) VerifyPassword(username, password string) (*User, error) {
	user, err := r.GetUserByUsername(username)
	if err != nil {
		return nil, err
	}
	if user == nil {
		return nil, nil
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(password)); err != nil {
		return nil, nil
	}

	return user, nil
}

// UserExists checks if a username already exists.
func (r *UserRepo) UserExists(username string) (bool, error) {
	var count int
	err := r.db.QueryRow("SELECT COUNT(*) FROM users WHERE username = ?", username).Scan(&count)
	if err != nil {
		return false, fmt.Errorf("check user exists: %w", err)
	}
	return count > 0, nil
}
