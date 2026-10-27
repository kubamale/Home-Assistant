// Package link keeps the agent's outbound WebSocket to the backend alive: it connects with the
// agent token, sends the hello, answers commands, sends heartbeats and reconnects with backoff.
package link

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"

	"homedisplay/agent/internal/protocol"
)

// Handler is what the link needs from the agent.
type Handler interface {
	Hello() protocol.Hello
	HandleCommand(ctx context.Context, cmd protocol.Command) protocol.Result
}

type Link struct {
	url     string
	token   string
	handler Handler
	log     *slog.Logger

	// PingEvery is how often a heartbeat is sent; the connection is dropped if nothing at all
	// arrives for SilenceLimit.
	PingEvery    time.Duration
	SilenceLimit time.Duration
	Backoff      []time.Duration

	mu   sync.RWMutex
	conn *websocket.Conn
}

func New(url, token string, handler Handler, log *slog.Logger) *Link {
	return &Link{
		url:          url,
		token:        token,
		handler:      handler,
		log:          log,
		PingEvery:    20 * time.Second,
		SilenceLimit: 60 * time.Second,
		Backoff:      []time.Duration{time.Second, 2 * time.Second, 5 * time.Second, 10 * time.Second, 30 * time.Second, 60 * time.Second},
	}
}

// Send delivers a message if connected. While disconnected it returns an error and the message is
// dropped; the next connection starts with a full hello anyway.
func (l *Link) Send(ctx context.Context, msg any) error {
	l.mu.RLock()
	conn := l.conn
	l.mu.RUnlock()
	if conn == nil {
		return errors.New("not connected")
	}
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageText, data)
}

// Run connects and reconnects until ctx ends.
func (l *Link) Run(ctx context.Context) {
	attempt := 0
	for ctx.Err() == nil {
		connectedAt := time.Now()
		err := l.session(ctx)
		if ctx.Err() != nil {
			return
		}
		if time.Since(connectedAt) > time.Minute {
			attempt = 0 // it was healthy for a while; start the backoff over
		}
		delay := l.Backoff[min(attempt, len(l.Backoff)-1)]
		attempt++
		// A little jitter so a restarted backend is not hit by every agent at once.
		delay += time.Duration(rand.Int64N(int64(delay)/5 + 1))
		l.log.Warn("backend link down, will retry", "err", err, "in", delay.Round(time.Millisecond))
		select {
		case <-time.After(delay):
		case <-ctx.Done():
			return
		}
	}
}

// session is one connection from dial to failure.
func (l *Link) session(ctx context.Context) error {
	dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	conn, _, err := websocket.Dial(dialCtx, l.url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": []string{"Bearer " + l.token}},
	})
	cancel()
	if err != nil {
		return err
	}
	conn.SetReadLimit(64 * 1024)
	defer conn.CloseNow()

	l.mu.Lock()
	l.conn = conn
	l.mu.Unlock()
	defer func() {
		l.mu.Lock()
		l.conn = nil
		l.mu.Unlock()
	}()

	sessionCtx, stop := context.WithCancel(ctx)
	defer stop()

	if err := l.Send(sessionCtx, l.handler.Hello()); err != nil {
		return err
	}
	l.log.Info("connected to backend")

	go l.heartbeat(sessionCtx, stop)

	for {
		readCtx, cancel := context.WithTimeout(sessionCtx, l.SilenceLimit)
		_, data, err := conn.Read(readCtx)
		cancel()
		if err != nil {
			return err
		}
		var env protocol.Envelope
		if json.Unmarshal(data, &env) != nil {
			continue
		}
		switch env.Type {
		case protocol.TypeCommand:
			var cmd protocol.Command
			if json.Unmarshal(data, &cmd) != nil {
				continue
			}
			// Commands may take a few seconds; do not hold up reading (and heartbeats) meanwhile.
			go func() {
				result := l.handler.HandleCommand(sessionCtx, cmd)
				if err := l.Send(sessionCtx, result); err != nil {
					l.log.Warn("could not send command result", "err", err)
				}
			}()
		case protocol.TypePing:
			_ = l.Send(sessionCtx, protocol.Heartbeat{V: protocol.Version, Type: protocol.TypePong})
		case protocol.TypePong:
			// our heartbeat was answered; being read at all resets the silence timer
		default:
			l.log.Debug("ignoring unknown message", "type", env.Type)
		}
	}
}

func (l *Link) heartbeat(ctx context.Context, stop context.CancelFunc) {
	ticker := time.NewTicker(l.PingEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := l.Send(ctx, protocol.Heartbeat{V: protocol.Version, Type: protocol.TypePing}); err != nil {
				stop()
				return
			}
		}
	}
}
