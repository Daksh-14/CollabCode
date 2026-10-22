# CollabCode 🚀

**CollabCode** is a real-time collaborative code editor that allows multiple users to write, edit, and execute code simultaneously. Built with a modern tech stack and containerized execution environment, it supports multi-file editing, Docker-based language support, and seamless real-time syncing.

---

## ✨ Features

* 🔁 **Real-time Collaboration** using WebSockets
* 🧠 **Multi-language Code Execution** (Java, Python, C++) via Docker
* 📁 **Folder & File Tree Support** for multi-file editing
* 💻 **Modern Monaco Editor Integration**
* 💬 **Live Code Updates and Synchronization**

---

## 👷️ Tech Stack

### Frontend

* [React](https://reactjs.org/)
* [Vite](https://vitejs.dev/)
* [Monaco Editor](https://microsoft.github.io/monaco-editor/)
* [React Arborist](https://react-arborist.netlify.app/) (for file tree)
* Tailwind CSS

### Backend

* [Spring Boot](https://spring.io/projects/spring-boot)
* WebSocket (for collaboration)
* REST (for code execution, session handling)
* Docker (for secure code execution)
* Redis (Pub/Sub for instance syncing)

---

## 📦 Getting Started

### Prerequisites

* Node.js & npm
* Java 17+
* Docker

### Local Setup

#### 1. Clone the Repo

```bash
git clone https://github.com/Daksh-14/CollabCode.git
cd CollabCode
```

#### 2. Start Backend

```bash
cd editor-backend
./mvnw spring-boot:run
```

#### 3. Start Frontend

```bash
cd ../editor-frontend
npm install
npm run dev
```

#### 4. Visit

```bash
http://localhost:5173/
```

---

## 🧪 Example Use Case

1. One user creates a session and starts coding.
2. Another user joins with the session ID.
3. Both can see live code updates, make changes, and execute code.
4. Output is returned and shown in real time.

---
