import React, { useMemo, useState } from 'react';
import { Check, Copy, Download, FileCode2, Search } from 'lucide-react';
import {
  AUDIT_ISSUES,
  FIXED_PATCHES,
  type AuditIssue,
  type BugCategory,
  type ScreenId
} from '../data/auditReport';

interface CodeFixesViewProps {
  onDownloadPatch: () => void;
  onJumpToScreen: (screen: ScreenId, build: 'broken' | 'fixed') => void;
}

export const CodeFixesView: React.FC<CodeFixesViewProps> = ({
  onDownloadPatch,
  onJumpToScreen
}) => {
  const [category, setCategory] = useState<'all' | BugCategory>('all');
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<string>(AUDIT_ISSUES[0].id);
  const [copiedPath, setCopiedPath] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return AUDIT_ISSUES.filter((issue) => {
      if (category !== 'all' && issue.category !== category) return false;
      if (!q) return true;
      return (
        issue.title.toLowerCase().includes(q) ||
        issue.file.toLowerCase().includes(q) ||
        issue.summary.toLowerCase().includes(q)
      );
    });
  }, [category, query]);

  const activeIssue: AuditIssue =
    filtered.find((i) => i.id === selectedId) || filtered[0] || AUDIT_ISSUES[0];

  const matchingPatch = FIXED_PATCHES.find((p) => p.issueIds.includes(activeIssue.id));

  const themeCount = AUDIT_ISSUES.filter((i) => i.category === 'theme').length;
  const featureCount = AUDIT_ISSUES.filter((i) => i.category === 'feature').length;

  const copyText = (key: string, text: string) => {
    navigator.clipboard?.writeText(text);
    setCopiedPath(key);
    window.setTimeout(() => setCopiedPath(null), 1800);
  };

  return (
    <div className="space-y-6">
      {/* Audit Summary Header */}
      <div className="flex flex-col lg:flex-row lg:items-end justify-between gap-4 pb-6 border-b border-slate-200 dark:border-slate-800">
        <div>
          <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-slate-900 dark:text-slate-100">
            Code Audit &amp; Kotlin Patches
          </h1>
          <div className="mt-2 flex flex-wrap items-center gap-2 text-sm text-slate-600 dark:text-slate-400 tabular-nums">
            <span>{AUDIT_ISSUES.length} confirmed issues in RatherAsif04/A4-TV-Bridge-Test</span>
            <span aria-hidden="true">·</span>
            <span>{themeCount} theme &amp; UI defects</span>
            <span aria-hidden="true">·</span>
            <span>{featureCount} feature &amp; ADB bugs</span>
            <span aria-hidden="true">·</span>
            <span>{FIXED_PATCHES.length} patched source files</span>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-1 p-1 bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-lg">
            {(
              [
                { id: 'all', label: `All (${AUDIT_ISSUES.length})` },
                { id: 'theme', label: `Theme & UI (${themeCount})` },
                { id: 'feature', label: `Features & ADB (${featureCount})` }
              ] as const
            ).map((tab) => (
              <button
                key={tab.id}
                onClick={() => setCategory(tab.id)}
                className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                  category === tab.id
                    ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                    : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>

          <button
            onClick={onDownloadPatch}
            className="px-4 py-2 text-xs font-semibold rounded-lg bg-[#00587B] text-white hover:bg-[#004663] transition-colors flex items-center gap-1.5 cursor-pointer whitespace-nowrap shrink-0"
          >
            <Download className="w-3.5 h-3.5" />
            Download git apply .patch
          </button>
        </div>
      </div>

      {/* 2-Column Split: Issue List + Code Diff Inspector */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left List */}
        <div className="lg:col-span-5 space-y-3">
          <div className="relative">
            <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Filter by file, bug, or screen…"
              className="w-full pl-9 pr-3 py-2 text-sm bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl outline-none focus:border-[#00587B] dark:focus:border-[#B0DEFF]"
            />
          </div>

          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl divide-y divide-slate-100 dark:divide-slate-800 overflow-hidden">
            {filtered.map((issue, idx) => {
              const selected = issue.id === activeIssue.id;
              return (
                <button
                  key={issue.id}
                  onClick={() => setSelectedId(issue.id)}
                  className={`w-full text-left p-4 transition-colors cursor-pointer ${
                    selected
                      ? 'bg-[#00587B]/[0.07] dark:bg-[#B0DEFF]/[0.08]'
                      : 'hover:bg-slate-50 dark:hover:bg-slate-800/50'
                  }`}
                >
                  <div className="flex items-center justify-between gap-2 text-xs text-slate-500 dark:text-slate-400">
                    <span className="font-mono tabular-nums">
                      {String(idx + 1).padStart(2, '0')} · {issue.category === 'theme' ? 'Theme & UI' : 'Feature / ADB'}
                    </span>
                    <span
                      className={`font-semibold ${
                        issue.severity === 'critical'
                          ? 'text-red-600 dark:text-red-400'
                          : issue.severity === 'high'
                            ? 'text-amber-600 dark:text-amber-400'
                            : 'text-slate-500 dark:text-slate-400'
                      }`}
                    >
                      {issue.severity === 'critical' ? 'Critical' : issue.severity === 'high' ? 'High' : 'Medium'}
                    </span>
                  </div>
                  <div className="mt-1 text-sm font-semibold text-slate-900 dark:text-slate-100 leading-snug">
                    {issue.title}
                  </div>
                  <div className="mt-1.5 text-xs font-mono text-slate-500 dark:text-slate-400 truncate">
                    {issue.file}
                  </div>
                </button>
              );
            })}
          </div>
        </div>

        {/* Right Detail & Diff Pane */}
        <div className="lg:col-span-7 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-6 space-y-6">
          <div>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="text-xs font-mono text-slate-500 dark:text-slate-400 flex items-center gap-1.5">
                <FileCode2 className="w-4 h-4 text-[#00587B] dark:text-[#B0DEFF]" />
                <span>{activeIssue.file}</span>
                <span aria-hidden="true">·</span>
                <span className="tabular-nums">{activeIssue.lines}</span>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => onJumpToScreen(activeIssue.screens[0], 'broken')}
                  className="px-3 py-1.5 text-xs font-semibold rounded-lg border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 cursor-pointer whitespace-nowrap"
                >
                  Reproduce Bug
                </button>
                <button
                  onClick={() => onJumpToScreen(activeIssue.screens[0], 'fixed')}
                  className="px-3 py-1.5 text-xs font-semibold rounded-lg bg-[#00587B] text-white hover:bg-[#004663] cursor-pointer whitespace-nowrap"
                >
                  Preview Fix
                </button>
              </div>
            </div>

            <h2 className="mt-3 text-xl font-bold text-slate-900 dark:text-slate-100 leading-snug">
              {activeIssue.title}
            </h2>
            <p className="mt-2 text-sm text-slate-600 dark:text-slate-300 leading-relaxed">
              {activeIssue.summary}
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-4 border-t border-slate-100 dark:border-slate-800">
            <div>
              <h3 className="text-xs font-bold text-slate-900 dark:text-slate-100 mb-1.5">Root Cause</h3>
              <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed">
                {activeIssue.rootCause}
              </p>
            </div>
            <div>
              <h3 className="text-xs font-bold text-slate-900 dark:text-slate-100 mb-1.5">Implemented Fix</h3>
              <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed">
                {activeIssue.fixSummary}
              </p>
            </div>
          </div>

          {/* Before / After Kotlin Snippets */}
          <div className="space-y-4">
            <div>
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-xs font-semibold text-red-600 dark:text-red-400">Before (v1.0.0)</span>
              </div>
              <pre className="p-3.5 rounded-xl bg-slate-950 text-red-200/95 text-xs font-mono overflow-x-auto thin-scroll leading-relaxed border border-slate-800">
                {activeIssue.beforeCode}
              </pre>
            </div>

            <div>
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-xs font-semibold text-emerald-700 dark:text-emerald-400">After (Corrected)</span>
                <button
                  onClick={() => copyText(activeIssue.id, activeIssue.afterCode)}
                  className="text-xs font-semibold text-slate-500 hover:text-slate-900 dark:hover:text-slate-200 flex items-center gap-1 cursor-pointer"
                >
                  {copiedPath === activeIssue.id ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
                  {copiedPath === activeIssue.id ? 'Copied' : 'Copy snippet'}
                </button>
              </div>
              <pre className="p-3.5 rounded-xl bg-slate-950 text-emerald-200/95 text-xs font-mono overflow-x-auto thin-scroll leading-relaxed border border-slate-800">
                {activeIssue.afterCode}
              </pre>
            </div>

            {matchingPatch && (
              <div className="pt-4 border-t border-slate-100 dark:border-slate-800">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-bold text-slate-900 dark:text-slate-100">
                    Unified Git Patch · {matchingPatch.path}
                  </span>
                  <button
                    onClick={() => copyText(matchingPatch.path, matchingPatch.diff)}
                    className="text-xs font-semibold text-[#00587B] dark:text-[#B0DEFF] flex items-center gap-1 cursor-pointer"
                  >
                    {copiedPath === matchingPatch.path ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                    {copiedPath === matchingPatch.path ? 'Copied diff' : 'Copy file diff'}
                  </button>
                </div>
                <pre className="p-3.5 rounded-xl bg-slate-950 text-slate-200 text-[11.5px] font-mono overflow-x-auto thin-scroll leading-relaxed border border-slate-800 max-h-[260px]">
                  {matchingPatch.diff}
                </pre>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
