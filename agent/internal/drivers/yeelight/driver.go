// Package yeelight controls Yeelight bulbs over their LAN protocol. Bulbs are found by discovery and
// identified by their stable hardware id; the IP address is runtime state that is refreshed
// whenever a bulb stops answering where it was last seen.
package yeelight

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"sort"
	"strings"
	"sync"
	"time"

	"homedisplay/agent/internal/drivers"
)

const (
	// A bulb that no discovery round has found this many times in a row is reported unreachable.
	missedBeforeUnreachable = 3
	// Do not search more often than this, however many bulbs are failing at once.
	minSearchGap = 5 * time.Second
	// Yeelight limits how fast commands may arrive, so keep a small gap between them per bulb.
	minCommandGap = 250 * time.Millisecond
	// Milliseconds a bulb takes to fade to a new setting.
	transition = 300

	channelMain       = "main"
	channelBackground = "background"
)

// Driver implements drivers.DeviceDriver for Yeelight bulbs.
type Driver struct {
	searcher   Searcher
	dial       func(ctx context.Context, addr string) (net.Conn, error)
	searchWait time.Duration
	cmdTimeout time.Duration
	now        func() time.Time
	log        *slog.Logger

	mu         sync.Mutex
	bulbs      map[string]*bulb
	lastSearch time.Time
}

type bulb struct {
	id        string
	name      string
	model     string
	addr      string
	power     bool
	reachable bool
	missed    int
	channels  []drivers.Channel
	support   map[string]bool
	last      []drivers.ChannelState // last state read, shown while the bulb is unreachable

	cmdMu       sync.Mutex // one command at a time per bulb
	lastCommand time.Time
}

// New creates a driver that discovers bulbs with the real multicast search.
func New(log *slog.Logger) *Driver {
	return &Driver{
		searcher: UDPSearcher{},
		dial: func(ctx context.Context, addr string) (net.Conn, error) {
			return (&net.Dialer{Timeout: 3 * time.Second}).DialContext(ctx, "tcp", addr)
		},
		searchWait: 2 * time.Second,
		cmdTimeout: 3 * time.Second,
		now:        time.Now,
		log:        log,
		bulbs:      map[string]*bulb{},
	}
}

func (d *Driver) Type() string { return "yeelight" }

// DeviceID turns a hardware id such as 0x000000000015243f into the id used by the rest of the system.
func DeviceID(hardwareID string) string {
	return "yeelight-" + strings.TrimPrefix(strings.ToLower(hardwareID), "0x")
}

func (d *Driver) Discover(ctx context.Context) ([]drivers.Device, error) {
	err := d.search(ctx, true)
	return d.devices(), err
}

// Observe records an advertisement a bulb sent by itself.
func (d *Driver) Observe(a Announcement) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.upsert(a)
}

func (d *Driver) State(ctx context.Context, id string) (drivers.State, error) {
	b, err := d.find(id)
	if err != nil {
		return drivers.State{}, err
	}
	channels := d.channelsOf(b)
	result, err := d.callWithRediscovery(ctx, b, "get_prop", propNames(channels))
	if err != nil {
		d.setReachable(b, false)
		d.log.Debug("bulb unreachable", "id", id, "err", err)
		return d.stateFrom(b, false), nil
	}
	states := parseProps(channels, result)
	d.mu.Lock()
	b.last, b.power, b.reachable, b.missed = states, anyOn(states), true, 0
	d.mu.Unlock()
	return drivers.State{Power: anyOn(states), Reachable: true, Channels: states}, nil
}

// SetPower switches every channel, the main light first.
func (d *Driver) SetPower(ctx context.Context, id string, on bool) error {
	b, err := d.find(id)
	if err != nil {
		return err
	}
	channels := d.channelsOf(b)
	for i := range channels {
		// On: main first. Off: background first, so switching the main light off does not have to
		// light the background again (see mainOff).
		ch := channels[i]
		if !on {
			ch = channels[len(channels)-1-i]
		}
		if err := d.apply(ctx, b, ch, drivers.Change{On: &on}); err != nil {
			return err
		}
	}
	return nil
}

// Set changes one channel. Several settings in one change are sent in a fixed order: power,
// brightness, then colour temperature or colour.
func (d *Driver) Set(ctx context.Context, id, channel string, change drivers.Change) error {
	b, err := d.find(id)
	if err != nil {
		return err
	}
	for _, ch := range d.channelsOf(b) {
		if ch.ID == channel {
			if err := change.Check(ch); err != nil {
				return err
			}
			return d.apply(ctx, b, ch, change)
		}
	}
	return fmt.Errorf("%s has no channel %q", b.name, channel)
}

// apply sends the commands for one channel's change. The background light uses the same methods
// with a bg_ prefix.
func (d *Driver) apply(ctx context.Context, b *bulb, ch drivers.Channel, c drivers.Change) error {
	prefix := ""
	if ch.ID == channelBackground {
		prefix = "bg_"
	}
	type step struct {
		method string
		params []any
	}
	var steps []step
	if ch.ID == channelMain && c.On != nil && !*c.On && d.supports(b, "bg_set_power") {
		// The bar's "off" switches the whole device off, background included: see mainOff.
		if err := d.mainOff(ctx, b); err != nil {
			d.setReachable(b, false)
			return fmt.Errorf("bulb %s did not accept the command: %w", b.name, err)
		}
		d.mu.Lock()
		b.reachable, b.missed = true, 0
		d.mu.Unlock()
		return nil
	}
	if ch.ID == channelBackground && c.On != nil && *c.On && d.supports(b, "bg_set_scene") {
		// Some bars ignore bg_set_power "on" (the Monitor Light Bar Pro, model lamp15, answers ok and
		// stays off); a scene turns the background light on reliably. It also carries brightness, white
		// and colour, so the separate steps below are not needed.
		params, err := d.backgroundScene(ctx, b, c)
		if err != nil {
			d.setReachable(b, false)
			return fmt.Errorf("bulb %s did not accept the command: %w", b.name, err)
		}
		steps = append(steps, step{"bg_set_scene", params})
	} else {
		if c.On != nil {
			steps = append(steps, step{prefix + "set_power", []any{onOff(*c.On), "smooth", transition}})
		}
		if c.Brightness != nil {
			steps = append(steps, step{prefix + "set_bright", []any{*c.Brightness, "smooth", transition}})
		}
		if c.ColorTemp != nil {
			steps = append(steps, step{prefix + "set_ct_abx", []any{*c.ColorTemp, "smooth", transition}})
		}
		if c.Hue != nil && c.Saturation != nil {
			steps = append(steps, step{prefix + "set_hsv", []any{*c.Hue, *c.Saturation, "smooth", transition}})
		}
	}
	for _, s := range steps {
		if _, err := d.callWithRediscovery(ctx, b, s.method, s.params); err != nil {
			d.setReachable(b, false)
			return fmt.Errorf("bulb %s did not accept the command: %w", b.name, err)
		}
	}
	d.mu.Lock()
	b.reachable, b.missed = true, 0
	d.mu.Unlock()
	return nil
}

// backgroundScene builds the bg_set_scene parameters that switch the background light on: the colour
// or white it last had, at its last brightness, with anything the change sets taking precedence.
func (d *Driver) backgroundScene(ctx context.Context, b *bulb, c drivers.Change) ([]any, error) {
	values, err := d.callWithRediscovery(ctx, b, "get_prop", []any{"bg_bright", "bg_ct", "bg_hue", "bg_sat", "bg_lmode"})
	if err != nil {
		return nil, err
	}
	return sceneParams(values, c), nil
}

// sceneParams turns the answer to get_prop bg_bright, bg_ct, bg_hue, bg_sat, bg_lmode into the
// parameters of bg_set_scene, letting the change's own brightness, white or colour win.
func sceneParams(values []string, c drivers.Change) []any {
	at := func(i int) string {
		if i < len(values) {
			return values[i]
		}
		return ""
	}
	or := func(p *int, fallback int) int {
		if p != nil {
			return *p
		}
		return fallback
	}
	bright := min(100, max(1, or(c.Brightness, or(number(at(0)), 50))))
	switch {
	case c.ColorTemp != nil:
		return []any{"ct", *c.ColorTemp, bright}
	case c.Hue != nil && c.Saturation != nil:
		return []any{"hsv", *c.Hue, *c.Saturation, bright}
	case at(4) == "2":
		return []any{"ct", or(number(at(1)), 4000), bright}
	}
	return []any{"hsv", or(number(at(2)), 0), or(number(at(3)), 100), bright}
}

// mainOff switches the main light off. On a bar with a background light (model lamp15) the plain "off"
// command switches the whole device off, so when the background is lit it is lit again on its own with
// a scene, which leaves the bar in its background-only mode (power on, main_power off). When the main
// light is already off there is nothing to do, and a bar that does not report main_power just gets "off".
func (d *Driver) mainOff(ctx context.Context, b *bulb) error {
	values, err := d.callWithRediscovery(ctx, b, "get_prop",
		[]any{"main_power", "bg_power", "bg_bright", "bg_ct", "bg_hue", "bg_sat", "bg_lmode"})
	if err != nil {
		return err
	}
	at := func(i int) string {
		if i < len(values) {
			return values[i]
		}
		return ""
	}
	mainPower, bgOn := at(0), at(1) == "on"
	if mainPower == "off" {
		return nil
	}
	if _, err := d.callWithRediscovery(ctx, b, "set_power", []any{"off", "smooth", transition}); err != nil {
		return err
	}
	if mainPower != "" && bgOn && d.supports(b, "bg_set_scene") {
		_, err := d.callWithRediscovery(ctx, b, "bg_set_scene", sceneParams(values[2:], drivers.Change{}))
		return err
	}
	return nil
}

func (d *Driver) supports(b *bulb, method string) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return b.support[method]
}

func onOff(on bool) string {
	if on {
		return "on"
	}
	return "off"
}

// callWithRediscovery tries the bulb at its last known address. If that fails (its address may have
// changed), it searches again and retries once at the address it finds.
func (d *Driver) callWithRediscovery(ctx context.Context, b *bulb, method string, params []any) ([]string, error) {
	result, err := d.call(ctx, b, method, params)
	if err == nil {
		return result, nil
	}
	oldAddr := d.address(b)
	d.log.Info("bulb did not answer, searching again", "id", b.id, "addr", oldAddr, "err", err)
	if searchErr := d.search(ctx, false); searchErr != nil {
		return nil, err
	}
	if newAddr := d.address(b); newAddr != oldAddr {
		d.log.Info("bulb moved", "id", b.id, "from", oldAddr, "to", newAddr)
		return d.call(ctx, b, method, params)
	}
	return nil, err
}

// call sends one command on a short-lived connection. Commands to a bulb are serialized and spaced.
func (d *Driver) call(ctx context.Context, b *bulb, method string, params []any) ([]string, error) {
	b.cmdMu.Lock()
	defer b.cmdMu.Unlock()
	if wait := minCommandGap - d.now().Sub(b.lastCommand); wait > 0 {
		select {
		case <-time.After(wait):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	defer func() { b.lastCommand = d.now() }()

	addr := d.address(b)
	conn, err := d.dial(ctx, addr)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(d.now().Add(d.cmdTimeout))

	request, _ := json.Marshal(map[string]any{"id": 1, "method": method, "params": params})
	if _, err := conn.Write(append(request, '\r', '\n')); err != nil {
		return nil, err
	}
	reader := bufio.NewReader(conn)
	for {
		line, err := reader.ReadBytes('\n')
		if err != nil {
			return nil, err
		}
		var reply struct {
			ID     *int  `json:"id"`
			Result []any `json:"result"`
			Error  *struct {
				Code    int
				Message string
			} `json:"error"`
			Method string `json:"method"`
		}
		if json.Unmarshal(line, &reply) != nil || reply.Method == "props" || reply.ID == nil || *reply.ID != 1 {
			continue // a pushed property notification or noise; keep reading for our answer
		}
		if reply.Error != nil {
			return nil, fmt.Errorf("bulb error %d: %s", reply.Error.Code, reply.Error.Message)
		}
		out := make([]string, len(reply.Result))
		for i, v := range reply.Result {
			out[i] = fmt.Sprint(v)
		}
		return out, nil
	}
}

// search runs one discovery round. Bulbs that did not answer are counted as missed only when
// countMisses is set (the periodic round), so a retry after one failed command does not mark others.
func (d *Driver) search(ctx context.Context, countMisses bool) error {
	d.mu.Lock()
	if !countMisses && d.now().Sub(d.lastSearch) < minSearchGap {
		d.mu.Unlock()
		return errors.New("searched very recently")
	}
	d.lastSearch = d.now()
	d.mu.Unlock()

	found, err := d.searcher.Search(ctx, d.searchWait)
	if err != nil && len(found) == 0 {
		return err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	seen := map[string]bool{}
	for _, a := range found {
		d.upsert(a)
		seen[a.ID] = true
	}
	if countMisses {
		for id, b := range d.bulbs {
			if !seen[id] {
				b.missed++
				if b.missed >= missedBeforeUnreachable {
					b.reachable = false
				}
			}
		}
	}
	return nil
}

// upsert must be called with d.mu held.
func (d *Driver) upsert(a Announcement) {
	b, ok := d.bulbs[a.ID]
	if !ok {
		b = &bulb{id: a.ID}
		d.bulbs[a.ID] = b
		d.log.Info("found bulb", "id", a.ID, "addr", a.Addr, "model", a.Model)
	}
	b.addr, b.model, b.power, b.reachable, b.missed = a.Addr, a.Model, a.Power, true, 0
	// An announcement without a support list (some advertisements) must not erase what is already known.
	if len(a.Support) > 0 || b.channels == nil {
		b.channels, b.support = channelsFor(a.Model, a.Support), a.Support
	}
	b.name = a.Name
	if b.name == "" {
		b.name = defaultName(a)
	}
}

func defaultName(a Announcement) string {
	suffix := strings.TrimPrefix(a.ID, "0x")
	if len(suffix) > 4 {
		suffix = suffix[len(suffix)-4:]
	}
	model := a.Model
	if model == "" {
		model = "light"
	}
	return "Yeelight " + model + " " + suffix
}

func (d *Driver) devices() []drivers.Device {
	d.mu.Lock()
	defer d.mu.Unlock()
	out := make([]drivers.Device, 0, len(d.bulbs))
	for _, b := range d.bulbs {
		out = append(out, drivers.Device{ID: DeviceID(b.id), Name: b.name, Type: d.Type(), Channels: b.channels})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

func (d *Driver) find(deviceID string) (*bulb, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	for _, b := range d.bulbs {
		if DeviceID(b.id) == deviceID {
			return b, nil
		}
	}
	return nil, fmt.Errorf("unknown device %s", deviceID)
}

func (d *Driver) address(b *bulb) string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return b.addr
}

func (d *Driver) channelsOf(b *bulb) []drivers.Channel {
	d.mu.Lock()
	defer d.mu.Unlock()
	return b.channels
}

// stateFrom is the last state read from the bulb, used while it cannot be reached. Before any read
// it is built from the power the bulb advertised.
func (d *Driver) stateFrom(b *bulb, reachable bool) drivers.State {
	d.mu.Lock()
	defer d.mu.Unlock()
	states := b.last
	if states == nil {
		states = make([]drivers.ChannelState, len(b.channels))
		for i, ch := range b.channels {
			states[i] = drivers.ChannelState{ID: ch.ID, On: ch.ID == channelMain && b.power}
		}
	}
	return drivers.State{Power: anyOn(states), Reachable: reachable, Channels: states}
}

func (d *Driver) setReachable(b *bulb, reachable bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	b.reachable = reachable
}
