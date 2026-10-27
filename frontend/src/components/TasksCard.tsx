import { useState, type FormEvent } from 'react'
import { formatDue } from '../format'
import { useCreateTask, useDeleteTask, useSession, useSetTaskDone, useTasks } from '../hooks/data'

interface Props {
  /** Show the form and delete buttons (phone). The door screen can only tick tasks. */
  editable?: boolean
  limit?: number
}

export function TasksCard({ editable = false, limit }: Props) {
  const session = useSession()
  const canEdit = editable && session.data?.role === 'user'
  const { data, isPending } = useTasks('today')
  const setDone = useSetTaskDone()
  const remove = useDeleteTask()

  const tasks = data ?? []
  const open = tasks.filter((t) => !t.done)
  const shown = limit ? tasks.slice(0, limit) : tasks
  const hidden = tasks.length - shown.length

  return (
    <section className="card tasks" aria-label="Tasks">
      <h2>
        Tasks <span className="count">{open.length > 0 ? `${open.length} open` : ''}</span>
      </h2>
      {isPending && <p className="muted">Loading tasks…</p>}
      {!isPending && tasks.length === 0 && <p className="muted">All clear for today.</p>}
      <ul className="task-list">
        {shown.map((t) => (
          <li key={t.id} className={t.done ? 'task done' : 'task'}>
            <label className="task-check">
              <input
                type="checkbox"
                checked={t.done}
                disabled={setDone.isPending}
                onChange={(e) => setDone.mutate({ id: t.id, done: e.target.checked })}
              />
              <span className="box" aria-hidden="true" />
              <span className="task-title">{t.title}</span>
            </label>
            {t.overdue && <span className="chip overdue">Overdue</span>}
            {!t.overdue && t.dueDate && !t.done && <span className="chip">{formatDue(t.dueDate)}</span>}
            {canEdit && (
              <button className="icon-button" aria-label={`Delete ${t.title}`} onClick={() => remove.mutate(t.id)}>
                ×
              </button>
            )}
          </li>
        ))}
      </ul>
      {hidden > 0 && <p className="muted small">+{hidden} more</p>}
      {canEdit && <AddTask />}
    </section>
  )
}

function AddTask() {
  const [title, setTitle] = useState('')
  const [due, setDue] = useState('')
  const create = useCreateTask()

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const trimmed = title.trim()
    if (!trimmed) return
    create.mutate(
      { title: trimmed, dueDate: due || undefined },
      {
        onSuccess: () => {
          setTitle('')
          setDue('')
        },
      },
    )
  }

  return (
    <form className="add-task" onSubmit={submit}>
      <input
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        placeholder="Add a task"
        aria-label="New task"
        maxLength={200}
      />
      <input type="date" value={due} onChange={(e) => setDue(e.target.value)} aria-label="Due date (optional)" />
      <button className="button primary" type="submit" disabled={create.isPending || !title.trim()}>
        Add
      </button>
      {create.isError && <p className="error">Could not add the task.</p>}
    </form>
  )
}
