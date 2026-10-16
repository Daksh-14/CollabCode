/**
 * Single source of truth for backend URLs.
 *
 * Set VITE_API_BASE to point at a deployed backend. When it is unset the app
 * targets its own origin, which is what you want for local development against
 * a backend running on the same host (or behind a proxy).
 *
 *   local:      (unset)            -> http://localhost:5173/api/...
 *   deployed:   VITE_API_BASE=https://api.example.com
 */

const RAW_BASE = import.meta.env.VITE_API_BASE ?? ''

/** Trailing-slash-stripped base URL, or '' when running same-origin. */
export const API_BASE = RAW_BASE.replace(/\/+$/, '')

/** Absolute API URL for a backend path such as `/api/sessions`. */
export function apiUrl(path) {
  return `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`
}

/**
 * Absolute WebSocket URL for a socket path such as `/ws/code`.
 * Derives ws:/wss: from the page or API base automatically.
 */
export function wsUrl(path) {
  const suffix = path.startsWith('/') ? path : `/${path}`

  if (!API_BASE) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}${suffix}`
  }

  const base = new URL(API_BASE)
  base.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${base.origin}${suffix}`
}