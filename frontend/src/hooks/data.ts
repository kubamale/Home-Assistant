import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api'
import type { ChannelChange, PinItem, SceneActionPayload, SessionInfo } from '../types'

// Data is refreshed by server events (see ConnectionProvider). Queries keep their last good data
// when a refetch fails, which is what the "reconnecting" screens rely on.
const common = { networkMode: 'always' as const, staleTime: 5 * 60_000, refetchInterval: 5 * 60_000, retry: 1 }

export function useSession() {
  return useQuery<SessionInfo | null>({
    queryKey: ['session'],
    queryFn: async () => {
      try {
        return await api.me()
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }
    },
    networkMode: 'always',
    retry: false,
    refetchInterval: (query) => (query.state.status === 'error' ? 5000 : false),
    staleTime: Infinity,
  })
}

export const useWeather = () => useQuery({ queryKey: ['weather'], queryFn: api.weather, ...common })
export const useCalendar = () => useQuery({ queryKey: ['calendar'], queryFn: api.calendar, ...common })
export const useDevices = () => useQuery({ queryKey: ['devices'], queryFn: api.devices, ...common })
export const useRooms = () => useQuery({ queryKey: ['rooms'], queryFn: api.rooms, ...common })
export const useScenes = () => useQuery({ queryKey: ['scenes'], queryFn: api.scenes, ...common })
export const useVoiceStatus = () => useQuery({ queryKey: ['voice', 'status'], queryFn: api.voiceStatus, ...common })
export const useSettings = () => useQuery({ queryKey: ['settings'], queryFn: api.settings, ...common })
export const useTasks = (scope: 'today' | 'all' = 'today') =>
  useQuery({ queryKey: ['tasks', scope], queryFn: () => api.tasks(scope), ...common })

export function useCreateTask() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (v: { title: string; dueDate?: string }) => api.createTask(v.title, v.dueDate),
    onSuccess: () => client.invalidateQueries({ queryKey: ['tasks'] }),
  })
}

export function useSetTaskDone() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (v: { id: number; done: boolean }) => api.setTaskDone(v.id, v.done),
    onSuccess: () => client.invalidateQueries({ queryKey: ['tasks'] }),
  })
}

export function useDeleteTask() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.deleteTask(id),
    onSuccess: () => client.invalidateQueries({ queryKey: ['tasks'] }),
  })
}

export function useSetPower() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (v: { id: string; on: boolean }) => api.setPower(v.id, v.on),
    onSettled: () => client.invalidateQueries({ queryKey: ['devices'] }),
  })
}

/**
 * Changes one channel of a device. Resolves once the devices have been refetched, so a slider can
 * keep showing the value the user chose until the real one arrives.
 */
export function useSetChannel() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (v: { deviceId: string; channel: string; change: ChannelChange }) => api.setChannel(v.deviceId, v.channel, v.change),
    onSettled: () => client.invalidateQueries({ queryKey: ['devices'] }),
  })
}

/** Changes to rooms, assignments, names, scenes and quick access: refetch the lists afterwards. */
function useHomeMutation<V, R>(fn: (v: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () =>
      Promise.all(['rooms', 'devices', 'scenes'].map((key) => client.invalidateQueries({ queryKey: [key] }))),
  })
}

export const useCreateRoom = () => useHomeMutation((name: string) => api.createRoom(name))
export const useRenameRoom = () => useHomeMutation((v: { id: number; name: string }) => api.renameRoom(v.id, v.name))
export const useDeleteRoom = () => useHomeMutation((id: number) => api.deleteRoom(id))
export const useReorderRooms = () => useHomeMutation((ids: number[]) => api.reorderRooms(ids))
export const useUpdateDevice = () =>
  useHomeMutation((v: { id: string; name?: string; roomId?: number | null }) =>
    api.updateDevice(v.id, { name: v.name, roomId: v.roomId }),
  )
export const useForgetDevice = () => useHomeMutation((id: string) => api.forgetDevice(id))
export const useSetQuickAccess = () => useHomeMutation((items: PinItem[]) => api.setQuickAccess(items))

export const useCreateScene = () => useHomeMutation((v: { name: string; actions: SceneActionPayload[] }) => api.createScene(v.name, v.actions))
export const useCaptureScene = () =>
  useHomeMutation((v: { name: string; channels: { deviceId: string; channel: string }[] }) => api.captureScene(v.name, v.channels))
export const useUpdateScene = () =>
  useHomeMutation((v: { id: number; name: string; actions?: SceneActionPayload[] }) => api.updateScene(v.id, v.name, v.actions))
export const useDeleteScene = () => useHomeMutation((id: number) => api.deleteScene(id))
export const useReorderScenes = () => useHomeMutation((ids: number[]) => api.reorderScenes(ids))

/** Applies a scene. The lights change one by one, so the devices are refetched afterwards. */
export function useApplyScene() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.applyScene(id),
    onSettled: () => client.invalidateQueries({ queryKey: ['devices'] }),
  })
}

/** The saved location's timezone, so every screen formats times in the same zone as the server. */
export function useZone(): string | undefined {
  const { data } = useSettings()
  return data?.location?.timezone
}
