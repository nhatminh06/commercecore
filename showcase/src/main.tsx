import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AppShell } from './components/AppShell';
import { Overview } from './pages/Overview';
import { Walkthrough } from './pages/Walkthrough';
import { Architecture } from './pages/Architecture';
import { Lab } from './pages/Lab';
import { Debugger } from './pages/Debugger';
import { Evidence } from './pages/Evidence';
import { NotFound } from './pages/NotFound';
import './styles/global.css';

const pages = { '/': Overview, '/walkthrough': Walkthrough, '/architecture': Architecture,
  '/lab': Lab, '/lab/debugger': Debugger, '/evidence': Evidence };
const path = window.location.pathname.replace(/\/$/, '') || '/';
const Page = pages[path as keyof typeof pages] ?? NotFound;

createRoot(document.getElementById('root')!).render(<StrictMode><AppShell><Page /></AppShell></StrictMode>);
