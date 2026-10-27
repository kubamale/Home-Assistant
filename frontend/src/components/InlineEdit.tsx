import { useState } from 'react'

/** A one-line text field with Save and Cancel, used to rename rooms and devices. */
export function InlineEdit({
  initial,
  placeholder,
  label,
  busy,
  onSave,
  onCancel,
}: {
  initial: string
  placeholder?: string
  label: string
  busy?: boolean
  onSave: (value: string) => void
  onCancel: () => void
}) {
  const [value, setValue] = useState(initial)
  return (
    <form
      className="inline-edit"
      onSubmit={(e) => {
        e.preventDefault()
        onSave(value.trim())
      }}
    >
      <input
        type="text"
        value={value}
        placeholder={placeholder}
        maxLength={60}
        aria-label={label}
        autoFocus
        onChange={(e) => setValue(e.target.value)}
      />
      <button type="submit" className="button primary compact" disabled={busy}>
        Save
      </button>
      <button type="button" className="button compact" onClick={onCancel}>
        Cancel
      </button>
    </form>
  )
}
