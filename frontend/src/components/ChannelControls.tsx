import { useConnection } from '../connection'
import { COLOR_PRESETS, hsvCss } from '../color'
import { useSetChannel } from '../hooks/data'
import type { Channel, ChannelChange, Device } from '../types'
import { Slider } from './Slider'

/** The controls of one channel: power plus whatever it supports of brightness, white and colour. */
export function ChannelControls({ device, channel, unavailable, showTitle }: {
  device: Device
  channel: Channel
  unavailable: boolean
  showTitle: boolean
}) {
  const set = useSetChannel()
  const offline = useConnection() === 'reconnecting'
  const { capabilities: caps } = channel
  const send = (change: ChannelChange) => set.mutateAsync({ deviceId: device.id, channel: channel.id, change })
  const adjustable = !unavailable && !offline && channel.on
  const hue = channel.hue ?? 0
  const saturation = channel.saturation ?? 100
  const range = caps.colorTemp

  return (
    <section className="channel" aria-label={channel.label}>
      <div className="channel-head">
        {showTitle ? <h3>{channel.label}</h3> : <span />}
        <button
          role="switch"
          aria-checked={channel.on}
          aria-label={`${showTitle ? channel.label : device.name} ${channel.on ? 'on' : 'off'}`}
          className={channel.on ? 'switch on' : 'switch'}
          disabled={unavailable || offline || set.isPending}
          onClick={() => void send({ on: !channel.on }).catch(() => undefined)}
        >
          <span className="knob" />
        </button>
      </div>

      {caps.brightness && (
        <Slider label="Brightness" unit="%" min={1} max={100} value={channel.brightness ?? 100} disabled={!adjustable}
          onCommit={(brightness) => send({ brightness })} />
      )}

      {range && (
        <Slider label="White" unit=" K" min={range.min} max={range.max} value={channel.colorTemp ?? Math.round((range.min + range.max) / 2)}
          highlighted={channel.colorMode === 'temperature'} disabled={!adjustable}
          trackStyle={{ background: 'linear-gradient(to right, #ffae5a, #fff3e0 45%, #ffffff 60%, #a9d1ff)' }}
          onCommit={(colorTemp) => send({ colorTemp })} />
      )}

      {caps.color && (
        <>
          <Slider label="Colour" min={0} max={359} value={hue} highlighted={channel.colorMode === 'color'} disabled={!adjustable}
            trackStyle={{ background: 'linear-gradient(to right, #f00, #ff0, #0f0, #0ff, #00f, #f0f, #f00)' }}
            onCommit={(h) => send({ hue: h, saturation })} />
          <Slider label="Saturation" unit="%" min={0} max={100} value={saturation} disabled={!adjustable}
            trackStyle={{ background: `linear-gradient(to right, #fff, ${hsvCss(hue, 100)})` }}
            onCommit={(s) => send({ hue, saturation: s })} />
          <div className="swatches" role="group" aria-label="Colour presets">
            {COLOR_PRESETS.map((p) => (
              <button key={p.name} className="swatch" aria-label={p.name} title={p.name} disabled={!adjustable}
                style={{ background: hsvCss(p.hue, p.saturation) }}
                onClick={() => void send({ hue: p.hue, saturation: p.saturation }).catch(() => undefined)} />
            ))}
          </div>
        </>
      )}

      {!channel.on && !unavailable && (caps.brightness || range || caps.color) && <p className="muted small">Turn it on to adjust.</p>}
      {set.isError && <p className="error small">{set.error.message}</p>}
    </section>
  )
}
