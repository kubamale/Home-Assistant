package drivers

import "testing"

func ptr[T any](v T) *T { return &v }

func TestChangeCheck(t *testing.T) {
	bar := Channel{ID: "background", Label: "Background", Brightness: true, ColorTemp: &Range{1700, 6500}, Color: true}
	white := Channel{ID: "main", Label: "Main", Brightness: true, ColorTemp: &Range{2700, 6500}}
	plain := Channel{ID: "main", Label: "Main"}

	cases := []struct {
		name   string
		ch     Channel
		change Change
		ok     bool
	}{
		{"power on anything", plain, Change{On: ptr(true)}, true},
		{"empty", plain, Change{}, false},
		{"brightness", white, Change{Brightness: ptr(40)}, true},
		{"brightness too high", white, Change{Brightness: ptr(101)}, false},
		{"brightness zero", white, Change{Brightness: ptr(0)}, false},
		{"brightness unsupported", plain, Change{Brightness: ptr(50)}, false},
		{"temperature in range", white, Change{ColorTemp: ptr(4000)}, true},
		{"temperature too warm", white, Change{ColorTemp: ptr(1700)}, false},
		{"temperature unsupported", plain, Change{ColorTemp: ptr(4000)}, false},
		{"colour", bar, Change{Hue: ptr(200), Saturation: ptr(80)}, true},
		{"hue alone", bar, Change{Hue: ptr(200)}, false},
		{"hue out of range", bar, Change{Hue: ptr(360), Saturation: ptr(50)}, false},
		{"saturation out of range", bar, Change{Hue: ptr(10), Saturation: ptr(101)}, false},
		{"colour unsupported", white, Change{Hue: ptr(10), Saturation: ptr(10)}, false},
		{"several settings", bar, Change{On: ptr(true), Brightness: ptr(20), ColorTemp: ptr(3000)}, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if err := c.change.Check(c.ch); (err == nil) != c.ok {
				t.Fatalf("Check() = %v, want ok=%v", err, c.ok)
			}
		})
	}
}
