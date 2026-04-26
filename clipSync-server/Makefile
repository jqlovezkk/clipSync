.PHONY: build run clean test deps

BINARY_NAME=clipsync-server
BUILD_DIR=bin

# Build the server binary
build:
	go build -o $(BUILD_DIR)/$(BINARY_NAME) ./cmd/server

# Build for Linux deployment
build-linux:
	GOOS=linux GOARCH=amd64 go build -o $(BUILD_DIR)/$(BINARY_NAME)-linux ./cmd/server

# Run the server
run:
	go run ./cmd/server

# Install dependencies
deps:
	go mod tidy

# Run tests
test:
	go test ./... -v -count=1

# Clean build artifacts
clean:
	rm -rf $(BUILD_DIR)
	rm -rf data/

# Full build cycle
all: deps build
