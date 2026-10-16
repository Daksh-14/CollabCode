import { useState } from 'react'

/**
 * Execution output for the current session.
 *
 * Output is deliberately NOT stored as a file in the project tree. Writing it to
 * `output.txt` mutated every collaborator's file tree, got persisted to the
 * database, and leaked into subsequent Export ZIP downloads and run payloads.
 */
const OutputPanel = ({ output, status, onClose }) => {
  const [copied, setCopied] = useState(false)

  if (!output && status !== 'running') return null

  const handleCopy = () => {
    if (!output) return
    navigator.clipboard.writeText(output).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  return (
    <div className="flex flex-col border-t border-gray-600 bg-[#1e1e1e]">
      <div className="flex items-center justify-between px-3 py-1.5 text-xs bg-[#2d2d2d] text-gray-300">
        <div className="flex items-center gap-2">
          <span className="font-semibold uppercase tracking-wide">Output</span>
          {status === 'running' && (
            <span className="text-yellow-400">Running…</span>
          )}
          {status === 'error' && (
            <span className="text-red-400">Failed</span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleCopy}
            disabled={!output}
            className="px-2 py-0.5 rounded bg-gray-700 hover:bg-gray-600 disabled:opacity-40 disabled:hover:bg-gray-700"
          >
            {copied ? 'Copied!' : 'Copy'}
          </button>
          <button
            onClick={onClose}
            className="px-2 py-0.5 rounded bg-gray-700 hover:bg-gray-600"
          >
            Close
          </button>
        </div>
      </div>
      <pre className="m-0 px-3 py-2 text-xs font-mono text-gray-200 whitespace-pre-wrap break-words overflow-auto max-h-64">
        {status === 'running' ? '' : output || '(no output)'}
      </pre>
    </div>
  )
}

export default OutputPanel