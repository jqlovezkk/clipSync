package encryption

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"io"
	"strings"

	"golang.org/x/crypto/pbkdf2"
	"golang.org/x/crypto/sha3"
)

const (
	saltSize   = 16
	keySize    = 32
	iterations = 10000
)

// Encrypt encrypts plaintext using AES-256-CBC with PBKDF2 key derivation.
// Returns a single string in the format: base64(salt):base64(IV + ciphertext)
// This format is compatible across all ClipSync clients (Android, Windows, Server).
func Encrypt(plaintext, password string) (string, error) {
	salt := make([]byte, saltSize)
	if _, err := io.ReadFull(rand.Reader, salt); err != nil {
		return "", fmt.Errorf("generate salt: %w", err)
	}

	key := pbkdf2.Key([]byte(password), salt, iterations, keySize, sha3.New256)

	block, err := aes.NewCipher(key)
	if err != nil {
		return "", fmt.Errorf("create cipher: %w", err)
	}

	iv := make([]byte, aes.BlockSize)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return "", fmt.Errorf("generate IV: %w", err)
	}

	plaintextBytes := pkcs7Pad([]byte(plaintext), aes.BlockSize)

	ciphertext := make([]byte, len(plaintextBytes))
	mode := cipher.NewCBCEncrypter(block, iv)
	mode.CryptBlocks(ciphertext, plaintextBytes)

	// Prepend IV to ciphertext
	combined := make([]byte, len(iv)+len(ciphertext))
	copy(combined, iv)
	copy(combined[len(iv):], ciphertext)

	// Format: base64(salt):base64(IV+ciphertext)
	encodedSalt := base64.StdEncoding.EncodeToString(salt)
	encodedContent := base64.StdEncoding.EncodeToString(combined)
	return encodedSalt + ":" + encodedContent, nil
}

// Decrypt decrypts AES-256-CBC encrypted content.
// Expects format: base64(salt):base64(IV+ciphertext)
func Decrypt(encryptedData, password string) (string, error) {
	parts := strings.SplitN(encryptedData, ":", 2)
	if len(parts) != 2 {
		return "", fmt.Errorf("invalid encrypted data format: expected 'salt:content'")
	}

	salt, err := base64.StdEncoding.DecodeString(parts[0])
	if err != nil {
		return "", fmt.Errorf("decode salt: %w", err)
	}

	combined, err := base64.StdEncoding.DecodeString(parts[1])
	if err != nil {
		return "", fmt.Errorf("decode content: %w", err)
	}

	if len(combined) < aes.BlockSize {
		return "", fmt.Errorf("ciphertext too short")
	}

	if len(salt) != saltSize {
		return "", fmt.Errorf("invalid salt size: expected %d, got %d", saltSize, len(salt))
	}

	key := pbkdf2.Key([]byte(password), salt, iterations, keySize, sha3.New256)

	block, err := aes.NewCipher(key)
	if err != nil {
		return "", fmt.Errorf("create cipher: %w", err)
	}

	iv := combined[:aes.BlockSize]
	ciphertext := combined[aes.BlockSize:]

	plaintext := make([]byte, len(ciphertext))
	mode := cipher.NewCBCDecrypter(block, iv)
	mode.CryptBlocks(plaintext, ciphertext)

	plaintext, err = pkcs7Unpad(plaintext, aes.BlockSize)
	if err != nil {
		return "", fmt.Errorf("unpad: %w", err)
	}

	return string(plaintext), nil
}

// pkcs7Pad adds PKCS#7 padding.
func pkcs7Pad(data []byte, blockSize int) []byte {
	padding := blockSize - len(data)%blockSize
	padText := make([]byte, padding)
	for i := range padText {
		padText[i] = byte(padding)
	}
	return append(data, padText...)
}

// pkcs7Unpad removes PKCS#7 padding.
func pkcs7Unpad(data []byte, blockSize int) ([]byte, error) {
	length := len(data)
	if length == 0 {
		return nil, fmt.Errorf("empty data")
	}
	padding := int(data[length-1])
	if padding > blockSize || padding == 0 {
		return nil, fmt.Errorf("invalid padding")
	}
	for i := 0; i < padding; i++ {
		if data[length-1-i] != byte(padding) {
			return nil, fmt.Errorf("invalid padding byte")
		}
	}
	return data[:length-padding], nil
}
