import { useRef, useEffect, useMemo, useState } from 'react';
import Editor from '@monaco-editor/react';

const CodeEditor = ({ filename, content, onCodeChange, path, reff }) => {
  const editorRef = useRef(null);
  const filenameRef = useRef(filename);
  const lastTimeoutRef = useRef(null);
  const typed = useRef(false);
  const [loading, setLoading] = useState(false);
  const [fileLoading, setFileLoading] = useState(false);
  const [language, setLanguage] = useState('plaintext');

  useEffect(() => {
    setFileLoading(true);
    filenameRef.current = filename;
    const getLanguageFromExtension = (filename) => {
      const ext = filename.split('.').pop();
      switch (ext) {
        case 'js': return 'javascript';
        case 'ts': return 'typescript';
        case 'py': return 'python';
        case 'java': return 'java';
        case 'cpp': return 'cpp';
        case 'c': return 'c';
        case 'html': return 'html';
        case 'css': return 'css';
        case 'json': return 'json';
        case 'txt': return 'plaintext';
        default: return 'plaintext';
      }
      
    };
    let lang = getLanguageFromExtension(filename);
    setLanguage(lang);
    setFileLoading(false);
  }, [filename]);
  const isReadOnly = filename.endsWith('/.keep');

  const handleMount = (editor) => {
    editorRef.current = editor;

    editor.onWillChangeModel(() => {
      if (editor) {
        const content = editor.getValue();
        onCodeChange(filenameRef.current, content, { updateFileState: true });
      }
    })

    editor.onDidChangeModelContent(() => {
      if (fileLoading) return;
      if (reff.current[filenameRef.current]) {
        reff.current[filenameRef.current] = false;
        return;
      }

      typed.current = true;

      if (lastTimeoutRef.current) clearTimeout(lastTimeoutRef.current);
      lastTimeoutRef.current = setTimeout(() => {
        onCodeChange(filenameRef.current, editor.getValue());
      }, 150);
    });
  };

  useEffect(() => {
    const interval = setInterval(() => {
      if (!(typed.current)) {
        return;
      }
      onCodeChange(filenameRef.current, editorRef.current.getValue());
      typed.current = false;
    }, 3000);
    return () => clearInterval(interval);
  }, [onCodeChange]);
  if (loading) {
    return <div>Loading...</div>;
  }
  return (
    <Editor
      key={path}
      height="100%"
      theme="vs-dark"
      defaultLanguage={language}
      onMount={handleMount}
      value={content}
      path={path}
      options={{
        readOnly: isReadOnly,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        automaticLayout: true,
      }}
    />
  );
};

export default CodeEditor;