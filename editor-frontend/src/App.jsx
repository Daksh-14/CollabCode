import { BrowserRouter, Routes, Route } from "react-router-dom";
import Home from './pages/Home'
import Editor from './pages/Editor'

function App() {
  return (
    <BrowserRouter basename="/">
      <Routes>
        <Route path="/" element={<Home/>} />
        <Route path="/editor/:sessionId" element={<Editor/>} />
      </Routes>
    </BrowserRouter>
  )
}

export default App;
