package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"

	"homedisplay/agent/internal/drivers"
	"homedisplay/agent/internal/protocol"
)

type fakeDriver struct {
	mu       sync.Mutex
	devices  []drivers.Device
	states   map[string]drivers.State
	setErr   error
	setCalls []string
}

func (f *fakeDriver) Type() string { return "fake" }

func (f *fakeDriver) Discover(ctx context.Context) ([]drivers.Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]drivers.Device(nil), f.devices...), nil
}

func (f *fakeDriver) State(ctx context.Context, id string) (drivers.State, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.states[id], nil
}

func (f *fakeDriver) SetPower(ctx context.Context, id string, on bool) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.setErr != nil {
		return f.setErr
	}
	f.setCalls = append(f.setCalls, id)
	st := f.states[id]
	st.Power, st.Reachable = on, true
	for i := range st.Channels {
		st.Channels[i].On = on
	}
	f.states[id] = st
	return nil
}

func (f *fakeDriver) Set(ctx context.Context, id, channel string, c drivers.Change) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.setErr != nil {
		return f.setErr
	}
	f.setCalls = append(f.setCalls, id+"/"+channel)
	st := f.states[id]
	for i := range st.Channels {
		if st.Channels[i].ID != channel {
			continue
		}
		if c.On != nil {
			st.Channels[i].On = *c.On
		}
		if c.Brightness != nil {
			st.Channels[i].Brightness = c.Brightness
		}
	}
	st.Power = false
	for _, ch := range st.Channels {
		st.Power = st.Power || ch.On
	}
	f.states[id] = st
	return nil
}

type recorder struct {
	mu   sync.Mutex
	sent []any
}

func (r *recorder) Send(ctx context.Context, msg any) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sent = append(r.sent, msg)
	return nil
}

func (r *recorder) take() []any {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := r.sent
	r.sent = nil
	return out
}

func setup() (*Agent, *fakeDriver, *recorder) {
	drv := &fakeDriver{
		devices: []drivers.Device{{ID: "lamp", Name: "Lamp", Type: "fake"}},
		states:  map[string]drivers.State{"lamp": {Power: false, Reachable: true}},
	}
	rec := &recorder{}
	ag := New("pi", []drivers.DeviceDriver{drv}, rec, slog.New(slog.NewTextHandler(io.Discard, nil)))
	return ag, drv, rec
}

func on(v bool) protocol.CommandArgs { return protocol.CommandArgs{On: &v} }

func ptr[T any](v T) *T { return &v }

// addBar adds a light bar with two channels to the fake driver.
func addBar(drv *fakeDriver) {
	drv.devices = append(drv.devices, drivers.Device{ID: "bar", Name: "Bar", Type: "fake", Channels: []drivers.Channel{
		{ID: "main", Label: "Main", Brightness: true},
		{ID: "background", Label: "Background", Brightness: true, Color: true},
	}})
	drv.states["bar"] = drivers.State{Power: true, Reachable: true, Channels: []drivers.ChannelState{
		{ID: "main", On: true, Brightness: ptr(80)},
		{ID: "background", On: false, Brightness: ptr(20)},
	}}
}

func TestFirstRefreshReportsDevicesWithTheirStates(t *testing.T) {
	ag, _, rec := setup()
	ag.refresh(context.Background(), true)

	sent := rec.take()
	if len(sent) != 1 {
		t.Fatalf("expected one devices message, got %d", len(sent))
	}
	msg, ok := sent[0].(protocol.Devices)
	if !ok || len(msg.Devices) != 1 || len(msg.States) != 1 || !msg.States[0].Reachable {
		t.Fatalf("unexpected message %+v", sent[0])
	}
	hello := ag.Hello()
	if hello.AgentID != "pi" || len(hello.Devices) != 1 || hello.Type != protocol.TypeHello {
		t.Fatalf("unexpected hello %+v", hello)
	}
}

func TestOnlyChangesAreReported(t *testing.T) {
	ag, drv, rec := setup()
	ag.refresh(context.Background(), true)
	rec.take()

	ag.refresh(context.Background(), false) // nothing changed
	if got := rec.take(); len(got) != 0 {
		t.Fatalf("expected silence, got %v", got)
	}

	drv.mu.Lock()
	drv.states["lamp"] = drivers.State{Power: true, Reachable: true} // switched on at the wall switch
	drv.mu.Unlock()
	ag.refresh(context.Background(), false)
	sent := rec.take()
	if len(sent) != 1 {
		t.Fatalf("expected one state message, got %v", sent)
	}
	if st, ok := sent[0].(protocol.State); !ok || !st.Power || st.DeviceID != "lamp" {
		t.Fatalf("unexpected message %+v", sent[0])
	}
}

func TestNewAndLostDevicesAreAnnounced(t *testing.T) {
	ag, drv, rec := setup()
	ag.refresh(context.Background(), true)
	rec.take()

	drv.mu.Lock()
	drv.devices = append(drv.devices, drivers.Device{ID: "bar", Name: "Bar", Type: "fake"})
	drv.states["bar"] = drivers.State{Power: true, Reachable: true}
	drv.mu.Unlock()
	ag.refresh(context.Background(), true)
	sent := rec.take()
	msg, ok := sent[0].(protocol.Devices)
	if len(sent) != 1 || !ok || len(msg.Devices) != 2 {
		t.Fatalf("expected a devices message with two devices, got %+v", sent)
	}
}

func TestCommandSwitchesTheDeviceAndAnswers(t *testing.T) {
	ag, drv, rec := setup()
	ag.refresh(context.Background(), true)
	rec.take()

	res := ag.HandleCommand(context.Background(), protocol.Command{ID: "c1", DeviceID: "lamp", Action: "setPower", Args: on(true)})
	if !res.OK || res.ID != "c1" || res.State == nil || !res.State.Power {
		t.Fatalf("unexpected result %+v", res)
	}
	if len(drv.setCalls) != 1 {
		t.Fatal("driver was not called")
	}
	if sent := rec.take(); len(sent) != 1 {
		t.Fatalf("expected a state message after the command, got %v", sent)
	}
}

func TestCommandFailuresAreReportedNotDropped(t *testing.T) {
	ag, drv, _ := setup()
	ag.refresh(context.Background(), true)

	drv.setErr = errors.New("bulb unreachable")
	res := ag.HandleCommand(context.Background(), protocol.Command{ID: "c2", DeviceID: "lamp", Action: "setPower", Args: on(true)})
	if res.OK || res.Error == "" || res.ID != "c2" {
		t.Fatalf("expected a failure result, got %+v", res)
	}
	for _, cmd := range []protocol.Command{
		{ID: "c3", DeviceID: "nope", Action: "setPower", Args: on(true)},
		{ID: "c4", DeviceID: "lamp", Action: "dance", Args: on(true)},
		{ID: "c5", DeviceID: "lamp", Action: "setPower"},
	} {
		if res := ag.HandleCommand(context.Background(), cmd); res.OK || res.ID != cmd.ID {
			t.Fatalf("command %s should have failed: %+v", cmd.ID, res)
		}
	}
}

func TestDeviceListCarriesChannelsAndStates(t *testing.T) {
	ag, drv, _ := setup()
	addBar(drv)
	ag.refresh(context.Background(), true)

	hello := ag.Hello()
	var bar protocol.DeviceInfo
	for _, d := range hello.Devices {
		if d.DeviceID == "bar" {
			bar = d
		}
	}
	if len(bar.Channels) != 2 || !bar.Channels[1].Capabilities.Color || bar.Channels[0].Capabilities.Color {
		t.Fatalf("channels = %+v", bar.Channels)
	}
	for _, st := range hello.States {
		if st.DeviceID == "bar" && (len(st.Channels) != 2 || !st.Channels[0].On || *st.Channels[1].Brightness != 20) {
			t.Fatalf("state = %+v", st)
		}
	}
}

func TestChannelChangesAreReportedWhenTheyDiffer(t *testing.T) {
	ag, drv, rec := setup()
	addBar(drv)
	ag.refresh(context.Background(), true)
	rec.take()

	drv.mu.Lock()
	st := drv.states["bar"]
	st.Channels = []drivers.ChannelState{{ID: "main", On: true, Brightness: ptr(80)}, {ID: "background", On: false, Brightness: ptr(55)}}
	drv.states["bar"] = st // only the background brightness changed, power is the same
	drv.mu.Unlock()
	ag.refresh(context.Background(), false)
	sent := rec.take()
	if len(sent) != 1 {
		t.Fatalf("expected one state message, got %v", sent)
	}
	if msg := sent[0].(protocol.State); *msg.Channels[1].Brightness != 55 {
		t.Fatalf("message = %+v", msg)
	}
}

func TestSetCommandChangesOneChannelAndReadsItBack(t *testing.T) {
	ag, drv, rec := setup()
	addBar(drv)
	ag.refresh(context.Background(), true)
	rec.take()

	res := ag.HandleCommand(context.Background(), protocol.Command{ID: "c1", DeviceID: "bar", Action: "set", Channel: "background",
		Args: protocol.CommandArgs{On: ptr(true), Brightness: ptr(40)}})
	if !res.OK || res.State == nil || len(res.State.Channels) != 2 {
		t.Fatalf("result = %+v", res)
	}
	main, bg := res.State.Channels[0], res.State.Channels[1]
	if !main.On || *main.Brightness != 80 || !bg.On || *bg.Brightness != 40 {
		t.Fatalf("main=%+v background=%+v", main, bg)
	}
	if got := drv.setCalls; len(got) != 1 || got[0] != "bar/background" {
		t.Fatalf("driver calls = %v", got)
	}
	if sent := rec.take(); len(sent) != 1 {
		t.Fatalf("expected a state message, got %v", sent)
	}
}

func TestSetPowerCommandSwitchesEveryChannel(t *testing.T) {
	ag, drv, _ := setup()
	addBar(drv)
	ag.refresh(context.Background(), true)

	res := ag.HandleCommand(context.Background(), protocol.Command{ID: "c1", DeviceID: "bar", Action: "setPower", Args: on(false)})
	if !res.OK || res.State.Power || res.State.Channels[0].On || res.State.Channels[1].On {
		t.Fatalf("result = %+v", res)
	}
}

func TestSetCommandNeedsAChannelAndAKnownDevice(t *testing.T) {
	ag, drv, _ := setup()
	addBar(drv)
	ag.refresh(context.Background(), true)
	for _, cmd := range []protocol.Command{
		{ID: "a", DeviceID: "bar", Action: "set", Args: protocol.CommandArgs{Brightness: ptr(10)}},
		{ID: "b", DeviceID: "nope", Action: "set", Channel: "main", Args: protocol.CommandArgs{Brightness: ptr(10)}},
	} {
		if res := ag.HandleCommand(context.Background(), cmd); res.OK || res.ID != cmd.ID {
			t.Fatalf("command %s should have failed: %+v", cmd.ID, res)
		}
	}
}

func TestExpectedStateAppliesACommandWhenTheDeviceCannotBeReadBack(t *testing.T) {
	prev := protocol.StateItem{DeviceID: "bar", Power: true, Reachable: true, Channels: []protocol.ChannelState{
		{ID: "main", On: true, Brightness: ptr(80)}, {ID: "background", On: false}}}
	got := expectedState(prev, protocol.Command{Action: "set", Channel: "background",
		Args: protocol.CommandArgs{On: ptr(true), Hue: ptr(120), Saturation: ptr(70)}})
	bg := got.Channels[1]
	if !bg.On || *bg.Hue != 120 || bg.ColorMode != drivers.ColorModeColor || !got.Power || !got.Channels[0].On {
		t.Fatalf("got %+v", got)
	}
	off := expectedState(prev, protocol.Command{Action: "setPower", Args: on(false)})
	if off.Power || off.Channels[0].On {
		t.Fatalf("got %+v", off)
	}
}
