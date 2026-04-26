package database

// User represents a registered user.
type User struct {
	ID        int64
	Username  string
	Password  string // bcrypt hashed
	CreatedAt int64  // Unix milliseconds
}

// Device represents a registered device for a user.
type Device struct {
	ID         string
	UserID     int64
	DeviceName string
	Platform   string
	LastSeen   int64 // Unix milliseconds
	CreatedAt  int64 // Unix milliseconds
}

// ClipboardEntry represents a stored clipboard item.
type ClipboardEntry struct {
	ID               int64
	UserID           int64
	ContentType      string
	Content          string
	Format           string
	Size             int64
	Checksum         string
	SourceDeviceID   string
	SourceDeviceName string
	CreatedAt        int64 // Unix milliseconds
}

// UploadedFile represents a file uploaded via HTTP.
type UploadedFile struct {
	ID        string
	UserID    int64
	Filename  string
	MimeType  string
	Size      int64
	Checksum  string
	FilePath  string
	CreatedAt int64 // Unix milliseconds
}
