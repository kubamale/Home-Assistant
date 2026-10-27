package config

import (
	"os"
	"path/filepath"
	"testing"
)

func write(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent.json")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestLoadAndWebSocketURL(t *testing.T) {
	t.Setenv("AGENT_TOKEN", "")
	t.Setenv("AGENT_BACKEND_URL", "")
	c, err := Load(write(t, `{"backendUrl":"https://home.example.com/","token":"abc","agentId":"pi"}`))
	if err != nil {
		t.Fatal(err)
	}
	if got := c.WebSocketURL(); got != "wss://home.example.com/agent/ws" {
		t.Fatalf("got %s", got)
	}
	c.BackendURL = "http://192.168.1.10:8080"
	if got := c.WebSocketURL(); got != "ws://192.168.1.10:8080/agent/ws" {
		t.Fatalf("got %s", got)
	}
}

func TestEnvironmentOverridesTheFile(t *testing.T) {
	t.Setenv("AGENT_TOKEN", "from-env")
	t.Setenv("AGENT_BACKEND_URL", "https://other.example.com")
	c, err := Load(write(t, `{"backendUrl":"https://home.example.com","token":"abc"}`))
	if err != nil || c.Token != "from-env" || c.BackendURL != "https://other.example.com" {
		t.Fatalf("got %+v, %v", c, err)
	}
}

func TestMissingPieceIsRejected(t *testing.T) {
	t.Setenv("AGENT_TOKEN", "")
	t.Setenv("AGENT_BACKEND_URL", "")
	if _, err := Load(write(t, `{"backendUrl":"https://home.example.com"}`)); err == nil {
		t.Fatal("expected an error for a missing token")
	}
	if _, err := Load(write(t, `{"token":"abc","backendUrl":"not a url"}`)); err == nil {
		t.Fatal("expected an error for a bad backend url")
	}
	if _, err := Load(filepath.Join(t.TempDir(), "absent.json")); err == nil {
		t.Fatal("expected an error when nothing is configured")
	}
}
