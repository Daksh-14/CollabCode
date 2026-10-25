import { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor'
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import jsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker'
import cssWorker from 'monaco-editor/esm/vs/language/css/css.worker?worker'
import htmlWorker from 'monaco-editor/esm/vs/language/html/html.worker?worker'
import tsWorker from 'monaco-editor/esm/vs/language/typescript/ts.worker?worker'

/**
 * Monaco bundles its language services as Web Workers. Under Vite they must be
 * declared explicitly, otherwise Monaco falls back to running them on the main
 * thread and language features degrade silently.
 *
 * Imported for its side effect from main.jsx — must run before any editor mounts.
 */
globalThis.MonacoEnvironment = {
  getWorker(_workerId, label) {
    switch (label) {
      case 'json':
        return new jsonWorker()
      case 'css':
      case 'scss':
      case 'less':
        return new cssWorker()
      case 'html':
      case 'handlebars':
      case 'razor':
        return new htmlWorker()
      case 'typescript':
      case 'javascript':
        return new tsWorker()
      default:
        return new editorWorker()
    }
  },
}

/**
 * Tell @monaco-editor/react to use THIS bundled Monaco instance instead of
 * loading its own copy from the CDN. Otherwise the wrapper and the app hold two
 * different Monaco instances, and `monaco.editor.getModels()` in Editor.jsx
 * cannot see the editor's models.
 */
loader.config({ monaco })

export default globalThis.MonacoEnvironment