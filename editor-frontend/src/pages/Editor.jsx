import { useEffect, useRef, useState } from 'react';
import FileStructure from '../components/FileStructure';
import CodeEditor from '../components/CodeEditor';
import axios from 'axios';
import { useParams } from 'react-router-dom';
import JSZip from 'jszip';
import { saveAs } from 'file-saver';
import ConfirmDelete from '../components/ConfirmDelete';

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

  const downloadZip = async () => {
    const zip = new JSZip();
    Object.entries(files).forEach(([path, content]) => {
      zip.file(path, content);
    });
    const blob = await zip.generateAsync({ type: 'blob' });
    saveAs(blob, `session-${sessionId}.zip`);
  };

  useEffect(() => {
    axios.get(`https://collabcode-backend-z04k.onrender.com/api/sessions/${sessionId}`)
      .then(res => setSessionName(res.data.sessionName))
      .catch();
  }, [sessionId]);

  const handleCopyJoinCode = () => {
    navigator.clipboard.writeText(sessionId).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  useEffect(() => {
    const ws = new WebSocket(`wss://collabcode-backend-z04k.onrender.com/ws/code?sessionId=${sessionId}`);
    socketRef.current = ws;
    ws.onmessage = ({ data }) => {
      const msg = JSON.parse(data);
      if (msg.type === 'init') {
        setFiles(msg.files);
        const firstFile = Object.keys(msg.files)[0];
        setCurrentFile(firstFile);
        setMainFile(firstFile);
      }
      if (msg.type === 'edit') {
        const { filename, content } = msg;
        externalUpdateRef.current[filename] = true;
        setFiles((prev) => ({
          ...prev,
          [filename]: content
        }));
      }
      if (msg.type === 'add') {
        console.log('File added:', msg);
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
      ws.onopen = () => {
        console.log('WebSocket connection established');
      }
      ws.onerror = (error) => {
        console.error('WebSocket error:', error);
      };
    };
    return () => ws.close();
  }, [sessionId]);

  const handleCodeChange = (filename, newContent, options = { updateFileState: false }) => {
    if (options.updateFileState) {
      setFiles((prev) => ({
        ...prev,
        [filename]: newContent
      }));
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
    if (!mainFile || !monaco) return;

    try {
      const mainModelUri = monaco.Uri.file(currentFile).toString();
      const editorModel = monaco.editor.getModels().find(
        m => m.uri.toString() === mainModelUri
      );
      const latestCurrentFileContent = editorModel?.getValue() || "";

      const codeFiles = Object.entries(files).map(([path, content]) => {
        if (path === currentFile) {
          return { path, content: latestCurrentFileContent };
        }
        return { path, content };
      });

      const response = await axios.post("https://collabcode-backend-z04k.onrender.com/api/sessions/execute", {
        sessionId,
        language: mainFile.split('.').pop(),
        entrypoint: mainFile,
        files: codeFiles,
      });
      const result = String(response.data);
      setFiles(prev => ({
        ...prev,
        "output.txt": result,
      }));
      setCurrentFile("output.txt");

      socketRef.current?.send(JSON.stringify({
        type: "edit",
        filename: "output.txt",
        content: result,
        sessionId
      }));
    } catch (err) {
      setFiles(prev => ({
        ...prev,
        "output.txt": "Execution failed: " + err.message,
      }));
      setCurrentFile("output.txt");
    }
  };


  const handleAdd = (newPath) => {
    setFiles(prev => ({
      ...prev,
      [newPath]: '',
    }));

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

  if (!currentFile) return (
    <div className="absolute inset-0 bg-black bg-opacity-40 z-10 flex items-center justify-center">
      <div className='flex flex-col gap-4 justify-center items-center'>
        <div className="text-slate-100 text-2xl">Joining the session...</div>
        <div className="animate-spin rounded-full h-10 w-10 border-t-4 border-blue-500"></div>
      </div>
    </div>
  );
  return (
    <div className="flex flex-col h-screen bg-[#1e1e1e] text-white">
      <div className="text-lg font-semibold text-white px-4 py-2 bg-[#2d2d2d] border-b border-gray-600 flex justify-between items-center">
        Session {`:` + " "} {sessionName}
        <button
          className="px-3 py-1 bg-gray-700 text-white rounded hover:bg-gray-600 text-sm"
          onClick={handleCopyJoinCode}
        >
          {copied ? "Copied!" : "Join Code"}
        </button>
      </div>

      <div className="flex h-screen">
        <div className="Debug h-full w-1/4 border-r border-gray-700 bg-[#1e1e1e] text-white">
          <FileStructure
            files={Object.keys(files)}
            currentFile={currentFile}
            mainFile={mainFile}
            onFileSelect={setCurrentFile}
            onSetMainFile={setMainFile}
            onAdd={handleAdd}
            onDelete={handleDelete}
            setConfirmDelete={setConfirmDelete}
          />
        </div>

        <div className="w-3/4 flex flex-col bg-[#1e1e1e] text-white z-10">
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
                className="px-3 py-1 bg-blue-600 text-white rounded hover:bg-blue-500"
                onClick={handleRun}
              >
                Run ▶
              </button>
            </div>
          </div>
          <CodeEditor
            filename={currentFile}
            content={files[currentFile] || ''}
            onCodeChange={handleCodeChange}
            path={currentFile}
            reff={externalUpdateRef}
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

export default Editor;
