import { useEffect, useRef, useState } from 'react';
import FileStructure from '../components/FileStructure';
import CodeEditor from '../components/CodeEditor';
import OutputPanel from '../components/OutputPanel';
import axios from 'axios';
import { useParams } from 'react-router-dom';
import * as monaco from 'monaco-editor';
import JSZip from 'jszip';
import { saveAs } from 'file-saver';
import ConfirmDelete from '../components/ConfirmDelete';
import { apiUrl, wsUrl } from '../config';

const Editor = () => {
  const { sessionId } = useParams();
  const socketRef = useRef(null);
  const [files, setFiles] = useState({});
  const [currentFile, setCurrentFile] = useState(null);
  const [mainFile, setMainFile] = useState(null);
  const externalUpdateRef = useRef({});
  const [copied, setCopied] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState({ visible: false, path: null });
  const [sessionName, setSessionName] = useState('');
  const [wsStatus, setWsStatus] = useState('connecting');
  const [runStatus, setRunStatus] = useState('idle');
  const [output, setOutput] = useState(null);

  const downloadZip = async () => {
    const zip = new JSZip();
    Object.entries(files).forEach(([path, content]) => {
      zip.file(path, content);
    });
    const blob = await zip.generateAsync({ type: 'blob' });
    saveAs(blob, `session-${sessionId}.zip`);
  };

  useEffect(() => {
    let cancelled = false;
    axios.get(apiUrl(`/api/sessions/${sessionId}`))
      .then(res => { if (!cancelled) setSessionName(res.data.sessionName); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [sessionId]);

  const handleCopyJoinCode = () => {
    navigator.clipboard.writeText(sessionId).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  useEffect(() => {
    setWsStatus('connecting');
    const ws = new WebSocket(wsUrl(`/ws/code?sessionId=${encodeURIComponent(sessionId)}`));
    socketRef.current = ws;

    // Registered up front. Assigning these inside onmessage was a bug: the
    // open/error events had already fired by then, so none of them ever ran.
    ws.onopen = () => {
      setWsStatus('connected');
    };
    ws.onerror = () => {
      setWsStatus('error');
    };
    ws.onclose = () => {
      setWsStatus((prev) => (prev === 'connected' ? 'disconnected' : prev));
    };

    ws.onmessage = ({ data }) => {
      let msg;
      try {
        msg = JSON.parse(data);
      } catch {
        return;
      }

      if (msg.type === 'init') {
        const incoming = msg.files ?? {};
        setFiles(incoming);
        // Object key order is not meaningful. Pick a real source file
        // deterministically — picking key 0 could select `folder/.keep`, which
        // then resolves to language "keep" and fails to execute.
        const firstFile = Object.keys(incoming).sort().find(isRunnableFile);
        setCurrentFile(firstFile ?? null);
        setMainFile(firstFile ?? null);
      }

      if (msg.type === 'edit') {
        const { filename, content } = msg;
        externalUpdateRef.current[filename] = true;
        setFiles((prev) => ({ ...prev, [filename]: content }));
      }

      if (msg.type === 'add') {
        const { filename, content } = msg;
        setFiles(prev => ({ ...prev, [filename]: content }));
      }

      if (msg.type === 'delete') {
        const { filename } = msg;
        setFiles(prev => {
          const copy = { ...prev };
          delete copy[filename];
          return copy;
        });
      }
    };

    return () => {
      ws.onclose = null;
      ws.close();
      socketRef.current = null;
    };
  }, [sessionId]);

  const handleCodeChange = (filename, newContent, options = { updateFileState: false }) => {
    if (options.updateFileState) {
      setFiles((prev) => ({ ...prev, [filename]: newContent }));
    }

    const ws = socketRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: 'edit',
        filename,
        content: newContent,
        sessionId
      }));
    }
  };

  const handleRun = async () => {
    if (!mainFile) return;

    // Read straight from the live Monaco model: `files` state can lag behind
    // the editor by up to the 150ms debounce window.
    const model = monaco.editor
      .getModels()
      .find(m => m.uri.toString() === monaco.Uri.parse(currentFile).toString());
    const latestContent = model?.getValue() ?? files[currentFile] ?? '';

    const codeFiles = Object.entries(files).map(([path, content]) =>
      path === currentFile ? { path, content: latestContent } : { path, content }
    );

    setRunStatus('running');
    setOutput(null);

    try {
      const response = await axios.post(apiUrl('/api/sessions/execute'), {
        sessionId,
        language: mainFile.split('.').pop(),
        entrypoint: mainFile,
        files: codeFiles,
      });
      setOutput(response.data ?? '');
      setRunStatus('success');
    } catch (err) {
      setOutput(`Execution failed: ${err.message}`);
      setRunStatus('error');
    }
  };

  const handleAdd = (newPath) => {
    setFiles(prev => ({ ...prev, [newPath]: '' }));
    setCurrentFile(newPath);

    const ws = socketRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({
        type: 'createFile',
        filename: newPath,
        content: '',
        sessionId,
      }));
    }
  };

  const handleDelete = (pathToDelete) => {
    const filesToDelete = [];
    setFiles(prev => {
      const copy = { ...prev };
      Object.keys(copy).forEach(path => {
        if (path === pathToDelete || path.startsWith(pathToDelete + '/')) {
          filesToDelete.push(path);
          delete copy[path];
        }
      });
      return copy;
    });

    const ws = socketRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      filesToDelete.forEach((filePath) => {
        ws.send(JSON.stringify({
          type: 'deleteFile',
          filename: filePath,
          sessionId,
        }));
      });
    }
  };

  if (!currentFile) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-slate-700">
        <div className="text-slate-100 text-2xl">
          {wsStatus === 'error' || wsStatus === 'disconnected'
            ? 'Connection lost. Reload to rejoin the session.'
            : 'Joining the session...'}
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-screen bg-[#1e1e1e] text-white">
      <div className="text-lg font-semibold text-white px-4 py-2 bg-[#2d2d2d] border-b border-gray-600 flex justify-between items-center">
        <div className="flex items-center gap-3">
          <span>{`Session : ${sessionName || '…'}`}</span>
          <span
            title={wsStatus}
            className={`text-xs font-normal px-2 py-0.5 rounded ${
              wsStatus === 'connected' ? 'bg-green-900 text-green-300'
                : wsStatus === 'connecting' ? 'bg-yellow-900 text-yellow-300'
                : 'bg-red-900 text-red-300'
            }`}
          >
            {wsStatus}
          </span>
        </div>
        <button
          className="px-3 py-1 bg-gray-700 text-white rounded hover:bg-gray-600 text-sm"
          onClick={handleCopyJoinCode}
        >
          {copied ? "Copied!" : "Join Code"}
        </button>
      </div>

      <div className="flex flex-1 min-h-0">
        <div className="h-full w-1/4 border-r border-gray-700 bg-[#1e1e1e] text-white">
          <FileStructure
            files={Object.keys(files)}
            currentFile={currentFile}
            mainFile={mainFile}
            onFileSelect={setCurrentFile}
            onSetMainFile={setMainFile}
            onAdd={handleAdd}
            setConfirmDelete={setConfirmDelete}
          />
        </div>

        <div className="w-3/4 flex flex-col min-w-0 bg-[#1e1e1e] text-white z-10">
          <div className="text-sm text-gray-400 w-full flex justify-between items-center p-2 border-b border-gray-600 bg-[#2d2d2d]">
            <div>Main File: <span className="text-green-400">{mainFile}</span></div>
            <button
              className="px-3 py-1 bg-purple-600 text-white rounded hover:bg-purple-500"
              onClick={downloadZip}
            >
              Export ZIP
            </button>
          </div>

          <div className="p-2 flex justify-between items-center border-b border-gray-600 bg-[#2d2d2d]">
            <div>Editing: <span className="text-green-400">{currentFile}</span></div>
            <div className="space-x-2">
              <button
                className="px-3 py-1 bg-gray-700 rounded hover:bg-gray-600"
                onClick={() => setMainFile(currentFile)}
              >
                Set as Main
              </button>
              <button
                className="px-3 py-1 bg-blue-600 text-white rounded hover:bg-blue-500 disabled:opacity-50"
                onClick={handleRun}
                disabled={!mainFile || runStatus === 'running'}
              >
                {runStatus === 'running' ? 'Running…' : 'Run ▶'}
              </button>
            </div>
          </div>

          <div className="flex-1 min-h-0">
            <CodeEditor
              filename={currentFile}
              content={files[currentFile] || ''}
              onCodeChange={handleCodeChange}
              path={currentFile}
              reff={externalUpdateRef}
            />
          </div>

          <OutputPanel
            output={output}
            status={runStatus}
            onClose={() => { setOutput(null); setRunStatus('idle'); }}
          />
        </div>

        {confirmDelete.visible && (
          <ConfirmDelete
            onCancel={() => setConfirmDelete({ visible: false, path: null })}
            onDelete={() => {
              handleDelete(confirmDelete.path);
              setConfirmDelete({ visible: false, path: null });
            }}
            confirmDelete={confirmDelete} />
        )}
      </div>
    </div>
  );
};

/** Folder markers and output files are not valid entrypoints. */
function isRunnableFile(path) {
  if (path.endsWith('/.keep')) return false;
  return /\.(java|py|cpp|c|js|ts|txt)$/i.test(path);
}

export default Editor;