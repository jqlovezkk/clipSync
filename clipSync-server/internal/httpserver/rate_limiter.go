package httpserver

import (
	"net/http"
	"sync"
	"time"
)

// RateLimiter provides IP-based rate limiting using a sliding window counter.
type RateLimiter struct {
	mu       sync.Mutex
	visitors map[string]*visitorInfo
	limit    int           // max requests per window
	window   time.Duration // time window
}

type visitorInfo struct {
	count    int
	expiresAt time.Time
}

// NewRateLimiter creates a new rate limiter.
func NewRateLimiter(limit int, window time.Duration) *RateLimiter {
	rl := &RateLimiter{
		visitors: make(map[string]*visitorInfo),
		limit:    limit,
		window:   window,
	}
	// Cleanup expired entries every minute
	go rl.cleanup()
	return rl
}

// Allow checks if a request from the given key is allowed.
func (rl *RateLimiter) Allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	v, exists := rl.visitors[key]
	if !exists || now.After(v.expiresAt) {
		rl.visitors[key] = &visitorInfo{
			count:     1,
			expiresAt: now.Add(rl.window),
		}
		return true
	}

	v.count++
	if v.count > rl.limit {
		return false
	}
	return true
}

func (rl *RateLimiter) cleanup() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		rl.mu.Lock()
		now := time.Now()
		for k, v := range rl.visitors {
			if now.After(v.expiresAt) {
				delete(rl.visitors, k)
			}
		}
		rl.mu.Unlock()
	}
}

// RateLimitMiddleware wraps an http.HandlerFunc with rate limiting.
func RateLimitMiddleware(limiter *RateLimiter, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		key := r.RemoteAddr
		if !limiter.Allow(key) {
			writeJSON(w, http.StatusTooManyRequests, map[string]interface{}{
				"success": false,
				"error":   "RATE_LIMITED",
				"message": "Too many requests. Please try again later.",
			})
			return
		}
		next(w, r)
	}
}
