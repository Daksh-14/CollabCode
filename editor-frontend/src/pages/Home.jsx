import { useState } from 'react';
import FAQ from '../components/FAQ';
import Footer from '../components/Footer';
import { useNavigate } from 'react-router-dom';
import Features from '../components/Features';
import HeroSection from '../components/HeroSection';
import axios from 'axios';

const HomePage = () => {
  const [showModal, setShowModal] = useState(0);
  const [sessionId, setSessionId] = useState('');
  const [sessionName, setSessionName] = useState('');
  const navigate = useNavigate();
  const [folderFiles, setFolderFiles] = useState([]);

  const handleFolderSelect = (e) => {
    setFolderFiles(Array.from(e.target.files));
  };

  const handleJoinSession = async () => {
    try {
      const res = await axios.get(`https://collabcode-backend-z04k.onrender.com/api/sessions/${sessionId}`);
      if (res.status === 200) {
        navigate(`/editor/${sessionId}`);
      }
    } catch (err) {
      alert('Failed to join session. Make sure the Session ID is correct.');
    }
  }

  const handleCreateSession = async () => {
    try {
      console.log("Creating session with name:", sessionName);
      const sessionRes = await axios.post('https://collabcode-backend-z04k.onrender.com/api/sessions', {
        name: sessionName,
      });
      console.log("Session created with ID:", sessionRes.data.id);
      const newSessionId = sessionRes.data.id;
      const filesPayload = [];
      if (folderFiles.length > 0) {
        const uploadedFiles = await Promise.all(
          folderFiles.map(async (file) => ({
            id: {
              sessionId: newSessionId,
              path: file.webkitRelativePath,
            },
            content: await file.text(),
          }))
        );

        filesPayload.push(...uploadedFiles);

        const folderSet = new Set();
        folderFiles.forEach((file) => {
          const parts = file.webkitRelativePath.split('/');
          for (let i = 1; i < parts.length; i++) {
            const folderPath = parts.slice(0, i).join('/');
            folderSet.add(folderPath);
          }
        });

        folderSet.forEach((folder) => {
          const keepPath = `${folder}/.keep`;
          filesPayload.push({
            id: {
              sessionId: newSessionId,
              path: keepPath,
            },
            content: "keep",
          });
        });
      } else {
        filesPayload.push({
          id: {
            sessionId: newSessionId,
            path: "temp/.keep",
          },
          content: "keep",
        });
      }
      await axios.post(`https://collabcode-backend-z04k.onrender.com/api/sessions/bulk`, filesPayload);
      navigate(`/editor/${newSessionId}`);
    } catch (err) {
      alert("Failed to create session.");
    }
  };

  return (
    <div className="flex flex-col gap-8 min-h-screen bg-slate-700 px-4 py-6 sm:px-8">
      <div className="text-center text-4xl font-extrabold text-slate-100">
        CodeCollab
      </div>
      <HeroSection handleClick={()=>setShowModal(1)}/>
      {showModal > 0 && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-slate-800 w-[90%] max-w-md p-6 rounded-lg shadow-2xl">

            <div className="flex justify-end mb-4">
              <button
                onClick={() => setShowModal(0)}
                className="w-8 h-8 flex items-center justify-center rounded-full bg-slate-700 hover:bg-slate-600 text-slate-200 transition"
              >
                ✕
              </button>
            </div>

            {showModal === 1 && (
              <div className="flex flex-col gap-4">
                <button
                  onClick={() => setShowModal(2)}
                  className="bg-cyan-500 hover:bg-cyan-600 text-slate-900 font-semibold py-3 px-6 rounded-md w-full transition"
                >
                  Join Existing Session
                </button>
                <button
                  onClick={() => setShowModal(3)}
                  className="bg-cyan-500 hover:bg-cyan-600 text-slate-900 font-semibold py-3 px-6 rounded-md w-full transition"
                >
                  Create New Session
                </button>
              </div>
            )}

            {showModal === 2 && (
              <div className="flex flex-col gap-4">
                <h2 className="text-xl font-semibold text-slate-100">Join Existing Session</h2>
                <input
                  type="text"
                  placeholder="Enter Session ID"
                  value={sessionId}
                  onChange={(e) => setSessionId(e.target.value)}
                  className="w-full border border-slate-700 bg-slate-900 text-slate-100 rounded px-4 py-2 focus:outline-none focus:ring-2 focus:ring-cyan-400"
                />
                <button className="bg-cyan-500 hover:bg-cyan-600 text-slate-900 px-6 py-2 rounded-md font-semibold transition" onClick={handleJoinSession}>
                  Join
                </button>
              </div>
            )}

            {showModal === 3 && (
              <div className="flex flex-col gap-4">
                <h2 className="text-xl font-semibold text-slate-100">Create New Session</h2>
                <input
                  type="text"
                  placeholder="Enter Session Name"
                  value={sessionName}
                  onChange={(e) => setSessionName(e.target.value)}
                  className="w-full border border-slate-700 bg-slate-900 text-slate-100 rounded px-4 py-2 focus:outline-none focus:ring-2 focus:ring-cyan-400"
                />
                <input
                  type="file"
                  webkitdirectory="true"
                  directory=""
                  onChange={handleFolderSelect}
                  className="w-full border border-slate-700 bg-slate-900 text-slate-100 rounded px-4 py-2"
                />
                <button className="bg-cyan-500 hover:bg-cyan-600 text-slate-900 px-6 py-2 rounded-md font-semibold transition" onClick={handleCreateSession}>
                  Create
                </button>
              </div>
            )}

          </div>
        </div>
      )}
      <Features/>
      <FAQ />
      <Footer />
    </div>
  );
};

export default HomePage;
