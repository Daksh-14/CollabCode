import React, { useState, useRef, useEffect } from 'react';
import { FiPlus, FiMinus } from 'react-icons/fi';

const faqs = [
  { question: 'What is CollabCode?',      answer: 'A real‑time collaborative code editor that lets multiple users write, edit, and run code together instantly.' },
  { question: 'Do I need to sign up?',     answer: 'No signup needed — just create or join a session to start coding with others.' },
  { question: 'What languages are supported?', answer: 'Currently supports Python and C++. More coming soon!' },
  { question: 'Is it safe to run code here?',  answer: 'Yes. Your code runs inside a secure and sandboxed environment to ensure safety and stability.' },
  { question: 'Can I invite others to my session?', answer: 'Absolutely! Just share your session link and collaborate in real time.' },
];

const FAQ = () => {
  const [active, setActive] = useState(null);
  const contentRefs = useRef([]);

  const toggle = (i) => setActive((prev) => (prev === i ? null : i));

  useEffect(() => {
    contentRefs.current.forEach((ref, idx) => {
      if (ref) {
        ref.style.maxHeight = active === idx
          ? `${ref.scrollHeight}px`
          : '0px';
      }
    });
  }, [active]);

  return (
    <div className="max-w-3xl mx-auto py-12 px-4">
      <h2 className="text-3xl font-bold text-center text-slate-100 mb-8">
        Frequently Asked Questions
      </h2>
      <div className="space-y-4">
        {faqs.map((f, i) => (
          <div
            key={i}
            className="bg-slate-800 border border-slate-700 rounded-lg shadow-md"
          >
            <button
              onClick={() => toggle(i)}
              className="w-full flex justify-between items-center px-6 py-4 focus:outline-none"
            >
              <span className="text-lg font-medium text-slate-100">
                {f.question}
              </span>
              {active === i
                ? <FiMinus className="text-cyan-400 text-xl" />
                : <FiPlus  className="text-cyan-400 text-xl" />}
            </button>
            <div
              ref={(el) => (contentRefs.current[i] = el)}
              className="overflow-hidden px-6 text-slate-300 transition-max-height duration-300"
              style={{ maxHeight: 0 }}
            >
              <p className="py-2">{f.answer}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default FAQ;
