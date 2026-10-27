// Package config loads the agent's small local configuration: where the backend is and the token
// that proves who the agent is. Device addresses are not configured; devices are discovered.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
)

type Config struct {
	// BackendURL is the public address of the backend, for example https://home.example.com.
	BackendURL string `json:"backendUrl"`
	// Token is the agent token created in the app under Settings, Home agent.
	Token string `json:"token"`
	// AgentID names this agent in the backend's logs. Defaults to the host name.
	AgentID string `json:"agentId"`
}

// Load reads the JSON file at path (if it exists) and lets AGENT_BACKEND_URL, AGENT_TOKEN and
// AGENT_ID override it, then checks the result.
func Load(path string) (Config, error) {
	var c Config
	data, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := json.Unmarshal(data, &c); err != nil {
			return c, fmt.Errorf("%s: %w", path, err)
		}
	case !errors.Is(err, os.ErrNotExist):
		return c, err
	}
	if v := os.Getenv("AGENT_BACKEND_URL"); v != "" {
		c.BackendURL = v
	}
	if v := os.Getenv("AGENT_TOKEN"); v != "" {
		c.Token = v
	}
	if v := os.Getenv("AGENT_ID"); v != "" {
		c.AgentID = v
	}
	if c.AgentID == "" {
		c.AgentID, _ = os.Hostname()
	}
	return c, c.Validate()
}

func (c Config) Validate() error {
	if c.Token == "" {
		return errors.New("no agent token configured (set \"token\" or AGENT_TOKEN)")
	}
	u, err := url.Parse(c.BackendURL)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return errors.New("backendUrl must be a full http(s) address such as https://home.example.com")
	}
	return nil
}

// WebSocketURL turns the backend address into the agent endpoint, using wss for https.
func (c Config) WebSocketURL() string {
	u, _ := url.Parse(strings.TrimRight(c.BackendURL, "/"))
	if u.Scheme == "https" {
		u.Scheme = "wss"
	} else {
		u.Scheme = "ws"
	}
	u.Path = strings.TrimRight(u.Path, "/") + "/agent/ws"
	return u.String()
}
