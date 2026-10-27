package yeelight

import (
	"context"
	"net"
	"time"
)

const multicastAddr = "239.255.255.250:1982"

// searchMessage is the Yeelight discovery request (an SSDP-style multicast search).
const searchMessage = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1982\r\nMAN: \"ssdp:discover\"\r\nST: wifi_bulb\r\n\r\n"

// Searcher finds bulbs on the network. It is an interface so tests need no network.
type Searcher interface {
	Search(ctx context.Context, wait time.Duration) ([]Announcement, error)
}

// UDPSearcher sends the multicast search and collects the answers for a short time.
type UDPSearcher struct{}

func (UDPSearcher) Search(ctx context.Context, wait time.Duration) ([]Announcement, error) {
	conn, err := net.ListenPacket("udp4", ":0")
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	dest, err := net.ResolveUDPAddr("udp4", multicastAddr)
	if err != nil {
		return nil, err
	}
	if _, err := conn.WriteTo([]byte(searchMessage), dest); err != nil {
		return nil, err
	}

	deadline := time.Now().Add(wait)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	_ = conn.SetReadDeadline(deadline)

	found := map[string]Announcement{}
	buf := make([]byte, 2048)
	for ctx.Err() == nil {
		n, _, err := conn.ReadFrom(buf)
		if err != nil {
			break // deadline reached
		}
		if a, err := ParseAnnouncement(buf[:n]); err == nil {
			found[a.ID] = a
		}
	}
	out := make([]Announcement, 0, len(found))
	for _, a := range found {
		out = append(out, a)
	}
	return out, ctx.Err()
}

// Listen joins the multicast group and passes on the advertisements bulbs send by themselves.
// It runs until ctx ends and is best effort: some networks do not forward multicast.
func Listen(ctx context.Context, onAnnouncement func(Announcement)) error {
	group, err := net.ResolveUDPAddr("udp4", multicastAddr)
	if err != nil {
		return err
	}
	conn, err := net.ListenMulticastUDP("udp4", nil, group)
	if err != nil {
		return err
	}
	go func() {
		<-ctx.Done()
		conn.Close()
	}()
	buf := make([]byte, 2048)
	for {
		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		if a, err := ParseAnnouncement(buf[:n]); err == nil {
			onAnnouncement(a)
		}
	}
}
