import { Toggle } from '../../components/Controls'
import { useBackgroundMode } from '../../hooks/background'
import { useMotionPreference } from '../../hooks/motion'
import type { BackgroundMode } from '../../sky'
import { Group, SettingsPage } from './SettingsLayout'

export const BACKGROUND_LABELS: Record<BackgroundMode, string> = {
  sky: 'Follows the weather',
  calm: 'Calm',
  plain: 'Plain',
}

const OPTIONS: { mode: BackgroundMode; description: string }[] = [
  { mode: 'sky', description: 'The sky changes with the weather and the time of day.' },
  { mode: 'calm', description: 'One soft gradient that never changes.' },
  { mode: 'plain', description: 'No background, just grey.' },
]

/** How this phone looks. The choices are kept on this device only; a wall display always follows the weather. */
export function AppearancePage() {
  const [mode, setMode] = useBackgroundMode()
  const [motion, setMotion] = useMotionPreference()
  return (
    <SettingsPage title="Appearance">
      <Group title="Background" footnote="Applies to this phone only. Cards and tiles are frosted glass over it.">
        <div role="radiogroup" aria-label="Background">
          {OPTIONS.map((o) => (
            <button key={o.mode} type="button" role="radio" aria-checked={mode === o.mode} className="row" onClick={() => setMode(o.mode)}>
              <span className={`bg-swatch ${o.mode}`} aria-hidden="true" />
              <span className="row-main">
                <span className="row-title">{BACKGROUND_LABELS[o.mode]}</span>
                <span className="row-sub">{o.description}</span>
              </span>
              {mode === o.mode && (
                <svg className="check-mark" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="m5 12.5 4.5 4.5L19 7" />
                </svg>
              )}
            </button>
          ))}
        </div>
      </Group>
      {mode === 'sky' && (
        <Group
          title="Motion"
          footnote="Clouds drift, rain falls, snow settles, stars twinkle, softly behind the cards. Off by default because it uses a little battery. It stays still when your phone's “reduce motion” setting is on. A wall display has its own switch, on its page in Settings."
        >
          <div className="row static">
            <span className="row-title">Move with the weather</span>
            <Toggle on={motion} label="Move the background with the weather" onChange={setMotion} />
          </div>
        </Group>
      )}
    </SettingsPage>
  )
}
