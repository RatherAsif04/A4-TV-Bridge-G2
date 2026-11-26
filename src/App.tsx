import React, { useMemo, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Download,
  FileCode2,
  Moon,
  Sun,
  Wifi,
  WifiOff
} from 'lucide-react';
import {
  AUDIT_ISSUES,
  SCREEN_META,
  generateUnifiedPatch,
  type ScreenId
} from './data/auditReport';
import { PhoneSimulator, type SimBuild, type SimTheme } from './components/PhoneSimulator';
import { GifStudio } from './components/GifStudio';
import { CodeFixesView } from './components/CodeFixesView';
import { PublishRepoView } from './components/PublishRepoView';
import { ChatAssistantView } from './components/ChatAssistantView';

type MainTab = 'preview' | 'gifs' | 'code' | 'publish' | 'assistant';

const QUICK_SCENARIOS: { label: string; screen: ScreenId; theme: SimTheme; connected: boolean }[] = [
  { label: 'Dark Dashboard & Storage', screen: 'DASHBOARD', theme: 'dark', connected: true },
  { label: 'Light Metrics Contrast', screen: 'DASHBOARD', theme: 'light', connected: true },
  { label: 'Welcome & Connect Sheet', screen: 'WELCOME', theme: 'dark', connected: false },
  { label: 'Remote D-pad & Queue', screen: 'REMOTE', theme: 'dark', connected: true },
  { label: 'Apps Tabs & Running', screen: 'APPS', theme: 'dark', connected: true },
  { label: 'File Manager (Toybox mv)', screen: 'FILES', theme: 'dark', connected: true },
  { label: '1-Click App Install', screen: 'INSTALL', theme: 'dark', connected: true },
  { label: 'ADB Shell Persistent Dock', screen: 'SHELL', theme: 'dark', connected: true },
  { label: 'Clean Up Dark Dropdown', screen: 'CLEAN', theme: 'dark', connected: true },
  { label: 'Device Info Gauges', screen: 'INFO', theme: 'dark', connected: true }
];

export default function App() {
  const [activeTab, setActiveTab] = useState<MainTab>('preview');
  const [workbenchDark, setWorkbenchDark] = useState(false);

  // Simulator controls
  const [simBuild, setSimBuild] = useState<SimBuild>('fixed');
  const [simTheme, setSimTheme] = useState<SimTheme>('dark');
  const [simConnected, setSimConnected] = useState(true);
  const [simScreen, setSimScreen] = useState<ScreenId>('DASHBOARD');

  const toggleWorkbenchTheme = () => {
    setWorkbenchDark((prev) => {
      const next = !prev;
      document.documentElement.classList.toggle('dark', next);
      return next;
    });
  };

  const handleDownloadPatch = () => {
    const content = generateUnifiedPatch();
    const blob = new Blob([content], { type: 'text/x-patch;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'a4-tv-bridge-fixes.patch';
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  const screenIssues = useMemo(
    () => AUDIT_ISSUES.filter((issue) => issue.screens.includes(simScreen)),
    [simScreen]
  );

  const meta = SCREEN_META[simScreen];

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 transition-colors">
      {/* Strict 3-Zone Top Bar Contract */}
      <header className="sticky top-0 z-30 bg-white/95 dark:bg-slate-950/95 backdrop-blur-md border-b border-slate-200 dark:border-slate-800">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between gap-8">
          {/* Zone 1: Single text element wordmark */}
          <button
            onClick={() => setActiveTab('preview')}
            className="text-base sm:text-lg font-display font-bold tracking-tight text-slate-900 dark:text-slate-100 whitespace-nowrap shrink-0 cursor-pointer"
          >
            A4 TV Bridge Audit
          </button>

          {/* Zone 2: Clean single-line text navigation links */}
          <nav className="flex items-center gap-5 sm:gap-7 text-sm font-medium overflow-x-auto">
            {(
              [
                { id: 'preview', label: 'Interactive Preview' },
                { id: 'gifs', label: 'GIF Studio' },
                { id: 'code', label: 'Code Fixes' },
                { id: 'publish', label: 'Publish Repo' },
                { id: 'assistant', label: 'TV Assistant' }
              ] as const
            ).map((nav) => {
              const active = activeTab === nav.id;
              return (
                <button
                  key={nav.id}
                  onClick={() => setActiveTab(nav.id)}
                  className={`py-1 border-b-2 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                    active
                      ? 'border-[#00587B] dark:border-[#B0DEFF] text-slate-900 dark:text-slate-100 font-semibold'
                      : 'border-transparent text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
                  }`}
                >
                  {nav.label}
                </button>
              );
            })}
          </nav>

          {/* Zone 3: 1 primary CTA action */}
          <div className="shrink-0">
            <button
              onClick={handleDownloadPatch}
              className="px-4 py-2 text-xs font-semibold text-white bg-[#00587B] hover:bg-[#004663] rounded-lg transition-colors flex items-center gap-1.5 whitespace-nowrap shrink-0 cursor-pointer"
            >
              <Download className="w-3.5 h-3.5" />
              Download Patch
            </button>
          </div>
        </div>
      </header>

      {/* Main Content Container */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {activeTab === 'preview' && (
          <div className="space-y-6">
            {/* Workspace Toolbar */}
            <div className="flex flex-col xl:flex-row xl:items-center justify-between gap-4 pb-6 border-b border-slate-200 dark:border-slate-800">
              <div>
                <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-slate-900 dark:text-slate-100 [text-wrap:balance]">
                  Interactive APK Fix Preview
                </h1>
                <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-slate-400 tabular-nums">
                  <span>RatherAsif04/A4-TV-Bridge-G2</span>
                  <span aria-hidden="true">·</span>
                  <span>6 Theme Fixes</span>
                  <span aria-hidden="true">·</span>
                  <span>8 Feature &amp; ADB Fixes</span>
                  <span aria-hidden="true">·</span>
                  <span>Flip Before / After to test live</span>
                </div>
              </div>

              {/* Build / Theme / Connection Controls */}
              <div className="flex flex-wrap items-center gap-2.5">
                <div className="flex items-center gap-1 p-1 bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-lg">
                  <button
                    onClick={() => setSimBuild('broken')}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                      simBuild === 'broken'
                        ? 'bg-red-600 text-white shadow-xs'
                        : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
                    }`}
                  >
                    Before (v1.0.0 Broken)
                  </button>
                  <button
                    onClick={() => setSimBuild('fixed')}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                      simBuild === 'fixed'
                        ? 'bg-emerald-700 text-white shadow-xs'
                        : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
                    }`}
                  >
                    After (Fixed)
                  </button>
                </div>

                <div className="flex items-center gap-1 p-1 bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-lg">
                  <button
                    onClick={() => setSimTheme('light')}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                      simTheme === 'light'
                        ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                        : 'text-slate-600 dark:text-slate-400'
                    }`}
                  >
                    Phone Light
                  </button>
                  <button
                    onClick={() => setSimTheme('dark')}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                      simTheme === 'dark' || simTheme === 'system'
                        ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                        : 'text-slate-600 dark:text-slate-400'
                    }`}
                  >
                    Phone Dark
                  </button>
                </div>

                <button
                  onClick={() => setSimConnected((c) => !c)}
                  className="px-3 py-2 text-xs font-semibold rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 flex items-center gap-1.5 cursor-pointer whitespace-nowrap shrink-0"
                >
                  {simConnected ? <Wifi className="w-3.5 h-3.5 text-emerald-600" /> : <WifiOff className="w-3.5 h-3.5 text-slate-400" />}
                  {simConnected ? 'TV Connected' : 'Disconnected'}
                </button>

                <button
                  onClick={toggleWorkbenchTheme}
                  aria-label="Toggle studio theme"
                  className="p-2 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 cursor-pointer shrink-0"
                >
                  {workbenchDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                </button>
              </div>
            </div>

            {/* 2-Column Simulator + Live Screen Inspector */}
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
              {/* Left Column: Live Interactive Android Device */}
              <div className="lg:col-span-5 flex flex-col items-center">
                <PhoneSimulator
                  build={simBuild}
                  theme={simTheme}
                  onThemeChange={setSimTheme}
                  connected={simConnected}
                  onConnectedChange={setSimConnected}
                  screen={simScreen}
                  onScreenChange={setSimScreen}
                />
              </div>

              {/* Right Column: Live Screen Inspector & Bug Scenarios */}
              <div className="lg:col-span-7 space-y-6">
                {/* Quick Scenario Jump Bar */}
                <section className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-5">
                  <h2 className="text-sm font-bold text-slate-900 dark:text-slate-100 mb-3">
                    Jump to Bug Test Scenarios
                  </h2>
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                    {QUICK_SCENARIOS.map((sc) => {
                      const active = simScreen === sc.screen && simTheme === sc.theme && simConnected === sc.connected;
                      return (
                        <button
                          key={sc.label}
                          onClick={() => {
                            setSimConnected(sc.connected);
                            setSimTheme(sc.theme);
                            setSimScreen(sc.screen);
                          }}
                          className={`px-3 py-2 text-left text-xs font-medium rounded-lg border transition-colors cursor-pointer truncate ${
                            active
                              ? 'border-[#00587B] dark:border-[#B0DEFF] bg-[#00587B]/[0.07] dark:bg-[#B0DEFF]/10 text-[#00587B] dark:text-[#B0DEFF] font-semibold'
                              : 'border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800'
                          }`}
                        >
                          {sc.label}
                        </button>
                      );
                    })}
                  </div>
                </section>

                {/* Active Screen Context & Fixes */}
                <section className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 space-y-5">
                  <div className="flex flex-wrap items-start justify-between gap-3 pb-4 border-b border-slate-100 dark:border-slate-800">
                    <div>
                      <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">
                        {meta.label}
                      </h2>
                      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
                        {meta.description}
                      </p>
                      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs font-mono text-slate-500 dark:text-slate-400">
                        <FileCode2 className="w-3.5 h-3.5 shrink-0" />
                        {meta.kotlinFiles.map((file, idx) => (
                          <React.Fragment key={file}>
                            {idx > 0 && <span aria-hidden="true">·</span>}
                            <span>{file}</span>
                          </React.Fragment>
                        ))}
                      </div>
                    </div>

                    <div className="text-xs font-semibold flex items-center gap-1.5 shrink-0">
                      {simBuild === 'broken' ? (
                        <span className="text-red-600 dark:text-red-400 flex items-center gap-1.5">
                          <AlertTriangle className="w-4 h-4" />
                          Showing v1.0.0 Bugs
                        </span>
                      ) : (
                        <span className="text-emerald-700 dark:text-emerald-400 flex items-center gap-1.5">
                          <CheckCircle2 className="w-4 h-4" />
                          All Fixes Active
                        </span>
                      )}
                    </div>
                  </div>

                  {screenIssues.length === 0 ? (
                    <p className="text-sm text-slate-500 dark:text-slate-400">
                      No screen-specific regressions here. Use the scenario buttons above or switch to GIF Studio and Code Fixes to inspect all 14 fixes.
                    </p>
                  ) : (
                    <div className="divide-y divide-slate-100 dark:divide-slate-800">
                      {screenIssues.map((issue) => (
                        <div key={issue.id} className="py-4 first:pt-0 last:pb-0 space-y-2">
                          <div className="flex items-center justify-between gap-2 text-xs text-slate-500 dark:text-slate-400">
                            <span className="font-mono">
                              {issue.category === 'theme' ? 'Theme & UI' : 'Feature / ADB'} · {issue.file.split('/').pop()} {issue.lines.split(' ')[0]}
                            </span>
                            <span
                              className={`font-semibold ${
                                issue.severity === 'critical'
                                  ? 'text-red-600 dark:text-red-400'
                                  : 'text-amber-600 dark:text-amber-400'
                              }`}
                            >
                              {issue.severity === 'critical' ? 'Critical' : issue.severity === 'high' ? 'High' : 'Medium'}
                            </span>
                          </div>
                          <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100">
                            {issue.title}
                          </h3>
                          <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed">
                            {simBuild === 'broken' ? issue.rootCause : issue.fixSummary}
                          </p>
                        </div>
                      ))}
                    </div>
                  )}

                  <div className="pt-4 border-t border-slate-100 dark:border-slate-800 flex flex-wrap items-center justify-between gap-3">
                    <button
                      onClick={() => setActiveTab('gifs')}
                      className="text-xs font-semibold text-[#00587B] dark:text-[#B0DEFF] hover:underline cursor-pointer"
                    >
                      Open GIF Studio to download GitHub demos →
                    </button>
                    <button
                      onClick={() => setActiveTab('code')}
                      className="text-xs font-semibold text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-100 cursor-pointer"
                    >
                      Inspect full Kotlin/XML diffs →
                    </button>
                  </div>
                </section>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'gifs' && <GifStudio />}

        {activeTab === 'code' && (
          <CodeFixesView
            onDownloadPatch={handleDownloadPatch}
            onJumpToScreen={(screen, build) => {
              setSimBuild(build);
              setSimConnected(screen !== 'WELCOME');
              setSimScreen(screen);
              setActiveTab('preview');
            }}
          />
        )}

        {activeTab === 'publish' && <PublishRepoView />}

        {activeTab === 'assistant' && <ChatAssistantView />}
      </main>
    </div>
  );
}
