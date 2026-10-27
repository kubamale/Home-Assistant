package yeelight

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"homedisplay/agent/internal/drivers"
)

// fakeBulb speaks enough of the Yeelight control protocol to test the driver.
type fakeBulb struct {
	ln    net.Listener
	mu    sync.Mutex
	power string
	props map[string]string // other properties by get_prop name, such as bright or bg_power
	// ignoreBgOn mimics the lamp15 firmware: bg_set_power "on" and bg_toggle answer ok and do nothing.
	ignoreBgOn bool
	// lamp15 mimics the rest of that bar's behaviour (measured on a real one): power is the power of the
	// device, main_power of the main light alone; "off" switches everything off unless only the
	// background glows; a background scene switches the device on, from off leaving the main light off.
	lamp15   bool
	requests []map[string]any
}

func newFakeBulb(t *testing.T) *fakeBulb {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	b := &fakeBulb{ln: ln, power: "off", props: map[string]string{}}
	go b.serve()
	t.Cleanup(func() { ln.Close() })
	return b
}

func (b *fakeBulb) addr() string { return b.ln.Addr().String() }

func (b *fakeBulb) serve() {
	for {
		conn, err := b.ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer conn.Close()
			line, err := bufio.NewReader(conn).ReadBytes('\n')
			if err != nil {
				return
			}
			var req map[string]any
			_ = json.Unmarshal(line, &req)
			b.mu.Lock()
			b.requests = append(b.requests, req)
			method, _ := req["method"].(string)
			params, _ := req["params"].([]any)
			var answer []string
			if method == "get_prop" {
				for _, name := range params {
					if name == "power" {
						answer = append(answer, b.power)
					} else {
						answer = append(answer, b.props[name.(string)])
					}
				}
			} else {
				b.apply(method, params)
				answer = []string{"ok"}
			}
			b.mu.Unlock()
			// a pushed notification arrives before the answer, as real bulbs do
			_, _ = io.WriteString(conn, `{"method":"props","params":{"power":"on"}}`+"\r\n")
			reply, _ := json.Marshal(map[string]any{"id": 1, "result": answer})
			_, _ = io.WriteString(conn, string(reply)+"\r\n")
		}()
	}
}

// apply updates the bulb's properties the way a real one does. The background light's methods and
// properties carry a bg_ prefix (its colour mode is bg_lmode).
func (b *fakeBulb) apply(method string, params []any) {
	if b.lamp15 && b.applyLamp15(method, params) {
		return
	}
	prefix, name := "", method
	if rest, ok := strings.CutPrefix(method, "bg_"); ok {
		prefix, name = "bg_", rest
	}
	mode := "color_mode"
	if prefix != "" {
		mode = "bg_lmode"
	}
	number := func(i int) string { return strconv.Itoa(int(params[i].(float64))) }
	switch name {
	case "set_power":
		if prefix == "" {
			b.power = params[0].(string)
		} else if !(b.ignoreBgOn && params[0] == "on") {
			b.props["bg_power"] = params[0].(string)
		}
	case "set_scene":
		// ["hsv", hue, sat, bright] or ["ct", kelvin, bright]; a scene switches the light on
		b.props[prefix+"power"] = "on"
		if prefix == "" {
			b.power = "on"
		}
		if params[0] == "ct" {
			b.props[prefix+"ct"], b.props[prefix+"bright"], b.props[mode] = number(1), number(2), "2"
		} else {
			b.props[prefix+"hue"], b.props[prefix+"sat"], b.props[prefix+"bright"], b.props[mode] = number(1), number(2), number(3), "3"
		}
	case "set_bright":
		b.props[prefix+"bright"] = number(0)
	case "set_ct_abx":
		b.props[prefix+"ct"], b.props[mode] = number(0), "2"
	case "set_hsv":
		b.props[prefix+"hue"], b.props[prefix+"sat"], b.props[mode] = number(0), number(1), "3"
	}
}

// lastParams returns the parameters of the most recent call of a method, or nil.
func (b *fakeBulb) lastParams(method string) []any {
	b.mu.Lock()
	defer b.mu.Unlock()
	for i := len(b.requests) - 1; i >= 0; i-- {
		if b.requests[i]["method"] == method {
			return b.requests[i]["params"].([]any)
		}
	}
	return nil
}

// applyLamp15 models the lamp15's device-level behaviour; it reports whether the command is fully handled.
func (b *fakeBulb) applyLamp15(method string, params []any) bool {
	switch method {
	case "set_power":
		if params[0] == "on" {
			b.props["main_power"] = "on"
			return false
		}
		if b.props["main_power"] == "off" && b.power == "on" {
			return true // only the background is lit: the main light is already off
		}
		b.props["main_power"], b.props["bg_power"] = "off", "off"
	case "bg_set_scene":
		b.power = "on" // from "off" this leaves main_power off: the background-only mode
	}
	return false
}

// methods lists the methods the bulb received, in order.
func (b *fakeBulb) methods() []string {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]string, len(b.requests))
	for i, r := range b.requests {
		out[i] = r["method"].(string)
	}
	return out
}

func (b *fakeBulb) lastMethod() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if len(b.requests) == 0 {
		return ""
	}
	return b.requests[len(b.requests)-1]["method"].(string)
}

type fakeSearcher struct {
	mu      sync.Mutex
	results []Announcement
	calls   int
}

func (s *fakeSearcher) set(a ...Announcement) {
	s.mu.Lock()
	s.results = a
	s.mu.Unlock()
}

func (s *fakeSearcher) Search(ctx context.Context, wait time.Duration) ([]Announcement, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls++
	return append([]Announcement(nil), s.results...), nil
}

func newTestDriver(s Searcher) *Driver {
	d := New(slog.New(slog.NewTextHandler(io.Discard, nil)))
	d.searcher = s
	d.cmdTimeout = time.Second
	return d
}

func TestDiscoverFindsBulbsAndNamesThem(t *testing.T) {
	bulb := newFakeBulb(t)
	s := &fakeSearcher{}
	s.set(Announcement{ID: "0x00000000000000ab", Model: "color", Addr: bulb.addr(), Power: true})
	d := newTestDriver(s)

	devices, err := d.Discover(context.Background())
	if err != nil || len(devices) != 1 {
		t.Fatalf("devices=%v err=%v", devices, err)
	}
	if devices[0].ID != "yeelight-00000000000000ab" || devices[0].Name != "Yeelight color 00ab" || devices[0].Type != "yeelight" {
		t.Fatalf("unexpected device %+v", devices[0])
	}
}

func TestSetPowerAndStateTalkToTheBulb(t *testing.T) {
	bulb := newFakeBulb(t)
	s := &fakeSearcher{}
	s.set(Announcement{ID: "0xab", Name: "Light bar", Addr: bulb.addr()})
	d := newTestDriver(s)
	ctx := context.Background()
	if _, err := d.Discover(ctx); err != nil {
		t.Fatal(err)
	}
	id := DeviceID("0xab")

	if err := d.SetPower(ctx, id, true); err != nil {
		t.Fatal(err)
	}
	if bulb.power != "on" {
		t.Fatalf("bulb power = %s", bulb.power)
	}
	st, err := d.State(ctx, id)
	if err != nil || !st.Power || !st.Reachable {
		t.Fatalf("state=%+v err=%v", st, err)
	}
	if err := d.SetPower(ctx, id, false); err != nil {
		t.Fatal(err)
	}
	st, _ = d.State(ctx, id)
	if st.Power {
		t.Fatal("expected the bulb to be off")
	}
}

func TestFindsABulbAgainWhenItsAddressChanges(t *testing.T) {
	oldBulb := newFakeBulb(t)
	newBulb := newFakeBulb(t)
	s := &fakeSearcher{}
	s.set(Announcement{ID: "0xab", Addr: oldBulb.addr()})
	d := newTestDriver(s)
	ctx := context.Background()
	if _, err := d.Discover(ctx); err != nil {
		t.Fatal(err)
	}

	// DHCP gives the bulb a new address: nothing listens at the old one any more
	oldBulb.ln.Close()
	s.set(Announcement{ID: "0xab", Addr: newBulb.addr()})
	d.mu.Lock()
	d.lastSearch = time.Time{} // the earlier discovery was long ago
	d.mu.Unlock()

	if err := d.SetPower(ctx, DeviceID("0xab"), true); err != nil {
		t.Fatalf("expected the command to succeed after rediscovery: %v", err)
	}
	if newBulb.lastMethod() != "set_power" {
		t.Fatal("the command did not reach the bulb at its new address")
	}
}

func TestUnreachableBulbIsReportedNotDropped(t *testing.T) {
	bulb := newFakeBulb(t)
	s := &fakeSearcher{}
	s.set(Announcement{ID: "0xab", Addr: bulb.addr(), Power: true})
	d := newTestDriver(s)
	ctx := context.Background()
	if _, err := d.Discover(ctx); err != nil {
		t.Fatal(err)
	}
	bulb.ln.Close() // the bulb is switched off at the wall

	st, err := d.State(ctx, DeviceID("0xab"))
	if err != nil {
		t.Fatal(err)
	}
	if st.Reachable {
		t.Fatal("expected unreachable")
	}
	if err := d.SetPower(ctx, DeviceID("0xab"), true); err == nil {
		t.Fatal("expected the command to fail")
	}
	devices, _ := d.Discover(ctx)
	if len(devices) != 1 {
		t.Fatalf("an unreachable bulb must stay in the list, got %v", devices)
	}
}

func TestBulbsMissedInSeveralRoundsBecomeUnreachable(t *testing.T) {
	bulb := newFakeBulb(t)
	s := &fakeSearcher{}
	s.set(Announcement{ID: "0xab", Addr: bulb.addr()})
	d := newTestDriver(s)
	ctx := context.Background()
	_, _ = d.Discover(ctx)

	s.set() // nothing answers any more
	for i := 0; i < missedBeforeUnreachable; i++ {
		_, _ = d.Discover(ctx)
	}
	d.mu.Lock()
	reachable := d.bulbs["0xab"].reachable
	d.mu.Unlock()
	if reachable {
		t.Fatal("expected the bulb to be flagged unreachable after repeated misses")
	}
}

func TestUnknownDeviceIsAnError(t *testing.T) {
	d := newTestDriver(&fakeSearcher{})
	if _, err := d.State(context.Background(), "yeelight-nope"); err == nil {
		t.Fatal("expected an error")
	}
	if err := d.SetPower(context.Background(), "yeelight-nope", true); err == nil {
		t.Fatal("expected an error")
	}
}

func TestBulbErrorsAreReported(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_, _ = bufio.NewReader(conn).ReadBytes('\n')
		_, _ = io.WriteString(conn, `{"id":1,"error":{"code":-1,"message":"client quota exceeded"}}`+"\r\n")
	}()
	s := &fakeSearcher{}
	s.set(Announcement{ID: "0xab", Addr: ln.Addr().String()})
	d := newTestDriver(s)
	_, _ = d.Discover(context.Background())
	err := d.SetPower(context.Background(), DeviceID("0xab"), true)
	if err == nil {
		t.Fatal("expected the bulb's error to be reported")
	}
}

// lamp15 is the Yeelight monitor light bar: a main light with brightness and colour temperature, and a
// background light that also shows colour.
var lamp15 = map[string]bool{
	"get_prop": true, "set_power": true, "toggle": true, "set_ct_abx": true, "set_bright": true,
	"bg_set_rgb": true, "bg_set_hsv": true, "bg_set_ct_abx": true, "bg_set_power": true, "bg_set_bright": true, "bg_set_scene": true,
}

func discoverLamp15(t *testing.T) (*Driver, *fakeBulb, string) {
	t.Helper()
	bulb := newFakeBulb(t)
	s := &fakeSearcher{}
	s.set(Announcement{ID: "0xab", Model: "lamp15", Name: "Light bar", Addr: bulb.addr(), Power: true, Support: lamp15})
	d := newTestDriver(s)
	if _, err := d.Discover(context.Background()); err != nil {
		t.Fatal(err)
	}
	return d, bulb, DeviceID("0xab")
}

func TestChannelsComeFromWhatTheBulbSupports(t *testing.T) {
	d, _, _ := discoverLamp15(t)
	devices, _ := d.Discover(context.Background())
	want := []drivers.Channel{
		{ID: "main", Label: "Main", Brightness: true, ColorTemp: &drivers.Range{Min: 2700, Max: 6500}},
		{ID: "background", Label: "Background", Brightness: true, ColorTemp: &drivers.Range{Min: 1700, Max: 6500}, Color: true},
	}
	if !reflect.DeepEqual(devices[0].Channels, want) {
		t.Fatalf("channels = %+v", devices[0].Channels)
	}

	plain := channelsFor("mono", map[string]bool{"set_power": true, "set_bright": true})
	if len(plain) != 1 || !plain[0].Brightness || plain[0].ColorTemp != nil || plain[0].Color {
		t.Fatalf("a plain white bulb has one channel with brightness only: %+v", plain)
	}
	if got := channelsFor("color", nil); len(got) != 1 || got[0].Brightness {
		t.Fatalf("a bulb that announces nothing is power only: %+v", got)
	}
}

func TestStateReadsEveryChannel(t *testing.T) {
	d, bulb, id := discoverLamp15(t)
	bulb.mu.Lock()
	bulb.power = "on"
	bulb.props = map[string]string{"bright": "80", "ct": "4000", "color_mode": "2",
		"bg_power": "off", "bg_bright": "30", "bg_hue": "200", "bg_sat": "90", "bg_lmode": "3", "bg_ct": "3000"}
	bulb.mu.Unlock()

	st, err := d.State(context.Background(), id)
	if err != nil || !st.Reachable || !st.Power {
		t.Fatalf("state=%+v err=%v", st, err)
	}
	main, bg := st.Channels[0], st.Channels[1]
	if !main.On || *main.Brightness != 80 || *main.ColorTemp != 4000 || main.ColorMode != drivers.ColorModeTemperature || main.Hue != nil {
		t.Fatalf("main = %+v", main)
	}
	if bg.On || *bg.Brightness != 30 || *bg.Hue != 200 || *bg.Saturation != 90 || bg.ColorMode != drivers.ColorModeColor {
		t.Fatalf("background = %+v", bg)
	}
}

func TestPowerIsOnWhenAnyChannelIsOn(t *testing.T) {
	d, bulb, id := discoverLamp15(t)
	bulb.mu.Lock()
	bulb.power = "off"
	bulb.props = map[string]string{"bg_power": "on"}
	bulb.mu.Unlock()
	st, _ := d.State(context.Background(), id)
	if !st.Power || st.Channels[0].On || !st.Channels[1].On {
		t.Fatalf("state = %+v", st)
	}
}

func TestSetSendsTheRightCommandsInOrder(t *testing.T) {
	d, bulb, id := discoverLamp15(t)
	ctx := context.Background()
	on, brightness, temp := true, 60, 3500
	if err := d.Set(ctx, id, "main", drivers.Change{ColorTemp: &temp, Brightness: &brightness, On: &on}); err != nil {
		t.Fatal(err)
	}
	if got, want := bulb.methods(), []string{"set_power", "set_bright", "set_ct_abx"}; !reflect.DeepEqual(got, want) { // the main light uses plain commands
		t.Fatalf("methods = %v", got)
	}

	hue, sat := 120, 70
	if err := d.Set(ctx, id, "background", drivers.Change{Hue: &hue, Saturation: &sat}); err != nil {
		t.Fatal(err)
	}
	if bulb.lastMethod() != "bg_set_hsv" {
		t.Fatalf("last method = %s", bulb.lastMethod())
	}
	last := bulb.requests[len(bulb.requests)-1]["params"].([]any)
	if last[0].(float64) != 120 || last[1].(float64) != 70 || last[2] != "smooth" {
		t.Fatalf("params = %v", last)
	}
	if bulb.props["bg_hue"] != "120" || bulb.props["bg_lmode"] != "3" {
		t.Fatalf("bulb did not take the colour: %v", bulb.props)
	}
}

func TestSetRefusesWhatTheChannelCannotDo(t *testing.T) {
	d, bulb, id := discoverLamp15(t)
	ctx := context.Background()
	hue, sat, bright := 10, 10, 101
	if err := d.Set(ctx, id, "main", drivers.Change{Hue: &hue, Saturation: &sat}); err == nil {
		t.Fatal("the main light has no colour")
	}
	if err := d.Set(ctx, id, "background", drivers.Change{Brightness: &bright}); err == nil {
		t.Fatal("brightness is out of range")
	}
	if err := d.Set(ctx, id, "front", drivers.Change{Brightness: &hue}); err == nil {
		t.Fatal("there is no such channel")
	}
	if n := len(bulb.methods()); n != 0 {
		t.Fatalf("nothing should have reached the bulb, got %v", bulb.methods())
	}
}

func TestSetPowerSwitchesEveryChannel(t *testing.T) {
	d, bulb, id := discoverLamp15(t)
	if err := d.SetPower(context.Background(), id, true); err != nil {
		t.Fatal(err)
	}
	// the background light is switched on with a scene, which first reads what it last showed
	if got, want := bulb.methods(), []string{"set_power", "get_prop", "bg_set_scene"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("methods = %v", got)
	}
}

func TestAnAnnouncementWithoutSupportKeepsTheKnownChannels(t *testing.T) {
	d, bulb, _ := discoverLamp15(t)
	// the bulb advertises itself again, this time without a support list
	d.Observe(Announcement{ID: "0xab", Model: "lamp15", Addr: bulb.addr(), Power: true})
	devices := d.devices()
	if len(devices) != 1 || len(devices[0].Channels) != 2 {
		t.Fatalf("channels were lost: %+v", devices)
	}
}

func TestBackgroundLightIsSwitchedOnWithAScene(t *testing.T) {
	d, bulb, id := discoverLamp15(t)
	bulb.mu.Lock()
	bulb.ignoreBgOn = true
	bulb.props = map[string]string{"bg_power": "off", "bg_bright": "50", "bg_lmode": "3", "bg_hue": "200", "bg_sat": "80", "bg_ct": "4000"}
	bulb.mu.Unlock()
	ctx := context.Background()
	on := true

	// plain "on" restores what the light last showed: its colour at its brightness
	if err := d.Set(ctx, id, "background", drivers.Change{On: &on}); err != nil {
		t.Fatal(err)
	}
	st, _ := d.State(ctx, id)
	if bg := st.Channels[1]; !bg.On || *bg.Hue != 200 || *bg.Saturation != 80 || *bg.Brightness != 50 || bg.ColorMode != drivers.ColorModeColor {
		t.Fatalf("background = %+v", bg)
	}
	if got := bulb.lastParams("bg_set_scene"); !reflect.DeepEqual(got, []any{"hsv", 200.0, 80.0, 50.0}) {
		t.Fatalf("scene = %v", got)
	}

	// switching off still uses the plain command
	off := false
	if err := d.Set(ctx, id, "background", drivers.Change{On: &off}); err != nil {
		t.Fatal(err)
	}
	if st, _ := d.State(ctx, id); st.Channels[1].On {
		t.Fatal("expected the background light to be off")
	}

	// a white last mode comes back as white, and the change's own values win
	bulb.mu.Lock()
	bulb.props["bg_lmode"] = "2"
	bulb.props["bg_ct"] = "3200"
	bulb.mu.Unlock()
	if err := d.Set(ctx, id, "background", drivers.Change{On: &on}); err != nil {
		t.Fatal(err)
	}
	if bg, _ := d.State(ctx, id); bg.Channels[1].ColorMode != drivers.ColorModeTemperature || *bg.Channels[1].ColorTemp != 3200 {
		t.Fatalf("background = %+v", bg.Channels[1])
	}
	_ = d.Set(ctx, id, "background", drivers.Change{On: &off})
	brightness, hue, sat := 20, 10, 90
	if err := d.Set(ctx, id, "background", drivers.Change{On: &on, Brightness: &brightness, Hue: &hue, Saturation: &sat}); err != nil {
		t.Fatal(err)
	}
	st, _ = d.State(ctx, id)
	if bg := st.Channels[1]; !bg.On || *bg.Brightness != 20 || *bg.Hue != 10 || *bg.Saturation != 90 {
		t.Fatalf("background = %+v", bg)
	}
	if got := bulb.lastParams("bg_set_scene"); !reflect.DeepEqual(got, []any{"hsv", 10.0, 90.0, 20.0}) {
		t.Fatalf("scene = %v", got)
	}
}

func TestBackgroundOnUsesPlainPowerWhenTheBulbHasNoScenes(t *testing.T) {
	bulb := newFakeBulb(t)
	s := &fakeSearcher{}
	noScenes := map[string]bool{}
	for k := range lamp15 {
		noScenes[k] = true
	}
	delete(noScenes, "bg_set_scene")
	s.set(Announcement{ID: "0xab", Model: "lamp15", Addr: bulb.addr(), Support: noScenes})
	d := newTestDriver(s)
	_, _ = d.Discover(context.Background())
	on := true
	if err := d.Set(context.Background(), DeviceID("0xab"), "background", drivers.Change{On: &on}); err != nil {
		t.Fatal(err)
	}
	if got := bulb.methods(); !reflect.DeepEqual(got, []string{"bg_set_power"}) {
		t.Fatalf("methods = %v", got)
	}
}

// bar15 is a lamp15 as the real one behaves, with the main light and the background light both on.
func bar15(t *testing.T) (*Driver, *fakeBulb, string) {
	t.Helper()
	d, bulb, id := discoverLamp15(t)
	bulb.mu.Lock()
	bulb.lamp15, bulb.ignoreBgOn = true, true
	bulb.power = "on"
	bulb.props = map[string]string{"main_power": "on", "bright": "100", "bg_power": "on", "bg_bright": "40",
		"bg_lmode": "3", "bg_hue": "200", "bg_sat": "80", "bg_ct": "4000"}
	bulb.mu.Unlock()
	return d, bulb, id
}

func (b *fakeBulb) setProps(power string, props map[string]string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.power = power
	for k, v := range props {
		b.props[k] = v
	}
}

func lights(t *testing.T, d *Driver, id string) (main, bg bool) {
	t.Helper()
	st, err := d.State(context.Background(), id)
	if err != nil {
		t.Fatal(err)
	}
	return st.Channels[0].On, st.Channels[1].On
}

func TestTheMainLightIsReadFromMainPowerNotFromDevicePower(t *testing.T) {
	d, bulb, id := bar15(t)
	bulb.setProps("on", map[string]string{"main_power": "off"}) // only the background glows
	st, _ := d.State(context.Background(), id)
	if st.Channels[0].On || !st.Channels[1].On || !st.Power {
		t.Fatalf("expected main off and background on, got %+v", st)
	}
}

func TestSwitchingTheMainLightOffKeepsTheBackgroundLit(t *testing.T) {
	d, bulb, id := bar15(t)
	off := false
	if err := d.Set(context.Background(), id, "main", drivers.Change{On: &off}); err != nil {
		t.Fatal(err)
	}
	if main, bg := lights(t, d, id); main || !bg {
		t.Fatalf("main=%v background=%v, want main off and background on", main, bg)
	}
	// the background came back as it was
	if got := bulb.lastParams("bg_set_scene"); !reflect.DeepEqual(got, []any{"hsv", 200.0, 80.0, 40.0}) {
		t.Fatalf("scene = %v", got)
	}
}

func TestMainOnFromBackgroundOnlyKeepsTheBackground(t *testing.T) {
	d, bulb, id := bar15(t)
	bulb.setProps("on", map[string]string{"main_power": "off"})
	on := true
	if err := d.Set(context.Background(), id, "main", drivers.Change{On: &on}); err != nil {
		t.Fatal(err)
	}
	if main, bg := lights(t, d, id); !main || !bg {
		t.Fatalf("main=%v background=%v, want both on", main, bg)
	}
	if bulb.lastParams("bg_set_scene") != nil {
		t.Fatal("the background did not need a scene")
	}
}

func TestMainOffWithTheBackgroundOffJustPowersOff(t *testing.T) {
	d, bulb, id := bar15(t)
	bulb.setProps("on", map[string]string{"bg_power": "off"})
	off := false
	if err := d.Set(context.Background(), id, "main", drivers.Change{On: &off}); err != nil {
		t.Fatal(err)
	}
	if got, want := bulb.methods(), []string{"get_prop", "set_power"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("methods = %v", got)
	}
	if main, bg := lights(t, d, id); main || bg {
		t.Fatalf("main=%v background=%v, want both off", main, bg)
	}
}

func TestMainOffWhenItIsAlreadyOffSendsNothing(t *testing.T) {
	d, bulb, id := bar15(t)
	bulb.setProps("on", map[string]string{"main_power": "off"})
	off := false
	if err := d.Set(context.Background(), id, "main", drivers.Change{On: &off}); err != nil {
		t.Fatal(err)
	}
	if got, want := bulb.methods(), []string{"get_prop"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("methods = %v", got)
	}
}

func TestASceneThatHasTheMainLightOffAndTheBackgroundOn(t *testing.T) {
	for name, start := range map[string]struct {
		power string
		props map[string]string
	}{
		"both on":         {"on", nil},
		"main only":       {"on", map[string]string{"bg_power": "off"}},
		"everything off":  {"off", map[string]string{"main_power": "off", "bg_power": "off"}},
		"background only": {"on", map[string]string{"main_power": "off"}},
	} {
		t.Run(name, func(t *testing.T) {
			d, bulb, id := bar15(t)
			bulb.setProps(start.power, start.props)
			ctx := context.Background()
			off, on, bright := false, true, 25
			if err := d.Set(ctx, id, "main", drivers.Change{On: &off}); err != nil {
				t.Fatal(err)
			}
			if err := d.Set(ctx, id, "background", drivers.Change{On: &on, Brightness: &bright}); err != nil {
				t.Fatal(err)
			}
			if main, bg := lights(t, d, id); main || !bg {
				t.Fatalf("main=%v background=%v, want main off and background on", main, bg)
			}
			// and the main light can be switched on and off again afterwards
			if err := d.Set(ctx, id, "main", drivers.Change{On: &on}); err != nil {
				t.Fatal(err)
			}
			if main, bg := lights(t, d, id); !main || !bg {
				t.Fatalf("main=%v background=%v, want both on", main, bg)
			}
			if err := d.Set(ctx, id, "main", drivers.Change{On: &off}); err != nil {
				t.Fatal(err)
			}
			if main, bg := lights(t, d, id); main || !bg {
				t.Fatalf("main=%v background=%v, want main off and background on", main, bg)
			}
		})
	}
}

func TestSwitchingTheWholeBarOffTurnsTheBackgroundOffFirst(t *testing.T) {
	d, bulb, id := bar15(t)
	if err := d.SetPower(context.Background(), id, false); err != nil {
		t.Fatal(err)
	}
	if got, want := bulb.methods(), []string{"bg_set_power", "get_prop", "set_power"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("methods = %v", got)
	}
	if main, bg := lights(t, d, id); main || bg {
		t.Fatalf("main=%v background=%v, want both off", main, bg)
	}
}
