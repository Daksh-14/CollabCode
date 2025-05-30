import React from 'react';
import { FaGithub, FaTwitter, FaEnvelope, FaDiscord } from 'react-icons/fa';

const Footer = () => (
  <footer className="bg-slate-800 border-t border-slate-700 text-slate-400 py-10">
    <div className="max-w-6xl mx-auto grid grid-cols-1 md:grid-cols-3 gap-8 px-6">
      {/* About */}
      <div>
        <h4 className="text-slate-100 font-semibold mb-3">About CodeCollab</h4>
        <p className="text-sm">
          A real‑time collaborative code editor with secure execution and instant sync.
        </p>
      </div>

      {/* Quick Links */}
      <div>
        <h4 className="text-slate-100 font-semibold mb-3">Quick Links</h4>
        <ul className="space-y-2 text-sm">
          <li><a href="/docs"     className="hover:text-cyan-400 transition">Documentation</a></li>
          <li><a href="/features" className="hover:text-cyan-400 transition">Features</a></li>
          <li><a href="/faq"      className="hover:text-cyan-400 transition">FAQ</a></li>
          <li><a href="/contact"  className="hover:text-cyan-400 transition">Contact Us</a></li>
        </ul>
      </div>

      {/* Newsletter & Social */}
      <div>
        <h4 className="text-slate-100 font-semibold mb-3">Stay in Touch</h4>
        <div className="flex items-center space-x-2 mb-4">
          <FaEnvelope className="text-cyan-400" />
          <input
            type="email"
            placeholder="Your email"
            className="flex-1 bg-slate-900 border border-slate-700 rounded px-3 py-2 text-slate-100 focus:outline-none focus:ring-2 focus:ring-cyan-400"
          />
        </div>
        <button className="bg-cyan-500 hover:bg-cyan-600 text-slate-900 font-semibold py-2 px-4 rounded transition mb-4">
          Subscribe
        </button>
        <div className="flex space-x-4">
          <a href="https://github.com/your-repo" aria-label="GitHub" className="hover:text-cyan-400 transition">
            <FaGithub size={20} />
          </a>
          <a href="https://twitter.com/your-handle" aria-label="Twitter" className="hover:text-cyan-400 transition">
            <FaTwitter size={20} />
          </a>
          <a href="https://discord.gg/your-server" aria-label="Discord" className="hover:text-cyan-400 transition">
            <FaDiscord size={20} />
          </a>
        </div>
      </div>
    </div>

    <div className="mt-10 text-center text-xs text-slate-500">
      © 2025 CodeCollab. All rights reserved.
    </div>
  </footer>
);

export default Footer;
