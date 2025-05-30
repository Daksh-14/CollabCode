import { useEffect, useState, useLayoutEffect, useRef } from 'react';
import { Tree } from 'react-arborist';
import useResizeObserver from '@react-hook/resize-observer';
import AddFileFolderModal from './AddFileFolder';

const useSize = (target) => {
  const [size, setSize] = useState();
  useLayoutEffect(() => {
    if (target.current) {
      setSize(target.current.getBoundingClientRect());
    }
  }, [target]);
  useResizeObserver(target, (entry) => {
    setSize(entry.contentRect);
  });
  return size;
};

const FileStructure = ({ files, currentFile, mainFile, onFileSelect, onSetMainFile, onAdd, onDelete, setConfirmDelete  }) => {
  const [fileStruct, setFileStruct] = useState([]);
  const containerRef = useRef(null);
  const size = useSize(containerRef);
  const [contextMenu, setContextMenu] = useState({
    visible: false,
    x: 0,
    y: 0,
    path: null,
    isFolder: false,
    isMain: false,
  });

  useEffect(() => {
    const buildTree = (filePaths) => {
      const root = {};

      filePaths.forEach((filePath) => {
        const parts = filePath.split('/');
        let node = root;

        parts.forEach((part, index) => {
          const isFile = index === parts.length - 1;
          if (!node[part]) {
            node[part] = {
              id: parts.slice(0, index + 1).join('/'),
              name: part,
              isInternal: !isFile,
              children: isFile ? undefined : {},
            };
          }
          if (!isFile) node = node[part].children;
        });
      });

      const convertToArray = (nodeMap) =>
        Object.values(nodeMap).map((node) => ({
          id: node.id,
          name: node.name,
          isInternal: node.isInternal,
          children: node.children ? convertToArray(node.children) : undefined,
        }));

      return convertToArray(root);
    };

    setFileStruct(buildTree(files));
  }, [files]);


  const [modalOpen, setModalOpen] = useState(false);
  const [isFolder, setIsFolder] = useState(false);
  const [targetPath, setTargetPath] = useState("");

  const handleAddFile = (path) => {
    setIsFolder(false);
    setTargetPath(path);
    setModalOpen(true);
  };

  const handleAddFolder = (path) => {
    setIsFolder(true);
    setTargetPath(path);
    setModalOpen(true);
  };

  const handleCreate = (name) => {
    const fullPath = isFolder
      ? `${targetPath}/${name}/.keep`
      : `${targetPath}/${name}`;
    onAdd(fullPath);
  };

  const handleDelete = (path) => {
    setConfirmDelete({ visible: true, path });
  };

  useEffect(() => {
    const handleClick = () =>
      setContextMenu((prev) => ({ ...prev, visible: false }));

    window.addEventListener('click', handleClick);

    return () => window.removeEventListener('click', handleClick);
  }, []);

  return (
    <div ref={containerRef} className="hehe h-full w-full m-2 ">
      <AddFileFolderModal
        isOpen={modalOpen}
        isFolder={isFolder}
        onClose={() => setModalOpen(false)}
        onSubmit={handleCreate}
      />
      {contextMenu.visible && (
        <div
          className="absolute z-50 bg-white shadow-md border rounded w-40 text-sm text-gray-800"
          style={{ top: contextMenu.y, left: contextMenu.x }}
          onClick={() => setContextMenu({ ...contextMenu, visible: false })}
        >
          {contextMenu.isFolder && (
            <>
              <div
                onClick={() => handleAddFile(contextMenu.path)}
                className="px-3 py-2 hover:bg-gray-100 cursor-pointer"
              >
                ➕ Add File
              </div>
              <div
                onClick={() => handleAddFolder(contextMenu.path)}
                className="px-3 py-2 hover:bg-gray-100 cursor-pointer"
              >
                📁 Add Folder
              </div>
            </>
          )}
          {!contextMenu.isFolder && !contextMenu.isMain && (
            <div
              onClick={() => onSetMainFile(contextMenu.path)}
              className="px-3 py-2 hover:bg-gray-100 cursor-pointer"
            >
              📌 Set as Main
            </div>
          )}
          <div
            onClick={() => handleDelete(contextMenu.path)}
            className="px-3 py-2 hover:bg-red-100 text-red-600 cursor-pointer"
          >
            ❌ Delete
          </div>
        </div>
      )}

      <Tree
        data={fileStruct}
        rowHeight={30}
        indent={24}
        height={size ? size.height : 100}
        width={size ? size.width : 100}
      >
        {(T) => {
          const id = T.node.data.id;
          const isSelected = id === currentFile;
          const isMain = id === mainFile;
          const isFolder = T.node.isInternal;

          return (
            <div
              style={{ paddingLeft: `${T.node.level * 1.5}rem` }}
              className={`min-w-max px-2 py-1 cursor-pointer rounded flex justify-between gap-[5vw] items-start ${isSelected ? 'bg-gray-600 text-white' : ''
                }`}


              onClick={() => {
                if (isFolder) {
                  T.node.toggle();
                } else {
                  onFileSelect(id);
                }
              }}

              onContextMenu={(e) => {
                e.preventDefault();
                setContextMenu({
                  visible: true,
                  x: e.clientX,
                  y: e.clientY,
                  path: id,
                  isFolder: isFolder,
                  isMain: isMain,
                });
              }}
            >
              <span>
                {isFolder ? (T.node.isOpen ? '📂' : '📁') : '📄'} {T.node.data.name}
              </span>

            </div>
          );
        }}
      </Tree>
    </div>
  );
};

export default FileStructure;
