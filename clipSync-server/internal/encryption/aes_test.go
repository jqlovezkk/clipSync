package encryption

import (
	"encoding/base64"
	"testing"
)

func TestEncryptDecrypt_RoundTrip(t *testing.T) {
	password := "testpassword123"
	testCases := []string{
		"Hello, World!",
		"剪贴板同步测试",
		"Special chars: !@#$%^&*()_+-=[]{}|;':\",./<>?",
		"Empty string test",
		"Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
	}

	for i, plaintext := range testCases {
		t.Run("", func(t *testing.T) {
			// Encrypt
			encrypted, err := Encrypt(plaintext, password)
			if err != nil {
				t.Fatalf("Encrypt failed: %v", err)
			}

			if encrypted == "" {
				t.Fatal("Encrypted output is empty")
			}

			// Verify format (salt:content)
			parts := splitEncryptedData(encrypted)
			if len(parts) != 2 {
				t.Fatalf("Invalid encrypted format, expected 2 parts, got %d", len(parts))
			}

			// Decrypt
			decrypted, err := Decrypt(encrypted, password)
			if err != nil {
				t.Fatalf("Decrypt failed: %v", err)
			}

			if decrypted != plaintext {
				t.Errorf("Roundtrip failed (case %d): expected %q, got %q", i+1, plaintext, decrypted)
			}
		})
	}
}

func TestEncrypt_DifferentOutputs(t *testing.T) {
	password := "testpassword123"
	plaintext := "Hello, World!"

	// Encrypt the same plaintext twice
	enc1, err := Encrypt(plaintext, password)
	if err != nil {
		t.Fatalf("First encryption failed: %v", err)
	}

	enc2, err := Encrypt(plaintext, password)
	if err != nil {
		t.Fatalf("Second encryption failed: %v", err)
	}

	// Due to random salt and IV, outputs should be different
	if enc1 == enc2 {
		t.Error("Expected different outputs for same plaintext due to random salt/IV, but got same output")
	}

	// But both should decrypt to the same plaintext
	dec1, err := Decrypt(enc1, password)
	if err != nil {
		t.Fatalf("Decrypt enc1 failed: %v", err)
	}

	dec2, err := Decrypt(enc2, password)
	if err != nil {
		t.Fatalf("Decrypt enc2 failed: %v", err)
	}

	if dec1 != plaintext || dec2 != plaintext {
		t.Errorf("Both should decrypt to %q, got %q and %q", plaintext, dec1, dec2)
	}
}

func TestDecrypt_WrongPassword(t *testing.T) {
	password := "correctpassword"
	wrongPassword := "wrongpassword"
	plaintext := "Secret data"

	encrypted, err := Encrypt(plaintext, password)
	if err != nil {
		t.Fatalf("Encrypt failed: %v", err)
	}

	_, err = Decrypt(encrypted, wrongPassword)
	if err == nil {
		t.Error("Expected error when decrypting with wrong password, but got success")
	}
}

func TestDecrypt_InvalidFormat(t *testing.T) {
	testCases := []string{
		"no_colon_here",
		"",
		"only:one:part",
		"invalid_base64:also_invalid_base64",
	}

	for _, data := range testCases {
		t.Run("", func(t *testing.T) {
			_, err := Decrypt(data, "password")
			if err == nil {
				t.Errorf("Expected error for invalid input %q, but got success", data)
			}
		})
	}
}

func TestDecrypt_EmptyInput(t *testing.T) {
	_, err := Decrypt("", "password")
	if err == nil {
		t.Error("Expected error for empty input, but got success")
	}
}

func TestEncrypt_EmptyPlaintext(t *testing.T) {
	password := "testpassword"
	encrypted, err := Encrypt("", password)
	if err != nil {
		t.Fatalf("Encrypt empty string failed: %v", err)
	}

	decrypted, err := Decrypt(encrypted, password)
	if err != nil {
		t.Fatalf("Decrypt failed: %v", err)
	}

	if decrypted != "" {
		t.Errorf("Expected empty string, got %q", decrypted)
	}
}

func TestPKCS7Padding_CorrectPadding(t *testing.T) {
	testCases := []struct {
		data      []byte
		blockSize int
		expected  int
	}{
		{[]byte("Hello"), 16, 11},
		{[]byte("Hello World!"), 16, 4},
		{[]byte("16 bytes exact!!"), 16, 16},
	}

	for _, tc := range testCases {
		padded := pkcs7Pad(tc.data, tc.blockSize)
		padding := int(padded[len(padded)-1])

		if padding != tc.expected {
			t.Errorf("Expected padding %d, got %d for data %q", tc.expected, padding, tc.data)
		}

		if len(padded)%tc.blockSize != 0 {
			t.Errorf("Padded data length %d is not multiple of block size %d", len(padded), tc.blockSize)
		}
	}
}

func TestPKCS7Unpad_InvalidPadding(t *testing.T) {
	testCases := [][]byte{
		{1, 2, 3, 4, 5}, // Padding value 5, but only 5 bytes - invalid padding pattern
		{1, 2, 3},        // Padding value 3, but bytes don't match
	}

	for i, data := range testCases {
		_, err := pkcs7Unpad(data, 16)
		if err == nil {
			t.Errorf("Case %d: Expected error for invalid padding (data=%v), but got success", i, data)
		}
	}
}

func TestCrossClientCompatibility(t *testing.T) {
	// Test that encryption matches expected cross-client format
	password := "sharedpassword"
	plaintext := "Clipboard content"

	encrypted, err := Encrypt(plaintext, password)
	if err != nil {
		t.Fatalf("Encrypt failed: %v", err)
	}

	// Verify salt size (should be 16 bytes, base64 encoded to ~24 chars)
	parts := splitEncryptedData(encrypted)
	saltBytes, err := decodeBase64(parts[0])
	if err != nil {
		t.Fatalf("Failed to decode salt: %v", err)
	}

	if len(saltBytes) != 16 {
		t.Errorf("Expected salt size 16, got %d", len(saltBytes))
	}

	// Verify IV + ciphertext structure
	combined, err := decodeBase64(parts[1])
	if err != nil {
		t.Fatalf("Failed to decode combined data: %v", err)
	}

	if len(combined) <= 16 {
		t.Errorf("Combined data too short: %d bytes (need IV + ciphertext)", len(combined))
	}
}

// Helper functions
func splitEncryptedData(data string) []string {
	parts := make([]string, 0, 2)
	for i, c := range data {
		if c == ':' {
			parts = append(parts, data[:i])
			parts = append(parts, data[i+1:])
			break
		}
	}
	return parts
}

func decodeBase64(s string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(s)
}
