
const Features = () => {
    return (
        <div className="px-4 py-8">
        <h2 className="text-center text-2xl font-bold mb-6 text-slate-100">
          Features
        </h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-6 max-w-4xl mx-auto">
          {[
            { title: 'Live Collaboration', desc: 'Work with others in real-time, instantly.' },
            { title: 'Multi-File Support', desc: 'Create folders and files, like a real project.' },
            { title: 'Secure Code Execution', desc: 'Run your code safely in real-time using isolated environments.' },
            { title: 'Real-time Sync', desc: 'Changes appear instantly for everyone in the session.' },
          ].map((f, i) => (
            <div
              key={i}
              className="p-6 bg-slate-800 border border-slate-700 rounded-lg hover:shadow-md transition text-center"
            >
              <h3 className="text-lg font-semibold text-cyan-400">{f.title}</h3>
              <p className="text-slate-300 mt-2">{f.desc}</p>
            </div>
          ))}
        </div>
      </div>
    )
}
export default Features;