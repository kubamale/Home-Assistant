// Package protocol defines the JSON messages exchanged with the backend over the agent WebSocket
// (see PRD section 6, "Agent protocol"). Every message carries a version and a type.
package protocol

const Version = 1

// Message types.
const (
	TypeHello   = "hello"
	TypeDevices = "devices"
	TypeState   = "state"
	TypeCommand = "command"
	TypeResult  = "result"
	TypePing    = "ping"
	TypePong    = "pong"
)

// Envelope is read first to find out what kind of message arrived.
type Envelope struct {
	V    int    `json:"v"`
	Type string `json:"type"`
}

// Range is an inclusive range of whole numbers.
type Range struct {
	Min int `json:"min"`
	Max int `json:"max"`
}

// Capabilities say which settings a channel supports. Power is always supported.
type Capabilities struct {
	Brightness bool   `json:"brightness"`
	ColorTemp  *Range `json:"colorTemp,omitempty"`
	Color      bool   `json:"color"`
}

// ChannelInfo is one independently controllable light of a device, such as "main" or "background".
type ChannelInfo struct {
	ID           string       `json:"id"`
	Label        string       `json:"label"`
	Capabilities Capabilities `json:"capabilities"`
}

// ChannelState is the state of one channel. Settings the channel does not support are left out.
type ChannelState struct {
	ID         string `json:"id"`
	On         bool   `json:"on"`
	Brightness *int   `json:"brightness,omitempty"`
	ColorTemp  *int   `json:"colorTemp,omitempty"`
	Hue        *int   `json:"hue,omitempty"`
	Saturation *int   `json:"saturation,omitempty"`
	ColorMode  string `json:"colorMode,omitempty"`
}

// DeviceInfo describes a device the agent can control.
type DeviceInfo struct {
	DeviceID string        `json:"deviceId"`
	Name     string        `json:"name"`
	Type     string        `json:"type"`
	Channels []ChannelInfo `json:"channels,omitempty"`
}

// StateItem is the last known state of one device. Power is true when any channel is on.
type StateItem struct {
	DeviceID  string         `json:"deviceId"`
	Power     bool           `json:"power"`
	Reachable bool           `json:"reachable"`
	UpdatedAt string         `json:"updatedAt,omitempty"`
	Channels  []ChannelState `json:"channels,omitempty"`
}

// Hello is the first message after connecting: everything the agent knows.
type Hello struct {
	V       int          `json:"v"`
	Type    string       `json:"type"`
	AgentID string       `json:"agentId"`
	Version string       `json:"version"`
	Devices []DeviceInfo `json:"devices"`
	States  []StateItem  `json:"states"`
}

// Devices is sent when discovery finds or loses a device.
type Devices struct {
	V       int          `json:"v"`
	Type    string       `json:"type"`
	Devices []DeviceInfo `json:"devices"`
	States  []StateItem  `json:"states"`
}

// State reports a changed device state.
type State struct {
	V         int            `json:"v"`
	Type      string         `json:"type"`
	DeviceID  string         `json:"deviceId"`
	Power     bool           `json:"power"`
	Reachable bool           `json:"reachable"`
	UpdatedAt string         `json:"updatedAt,omitempty"`
	Channels  []ChannelState `json:"channels,omitempty"`
}

// Command actions.
const (
	// ActionSetPower switches the whole device (every channel); args: on.
	ActionSetPower = "setPower"
	// ActionSet changes one channel; the command names the channel and args carry the change.
	ActionSet = "set"
)

// CommandArgs are the arguments of a command. For "set" every field is optional.
type CommandArgs struct {
	On         *bool `json:"on,omitempty"`
	Brightness *int  `json:"brightness,omitempty"`
	ColorTemp  *int  `json:"colorTemp,omitempty"`
	Hue        *int  `json:"hue,omitempty"`
	Saturation *int  `json:"saturation,omitempty"`
}

// Command is sent by the backend; every command gets exactly one Result.
type Command struct {
	V        int         `json:"v"`
	Type     string      `json:"type"`
	ID       string      `json:"id"`
	DeviceID string      `json:"deviceId"`
	Action   string      `json:"action"`
	Channel  string      `json:"channel,omitempty"`
	Args     CommandArgs `json:"args"`
}

// Result answers a Command.
type Result struct {
	V     int        `json:"v"`
	Type  string     `json:"type"`
	ID    string     `json:"id"`
	OK    bool       `json:"ok"`
	Error string     `json:"error,omitempty"`
	State *StateItem `json:"state,omitempty"`
}

// Heartbeat is used for both ping and pong.
type Heartbeat struct {
	V    int    `json:"v"`
	Type string `json:"type"`
}
