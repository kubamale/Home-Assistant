import { useState } from 'react'
import { api, ApiError } from '../../api'
import { Toggle } from '../../components/Controls'
import { useSaveVoiceSettings, useVoiceSettings, useVoiceStatus, useVoiceVoices } from '../../hooks/data'
import type { VoiceSettings } from '../../types'
import { Field, Group, SettingsPage } from './SettingsLayout'

/** The languages the household speaks, as hints for the listening service. */
const LANGUAGES = [
  { code: 'pl-PL', label: 'Polish' },
  { code: 'en-US', label: 'English' },
]

const SENSITIVITIES: { value: VoiceSettings['wakeSensitivity']; label: string }[] = [
  { value: 'low', label: 'Less' },
  { value: 'normal', label: 'Normal' },
  { value: 'high', label: 'More' },
]

/** How the assistant listens and sounds, for every screen. Changes are a draft until the Save bar is pressed. */
export function VoicePage() {
  const { data: saved } = useVoiceSettings()
  const { data: voices } = useVoiceVoices()
  const { data: status } = useVoiceStatus()
  const save = useSaveVoiceSettings()
  const [edits, setEdits] = useState<VoiceSettings | null>(null)
  const [playing, setPlaying] = useState(false)
  const [sampleError, setSampleError] = useState<string | null>(null)

  const draft = edits ?? saved
  if (!draft) return <SettingsPage title="Voice">{null}</SettingsPage>
  const change = (patch: Partial<VoiceSettings>) => setEdits({ ...draft, ...patch })
  const toggleLanguage = (code: string) =>
    change({ languages: draft.languages.includes(code) ? draft.languages.filter((l) => l !== code) : [...draft.languages, code] })

  const play = async () => {
    setSampleError(null)
    setPlaying(true)
    try {
      const url = URL.createObjectURL(await api.voiceSample(draft.voice, draft.style))
      const audio = new Audio(url)
      audio.onended = () => URL.revokeObjectURL(url)
      await audio.play()
    } catch (e) {
      setSampleError(e instanceof ApiError ? e.message : 'The sample could not be played.')
    } finally {
      setPlaying(false)
    }
  }

  return (
    <SettingsPage title="Voice">
      {status && !status.speaking && <p className="footnote notice">Talking and listening need a <code>GEMINI_API_KEY</code> on the server.</p>}

      <Group title="How it sounds">
        <div className="row static">
          <span className="row-title">Voice</span>
          <select aria-label="Voice" value={draft.voice} onChange={(e) => change({ voice: e.target.value })}>
            {(voices?.voices ?? [draft.voice]).map((voice) => (
              <option key={voice} value={voice}>
                {voice}
              </option>
            ))}
          </select>
        </div>
        <button type="button" className="row" disabled={playing || !status?.speaking} onClick={() => void play()}>
          <span className="row-title accent-text">{playing ? 'Playing…' : 'Play a sample'}</span>
        </button>
        <Field label="How to speak" hint="Optional, for example “calm and friendly”.">
          <input type="text" value={draft.style} maxLength={200} onChange={(e) => change({ style: e.target.value })} />
        </Field>
        <div className="row static">
          <span className="row-title">Speak the answers</span>
          <Toggle on={draft.speakReplies} label="Speak the answers out loud" onChange={(speakReplies) => change({ speakReplies })} />
        </div>
      </Group>
      {sampleError && <p className="error">{sampleError}</p>}

      <Group title="I speak" footnote="Hints for the listening service. Leave both off to let it detect the language.">
        {LANGUAGES.map((language) => (
          <div className="row static" key={language.code}>
            <span className="row-title">{language.label}</span>
            <Toggle on={draft.languages.includes(language.code)} label={language.label} onChange={() => toggleLanguage(language.code)} />
          </div>
        ))}
      </Group>

      <Group title="Wake word on door screens" footnote="Say “Hey Jarvis” in English. More sensitive if the screen does not hear you, less if it wakes up by itself.">
        <div className="row static">
          <span className="row-title">Sensitivity</span>
          <div className="segmented" role="group" aria-label="Wake word sensitivity">
            {SENSITIVITIES.map((option) => (
              <button type="button" key={option.value} aria-pressed={draft.wakeSensitivity === option.value} onClick={() => change({ wakeSensitivity: option.value })}>
                {option.label}
              </button>
            ))}
          </div>
        </div>
        <Field label="After the wake word, say" hint="So you know it is listening, for example “Yes?” or “Tak?”. Leave it empty for no sound. Only used when answers are spoken.">
          <input type="text" value={draft.wakeReply} maxLength={40} placeholder="Nothing" onChange={(e) => change({ wakeReply: e.target.value })} />
        </Field>
      </Group>

      <p className="footnote">
        What you say is sent to Google (Gemini) to be written down, and the answers to be spoken. On Google's free tier it may use those requests to improve its
        products; on a paid plan it does not, so use a paid key.
      </p>
      {save.error && <p className="error">{save.error.message}</p>}

      {edits && (
        <div className="savebar" role="region" aria-label="Unsaved changes">
          <span>Unsaved changes</span>
          <span className="savebar-actions">
            <button type="button" className="button" onClick={() => setEdits(null)}>
              Undo
            </button>
            <button type="button" className="button primary" disabled={save.isPending} onClick={() => save.mutate(draft, { onSuccess: () => setEdits(null) })}>
              {save.isPending ? 'Saving…' : 'Save'}
            </button>
          </span>
        </div>
      )}
    </SettingsPage>
  )
}
