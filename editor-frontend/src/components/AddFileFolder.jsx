import { useState } from "react";

export default function AddFileFolderModal({ isOpen, onClose, onSubmit, isFolder }) {
  const [name, setName] = useState("");
  const [error, setError] = useState("");

  const handleSubmit = () => {
    const trimmedName = name.trim();

    if (!trimmedName) {
      setError("Name cannot be empty.");
      return;
    }

    if (trimmedName.includes("/")) {
      setError("Name cannot contain '/'");
      return;
    }

    onSubmit(trimmedName);
    setName("");
    setError("");
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-opacity-100 flex justify-center items-center z-50">
      <div className="bg-white rounded-lg p-6 w-full max-w-sm shadow-xl">
        <h2 className="text-lg text-gray-800 font-semibold mb-4">
          Create new {isFolder ? "folder" : "file"}
        </h2>
        <input
          type="text"
          className="w-full border px-3 py-2 rounded mb-2 focus:outline-none text-gray-800"
          placeholder={isFolder ? "Folder name" : "Filename (e.g., index.js)"}
          value={name}
          onChange={(e) => {
            setName(e.target.value);
            if (error) setError(""); // Clear error on input
          }}
        />
        {error && (
          <p className="text-sm text-red-600 mb-2">{error}</p>
        )}
        <div className="flex justify-end gap-2">
          <button onClick={onClose} className="px-4 py-2 bg-gray-300 rounded hover:bg-gray-400">Cancel</button>
          <button onClick={handleSubmit} className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600">Create</button>
        </div>
      </div>
    </div>
  );
}
