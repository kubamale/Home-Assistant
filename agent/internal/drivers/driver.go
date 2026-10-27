// Package drivers defines the interface every device protocol implements. The rest of the agent
// knows devices and channels, not Yeelight or Zigbee.
package drivers

import (
	"context"
	"fmt"
)

// Colour modes a channel can be in.
const (
	ColorModeTemperature = "temperature"
	ColorModeColor       = "color"
)

// Range is an inclusive range of whole numbers.
type Range struct {
	Min, Max int
}

// Channel is one independently controllable light of a device: a Yeelight light bar has "main" and
// "background", a plain bulb only "main". Power is always supported; the rest is declared here.
type Channel struct {
	ID         string
	Label      string
	Brightness bool   // 1 to 100
	ColorTemp  *Range // Kelvin, nil when the channel has no adjustable white
	Color      bool   // hue 0 to 359 and saturation 0 to 100
}

// Device describes something that can be controlled.
type Device struct {
	ID       string
	Name     string
	Type     string
	Channels []Channel
}

// ChannelState is the last known state of one channel. Values the channel does not support are nil.
type ChannelState struct {
	ID         string
	On         bool
	Brightness *int
	ColorTemp  *int
	Hue        *int
	Saturation *int
	ColorMode  string
}

// State is the last known state of a device. Power is true when any channel is on.
type State struct {
	Power     bool
	Reachable bool
	Channels  []ChannelState
}

// Change is a partial update of one channel; nil fields are left alone.
type Change struct {
	On         *bool
	Brightness *int
	ColorTemp  *int
	Hue        *int
	Saturation *int
}

// Empty reports whether the change asks for nothing.
func (c Change) Empty() bool {
	return c.On == nil && c.Brightness == nil && c.ColorTemp == nil && c.Hue == nil && c.Saturation == nil
}

// Check refuses a change the channel cannot do: an unsupported setting, a value out of range, or
// only one of hue and saturation.
func (c Change) Check(ch Channel) error {
	if c.Empty() {
		return fmt.Errorf("nothing to change")
	}
	if c.Brightness != nil {
		if !ch.Brightness {
			return fmt.Errorf("%s has no brightness", ch.Label)
		}
		if *c.Brightness < 1 || *c.Brightness > 100 {
			return fmt.Errorf("brightness must be 1 to 100")
		}
	}
	if c.ColorTemp != nil {
		if ch.ColorTemp == nil {
			return fmt.Errorf("%s has no colour temperature", ch.Label)
		}
		if *c.ColorTemp < ch.ColorTemp.Min || *c.ColorTemp > ch.ColorTemp.Max {
			return fmt.Errorf("colour temperature must be %d to %d K", ch.ColorTemp.Min, ch.ColorTemp.Max)
		}
	}
	if (c.Hue == nil) != (c.Saturation == nil) {
		return fmt.Errorf("hue and saturation go together")
	}
	if c.Hue != nil {
		if !ch.Color {
			return fmt.Errorf("%s has no colour", ch.Label)
		}
		if *c.Hue < 0 || *c.Hue > 359 {
			return fmt.Errorf("hue must be 0 to 359")
		}
		if *c.Saturation < 0 || *c.Saturation > 100 {
			return fmt.Errorf("saturation must be 0 to 100")
		}
	}
	return nil
}

// DeviceDriver is one device protocol (Yeelight now, Zigbee later).
type DeviceDriver interface {
	// Type names the protocol, for example "yeelight".
	Type() string
	// Discover finds devices on the network and returns every device known so far, with their channels.
	Discover(ctx context.Context) ([]Device, error)
	// State reads a device's state. An unreachable device is reported with Reachable false, not as an error.
	State(ctx context.Context, id string) (State, error)
	// SetPower switches every channel of a device on or off.
	SetPower(ctx context.Context, id string, on bool) error
	// Set applies a change to one channel.
	Set(ctx context.Context, id, channel string, change Change) error
}
