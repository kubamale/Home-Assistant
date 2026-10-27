// Command agent is the home agent that runs on the Raspberry Pi. It owns every local device
// protocol and talks to the backend over an outbound WebSocket, so nothing at home listens for
// the internet.
package main

import (
	"context"
	"flag"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"homedisplay/agent/internal/agent"
	"homedisplay/agent/internal/config"
	"homedisplay/agent/internal/drivers"
	"homedisplay/agent/internal/drivers/yeelight"
	"homedisplay/agent/internal/link"
)

func main() {
	configPath := flag.String("config", "/etc/homedisplay/agent.json", "path to the agent config file")
	verbose := flag.Bool("v", false, "log debug messages")
	flag.Parse()

	level := slog.LevelInfo
	if *verbose {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Error("invalid configuration", "err", err)
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	yl := yeelight.New(log)
	ag := agent.New(cfg.AgentID, []drivers.DeviceDriver{yl}, nil, log)
	lk := link.New(cfg.WebSocketURL(), cfg.Token, ag, log)
	ag.SetSender(lk)

	// Bulbs also announce themselves from time to time; listening is best effort.
	go func() {
		if err := yeelight.Listen(ctx, yl.Observe); err != nil {
			log.Info("not listening for bulb advertisements", "err", err)
		}
	}()
	go ag.Run(ctx)

	log.Info("home agent started", "version", agent.Version, "backend", cfg.WebSocketURL())
	lk.Run(ctx)
	log.Info("home agent stopped")
}
