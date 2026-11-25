import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Check, Copy, Download, Film, RefreshCw } from 'lucide-react';
import {
  GIF_PRESETS,
  generateDemoGif,
  type GeneratedGif,
  type GifBuildOptions,
  type GifPresetId
} from '../utils/gifGenerator';

function formatKb(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  return `${Math.round(bytes / 1024)} KB`;
}

export const GifStudio: React.FC = () => {
  const [mode, setMode] = useState<'compare' | 'fixed'>('compare');
  const [speed, setSpeed] = useState<'normal' | 'fast'>('normal');
  const [gifs, setGifs] = useState<Partial<Record<GifPresetId, GeneratedGif>>>({});
  const [rendering, setRendering] = useState(false);
  const [progress, setProgress] = useState(0);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [copiedAll, setCopiedAll] = useState(false);
  const urlsRef = useRef<string[]>([]);

  const buildAll = useCallback(async (opts: GifBuildOptions) => {
    setRendering(true);
    setProgress(0);

    // Revoke previous object URLs
    urlsRef.current.forEach((u) => URL.revokeObjectURL(u));
    urlsRef.current = [];
    setGifs({});

    for (let i = 0; i < GIF_PRESETS.length; i++) {
      const preset = GIF_PRESETS[i];
      try {
        const result = await generateDemoGif(preset.id, opts);
        urlsRef.current.push(result.url);
        setGifs((prev) => ({ ...prev, [preset.id]: result }));
        setProgress(i + 1);
      } catch {
        // Keep moving through remaining presets
      }
    }
    setRendering(false);
  }, []);

  useEffect(() => {
    buildAll({ mode, speed });
    return () => {
      urlsRef.current.forEach((u) => URL.revokeObjectURL(u));
      urlsRef.current = [];
    };
  }, [mode, speed, buildAll]);

  const downloadGif = (presetId: GifPresetId, filename: string) => {
    const g = gifs[presetId];
    if (!g) return;
    const a = document.createElement('a');
    a.href = g.url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
  };

  const downloadAllGifs = async () => {
    for (const preset of GIF_PRESETS) {
      const g = gifs[preset.id];
      if (!g) continue;
      downloadGif(preset.id, preset.filename);
      await new Promise((r) => setTimeout(r, 220));
    }
  };

  const copyMarkdown = (presetId: GifPresetId, filename: string, alt: string) => {
    const md = `![${alt}](docs/gifs/${filename})`;
    navigator.clipboard?.writeText(md);
    setCopiedId(presetId);
    window.setTimeout(() => setCopiedId(null), 1800);
  };

  const copyAllMarkdown = () => {
    const md = GIF_PRESETS.map(
      (p) => `### ${p.title}\n\n![${p.markdownAlt}](docs/gifs/${p.filename})\n`
    ).join('\n');
    navigator.clipboard?.writeText(md);
    setCopiedAll(true);
    window.setTimeout(() => setCopiedAll(false), 2000);
  };

  const readyCount = Object.keys(gifs).length;

  return (
    <div className="space-y-8">
      {/* Studio Controls Header */}
      <div className="flex flex-col lg:flex-row lg:items-end justify-between gap-6 pb-6 border-b border-slate-200 dark:border-slate-800">
        <div className="max-w-2xl">
          <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-slate-900 dark:text-slate-100 [text-wrap:balance]">
            GitHub Demo GIFs
          </h1>
          <p className="mt-2 text-[15px] text-slate-600 dark:text-slate-400 leading-relaxed">
            Real looping GIF89a animations encoded directly in your browser. Inspect every theme and feature fix visually, then download the files for your GitHub PR or README.
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-slate-400 tabular-nums">
            <span>{readyCount} of {GIF_PRESETS.length} GIFs encoded</span>
            <span aria-hidden="true">·</span>
            <span>{mode === 'compare' ? '560 × 430 px side-by-side' : '300 × 430 px fixed view'}</span>
            <span aria-hidden="true">·</span>
            <span>18 frames · infinite loop</span>
          </div>
        </div>

        {/* Workspace Toolbar Controls */}
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-1 p-1 bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-lg">
            <button
              onClick={() => setMode('compare')}
              className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                mode === 'compare'
                  ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
              }`}
            >
              Before vs After
            </button>
            <button
              onClick={() => setMode('fixed')}
              className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                mode === 'fixed'
                  ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
              }`}
            >
              Fixed Only
            </button>
          </div>

          <div className="flex items-center gap-1 p-1 bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-lg">
            <button
              onClick={() => setSpeed('normal')}
              className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                speed === 'normal'
                  ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
              }`}
            >
              Normal Speed
            </button>
            <button
              onClick={() => setSpeed('fast')}
              className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                speed === 'fast'
                  ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
              }`}
            >
              Fast
            </button>
          </div>

          <button
            onClick={copyAllMarkdown}
            className="px-3.5 py-2 text-xs font-semibold rounded-lg border border-slate-300 dark:border-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors flex items-center gap-1.5 cursor-pointer whitespace-nowrap shrink-0"
          >
            {copiedAll ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
            {copiedAll ? 'Copied README Markdown' : 'Copy README Markdown'}
          </button>

          <button
            onClick={downloadAllGifs}
            disabled={readyCount === 0}
            className="px-4 py-2 text-xs font-semibold rounded-lg bg-[#00587B] text-white hover:bg-[#004663] disabled:opacity-50 transition-colors flex items-center gap-1.5 cursor-pointer whitespace-nowrap shrink-0"
          >
            <Download className="w-3.5 h-3.5" />
            Download All GIFs
          </button>
        </div>
      </div>

      {/* GIF Cards Grid */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {GIF_PRESETS.map((preset, index) => {
          const generated = gifs[preset.id];
          return (
            <article
              key={preset.id}
              className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl overflow-hidden flex flex-col"
            >
              {/* Animated GIF Preview Stage */}
              <div className="bg-slate-950 flex items-center justify-center p-4 min-h-[340px] border-b border-slate-200 dark:border-slate-800">
                {generated ? (
                  <img
                    src={generated.url}
                    alt={preset.markdownAlt}
                    className="max-h-[380px] w-auto rounded-xl object-contain"
                  />
                ) : (
                  <div className="flex flex-col items-center gap-2 text-slate-400 text-xs">
                    <RefreshCw className="w-5 h-5 animate-spin text-[#B0DEFF]" />
                    <span className="tabular-nums">
                      Encoding {preset.filename} ({progress}/{GIF_PRESETS.length})…
                    </span>
                  </div>
                )}
              </div>

              {/* Card Footer */}
              <div className="p-5 flex-1 flex flex-col justify-between gap-4">
                <div>
                  <div className="flex items-center justify-between gap-3">
                    <h2 className="text-base font-bold text-slate-900 dark:text-slate-100">
                      0{index + 1}. {preset.title}
                    </h2>
                    <span className="text-xs font-mono text-slate-500 dark:text-slate-400 tabular-nums shrink-0">
                      {generated ? formatKb(generated.bytes) : 'Encoding…'}
                    </span>
                  </div>
                  <p className="mt-1.5 text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
                    {preset.subtitle}
                  </p>
                  <div className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500 dark:text-slate-400">
                    {preset.highlights.map((h, i) => (
                      <React.Fragment key={h}>
                        {i > 0 && <span aria-hidden="true">·</span>}
                        <span>{h}</span>
                      </React.Fragment>
                    ))}
                  </div>
                </div>

                <div className="pt-3 border-t border-slate-100 dark:border-slate-800 flex flex-wrap items-center justify-between gap-3">
                  <span className="text-xs font-mono text-slate-500 dark:text-slate-400 flex items-center gap-1.5 truncate">
                    <Film className="w-3.5 h-3.5 shrink-0" />
                    {preset.filename}
                  </span>
                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      onClick={() => copyMarkdown(preset.id, preset.filename, preset.markdownAlt)}
                      className="px-3 py-1.5 text-xs font-semibold rounded-lg border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors flex items-center gap-1.5 cursor-pointer whitespace-nowrap"
                    >
                      {copiedId === preset.id ? (
                        <Check className="w-3.5 h-3.5 text-emerald-600" />
                      ) : (
                        <Copy className="w-3.5 h-3.5" />
                      )}
                      {copiedId === preset.id ? 'Copied' : 'Markdown'}
                    </button>
                    <button
                      disabled={!generated || rendering}
                      onClick={() => downloadGif(preset.id, preset.filename)}
                      className="px-3.5 py-1.5 text-xs font-semibold rounded-lg bg-[#00587B] text-white hover:bg-[#004663] disabled:opacity-50 transition-colors flex items-center gap-1.5 cursor-pointer whitespace-nowrap"
                    >
                      <Download className="w-3.5 h-3.5" />
                      Download .gif
                    </button>
                  </div>
                </div>
              </div>
            </article>
          );
        })}
      </div>
    </div>
  );
};
