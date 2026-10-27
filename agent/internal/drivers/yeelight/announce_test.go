package yeelight

import "testing"

const sampleResponse = "HTTP/1.1 200 OK\r\n" +
	"Cache-Control: max-age=3584\r\n" +
	"Date: \r\n" +
	"Ext: \r\n" +
	"Location: yeelight://192.168.1.239:55443\r\n" +
	"Server: POSIX UPnP/1.0 YGLC/1\r\n" +
	"id: 0x000000000015243F\r\n" +
	"model: color\r\n" +
	"fw_ver: 18\r\n" +
	"support: get_prop set_default set_power toggle set_bright\r\n" +
	"power: on\r\n" +
	"bright: 100\r\n" +
	"name: Light bar\r\n\r\n"

func TestParseAnnouncement(t *testing.T) {
	a, err := ParseAnnouncement([]byte(sampleResponse))
	if err != nil {
		t.Fatal(err)
	}
	if a.ID != "0x000000000015243f" || a.Addr != "192.168.1.239:55443" || a.Model != "color" || a.Name != "Light bar" || !a.Power {
		t.Fatalf("unexpected announcement: %+v", a)
	}
	if got := DeviceID(a.ID); got != "yeelight-000000000015243f" {
		t.Fatalf("device id = %s", got)
	}
}

func TestParseAnnouncementAcceptsNotify(t *testing.T) {
	notify := "NOTIFY * HTTP/1.1\r\nHost: 239.255.255.250:1982\r\nLocation: yeelight://10.0.0.5:55443\r\nid: 0x1\r\npower: off\r\n\r\n"
	a, err := ParseAnnouncement([]byte(notify))
	if err != nil || a.Power || a.Addr != "10.0.0.5:55443" {
		t.Fatalf("got %+v, %v", a, err)
	}
}

func TestParseAnnouncementRejectsOtherTraffic(t *testing.T) {
	for _, bad := range []string{
		"",
		"M-SEARCH * HTTP/1.1\r\nST: wifi_bulb\r\n\r\n",
		"HTTP/1.1 200 OK\r\nLocation: yeelight://1.2.3.4:55443\r\n\r\n",     // no id
		"HTTP/1.1 200 OK\r\nid: 0x1\r\nLocation: http://1.2.3.4:80\r\n\r\n", // wrong scheme
	} {
		if _, err := ParseAnnouncement([]byte(bad)); err == nil {
			t.Errorf("expected an error for %q", bad)
		}
	}
}

// What the user's light bar really answers (model lamp15), shortened to the lines that matter.
const lamp15Response = "HTTP/1.1 200 OK\r\n" +
	"Location: yeelight://192.168.1.50:55443\r\n" +
	"id: 0x000000001e77cd7d\r\n" +
	"model: lamp15\r\n" +
	"power: on\r\n" +
	"support: get_prop set_power set_ct_abx set_bright bg_set_rgb bg_set_hsv bg_set_ct_abx bg_set_power bg_set_bright dev_toggle\r\n\r\n"

func TestParseAnnouncementReadsWhatTheBulbSupports(t *testing.T) {
	a, err := ParseAnnouncement([]byte(lamp15Response))
	if err != nil {
		t.Fatal(err)
	}
	if !a.Support["bg_set_hsv"] || !a.Support["set_ct_abx"] || a.Support["set_hsv"] {
		t.Fatalf("support = %v", a.Support)
	}
	channels := channelsFor(a.Model, a.Support)
	if len(channels) != 2 || channels[0].Color || !channels[1].Color {
		t.Fatalf("expected a white main light and a colour background light: %+v", channels)
	}
}
