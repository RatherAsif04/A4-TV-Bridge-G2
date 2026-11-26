import React, { useEffect, useRef, useState } from 'react';
import {
  AlertCircle,
  Check,
  Code2,
  Copy,
  MessageSquare,
  RotateCcw,
  Send,
  Square,
  Terminal,
  Wrench,
  Zap
} from 'lucide-react';

type ModelId = 'gemini-4-hydrogen' | 'gemini-3.1-flash-lite';
type RoleId = 'adb-troubleshooter' | 'shell-helper' | 'kotlin-auditor';

interface ChatMessage {
  id: string;
  role: 'user' | 'model';
  text: string;
  model?: ModelId;
  timestamp: string;
}

interface RolePreset {
  id: RoleId;
  title: string;
  shortLabel: string;
  summary: string;
  defaultModel: ModelId;
  icon: React.ComponentType<{ className?: string }>;
  prompts: string[];
}

const ROLE_PRESETS: RolePreset[] = [
  {
    id: 'adb-troubleshooter',
    title: 'ADB & TV Troubleshooter',
    shortLabel: 'Troubleshooter',
    summary: 'Wireless ADB pairing, LAN scan fixes, CleanYourTV package safety, and Private DNS setup.',
    defaultModel: 'gemini-4-hydrogen',
    icon: Wrench,
    prompts: [
      'My Android TV on Wi-Fi shows "Connection refused" on port 5555. How do I pair it?',
      'Which pre-installed Android TV packages are safe to disable without breaking the launcher?',
      'How do I set up AdGuard Private DNS on Android TV and revert it if Wi-Fi stops working?'
    ]
  },
  {
    id: 'shell-helper',
    title: 'Quick ADB Command Helper',
    shortLabel: 'Shell Commands',
    summary: 'Fast, Toybox-safe Android 8–15 shell one-liners ready to paste into ADB Terminal.',
    defaultModel: 'gemini-3.1-flash-lite',
    icon: Terminal,
    prompts: [
      'Give me a Toybox-safe command to list all disabled user-0 packages on Android TV.',
      'How do I check remaining /data storage and top RAM-using apps via ADB shell?',
      'What keyevent codes trigger Home, Back, Volume Up, Mute, and Play/Pause?'
    ]
  },
  {
    id: 'kotlin-auditor',
    title: 'Kotlin & APK Build Advisor',
    shortLabel: 'Kotlin & CI',
    summary: 'A4 TV Bridge Kotlin Views code, kadb 2.1.1, Gradle SDK 36 builds, and .p12 APK signing.',
    defaultModel: 'gemini-4-hydrogen',
    icon: Code2,
    prompts: [
      'Explain why signing a public release APK with a .p12 keystore is required for future app updates.',
      'How does A4 TV Bridge keep the ADB connection alive across screen rotation and dark mode switches?',
      'Why does kadb 2.1.1 align better with compileSdk 36 and AGP 9.1.1 than kadb 2.1.5?'
    ]
  }
];

const STORAGE_KEY = 'a4-tv-bridge-chat-v1';

function formatTime(): string {
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

interface ParsedBlock {
  type: 'code' | 'text';
  lang?: string;
  content: string;
}

function splitMarkdownBlocks(raw: string): ParsedBlock[] {
  const blocks: ParsedBlock[] = [];
  const regex = /```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = regex.exec(raw)) !== null) {
    if (match.index > lastIndex) {
      blocks.push({ type: 'text', content: raw.slice(lastIndex, match.index) });
    }
    blocks.push({
      type: 'code',
      lang: match[1] || 'sh',
      content: match[2].replace(/\n$/, '')
    });
    lastIndex = regex.lastIndex;
  }

  if (lastIndex < raw.length) {
    blocks.push({ type: 'text', content: raw.slice(lastIndex) });
  }

  return blocks;
}

function renderInlineText(text: string): React.ReactNode[] {
  const parts = text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g);
  return parts.map((segment, index) => {
    if (segment.startsWith('`') && segment.endsWith('`') && segment.length > 2) {
      return (
        <code
          key={index}
          className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 font-mono text-[12px] text-slate-900 dark:text-slate-100"
        >
          {segment.slice(1, -1)}
        </code>
      );
    }
    if (segment.startsWith('**') && segment.endsWith('**') && segment.length > 4) {
      return (
        <strong key={index} className="font-semibold text-slate-900 dark:text-slate-100">
          {segment.slice(2, -2)}
        </strong>
      );
    }
    return <React.Fragment key={index}>{segment}</React.Fragment>;
  });
}

export const ChatAssistantView: React.FC = () => {
  const [roleId, setRoleId] = useState<RoleId>('adb-troubleshooter');
  const [model, setModel] = useState<ModelId>('gemini-4-hydrogen');
  const [messagesByRole, setMessagesByRole] = useState<Record<RoleId, ChatMessage[]>>(() => {
    try {
      const saved = window.localStorage.getItem(STORAGE_KEY);
      if (saved) {
        const parsed = JSON.parse(saved) as Partial<Record<RoleId, ChatMessage[]>>;
        return {
          'adb-troubleshooter': Array.isArray(parsed['adb-troubleshooter']) ? parsed['adb-troubleshooter'] : [],
          'shell-helper': Array.isArray(parsed['shell-helper']) ? parsed['shell-helper'] : [],
          'kotlin-auditor': Array.isArray(parsed['kotlin-auditor']) ? parsed['kotlin-auditor'] : []
        };
      }
    } catch {
      // Ignore storage read errors
    }
    return {
      'adb-troubleshooter': [],
      'shell-helper': [],
      'kotlin-auditor': []
    };
  });
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const threadEndRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  const activeRole = ROLE_PRESETS.find((r) => r.id === roleId) ?? ROLE_PRESETS[0];
  const messages = messagesByRole[roleId];

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(messagesByRole));
    } catch {
      // Ignore storage quota errors
    }
  }, [messagesByRole]);

  useEffect(() => {
    threadEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, streaming]);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const selectRole = (nextRole: RolePreset) => {
    if (streaming) {
      abortRef.current?.abort();
      setStreaming(false);
    }
    setRoleId(nextRole.id);
    setModel(nextRole.defaultModel);
    setError(null);
  };

  const copyCode = async (id: string, code: string) => {
    try {
      await navigator.clipboard.writeText(code);
    } catch {
      const el = document.createElement('textarea');
      el.value = code;
      el.style.position = 'fixed';
      el.style.opacity = '0';
      document.body.appendChild(el);
      el.select();
      document.execCommand('copy');
      el.remove();
    }
    setCopiedId(id);
    window.setTimeout(() => {
      setCopiedId((current) => (current === id ? null : current));
    }, 1800);
  };

  const stopStreaming = () => {
    abortRef.current?.abort();
    abortRef.current = null;
    setStreaming(false);
  };

  const resetThread = () => {
    stopStreaming();
    setError(null);
    setMessagesByRole((prev) => ({
      ...prev,
      [roleId]: []
    }));
  };

  const sendMessage = async (overrideText?: string) => {
    const trimmed = (overrideText ?? input).trim();
    if (!trimmed || streaming) return;

    setError(null);
    if (!overrideText) {
      setInput('');
    }

    const currentRole = roleId;
    const currentModel = model;
    const priorHistory = messagesByRole[currentRole]
      .filter((m) => m.text.trim().length > 0)
      .map((m) => ({ role: m.role, text: m.text }));

    const userMsg: ChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      text: trimmed,
      timestamp: formatTime()
    };

    const assistantId = `m-${Date.now() + 1}`;
    const placeholderMsg: ChatMessage = {
      id: assistantId,
      role: 'model',
      text: '',
      model: currentModel,
      timestamp: formatTime()
    };

    setMessagesByRole((prev) => ({
      ...prev,
      [currentRole]: [...prev[currentRole], userMsg, placeholderMsg]
    }));

    const controller = new AbortController();
    abortRef.current = controller;
    setStreaming(true);

    try {
      const response = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          roleId: currentRole,
          model: currentModel,
          history: priorHistory,
          message: trimmed
        }),
        signal: controller.signal
      });

      if (!response.ok) {
        let errText = `Request failed (${response.status})`;
        try {
          const payload = (await response.json()) as { error?: string };
          if (payload.error) errText = payload.error;
        } catch {
          // Keep default error
        }
        throw new Error(errText);
      }

      if (!response.body) {
        throw new Error('Streaming response body was empty.');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const events = buffer.split('\n\n');
        buffer = events.pop() ?? '';

        for (const rawEvent of events) {
          const dataLine = rawEvent
            .split('\n')
            .find((line) => line.startsWith('data: '));
          if (!dataLine) continue;
          const payload = JSON.parse(dataLine.slice(6)) as {
            text?: string;
            error?: string;
            done?: boolean;
          };
          if (payload.error) {
            throw new Error(payload.error);
          }
          if (payload.text) {
            const delta = payload.text;
            setMessagesByRole((prev) => ({
              ...prev,
              [currentRole]: prev[currentRole].map((m) =>
                m.id === assistantId ? { ...m, text: m.text + delta } : m
              )
            }));
          }
        }
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        // User stopped stream manually; keep partial text if any
      } else {
        const msg = err instanceof Error ? err.message : 'Unable to reach Gemini assistant.';
        setError(msg);
        setMessagesByRole((prev) => ({
          ...prev,
          [currentRole]: prev[currentRole].filter(
            (m) => !(m.id === assistantId && m.text.trim().length === 0)
          )
        }));
      }
    } finally {
      abortRef.current = null;
      setStreaming(false);
    }
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void sendMessage();
    }
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col lg:flex-row lg:items-end justify-between gap-4 pb-6 border-b border-slate-200 dark:border-slate-800">
        <div>
          <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-slate-900 dark:text-slate-100 [text-wrap:balance]">
            A4 TV Bridge Assistant
          </h1>
          <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-slate-400 tabular-nums">
            <span>{activeRole.title}</span>
            <span aria-hidden="true">·</span>
            <span className="font-mono">{model}</span>
            <span aria-hidden="true">·</span>
            <span>{messages.length} {messages.length === 1 ? 'message' : 'messages'} in thread</span>
          </div>
        </div>

        {/* Model / Speed Segmented Selector + Reset */}
        <div className="flex flex-wrap items-center gap-2.5 shrink-0">
          <div className="flex items-center gap-1 p-1 bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-lg">
            <button
              type="button"
              onClick={() => setModel('gemini-4-hydrogen')}
              className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors cursor-pointer whitespace-nowrap shrink-0 ${
                model === 'gemini-4-hydrogen'
                  ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
              }`}
            >
              Balanced · 4 Hydrogen
            </button>
            <button
              type="button"
              onClick={() => setModel('gemini-3.1-flash-lite')}
              className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors flex items-center gap-1 cursor-pointer whitespace-nowrap shrink-0 ${
                model === 'gemini-3.1-flash-lite'
                  ? 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xs'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
              }`}
            >
              <Zap className="w-3.5 h-3.5" />
              Fast · 3.1 Flash Lite
            </button>
          </div>

          <button
            type="button"
            onClick={resetThread}
            disabled={messages.length === 0 && !streaming}
            className="px-3.5 py-2 text-xs font-semibold rounded-lg border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800 disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5 whitespace-nowrap shrink-0 cursor-pointer"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            New Chat
          </button>
        </div>
      </div>

      {/* Main 2-Column Workspace */}
      <div className="grid grid-cols-1 lg:grid-cols-[270px_1fr] gap-6 items-start">
        {/* Role Sidebar */}
        <aside className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl p-4 space-y-4">
          <div>
            <h2 className="text-xs font-semibold text-slate-500 dark:text-slate-400 px-2 pb-2">
              Assistant Roles
            </h2>
            <div className="space-y-1">
              {ROLE_PRESETS.map((preset) => {
                const Icon = preset.icon;
                const active = preset.id === roleId;
                const count = messagesByRole[preset.id].length;
                return (
                  <button
                    key={preset.id}
                    type="button"
                    onClick={() => selectRole(preset)}
                    className={`w-full text-left px-3 py-2.5 rounded-xl transition-colors cursor-pointer ${
                      active
                        ? 'bg-[#DDF3FC]/70 dark:bg-[#142835] text-slate-900 dark:text-slate-100'
                        : 'text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/60'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 min-w-0">
                        <Icon
                          className={`w-4 h-4 shrink-0 ${
                            active
                              ? 'text-[#00587B] dark:text-[#B0DEFF]'
                              : 'text-slate-400 dark:text-slate-500'
                          }`}
                        />
                        <span className="text-sm font-semibold truncate">{preset.shortLabel}</span>
                      </div>
                      {count > 0 && (
                        <span className="text-[11px] font-mono text-slate-500 dark:text-slate-400 tabular-nums shrink-0">
                          {count}
                        </span>
                      )}
                    </div>
                    <p className="mt-1 text-xs text-slate-500 dark:text-slate-400 leading-relaxed line-clamp-2">
                      {preset.summary}
                    </p>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="pt-3 border-t border-slate-100 dark:border-slate-800 px-2 space-y-2">
            <div className="text-xs font-semibold text-slate-700 dark:text-slate-300">
              Quick Prompts
            </div>
            <div className="space-y-1.5">
              {activeRole.prompts.map((prompt) => (
                <button
                  key={prompt}
                  type="button"
                  disabled={streaming}
                  onClick={() => void sendMessage(prompt)}
                  className="w-full text-left text-xs text-slate-600 dark:text-slate-400 hover:text-[#00587B] dark:hover:text-[#B0DEFF] py-1.5 leading-snug transition-colors disabled:opacity-50 cursor-pointer"
                >
                  {prompt}
                </button>
              ))}
            </div>
          </div>
        </aside>

        {/* Scrollable Multi-Turn Thread + Composer */}
        <section className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl flex flex-col h-[640px] min-h-[520px] overflow-hidden">
          {/* Scrollable Message Thread */}
          <div className="flex-1 overflow-y-auto px-5 sm:px-6 py-5 space-y-5">
            {messages.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-center max-w-lg mx-auto py-8 space-y-4">
                <div className="w-11 h-11 rounded-xl bg-[#DDF3FC] dark:bg-[#142835] flex items-center justify-center text-[#00587B] dark:text-[#B0DEFF]">
                  <MessageSquare className="w-5 h-5" />
                </div>
                <div className="space-y-1.5">
                  <h2 className="text-base font-bold text-slate-900 dark:text-slate-100">
                    {activeRole.title}
                  </h2>
                  <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
                    {activeRole.summary} Conversation history stays active across follow-up questions in this thread.
                  </p>
                </div>
                <div className="w-full pt-2 grid grid-cols-1 gap-2 text-left">
                  {activeRole.prompts.map((prompt) => (
                    <button
                      key={prompt}
                      type="button"
                      onClick={() => void sendMessage(prompt)}
                      className="px-3.5 py-2.5 rounded-xl border border-slate-200 dark:border-slate-800 hover:border-[#00587B] dark:hover:border-[#B0DEFF] hover:bg-slate-50 dark:hover:bg-slate-800/50 text-xs sm:text-sm text-slate-700 dark:text-slate-300 transition-colors cursor-pointer"
                    >
                      {prompt}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              messages.map((msg) => {
                const isUser = msg.role === 'user';
                const blocks = isUser ? [] : splitMarkdownBlocks(msg.text);
                return (
                  <div
                    key={msg.id}
                    className={`flex flex-col ${isUser ? 'items-end' : 'items-start'}`}
                  >
                    <div className="flex items-center gap-2 text-[11px] text-slate-500 dark:text-slate-400 mb-1.5 px-1 tabular-nums">
                      <span className="font-semibold text-slate-700 dark:text-slate-300">
                        {isUser ? 'You' : activeRole.shortLabel}
                      </span>
                      {!isUser && msg.model && (
                        <>
                          <span aria-hidden="true">·</span>
                          <span className="font-mono">{msg.model}</span>
                        </>
                      )}
                      <span aria-hidden="true">·</span>
                      <span>{msg.timestamp}</span>
                    </div>

                    {isUser ? (
                      <div className="max-w-[85%] sm:max-w-[75%] px-4 py-3 rounded-2xl bg-[#00587B] text-white text-sm leading-relaxed whitespace-pre-wrap">
                        {msg.text}
                      </div>
                    ) : (
                      <div className="max-w-[92%] sm:max-w-[85%] px-4 py-3.5 rounded-2xl bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 text-sm text-slate-700 dark:text-slate-300 leading-relaxed space-y-3">
                        {msg.text.length === 0 ? (
                          <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                            <span>Thinking…</span>
                          </div>
                        ) : (
                          blocks.map((block, bIndex) => {
                            if (block.type === 'code') {
                              const codeKey = `${msg.id}-code-${bIndex}`;
                              return (
                                <div
                                  key={codeKey}
                                  className="rounded-xl overflow-hidden border border-slate-800 bg-slate-950 text-slate-100"
                                >
                                  <div className="px-3 py-1.5 bg-slate-900 border-b border-slate-800 flex items-center justify-between gap-2 text-[11px] text-slate-400">
                                    <span className="font-mono">{block.lang}</span>
                                    <button
                                      type="button"
                                      onClick={() => void copyCode(codeKey, block.content)}
                                      className="flex items-center gap-1 text-slate-300 hover:text-white cursor-pointer"
                                    >
                                      {copiedId === codeKey ? (
                                        <>
                                          <Check className="w-3 h-3 text-emerald-400" />
                                          Copied
                                        </>
                                      ) : (
                                        <>
                                          <Copy className="w-3 h-3" />
                                          Copy
                                        </>
                                      )}
                                    </button>
                                  </div>
                                  <pre className="p-3 text-xs font-mono overflow-x-auto leading-relaxed">
                                    <code>{block.content}</code>
                                  </pre>
                                </div>
                              );
                            }

                            return (
                              <div key={`${msg.id}-t-${bIndex}`} className="space-y-1.5">
                                {block.content
                                  .trim()
                                  .split('\n')
                                  .map((line, lIdx) => {
                                    const trimmedLine = line.trim();
                                    if (!trimmedLine) return <div key={lIdx} className="h-1" />;
                                    if (trimmedLine.startsWith('- ') || trimmedLine.startsWith('* ')) {
                                      return (
                                        <div key={lIdx} className="flex items-start gap-2 pl-1">
                                          <span className="text-slate-400 select-none">•</span>
                                          <span>{renderInlineText(trimmedLine.slice(2))}</span>
                                        </div>
                                      );
                                    }
                                    return <p key={lIdx}>{renderInlineText(line)}</p>;
                                  })}
                              </div>
                            );
                          })
                        )}
                      </div>
                    )}
                  </div>
                );
              })
            )}
            <div ref={threadEndRef} />
          </div>

          {/* Error Banner */}
          {error && (
            <div className="mx-5 mb-2 px-3.5 py-2.5 rounded-xl bg-red-50 dark:bg-red-950/50 border border-red-200 dark:border-red-900 flex items-start justify-between gap-3 text-xs text-red-700 dark:text-red-300">
              <div className="flex items-start gap-2">
                <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>{error}</span>
              </div>
              <button
                type="button"
                onClick={() => setError(null)}
                className="font-semibold hover:underline shrink-0 cursor-pointer"
              >
                Dismiss
              </button>
            </div>
          )}

          {/* Message Composer */}
          <div className="p-4 border-t border-slate-200 dark:border-slate-800 bg-slate-50/70 dark:bg-slate-950/60">
            <div className="flex items-end gap-2.5">
              <textarea
                ref={textareaRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                rows={2}
                placeholder={`Ask ${activeRole.shortLabel} a question… (Enter to send, Shift+Enter for newline)`}
                className="flex-1 resize-none rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 px-3.5 py-2.5 text-sm text-slate-900 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 focus:outline-none focus:border-[#00587B] dark:focus:border-[#B0DEFF]"
              />
              {streaming ? (
                <button
                  type="button"
                  onClick={stopStreaming}
                  className="px-4 py-2.5 h-[44px] text-xs font-semibold rounded-xl bg-slate-800 hover:bg-slate-700 text-white transition-colors flex items-center gap-1.5 whitespace-nowrap shrink-0 cursor-pointer"
                >
                  <Square className="w-3.5 h-3.5" />
                  Stop
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => void sendMessage()}
                  disabled={!input.trim()}
                  className="px-4 py-2.5 h-[44px] text-xs font-semibold rounded-xl bg-[#00587B] hover:bg-[#004663] disabled:opacity-40 disabled:cursor-not-allowed text-white transition-colors flex items-center gap-1.5 whitespace-nowrap shrink-0 cursor-pointer"
                >
                  <Send className="w-3.5 h-3.5" />
                  Send
                </button>
              )}
            </div>
          </div>
        </section>
      </div>
    </div>
  );
};
