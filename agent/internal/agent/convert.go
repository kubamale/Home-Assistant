package agent

import (
	"homedisplay/agent/internal/drivers"
	"homedisplay/agent/internal/protocol"
)

func toChannelInfos(channels []drivers.Channel) []protocol.ChannelInfo {
	if len(channels) == 0 {
		return nil
	}
	out := make([]protocol.ChannelInfo, len(channels))
	for i, ch := range channels {
		caps := protocol.Capabilities{Brightness: ch.Brightness, Color: ch.Color}
		if ch.ColorTemp != nil {
			caps.ColorTemp = &protocol.Range{Min: ch.ColorTemp.Min, Max: ch.ColorTemp.Max}
		}
		out[i] = protocol.ChannelInfo{ID: ch.ID, Label: ch.Label, Capabilities: caps}
	}
	return out
}

func toProtocolChannels(states []drivers.ChannelState) []protocol.ChannelState {
	if len(states) == 0 {
		return nil
	}
	out := make([]protocol.ChannelState, len(states))
	for i, s := range states {
		out[i] = protocol.ChannelState{ID: s.ID, On: s.On, Brightness: s.Brightness, ColorTemp: s.ColorTemp,
			Hue: s.Hue, Saturation: s.Saturation, ColorMode: s.ColorMode}
	}
	return out
}

func fromProtocolChannels(states []protocol.ChannelState) []drivers.ChannelState {
	out := make([]drivers.ChannelState, len(states))
	for i, s := range states {
		out[i] = drivers.ChannelState{ID: s.ID, On: s.On, Brightness: s.Brightness, ColorTemp: s.ColorTemp,
			Hue: s.Hue, Saturation: s.Saturation, ColorMode: s.ColorMode}
	}
	return out
}
