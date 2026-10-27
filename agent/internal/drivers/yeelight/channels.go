package yeelight

import (
	"strconv"

	"homedisplay/agent/internal/drivers"
)

// channelsFor works out what a bulb can do from the control methods it announces. Every bulb has a
// main light; bulbs with a background light (such as the light bar) announce bg_set_power.
//
// The colour temperature range is not announced, so it comes from the kind of channel: lights that
// can show colour go warmer (1700 K) than white-only ones (2700 K). Check on real hardware.
func channelsFor(model string, support map[string]bool) []drivers.Channel {
	main := drivers.Channel{ID: channelMain, Label: "Main", Brightness: support["set_bright"],
		Color: support["set_hsv"] || support["set_rgb"]}
	if support["set_ct_abx"] {
		main.ColorTemp = tempRange(main.Color)
	}
	channels := []drivers.Channel{main}
	if support["bg_set_power"] {
		bg := drivers.Channel{ID: channelBackground, Label: "Background", Brightness: support["bg_set_bright"],
			Color: support["bg_set_hsv"] || support["bg_set_rgb"]}
		if support["bg_set_ct_abx"] {
			bg.ColorTemp = tempRange(bg.Color)
		}
		channels = append(channels, bg)
	}
	return channels
}

func tempRange(canShowColor bool) *drivers.Range {
	if canShowColor {
		return &drivers.Range{Min: 1700, Max: 6500}
	}
	return &drivers.Range{Min: 2700, Max: 6500}
}

// propNames lists the get_prop properties needed for the channels, six per channel in a fixed order:
// power, bright, ct, hue, sat, color_mode (the background light's names start with bg_ and its
// color mode is bg_lmode), then main_power last.
//
// "power" is the power of the device: on when any light is on. Bars with a background light (the
// Monitor Light Bar Pro, model lamp15) also report "main_power", the main light alone, which is off
// while only the background glows. Other bulbs answer an empty string for it.
func propNames(channels []drivers.Channel) []any {
	names := []any{"power", "bright", "ct", "hue", "sat", "color_mode"}
	for _, ch := range channels {
		if ch.ID == channelBackground {
			names = append(names, "bg_power", "bg_bright", "bg_ct", "bg_hue", "bg_sat", "bg_lmode")
		}
	}
	return append(names, "main_power")
}

// parseProps reads a get_prop answer asked with propNames. The bulb answers an empty string for
// anything it does not have.
func parseProps(channels []drivers.Channel, values []string) []drivers.ChannelState {
	at := func(i int) string {
		if i < len(values) {
			return values[i]
		}
		return ""
	}
	mainPower := at(len(propNames(channels)) - 1)
	states := make([]drivers.ChannelState, 0, len(channels))
	for _, ch := range channels {
		offset := 0
		if ch.ID == channelBackground {
			offset = 6
		}
		state := drivers.ChannelState{ID: ch.ID, On: at(offset) == "on"}
		if ch.ID == channelMain && mainPower != "" {
			state.On = mainPower == "on" // the main light alone, not the device
		}
		if ch.Brightness {
			state.Brightness = number(at(offset + 1))
		}
		if ch.ColorTemp != nil {
			state.ColorTemp = number(at(offset + 2))
		}
		if ch.Color {
			state.Hue, state.Saturation = number(at(offset+3)), number(at(offset+4))
		}
		state.ColorMode = colorMode(ch, at(offset+5))
		states = append(states, state)
	}
	return states
}

// colorMode maps the bulb's 1 (RGB), 2 (colour temperature), 3 (HSV) to the two modes the app knows,
// limited to what the channel can actually do.
func colorMode(ch drivers.Channel, value string) string {
	switch {
	case value == "2" && ch.ColorTemp != nil:
		return drivers.ColorModeTemperature
	case (value == "1" || value == "3") && ch.Color:
		return drivers.ColorModeColor
	case ch.ColorTemp != nil && !ch.Color:
		return drivers.ColorModeTemperature
	case ch.Color && ch.ColorTemp == nil:
		return drivers.ColorModeColor
	}
	return ""
}

func number(s string) *int {
	n, err := strconv.Atoi(s)
	if err != nil {
		return nil
	}
	return &n
}

func anyOn(states []drivers.ChannelState) bool {
	for _, s := range states {
		if s.On {
			return true
		}
	}
	return false
}
