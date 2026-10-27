// Package agent is the glue between the backend link and the device drivers: it tells the backend
// what devices exist and what state they are in, and carries out the backend's commands.
package agent

import (
	"context"
	"fmt"
	"log/slog"
	"reflect"
	"sort"
	"sync"
	"time"

	"homedisplay/agent/internal/drivers"
	"homedisplay/agent/internal/protocol"
)

// Version is reported to the backend in the hello message.
const Version = "0.1.0"

// Sender delivers a message to the backend. It may fail while the link is down; that is fine,
// because a full hello is sent again on every reconnect.
type Sender interface {
	Send(ctx context.Context, msg any) error
}

type Agent struct {
	id      string
	drivers []drivers.DeviceDriver
	send    Sender
	log     *slog.Logger
	now     func() time.Time

	// How often device state is polled, and how often discovery runs.
	PollEvery     time.Duration
	DiscoverEvery time.Duration

	mu      sync.Mutex
	devices map[string]deviceEntry
}

type deviceEntry struct {
	info   protocol.DeviceInfo
	driver drivers.DeviceDriver
	state  protocol.StateItem
}

func New(id string, ds []drivers.DeviceDriver, send Sender, log *slog.Logger) *Agent {
	return &Agent{
		id:            id,
		drivers:       ds,
		send:          send,
		log:           log,
		now:           time.Now,
		PollEvery:     30 * time.Second,
		DiscoverEvery: 60 * time.Second,
		devices:       map[string]deviceEntry{},
	}
}

// SetSender sets where messages go. It exists because the link needs the agent as its handler
// while the agent needs the link as its sender.
func (a *Agent) SetSender(s Sender) { a.send = s }

// Hello is the full snapshot sent right after every (re)connect.
func (a *Agent) Hello() protocol.Hello {
	infos, states := a.snapshot()
	return protocol.Hello{V: protocol.Version, Type: protocol.TypeHello, AgentID: a.id, Version: Version,
		Devices: infos, States: states}
}

// Run discovers devices and then polls them until ctx ends.
func (a *Agent) Run(ctx context.Context) {
	a.refresh(ctx, true)
	poll := time.NewTicker(a.PollEvery)
	discover := time.NewTicker(a.DiscoverEvery)
	defer poll.Stop()
	defer discover.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-discover.C:
			a.refresh(ctx, true)
		case <-poll.C:
			a.refresh(ctx, false)
		}
	}
}

// refresh optionally runs discovery, then reads every device's state and reports what changed.
func (a *Agent) refresh(ctx context.Context, discover bool) {
	devicesChanged := false
	if discover {
		for _, drv := range a.drivers {
			found, err := drv.Discover(ctx)
			if err != nil {
				a.log.Warn("discovery failed", "driver", drv.Type(), "err", err)
			}
			if a.mergeDevices(drv, found) {
				devicesChanged = true
			}
		}
	}

	var changed []protocol.State
	for _, entry := range a.entries() {
		st, err := entry.driver.State(ctx, entry.info.DeviceID)
		if err != nil {
			a.log.Warn("state read failed", "device", entry.info.DeviceID, "err", err)
			continue
		}
		if msg, ok := a.updateState(entry.info.DeviceID, st); ok {
			changed = append(changed, msg)
		}
	}

	if devicesChanged {
		infos, states := a.snapshot()
		_ = a.send.Send(ctx, protocol.Devices{V: protocol.Version, Type: protocol.TypeDevices, Devices: infos, States: states})
		return // the devices message already carries all states
	}
	for _, msg := range changed {
		_ = a.send.Send(ctx, msg)
	}
}

// HandleCommand carries out one command and always returns a result. After a successful command the
// device is read back, so the result (and the state sent to the backend) shows what it really did.
func (a *Agent) HandleCommand(ctx context.Context, cmd protocol.Command) protocol.Result {
	fail := func(format string, args ...any) protocol.Result {
		return protocol.Result{V: protocol.Version, Type: protocol.TypeResult, ID: cmd.ID, OK: false, Error: fmt.Sprintf(format, args...)}
	}
	a.mu.Lock()
	entry, ok := a.devices[cmd.DeviceID]
	a.mu.Unlock()

	var err error
	switch cmd.Action {
	case protocol.ActionSetPower:
		if cmd.Args.On == nil {
			return fail("setPower needs \"on\"")
		}
		if !ok {
			return fail("unknown device %s", cmd.DeviceID)
		}
		err = entry.driver.SetPower(ctx, cmd.DeviceID, *cmd.Args.On)
	case protocol.ActionSet:
		if cmd.Channel == "" {
			return fail("set needs a channel")
		}
		if !ok {
			return fail("unknown device %s", cmd.DeviceID)
		}
		err = entry.driver.Set(ctx, cmd.DeviceID, cmd.Channel, drivers.Change{On: cmd.Args.On,
			Brightness: cmd.Args.Brightness, ColorTemp: cmd.Args.ColorTemp, Hue: cmd.Args.Hue, Saturation: cmd.Args.Saturation})
	default:
		return fail("unsupported command %q", cmd.Action)
	}
	if err != nil {
		a.log.Warn("command failed", "device", cmd.DeviceID, "err", err)
		return fail("%v", err)
	}

	state, readErr := entry.driver.State(ctx, cmd.DeviceID)
	if readErr != nil || !state.Reachable {
		// The command went through but the read-back did not; assume the device did what was asked.
		state = expectedState(entry.state, cmd)
	}
	item := a.stateItem(cmd.DeviceID, state)
	if msg, changed := a.updateState(cmd.DeviceID, state); changed {
		_ = a.send.Send(ctx, msg)
	}
	return protocol.Result{V: protocol.Version, Type: protocol.TypeResult, ID: cmd.ID, OK: true, State: &item}
}

// expectedState is the previous state with the command applied, used when the device cannot be read back.
func expectedState(prev protocol.StateItem, cmd protocol.Command) drivers.State {
	channels := fromProtocolChannels(prev.Channels)
	for i := range channels {
		if cmd.Action == protocol.ActionSetPower || channels[i].ID == cmd.Channel {
			if cmd.Args.On != nil {
				channels[i].On = *cmd.Args.On
			}
			if cmd.Action == protocol.ActionSet {
				if cmd.Args.Brightness != nil {
					channels[i].Brightness = cmd.Args.Brightness
				}
				if cmd.Args.ColorTemp != nil {
					channels[i].ColorTemp, channels[i].ColorMode = cmd.Args.ColorTemp, drivers.ColorModeTemperature
				}
				if cmd.Args.Hue != nil && cmd.Args.Saturation != nil {
					channels[i].Hue, channels[i].Saturation, channels[i].ColorMode = cmd.Args.Hue, cmd.Args.Saturation, drivers.ColorModeColor
				}
			}
		}
	}
	power := prev.Power
	if len(channels) > 0 {
		power = false
		for _, c := range channels {
			power = power || c.On
		}
	} else if cmd.Args.On != nil {
		power = *cmd.Args.On
	}
	return drivers.State{Power: power, Reachable: true, Channels: channels}
}

// mergeDevices records the devices a driver knows. It reports whether the device set changed.
func (a *Agent) mergeDevices(drv drivers.DeviceDriver, found []drivers.Device) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	changed := false
	ids := map[string]bool{}
	for _, d := range found {
		ids[d.ID] = true
		existing, ok := a.devices[d.ID]
		info := protocol.DeviceInfo{DeviceID: d.ID, Name: d.Name, Type: d.Type, Channels: toChannelInfos(d.Channels)}
		if !ok || !reflect.DeepEqual(existing.info, info) {
			changed = true
		}
		entry := deviceEntry{info: info, driver: drv, state: existing.state}
		a.devices[d.ID] = entry
	}
	// Devices a driver no longer lists are dropped; drivers keep unreachable devices in their list.
	for id, entry := range a.devices {
		if entry.driver == drv && !ids[id] {
			delete(a.devices, id)
			changed = true
		}
	}
	return changed
}

func (a *Agent) entries() []deviceEntry {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]deviceEntry, 0, len(a.devices))
	for _, e := range a.devices {
		out = append(out, e)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].info.DeviceID < out[j].info.DeviceID })
	return out
}

func (a *Agent) snapshot() ([]protocol.DeviceInfo, []protocol.StateItem) {
	entries := a.entries()
	infos := make([]protocol.DeviceInfo, 0, len(entries))
	states := make([]protocol.StateItem, 0, len(entries))
	for _, e := range entries {
		infos = append(infos, e.info)
		states = append(states, e.state)
	}
	return infos, states
}

func (a *Agent) stateItem(id string, st drivers.State) protocol.StateItem {
	return protocol.StateItem{DeviceID: id, Power: st.Power, Reachable: st.Reachable,
		UpdatedAt: a.now().UTC().Format(time.RFC3339Nano), Channels: toProtocolChannels(st.Channels)}
}

// updateState stores a new state and returns the message to send if it differs from the old one.
func (a *Agent) updateState(id string, st drivers.State) (protocol.State, bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	entry, ok := a.devices[id]
	if !ok {
		return protocol.State{}, false
	}
	old := entry.state
	item := a.stateItem(id, st)
	if old.UpdatedAt != "" && old.Power == st.Power && old.Reachable == st.Reachable &&
		reflect.DeepEqual(old.Channels, item.Channels) {
		return protocol.State{}, false
	}
	entry.state = item
	a.devices[id] = entry
	return protocol.State{V: protocol.Version, Type: protocol.TypeState, DeviceID: id, Power: item.Power,
		Reachable: item.Reachable, UpdatedAt: item.UpdatedAt, Channels: item.Channels}, true
}
