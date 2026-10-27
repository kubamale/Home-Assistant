package yeelight

import (
	"errors"
	"net/url"
	"strings"
)

// Announcement is what a bulb says about itself, either in answer to a search or when it advertises.
type Announcement struct {
	ID    string // stable hardware id, such as 0x000000000015243f
	Model string
	Name  string
	Addr  string // host:port of the control interface
	Power bool
	// Support lists the control methods the bulb accepts, such as set_bright or bg_set_hsv. What a bulb
	// can do (and whether it has a background light) is read from here.
	Support map[string]bool
}

// ParseAnnouncement reads a Yeelight discovery response or NOTIFY advertisement.
func ParseAnnouncement(data []byte) (Announcement, error) {
	lines := strings.Split(strings.ReplaceAll(string(data), "\r\n", "\n"), "\n")
	if len(lines) == 0 {
		return Announcement{}, errors.New("empty message")
	}
	first := strings.TrimSpace(lines[0])
	if !strings.HasPrefix(first, "HTTP/1.1 200") && !strings.HasPrefix(first, "NOTIFY") {
		return Announcement{}, errors.New("not a Yeelight announcement")
	}
	headers := map[string]string{}
	for _, line := range lines[1:] {
		key, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		headers[strings.ToLower(strings.TrimSpace(key))] = strings.TrimSpace(value)
	}
	id := headers["id"]
	if id == "" {
		return Announcement{}, errors.New("announcement has no id")
	}
	location, err := url.Parse(headers["location"])
	if err != nil || location.Scheme != "yeelight" || location.Host == "" {
		return Announcement{}, errors.New("announcement has no usable location")
	}
	return Announcement{
		ID:      strings.ToLower(id),
		Model:   headers["model"],
		Name:    headers["name"],
		Addr:    location.Host,
		Power:   headers["power"] == "on",
		Support: parseSupport(headers["support"]),
	}, nil
}

func parseSupport(value string) map[string]bool {
	support := map[string]bool{}
	for _, method := range strings.Fields(value) {
		support[method] = true
	}
	return support
}
