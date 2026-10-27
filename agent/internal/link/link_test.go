package link

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"homedisplay/agent/internal/protocol"
)

type fakeHandler struct{}

func (fakeHandler) Hello() protocol.Hello {
	return protocol.Hello{V: 1, Type: protocol.TypeHello, AgentID: "pi", Devices: []protocol.DeviceInfo{{DeviceID: "lamp"}}}
}

func (fakeHandler) HandleCommand(ctx context.Context, cmd protocol.Command) protocol.Result {
	return protocol.Result{V: 1, Type: protocol.TypeResult, ID: cmd.ID, OK: true}
}

// fakeBackend accepts agent connections and records what they say.
type fakeBackend struct {
	srv      *httptest.Server
	mu       sync.Mutex
	messages chan map[string]any
	conns    chan *websocket.Conn
	authSeen []string
}

func newFakeBackend(t *testing.T) *fakeBackend {
	t.Helper()
	b := &fakeBackend{messages: make(chan map[string]any, 20), conns: make(chan *websocket.Conn, 5)}
	b.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b.mu.Lock()
		b.authSeen = append(b.authSeen, r.Header.Get("Authorization"))
		b.mu.Unlock()
		if r.Header.Get("Authorization") != "Bearer secret" {
			http.Error(w, "no", http.StatusUnauthorized)
			return
		}
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		b.conns <- c
		for {
			_, data, err := c.Read(context.Background())
			if err != nil {
				return
			}
			var m map[string]any
			_ = json.Unmarshal(data, &m)
			b.messages <- m
		}
	}))
	t.Cleanup(b.srv.Close)
	return b
}

func (b *fakeBackend) wsURL() string { return "ws" + strings.TrimPrefix(b.srv.URL, "http") }

func (b *fakeBackend) next(t *testing.T) map[string]any {
	t.Helper()
	select {
	case m := <-b.messages:
		return m
	case <-time.After(3 * time.Second):
		t.Fatal("no message from the agent within 3 s")
		return nil
	}
}

func newLink(url, token string) *Link {
	l := New(url, token, fakeHandler{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	l.Backoff = []time.Duration{20 * time.Millisecond}
	l.PingEvery = 50 * time.Millisecond
	return l
}

func TestSendsHelloAuthenticatesAndAnswersCommands(t *testing.T) {
	b := newFakeBackend(t)
	l := newLink(b.wsURL(), "secret")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go l.Run(ctx)

	hello := b.next(t)
	if hello["type"] != "hello" || hello["agentId"] != "pi" {
		t.Fatalf("unexpected first message %v", hello)
	}

	conn := <-b.conns
	cmd, _ := json.Marshal(protocol.Command{V: 1, Type: protocol.TypeCommand, ID: "c1", DeviceID: "lamp", Action: "setPower"})
	if err := conn.Write(context.Background(), websocket.MessageText, cmd); err != nil {
		t.Fatal(err)
	}
	for {
		m := b.next(t)
		if m["type"] == "result" {
			if m["id"] != "c1" || m["ok"] != true {
				t.Fatalf("unexpected result %v", m)
			}
			break
		}
		if m["type"] != "ping" {
			t.Fatalf("unexpected message %v", m)
		}
	}
}

func TestSendsHeartbeats(t *testing.T) {
	b := newFakeBackend(t)
	l := newLink(b.wsURL(), "secret")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go l.Run(ctx)
	b.next(t) // hello
	if m := b.next(t); m["type"] != "ping" {
		t.Fatalf("expected a ping, got %v", m)
	}
}

func TestReconnectsAfterTheBackendDropsTheConnection(t *testing.T) {
	b := newFakeBackend(t)
	l := newLink(b.wsURL(), "secret")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go l.Run(ctx)

	b.next(t) // first hello
	first := <-b.conns
	_ = first.Close(websocket.StatusGoingAway, "restart")

	deadline := time.After(3 * time.Second)
	for {
		select {
		case m := <-b.messages:
			if m["type"] == "hello" {
				return // a new connection greeted the backend again
			}
		case <-deadline:
			t.Fatal("the agent did not reconnect")
		}
	}
}

func TestKeepsRetryingWithAWrongToken(t *testing.T) {
	b := newFakeBackend(t)
	l := newLink(b.wsURL(), "wrong")
	ctx, cancel := context.WithCancel(context.Background())
	go l.Run(ctx)
	time.Sleep(300 * time.Millisecond)
	cancel()

	b.mu.Lock()
	attempts := len(b.authSeen)
	b.mu.Unlock()
	if attempts < 3 {
		t.Fatalf("expected repeated attempts, got %d", attempts)
	}
	select {
	case <-b.conns:
		t.Fatal("a wrong token must never get a connection")
	default:
	}
}

func TestSendFailsWhileDisconnected(t *testing.T) {
	l := newLink("ws://127.0.0.1:1", "secret")
	if err := l.Send(context.Background(), protocol.Heartbeat{}); err == nil {
		t.Fatal("expected an error")
	}
}
