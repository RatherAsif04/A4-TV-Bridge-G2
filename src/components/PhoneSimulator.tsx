import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  AppWindow,
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Camera,
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  Download,
  ExternalLink,
  FileText,
  Folder,
  FolderPlus,
  HardDrive,
  Home,
  Info,
  Keyboard,
  Menu,
  MoreVertical,
  Pause,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Settings,
  Shield,
  SkipBack,
  SkipForward,
  Sliders,
  Sparkles,
  Terminal,
  Trash2,
  Tv,
  Upload,
  Video,
  Volume2,
  VolumeX,
  Wifi,
  X
} from 'lucide-react';
import type { ScreenId } from '../data/auditReport';

export type SimBuild = 'fixed' | 'broken';
export type SimTheme = 'dark' | 'light' | 'system';

interface PhoneSimulatorProps {
  build: SimBuild;
  theme: SimTheme;
  onThemeChange: (t: SimTheme) => void;
  connected: boolean;
  onConnectedChange: (c: boolean) => void;
  screen: ScreenId;
  onScreenChange: (s: ScreenId) => void;
}

interface SimApp {
  id: string;
  name: string;
  system: boolean;
  enabled: boolean;
  running: boolean;
}

interface SimFile {
  name: string;
  folder: boolean;
  size: string;
}

const INITIAL_APPS: SimApp[] = [
  { id: 'org.smarttube.stable', name: 'SmartTube', system: false, enabled: true, running: true },
  { id: 'org.jellyfin.androidtv', name: 'Jellyfin', system: false, enabled: true, running: true },
  { id: 'com.neilturner.aerialviews', name: 'Aerial Views', system: false, enabled: true, running: false },
  { id: 'com.netflix.ninja', name: 'Netflix', system: false, enabled: true, running: false },
  { id: 'com.google.android.tvlauncher', name: 'Android TV Home', system: true, enabled: true, running: true },
  { id: 'com.xiaomi.mitv.shop', name: 'Mi TV Shop', system: true, enabled: true, running: false },
  { id: 'com.xiaomi.mitv.calendar', name: 'Mi TV Calendar', system: true, enabled: true, running: false },
  { id: 'com.google.android.feedback', name: 'Feedback Agent', system: true, enabled: false, running: false }
];

const INITIAL_FILES: Record<string, SimFile[]> = {
  '/sdcard': [
    { name: 'Download', folder: true, size: '' },
    { name: 'Movies', folder: true, size: '' },
    { name: 'Music', folder: true, size: '' },
    { name: 'Pictures', folder: true, size: '' },
    { name: 'A4-notes.txt', folder: false, size: '4.2 KB' }
  ],
  '/sdcard/Download': [
    { name: 'A4TVBridge', folder: true, size: '' },
    { name: 'SmartTube_32.80_arm64-v8a.apk', folder: false, size: '25.5 MB' },
    { name: 'livingroom-capture.png', folder: false, size: '1.4 MB' }
  ],
  '/sdcard/Movies': [
    { name: 'A4TVBridge', folder: true, size: '' },
    { name: 'demo-4k-hdr.mkv', folder: false, size: '412.0 MB' }
  ]
};

const CLEANUP_PROFILES = [
  'xiaomi-mi-tv-4a',
  'xiaomi-mitv-axso2-android11',
  'xiaomi-tv-a43-2025',
  'xiaomi-tv-a-pro-2026',
  'sony-bravia-vh2',
  'tcl-beyondtv6',
  'google-chromecast-sabrina',
  'amazon-fire-tv-stick-4k'
];

export const PhoneSimulator: React.FC<PhoneSimulatorProps> = ({
  build,
  theme,
  onThemeChange,
  connected,
  onConnectedChange,
  screen,
  onScreenChange
}) => {
  const isBroken = build === 'broken';
  const isDark = theme === 'dark' || theme === 'system';

  // Exact A4 TV Bridge v1.4.1 color tokens from BridgeActivity.kt
  const pal = useMemo(
    () =>
      isDark
        ? {
            surface: '#05070A',
            field: '#0D1219',
            ink: '#F8FAFC',
            muted: '#94A3B8',
            accent: '#38BDF8',
            onAccent: '#FFFFFF',
            edge: '#1E293B',
            tint: '#091D2C',
            green: '#34D399',
            purple: '#A78BFA',
            orange: '#F59E0B',
            corner: 18
          }
        : {
            surface: '#F1F5F9',
            field: '#FFFFFF',
            ink: '#0F172A',
            muted: '#475569',
            accent: '#00587B',
            onAccent: '#FFFFFF',
            edge: '#CBD5E1',
            tint: '#E0F2FE',
            green: '#059669',
            purple: '#6D28D9',
            orange: '#D97706',
            corner: 18
          },
    [isDark]
  );

  // Interactive simulated states
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [sheet, setSheet] = useState<'none' | 'connect' | 'control' | 'about' | 'dns' | 'keyboard'>('none');
  const [scanningLan, setScanningLan] = useState(false);
  const [discoveredTvs, setDiscoveredTvs] = useState([
    { name: 'Xiaomi Mi TV (AXSO2)', address: '192.168.1.42:5555' }
  ]);
  const [tvName, setTvName] = useState('Xiaomi Mi TV (AXSO2)');

  // Telemetry state
  const [cpu, setCpu] = useState(24);
  const [ram, setRam] = useState(61);
  const [netDown, setNetDown] = useState(420);
  const [netUp, setNetUp] = useState(84);
  const [trend, setTrend] = useState<number[]>([22, 26, 21, 30, 28, 25, 24, 29, 27, 23, 24]);

  // Remote state
  const [touchMode, setTouchMode] = useState(false);
  const [lastKey, setLastKey] = useState<string>('');
  const [mirrorPaused, setMirrorPaused] = useState(false);
  const [mirrorCursor, setMirrorCursor] = useState<{ x: number; y: number } | null>(null);
  const [keyboardText, setKeyboardText] = useState('');

  // Apps & Files state
  const [apps, setApps] = useState<SimApp[]>(INITIAL_APPS);
  const [appMode, setAppMode] = useState<0 | 1 | 2>(0);
  const [appQuery, setAppQuery] = useState('');
  const [currentFolder, setCurrentFolder] = useState('/sdcard');
  const [filesByFolder, setFilesByFolder] = useState<Record<string, SimFile[]>>(INITIAL_FILES);
  const [sendTarget, setSendTarget] = useState<'Downloads' | 'Movies' | 'Music' | 'Pictures' | 'Documents'>('Downloads');
  const [sentHistory, setSentHistory] = useState<{ name: string; size: string; folder: string }[]>([]);

  // Terminal state
  const [shellCmd, setShellCmd] = useState('df -Pk /data');
  const [shellOut, setShellOut] = useState('');

  // TV Care state
  const [profileQuery, setProfileQuery] = useState('xiaomi-mitv-axso2-android11');
  const [profileOpen, setProfileOpen] = useState(false);
  const [dnsHost, setDnsHost] = useState('dns.adguard-dns.com');
  const [dnsActive, setDnsActive] = useState(false);
  const [monitorTab, setMonitorTab] = useState<'Overview' | 'CPU' | 'Memory' | 'Network'>('Overview');

  const noticeTimerRef = useRef<number | null>(null);
  const showNotice = (msg: string, duration = 2600) => {
    setNotice(msg);
    if (noticeTimerRef.current) window.clearTimeout(noticeTimerRef.current);
    if (duration > 0) {
      noticeTimerRef.current = window.setTimeout(() => setNotice(''), duration);
    }
  };

  // Live simulated telemetry ticks
  useEffect(() => {
    if (!connected) return;
    const id = window.setInterval(() => {
      const nextCpu = Math.max(8, Math.min(76, Math.round(cpu + (Math.random() - 0.48) * 10)));
      const nextRam = Math.max(48, Math.min(78, Math.round(ram + (Math.random() - 0.5) * 4)));
      setCpu(nextCpu);
      setRam(nextRam);
      setNetDown(Math.max(40, Math.round(280 + Math.random() * 520)));
      setNetUp(Math.max(12, Math.round(45 + Math.random() * 110)));
      setTrend((prev) => [...prev.slice(-17), nextCpu]);
    }, 1400);
    return () => window.clearInterval(id);
  }, [connected, cpu, ram]);

  // Keep WELCOME/DASHBOARD synced with connection toggle
  useEffect(() => {
    if (!connected && screen !== 'WELCOME' && screen !== 'APPEARANCE') {
      onScreenChange('WELCOME');
    } else if (connected && screen === 'WELCOME') {
      onScreenChange('DASHBOARD');
    }
  }, [connected, screen, onScreenChange]);

  const sendRemoteKey = (label: string, code: number) => {
    if (isBroken) {
      if (busy) {
        showNotice('Wait for the current operation.');
        return;
      }
      setBusy(true);
      setNotice('Working…');
      window.setTimeout(() => {
        setBusy(false);
        setNotice('');
        setLastKey(`${label} (keycode ${code})`);
      }, 650);
    } else {
      // Fixed: queued non-blocking input channel with zero layout bounce
      setLastKey(`${label} · keyevent ${code}`);
    }
  };

  const runShellCommand = () => {
    const trimmed = shellCmd.trim();
    if (!trimmed) {
      showNotice('Enter a command.');
      return;
    }
    if (trimmed.startsWith('df')) {
      setShellOut(
        `Filesystem            1024-blocks    Used Available Capacity Mounted on\n/dev/block/dm-3          16777216 7129040   9648176      43% /data\n\nExit code 0`
      );
    } else if (trimmed.startsWith('getprop')) {
      setShellOut(`MiTV-AXSO2\nAndroid 11 · arm64-v8a,armeabi-v7a\n\nExit code 0`);
    } else if (trimmed.startsWith('pm list')) {
      setShellOut(
        apps
          .filter((a) => a.enabled)
          .map((a) => `package:${a.id}`)
          .join('\n') + '\n\nExit code 0'
      );
    } else {
      setShellOut(`$ ${trimmed}\nOK (simulated ADB shell on ${tvName})\n\nExit code 0`);
    }
  };

  const handleRenameFirstFile = (fileName: string) => {
    if (isBroken) {
      showNotice("mv: Unknown option 'n' (Android 8–12 Toybox failure)", 3500);
      return;
    }
    const nextName = fileName.includes('renamed') ? fileName : `renamed-${fileName}`;
    setFilesByFolder((prev) => ({
      ...prev,
      [currentFolder]: (prev[currentFolder] || []).map((f) =>
        f.name === fileName ? { ...f, name: nextName } : f
      )
    }));
    showNotice(`Renamed to ${nextName}.`);
  };

  const handleUploadDemoFile = () => {
    if (isBroken) {
      showNotice("mv: Unknown option 'n' — Upload failed on TV.", 3500);
      return;
    }
    const fname = `clip-${Math.floor(100 + Math.random() * 899)}.mp4`;
    setFilesByFolder((prev) => ({
      ...prev,
      [currentFolder]: [...(prev[currentFolder] || []), { name: fname, folder: false, size: '18.4 MB' }]
    }));
    showNotice(`Uploaded ${fname}.`);
  };

  const handleOneClickInstall = (title: string) => {
    if (isBroken) {
      showNotice('GitHub returned HTTP 403 (missing User-Agent / digest).', 3500);
      return;
    }
    showNotice(`Verified SHA-256 & installed ${title} on TV.`);
  };

  const handleToggleApp = (app: SimApp) => {
    if (isBroken && app.system && app.enabled) {
      showNotice('The TV’s active home, keyboard, or system support must remain enabled.', 3400);
      return;
    }
    setApps((prev) =>
      prev.map((a) => (a.id === app.id ? { ...a, enabled: !a.enabled } : a))
    );
    showNotice(app.enabled ? 'Cleaned by A4 TV Bridge' : 'App restored.');
  };

  const showDock =
    connected &&
    (isBroken
      ? screen === 'DASHBOARD' || screen === 'LIBRARY' || screen === 'CARE'
      : screen === 'DASHBOARD' || screen === 'LIBRARY' || screen === 'SHELL' || screen === 'CARE');

  const isPrimaryHeader =
    connected &&
    (isBroken
      ? screen === 'DASHBOARD' || screen === 'LIBRARY' || screen === 'CARE'
      : screen === 'DASHBOARD' || screen === 'LIBRARY' || screen === 'SHELL' || screen === 'CARE');

  const disabledCount = apps.filter((a) => !a.enabled).length;

  return (
    <div className="flex flex-col items-center">
      {/* Realistic Android Phone Shell */}
      <div
        className="relative w-[348px] sm:w-[366px] h-[724px] rounded-[42px] p-[11px] bg-slate-900 shadow-2xl border-[2px] border-slate-700/80 select-none"
      >
        {/* Camera punch-hole */}
        <div className="absolute top-[20px] left-1/2 -translate-x-1/2 w-3.5 h-3.5 rounded-full bg-black z-30 border border-slate-800" />

        {/* Screen viewport */}
        <div
          className="relative w-full h-full rounded-[32px] overflow-hidden flex flex-col transition-colors duration-200"
          style={{
            backgroundColor: pal.surface,
            color: pal.ink
          }}
        >
          {/* Android Status Bar */}
          <div
            className="flex items-center justify-between px-6 pt-3 pb-1.5 text-[11px] font-semibold shrink-0"
            style={{ color: pal.ink }}
          >
            <span className="tabular-nums">9:41</span>
            <div className="flex items-center gap-1.5 text-[10px]">
              <Wifi className="w-3.5 h-3.5" />
              <span className="tabular-nums">94%</span>
            </div>
          </div>

          {/* App Header */}
          <div className="flex items-center justify-between px-4 py-2 shrink-0 min-h-[48px]">
            {screen === 'APPEARANCE' ? (
              <button
                onClick={() => onScreenChange(connected ? 'DASHBOARD' : 'WELCOME')}
                className="flex items-center gap-1 text-[15px] font-semibold cursor-pointer whitespace-nowrap"
                style={{ color: pal.accent }}
              >
                <ChevronLeft className="w-4 h-4" />
                Back
              </button>
            ) : !connected && screen === 'WELCOME' ? (
              <div className="flex items-center justify-between w-full gap-1">
                <button
                  onClick={() => showNotice('Opens GitHub Issues in browser.')}
                  className="text-[13px] font-semibold cursor-pointer whitespace-nowrap px-1"
                  style={{ color: pal.accent }}
                >
                  Feedback
                </button>
                <button
                  onClick={() => onScreenChange('APPEARANCE')}
                  className="text-[13px] font-semibold cursor-pointer whitespace-nowrap px-1"
                  style={{ color: pal.accent }}
                >
                  Appearance
                </button>
                <button
                  onClick={() => setSheet('connect')}
                  className="text-[13px] font-semibold cursor-pointer whitespace-nowrap px-1"
                  style={{ color: pal.accent }}
                >
                  Restore a TV
                </button>
                <button
                  onClick={() => setSheet('about')}
                  aria-label="About A4 TV Bridge"
                  className="p-1.5 rounded-full cursor-pointer"
                  style={{ color: pal.accent }}
                >
                  <Info className="w-4 h-4" />
                </button>
              </div>
            ) : isPrimaryHeader ? (
              <>
                <div className="flex items-center gap-2">
                  <img
                    src="/src/assets/a4_mark.webp"
                    alt="A4 mark"
                    className="w-7 h-7 rounded-lg object-cover"
                    referrerPolicy="no-referrer"
                  />
                  <span className="text-[15px] font-bold tracking-tight whitespace-nowrap">A4 TV Bridge</span>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => onScreenChange('APPEARANCE')}
                    aria-label="Appearance"
                    className="p-2 rounded-full cursor-pointer"
                    style={{ color: pal.accent }}
                  >
                    <Settings className="w-[18px] h-[18px]" />
                  </button>
                  <button
                    onClick={() => setSheet('about')}
                    aria-label="About A4 TV Bridge"
                    className="p-2 rounded-full cursor-pointer"
                    style={{ color: pal.accent }}
                  >
                    <Info className="w-[18px] h-[18px]" />
                  </button>
                </div>
              </>
            ) : (
              <>
                <button
                  onClick={() => {
                    if (screen === 'APPS' || screen === 'FILES' || screen === 'INSTALL' || screen === 'SEND') {
                      onScreenChange('LIBRARY');
                    } else if (screen === 'CLEAN' || screen === 'CHECK' || screen === 'RESTORE' || screen === 'SPACE') {
                      onScreenChange('CARE');
                    } else {
                      onScreenChange('DASHBOARD');
                    }
                  }}
                  className="flex items-center gap-1 text-[15px] font-semibold cursor-pointer whitespace-nowrap"
                  style={{ color: pal.accent }}
                >
                  <ChevronLeft className="w-4 h-4" />
                  {screen === 'APPS' || screen === 'FILES' || screen === 'REMOTE' ? 'Back' : 'My TV'}
                </button>
                {screen !== 'INFO' && (
                  <span
                    className="text-[11px] font-bold px-2.5 py-1 rounded-full truncate max-w-[155px]"
                    style={{ backgroundColor: pal.tint, color: pal.ink }}
                  >
                    {tvName}
                  </span>
                )}
              </>
            )}
          </div>

          {/* Notice bar (note: in Broken build this causes vertical shift!) */}
          {notice && (
            <div
              className="px-4 py-1.5 text-[12px] font-medium shrink-0"
              style={{
                color: notice.includes('403') || notice.includes('mv:') || notice.includes('Wait') || notice.includes('must remain')
                  ? '#F87171'
                  : pal.muted
              }}
            >
              {notice}
            </div>
          )}

          {/* Scrollable Body */}
          <div className="flex-1 overflow-y-auto thin-scroll px-4 pb-5 pt-1">
            {/* WELCOME SCREEN */}
            {screen === 'WELCOME' && (
              <div className="space-y-3.5">
                <div
                  className="flex items-center justify-between gap-2 p-4 rounded-[20px]"
                  style={{
                    backgroundColor: !isBroken && isDark ? pal.field : '#0B1327',
                    border: !isBroken && isDark ? `1px solid ${pal.edge}` : 'none'
                  }}
                >
                  <div className="flex-1">
                    <div
                      className="text-[20px] font-bold leading-tight"
                      style={{ color: !isBroken && isDark ? pal.ink : '#FFFFFF' }}
                    >
                      A4 TV Bridge
                    </div>
                    <p
                      className="text-[12.5px] mt-1.5 leading-relaxed"
                      style={{ color: !isBroken && isDark ? pal.muted : '#B4C4D6' }}
                    >
                      Connect to your TV to use the remote, manage apps, or send files.
                    </p>
                  </div>
                  <img
                    src="/src/assets/a4_mark.webp"
                    alt="A4 TV Bridge logo"
                    className="w-16 h-16 rounded-2xl shrink-0 object-cover"
                    referrerPolicy="no-referrer"
                  />
                </div>

                <button
                  onClick={() => setSheet('connect')}
                  className="w-full h-[50px] rounded-[16px] font-bold text-[15px] flex items-center justify-center gap-2 cursor-pointer transition-opacity active:opacity-85"
                  style={{ backgroundColor: pal.accent, color: pal.onAccent }}
                >
                  <Tv className="w-5 h-5" />
                  Connect your TV
                </button>

                <button
                  onClick={() => setSheet('connect')}
                  className="text-[14px] font-semibold py-1 cursor-pointer"
                  style={{ color: pal.accent }}
                >
                  Other TV types
                </button>
              </div>
            )}

            {/* DASHBOARD SCREEN */}
            {screen === 'DASHBOARD' && connected && (
              <div className="space-y-4">
                <div
                  className="p-4 rounded-[20px]"
                  style={{ backgroundColor: pal.field, border: `1.5px solid ${pal.edge}` }}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <div className="text-[19px] font-bold leading-tight">{tvName}</div>
                      <div className="text-[12px] mt-0.5 tabular-nums" style={{ color: pal.muted }}>
                        192.168.1.42:5555
                      </div>
                      <div className="text-[11px] font-bold mt-1" style={{ color: pal.green }}>
                        Connected TV
                      </div>
                    </div>
                    <button
                      onClick={() => setSheet('connect')}
                      className="text-[13px] font-semibold cursor-pointer whitespace-nowrap"
                      style={{ color: pal.accent }}
                    >
                      Change TV
                    </button>
                  </div>

                  <div className="my-3 h-px" style={{ backgroundColor: pal.edge }} />

                  <div className="flex items-center justify-between mb-2">
                    <span className="text-[14px] font-bold">Device activity</span>
                    <button
                      onClick={() => onScreenChange('INFO')}
                      className="text-[13px] font-semibold cursor-pointer"
                      style={{ color: pal.accent }}
                    >
                      Device Info
                    </button>
                  </div>

                  <div className="flex items-center justify-between text-[11.5px] mb-1">
                    <span style={{ color: pal.muted }}>Storage used</span>
                    <span className="font-bold tabular-nums">
                      {isBroken ? '—' : '6.8 GB / 16.0 GB'}
                    </span>
                  </div>
                  <div className="w-full h-2 rounded-full overflow-hidden mb-3" style={{ backgroundColor: pal.edge }}>
                    <div
                      className="h-full rounded-full transition-all duration-300"
                      style={{ width: isBroken ? '0%' : '43%', backgroundColor: pal.accent }}
                    />
                  </div>

                  <div className="grid grid-cols-3 gap-2">
                    {[
                      { label: 'CPU', val: `${cpu}%` },
                      { label: 'RAM', val: `${ram}%` },
                      { label: 'Network', val: `${netDown} KB/s` }
                    ].map((m) => (
                      <div
                        key={m.label}
                        className="p-2 rounded-[11px]"
                        style={{
                          backgroundColor: pal.surface,
                          border: isBroken ? 'none' : `1px solid ${pal.edge}`
                        }}
                      >
                        <div className="text-[10.5px]" style={{ color: pal.muted }}>
                          {m.label}
                        </div>
                        <div className="text-[13.5px] font-bold mt-0.5 tabular-nums truncate">
                          {m.val}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                <div>
                  <h3 className="text-[18px] font-bold mb-2.5">Quick actions</h3>
                  <div className="grid grid-cols-2 gap-2.5">
                    {[
                      { title: 'Remote', sub: 'Remote control', icon: Tv, col: pal.purple, sel: true, go: () => onScreenChange('REMOTE') },
                      { title: 'Clean Up TV', sub: 'Review apps', icon: Sparkles, col: pal.accent, sel: false, go: () => onScreenChange('CLEAN') },
                      { title: 'Install Apps', sub: 'Install an APK', icon: Download, col: pal.accent, sel: false, go: () => onScreenChange('INSTALL') },
                      { title: 'Send files', sub: 'Phone to TV', icon: Upload, col: pal.orange, sel: false, go: () => onScreenChange('SEND') },
                      { title: 'ADB Shell', sub: 'Run commands', icon: Terminal, col: pal.purple, sel: false, go: () => onScreenChange('SHELL') },
                      { title: 'Block Ads', sub: 'Private DNS', icon: Shield, col: pal.purple, sel: false, go: () => setSheet('dns') }
                    ].map((tile) => {
                      const Icon = tile.icon;
                      return (
                        <button
                          key={tile.title}
                          onClick={tile.go}
                          className="flex items-center gap-2.5 p-3 text-left cursor-pointer transition-transform active:scale-[0.98]"
                          style={{
                            borderRadius: pal.corner,
                            backgroundColor: tile.sel ? pal.tint : pal.field,
                            border: `1px solid ${pal.edge}`
                          }}
                        >
                          <Icon className="w-5 h-5 shrink-0" style={{ color: tile.col }} />
                          <div className="min-w-0">
                            <div className="text-[13px] font-bold truncate">{tile.title}</div>
                            <div className="text-[10.5px] truncate" style={{ color: pal.muted }}>
                              {tile.sub}
                            </div>
                          </div>
                        </button>
                      );
                    })}
                  </div>
                </div>
              </div>
            )}

            {/* LIBRARY (APPS & FILES HUB) */}
            {screen === 'LIBRARY' && (
              <div className="space-y-2.5">
                <h2 className="text-[20px] font-bold mb-2">Apps and files</h2>
                {[
                  { label: 'Apps', icon: AppWindow, col: pal.accent, to: 'APPS' as ScreenId },
                  { label: 'Install Apps', icon: Download, col: pal.accent, to: 'INSTALL' as ScreenId },
                  { label: 'File Manager', icon: Folder, col: pal.orange, to: 'FILES' as ScreenId },
                  { label: 'Send files', icon: Upload, col: pal.orange, to: 'SEND' as ScreenId }
                ].map((item) => {
                  const Icon = item.icon;
                  return (
                    <button
                      key={item.label}
                      onClick={() => onScreenChange(item.to)}
                      className="w-full flex items-center gap-3 p-3.5 text-left cursor-pointer"
                      style={{ borderRadius: pal.corner, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                    >
                      <Icon className="w-5 h-5 shrink-0" style={{ color: item.col }} />
                      <span className="text-[15px] font-bold flex-1">{item.label}</span>
                      <ChevronRight className="w-4 h-4" style={{ color: pal.muted }} />
                    </button>
                  );
                })}
              </div>
            )}

            {/* APPS MANAGER */}
            {screen === 'APPS' && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">Apps</h2>
                {/* Filter tabs — BROKEN has zero active styling */}
                <div
                  className={isBroken ? 'grid grid-cols-3 gap-2' : 'grid grid-cols-3 gap-1 p-1'}
                  style={
                    isBroken
                      ? undefined
                      : { backgroundColor: pal.field, border: `1px solid ${pal.edge}`, borderRadius: 13 }
                  }
                >
                  {(['Installed', 'Built-in', 'Running'] as const).map((label, idx) => {
                    const active = appMode === idx;
                    return (
                      <button
                        key={label}
                        onClick={() => setAppMode(idx as 0 | 1 | 2)}
                        className="h-9 text-[12.5px] font-semibold cursor-pointer transition-colors"
                        style={
                          isBroken
                            ? {
                                backgroundColor: pal.field,
                                color: pal.ink,
                                border: `1px solid ${pal.edge}`,
                                borderRadius: pal.corner
                              }
                            : {
                                backgroundColor: active ? pal.tint : 'transparent',
                                color: active ? pal.accent : pal.muted,
                                borderRadius: 9
                              }
                        }
                      >
                        {label}
                      </button>
                    );
                  })}
                </div>

                <input
                  value={appQuery}
                  onChange={(e) => setAppQuery(e.target.value)}
                  placeholder="Search apps"
                  className="w-full px-3 py-2 text-[13.5px] outline-none bg-transparent"
                  style={{
                    color: pal.ink,
                    borderBottom: `1.5px solid ${pal.accent}`
                  }}
                />

                <div className="space-y-2">
                  {(() => {
                    const filtered =
                      isBroken && appMode === 2
                        ? []
                        : apps.filter((a) => {
                            const matchesQ =
                              a.name.toLowerCase().includes(appQuery.toLowerCase()) ||
                              a.id.toLowerCase().includes(appQuery.toLowerCase());
                            if (!matchesQ) return false;
                            if (appMode === 0) return !a.system;
                            if (appMode === 1) return a.system;
                            return a.running;
                          });

                    if (filtered.length === 0) {
                      return (
                        <div className="text-[13px] py-4" style={{ color: pal.muted }}>
                          {isBroken && appMode === 2
                            ? 'No matching apps. (ps -A -o NAME failed on TV)'
                            : 'No matching apps.'}
                        </div>
                      );
                    }
                    return filtered.map((app) => (
                      <div
                        key={app.id}
                        className="flex items-center justify-between gap-2 p-3"
                        style={{
                          borderRadius: pal.corner,
                          backgroundColor: pal.field,
                          border: `1px solid ${pal.edge}`
                        }}
                      >
                        <div className="min-w-0">
                          <div className="text-[14px] font-bold truncate">{app.name}</div>
                          <div className="text-[11px] truncate" style={{ color: pal.muted }}>
                            {app.id}
                            {!app.enabled ? ' · Disabled' : app.running ? ' · Running' : ''}
                          </div>
                        </div>
                        <button
                          onClick={() => handleToggleApp(app)}
                          className="text-[11.5px] font-semibold px-2.5 py-1.5 rounded-lg cursor-pointer shrink-0"
                          style={{ backgroundColor: pal.tint, color: pal.accent }}
                        >
                          {app.enabled ? 'Disable' : 'Enable'}
                        </button>
                      </div>
                    ));
                  })()}
                </div>
              </div>
            )}

            {/* INSTALL APPS */}
            {screen === 'INSTALL' && (
              <div className="space-y-3.5">
                <h2 className="text-[20px] font-bold">Install Apps</h2>
                <p className="text-[13px] leading-relaxed" style={{ color: pal.muted }}>
                  Choose a single APK or its matching base and split APKs. Android installs them on the connected TV.
                </p>

                <button
                  onClick={() => showNotice('Opened system APK file picker.')}
                  className="w-full h-11 rounded-[14px] font-bold text-[13.5px] flex items-center justify-center gap-2 cursor-pointer"
                  style={{ backgroundColor: pal.tint, color: pal.accent }}
                >
                  <Plus className="w-4 h-4" />
                  Choose APKs
                </button>

                <h3 className="text-[16px] font-bold pt-1">1 Click App Install</h3>
                <div
                  className="rounded-[18px] px-3.5 py-1"
                  style={{ backgroundColor: pal.field, border: `1.5px solid ${pal.edge}` }}
                >
                  {['SmartTube', 'Jellyfin for Android TV', 'Aerial Views'].map((app, i) => (
                    <div
                      key={app}
                      className="flex items-center justify-between py-3"
                      style={{ borderTop: i > 0 ? `1px solid ${pal.edge}` : 'none' }}
                    >
                      <span className="text-[14px] font-bold pr-2">{app}</span>
                      <button
                        onClick={() => handleOneClickInstall(app)}
                        className="px-4 h-9 rounded-full text-[13px] font-bold cursor-pointer shrink-0"
                        style={{ backgroundColor: pal.tint, color: pal.accent }}
                      >
                        Install
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* FILE MANAGER */}
            {screen === 'FILES' && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">File Manager</h2>
                <div
                  className="p-3.5 rounded-[18px]"
                  style={{ backgroundColor: pal.field, border: `1.5px solid ${pal.edge}` }}
                >
                  <div className="text-[14px] font-bold">
                    {currentFolder === '/sdcard'
                      ? 'TV storage'
                      : `TV storage / ${currentFolder.replace('/sdcard/', '').replace(/\//g, ' / ')}`}
                  </div>
                  <div className="flex flex-wrap gap-3 mt-2 text-[12px] font-semibold" style={{ color: pal.accent }}>
                    {currentFolder !== '/sdcard' && (
                      <button onClick={() => setCurrentFolder('/sdcard')} className="cursor-pointer">
                        Parent
                      </button>
                    )}
                    <button onClick={() => showNotice('Refreshed TV folder.')} className="cursor-pointer">
                      Refresh
                    </button>
                    <button
                      onClick={() => {
                        const dirName = `Folder-${Math.floor(10 + Math.random() * 89)}`;
                        setFilesByFolder((p) => ({
                          ...p,
                          [currentFolder]: [{ name: dirName, folder: true, size: '' }, ...(p[currentFolder] || [])]
                        }));
                        showNotice(`Created ${dirName}.`);
                      }}
                      className="cursor-pointer flex items-center gap-1"
                    >
                      <FolderPlus className="w-3.5 h-3.5" />
                      New folder
                    </button>
                    <button onClick={handleUploadDemoFile} className="cursor-pointer flex items-center gap-1">
                      <Upload className="w-3.5 h-3.5" />
                      Upload
                    </button>
                  </div>
                </div>

                {/* Folders */}
                <div className="grid grid-cols-2 gap-2">
                  {(filesByFolder[currentFolder] || [])
                    .filter((f) => f.folder)
                    .map((dir) => (
                      <button
                        key={dir.name}
                        onClick={() => {
                          const target = `${currentFolder}/${dir.name}`;
                          if (filesByFolder[target]) setCurrentFolder(target);
                          else showNotice(`Opened ${dir.name} (empty).`);
                        }}
                        className="p-3 text-left cursor-pointer"
                        style={{ borderRadius: pal.corner, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                      >
                        <div className="flex items-center justify-between mb-2">
                          <Folder className="w-5 h-5" style={{ color: pal.accent }} />
                          <MoreVertical className="w-3.5 h-3.5" style={{ color: pal.muted }} />
                        </div>
                        <div className="text-[13.5px] font-bold truncate">{dir.name}</div>
                      </button>
                    ))}
                </div>

                {/* Files */}
                <div className="space-y-2">
                  {(filesByFolder[currentFolder] || [])
                    .filter((f) => !f.folder)
                    .map((file) => (
                      <div
                        key={file.name}
                        className="flex items-center justify-between gap-2 p-3"
                        style={{ borderRadius: pal.corner, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                      >
                        <div className="flex items-center gap-2.5 min-w-0">
                          <FileText className="w-4 h-4 shrink-0" style={{ color: pal.muted }} />
                          <div className="min-w-0">
                            <div className="text-[13px] font-medium truncate">{file.name}</div>
                            <div className="text-[10.5px]" style={{ color: pal.muted }}>
                              {file.size}
                            </div>
                          </div>
                        </div>
                        <button
                          onClick={() => handleRenameFirstFile(file.name)}
                          className="text-[11px] font-semibold px-2.5 py-1 rounded-lg cursor-pointer shrink-0"
                          style={{ backgroundColor: pal.tint, color: pal.accent }}
                        >
                          Rename
                        </button>
                      </div>
                    ))}
                </div>
              </div>
            )}

            {/* SEND FILES */}
            {screen === 'SEND' && (
              <div className="space-y-3.5">
                <div className="text-[12px] font-bold" style={{ color: pal.muted }}>
                  Save in
                </div>
                <div className="grid grid-cols-3 gap-2">
                  {(['Downloads', 'Movies', 'Music', 'Pictures', 'Documents'] as const).map((t) => {
                    const sel = sendTarget === t;
                    return (
                      <button
                        key={t}
                        onClick={() => setSendTarget(t)}
                        className="h-9 rounded-[9px] text-[12.5px] font-semibold cursor-pointer"
                        style={{
                          backgroundColor: sel ? (isDark ? '#163C33' : '#E2F5EA') : pal.surface,
                          color: sel ? pal.green : pal.muted,
                          border: sel ? 'none' : `1px solid ${pal.edge}`
                        }}
                      >
                        {t}
                      </button>
                    );
                  })}
                </div>
                <div
                  className="inline-block px-3 py-1.5 rounded-full text-[12px] font-bold tabular-nums"
                  style={{ backgroundColor: isDark ? pal.field : '#ECEFF3' }}
                >
                  9.2 GB free on the TV
                </div>
                <button
                  onClick={() => {
                    if (isBroken) {
                      showNotice("mv: Unknown option 'n' — Send failed.", 3500);
                      return;
                    }
                    setSentHistory((prev) => [
                      { name: 'family-video.mp4', size: '42.6 MB', folder: sendTarget },
                      ...prev
                    ]);
                  }}
                  className="w-full h-11 rounded-[14px] font-bold text-[13.5px] flex items-center justify-center gap-2 cursor-pointer"
                  style={{ backgroundColor: pal.tint, color: pal.accent }}
                >
                  <Upload className="w-4 h-4" />
                  Send sample file to {sendTarget}
                </button>
                {sentHistory.map((item, idx) => (
                  <div
                    key={idx}
                    className="p-3 rounded-[14px]"
                    style={{
                      backgroundColor: isDark ? '#163C33' : '#E2F5EA',
                      border: `1.5px solid ${pal.green}`
                    }}
                  >
                    <div className="text-[13px] font-bold" style={{ color: pal.green }}>
                      ✓ Sent {item.name} ({item.size})
                    </div>
                    <div className="text-[11.5px] mt-0.5" style={{ color: pal.green }}>
                      On TV, open {item.folder} › A4TVBridge.
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* REMOTE CONTROL */}
            {screen === 'REMOTE' && (
              <div className="space-y-3.5">
                <div className="flex items-center justify-center gap-2.5">
                  {[
                    { icon: Keyboard, label: 'Keyboard', go: () => setSheet('keyboard') },
                    { icon: Camera, label: 'Screenshot', go: () => showNotice('Saved TV screenshot PNG.') },
                    { icon: AppWindow, label: 'Apps', go: () => onScreenChange('APPS') }
                  ].map((act) => {
                    const Icon = act.icon;
                    return (
                      <button
                        key={act.label}
                        onClick={act.go}
                        aria-label={act.label}
                        className="w-11 h-11 rounded-full flex items-center justify-center cursor-pointer"
                        style={{ backgroundColor: pal.tint, color: pal.accent }}
                      >
                        <Icon className="w-4 h-4" />
                      </button>
                    );
                  })}
                </div>

                {/* D-pad / Touchpad switch */}
                <div className="flex justify-center">
                  <div
                    className="grid grid-cols-2 p-1 rounded-full w-[205px]"
                    style={{ backgroundColor: pal.tint }}
                  >
                    {(['D-pad', 'Touchpad'] as const).map((m, i) => {
                      const active = (i === 1) === touchMode;
                      return (
                        <button
                          key={m}
                          onClick={() => setTouchMode(i === 1)}
                          className="h-8 rounded-full text-[13px] font-semibold cursor-pointer transition-colors"
                          style={{
                            backgroundColor: active ? pal.surface : 'transparent',
                            color: isBroken ? pal.accent : active ? pal.accent : pal.muted
                          }}
                        >
                          {m}
                        </button>
                      );
                    })}
                  </div>
                </div>

                {/* RemotePad */}
                <div
                  className="relative h-[228px] rounded-[28px] flex items-center justify-center overflow-hidden"
                  style={{
                    background:
                      !isBroken && isDark
                        ? 'linear-gradient(135deg, #0E2230 0%, #15324A 100%)'
                        : isBroken
                          ? 'linear-gradient(135deg, #00587B 0%, #1C49CB 100%)'
                          : 'linear-gradient(135deg, #00587B 0%, #0C6B94 100%)',
                    border: !isBroken && isDark ? `1px solid ${pal.edge}` : 'none'
                  }}
                >
                  {touchMode ? (
                    <button
                      onClick={() => sendRemoteKey('Tap Select', 23)}
                      className="w-full h-full flex flex-col items-center justify-center text-white cursor-pointer"
                    >
                      <span className="text-[14px] font-semibold">Swipe to move · Tap to select</span>
                      {lastKey && <span className="text-[11px] opacity-75 mt-2">{lastKey}</span>}
                    </button>
                  ) : (
                    <div className="relative w-[196px] h-[196px] rounded-full bg-white/15 flex items-center justify-center">
                      <button
                        onClick={() => sendRemoteKey('Up', 19)}
                        aria-label="Up"
                        className="absolute top-2.5 left-1/2 -translate-x-1/2 w-11 h-11 rounded-full flex items-center justify-center text-white hover:bg-white/15 cursor-pointer"
                      >
                        <ArrowUp className="w-5 h-5" />
                      </button>
                      <button
                        onClick={() => sendRemoteKey('Down', 20)}
                        aria-label="Down"
                        className="absolute bottom-2.5 left-1/2 -translate-x-1/2 w-11 h-11 rounded-full flex items-center justify-center text-white hover:bg-white/15 cursor-pointer"
                      >
                        <ArrowDown className="w-5 h-5" />
                      </button>
                      <button
                        onClick={() => sendRemoteKey('Left', 21)}
                        aria-label="Left"
                        className="absolute left-2.5 top-1/2 -translate-y-1/2 w-11 h-11 rounded-full flex items-center justify-center text-white hover:bg-white/15 cursor-pointer"
                      >
                        <ArrowLeft className="w-5 h-5" />
                      </button>
                      <button
                        onClick={() => sendRemoteKey('Right', 22)}
                        aria-label="Right"
                        className="absolute right-2.5 top-1/2 -translate-y-1/2 w-11 h-11 rounded-full flex items-center justify-center text-white hover:bg-white/15 cursor-pointer"
                      >
                        <ArrowRight className="w-5 h-5" />
                      </button>
                      <button
                        onClick={() => sendRemoteKey('OK', 23)}
                        className="w-[74px] h-[74px] rounded-full font-bold text-[16px] shadow-md cursor-pointer active:scale-95 transition-transform"
                        style={{ backgroundColor: pal.surface, color: pal.accent }}
                      >
                        OK
                      </button>
                    </div>
                  )}
                </div>

                {lastKey && (
                  <div className="text-center text-[11.5px] font-mono tabular-nums" style={{ color: pal.muted }}>
                    Sent: {lastKey}
                  </div>
                )}

                {/* Back / Home / Menu */}
                <div className="grid grid-cols-3 gap-2">
                  {[
                    { label: 'Back', code: 4, icon: ChevronLeft },
                    { label: 'Home', code: 3, icon: Home },
                    { label: 'Menu', code: 82, icon: Menu }
                  ].map((k) => {
                    const Icon = k.icon;
                    return (
                      <button
                        key={k.label}
                        onClick={() => sendRemoteKey(k.label, k.code)}
                        className="h-11 rounded-[14px] flex items-center justify-center gap-1.5 text-[13px] font-semibold cursor-pointer"
                        style={{ backgroundColor: pal.tint, color: pal.accent }}
                      >
                        <Icon className="w-4 h-4" />
                        {k.label}
                      </button>
                    );
                  })}
                </div>

                {/* Volume & Playback */}
                <div className="grid grid-cols-5 gap-1.5">
                  {[
                    { icon: Volume2, label: 'Vol-', code: 25 },
                    { icon: SkipBack, label: 'Prev', code: 88 },
                    { icon: Play, label: 'Play', code: 85 },
                    { icon: SkipForward, label: 'Next', code: 87 },
                    { icon: VolumeX, label: 'Mute', code: 164 }
                  ].map((k) => {
                    const Icon = k.icon;
                    return (
                      <button
                        key={k.label}
                        onClick={() => sendRemoteKey(k.label, k.code)}
                        aria-label={k.label}
                        className="h-11 rounded-[14px] flex items-center justify-center cursor-pointer"
                        style={{ backgroundColor: pal.tint, color: pal.accent }}
                      >
                        <Icon className="w-4 h-4" />
                      </button>
                    );
                  })}
                </div>

                <div className="flex items-center justify-between pt-1">
                  <button
                    onClick={() => sendRemoteKey('Mute', 164)}
                    className="text-[13px] font-semibold cursor-pointer"
                    style={{ color: pal.accent }}
                  >
                    Mute
                  </button>
                  <button
                    onClick={() => onScreenChange('MIRROR')}
                    className="text-[13px] font-semibold cursor-pointer"
                    style={{ color: pal.accent }}
                  >
                    Screen Mirror
                  </button>
                </div>
              </div>
            )}

            {/* SCREEN MIRROR */}
            {screen === 'MIRROR' && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">Screen Mirror</h2>
                <div className="flex items-center justify-between">
                  <span className="text-[13.5px] font-bold" style={{ color: mirrorPaused ? pal.muted : pal.green }}>
                    {mirrorPaused ? 'Mirror paused' : 'Live view'}
                  </span>
                  <button
                    onClick={() => setMirrorPaused((p) => !p)}
                    className="text-[13px] font-semibold cursor-pointer"
                    style={{ color: pal.accent }}
                  >
                    {mirrorPaused ? 'Resume mirror' : 'Pause mirror'}
                  </button>
                </div>

                {/* Live TV Frame Preview */}
                <div
                  onClick={(e) => {
                    const rect = e.currentTarget.getBoundingClientRect();
                    const x = Math.round(((e.clientX - rect.left) / rect.width) * 1920);
                    const y = Math.round(((e.clientY - rect.top) / rect.height) * 1080);
                    setMirrorCursor({ x: e.clientX - rect.left, y: e.clientY - rect.top });
                    sendRemoteKey(`Tap (${x}, ${y})`, 0);
                  }}
                  className="relative w-full aspect-video rounded-[16px] overflow-hidden cursor-crosshair flex flex-col justify-between p-3"
                  style={{
                    backgroundColor: '#101820',
                    border: isBroken ? 'none' : `1px solid ${pal.edge}`
                  }}
                >
                  <div className="flex items-center justify-between text-[10px] text-slate-300">
                    <span>Google TV Home · 1920×1080</span>
                    <span>{mirrorPaused ? 'PAUSED' : 'LIVE'}</span>
                  </div>
                  <div className="grid grid-cols-4 gap-1.5 my-auto">
                    {['YouTube', 'SmartTube', 'Jellyfin', 'Netflix'].map((tile, i) => (
                      <div
                        key={tile}
                        className="h-10 rounded-md flex items-center justify-center text-[9.5px] font-bold text-white"
                        style={{
                          backgroundColor: i === 1 ? '#00587B' : '#1E293B',
                          border: i === 1 ? '1.5px solid #B0DEFF' : '1px solid #334155'
                        }}
                      >
                        {tile}
                      </div>
                    ))}
                  </div>
                  {mirrorCursor && (
                    <span
                      className="absolute w-5 h-5 -ml-2.5 -mt-2.5 rounded-full border-2 border-[#B0DEFF] bg-[#B0DEFF]/30 pointer-events-none"
                      style={{ left: mirrorCursor.x, top: mirrorCursor.y }}
                    />
                  )}
                  <div className="text-[9.5px] text-slate-400">
                    {lastKey || 'Tap anywhere on the TV screen to send touch/gesture'}
                  </div>
                </div>

                <div className="flex items-center justify-between pt-1">
                  <button
                    onClick={() => showNotice('Saved TV screenshot PNG.')}
                    className="flex items-center gap-1.5 text-[13px] font-semibold cursor-pointer"
                    style={{ color: pal.accent }}
                  >
                    <Camera className="w-4 h-4" />
                    Take screenshot
                  </button>
                  <button
                    onClick={() =>
                      showNotice(
                        isBroken
                          ? 'screenrecord failed (bitrate unsupported on TV encoder).'
                          : 'Recording 15s MP4 (--bit-rate 4000000 fallback active).'
                      )
                    }
                    className="flex items-center gap-1.5 text-[13px] font-semibold cursor-pointer"
                    style={{ color: pal.accent }}
                  >
                    <Video className="w-4 h-4" />
                    Record
                  </button>
                </div>
              </div>
            )}

            {/* ADB SHELL / TERMINAL */}
            {screen === 'SHELL' && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">ADB Shell / Terminal</h2>
                <p className="text-[12.5px] leading-relaxed" style={{ color: pal.muted }}>
                  Runs a non-interactive command as the connected TV’s shell user. Results include stdout, stderr, and exit code.
                </p>
                <div
                  className="p-2.5 rounded-[8px]"
                  style={{ backgroundColor: pal.surface, border: `1px solid ${pal.edge}` }}
                >
                  <div className="text-[10.5px] mb-1" style={{ color: pal.muted }}>
                    Command
                  </div>
                  <textarea
                    rows={2}
                    value={shellCmd}
                    onChange={(e) => setShellCmd(e.target.value)}
                    className="w-full bg-transparent outline-none text-[13px] font-mono resize-none"
                    style={{ color: pal.ink }}
                  />
                </div>
                <button
                  onClick={runShellCommand}
                  className="w-full h-11 rounded-[14px] font-bold text-[14px] cursor-pointer"
                  style={{ backgroundColor: pal.accent, color: pal.onAccent }}
                >
                  Run command
                </button>
                <div className="flex items-center gap-4 text-[13px] font-semibold" style={{ color: pal.accent }}>
                  <button
                    onClick={() => showNotice(shellOut ? 'Output copied.' : 'No output to copy.')}
                    className="cursor-pointer flex items-center gap-1"
                  >
                    <Copy className="w-3.5 h-3.5" />
                    Copy output
                  </button>
                  <button onClick={() => setShellOut('')} className="cursor-pointer">
                    Clear output
                  </button>
                </div>
                {shellOut && (
                  <pre
                    className="p-3 rounded-[16px] text-[11px] font-mono whitespace-pre-wrap break-all leading-relaxed"
                    style={{ backgroundColor: pal.field, border: `1.5px solid ${pal.edge}` }}
                  >
                    {shellOut}
                  </pre>
                )}
              </div>
            )}

            {/* TV CARE HUB */}
            {screen === 'CARE' && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">Cleanup and restore</h2>
                <div className="space-y-2">
                  {[
                    { title: 'Clean Up TV', sub: `${disabledCount} off`, icon: Sparkles, col: pal.accent, go: () => onScreenChange('CLEAN') },
                    { title: 'Check my TV', sub: '', icon: Check, col: pal.orange, go: () => onScreenChange('CHECK') },
                    { title: 'Free up space', sub: '', icon: HardDrive, col: pal.accent, go: () => onScreenChange('SPACE') },
                    { title: 'Block Ads', sub: dnsActive ? dnsHost : '', icon: Shield, col: pal.purple, go: () => setSheet('dns') },
                    { title: 'Restore', sub: '', icon: RotateCcw, col: pal.accent, go: () => onScreenChange('RESTORE') }
                  ].map((t) => {
                    const Icon = t.icon;
                    return (
                      <button
                        key={t.title}
                        onClick={t.go}
                        className="w-full flex items-center gap-3 p-3 text-left cursor-pointer"
                        style={{ borderRadius: pal.corner, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                      >
                        <Icon className="w-5 h-5 shrink-0" style={{ color: t.col }} />
                        <div className="flex-1 min-w-0">
                          <div className="text-[14px] font-bold">{t.title}</div>
                          {t.sub && (
                            <div className="text-[11px]" style={{ color: pal.muted }}>
                              {t.sub}
                            </div>
                          )}
                        </div>
                        <ChevronRight className="w-4 h-4" style={{ color: pal.muted }} />
                      </button>
                    );
                  })}
                </div>
                <h3 className="text-[17px] font-bold pt-2">Device tools</h3>
                <div className="space-y-2">
                  <button
                    onClick={() => onScreenChange('INFO')}
                    className="w-full flex items-center gap-3 p-3 text-left cursor-pointer"
                    style={{ borderRadius: pal.corner, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                  >
                    <Activity className="w-5 h-5" style={{ color: pal.accent }} />
                    <span className="text-[14px] font-bold flex-1">Device Info</span>
                    <ChevronRight className="w-4 h-4" style={{ color: pal.muted }} />
                  </button>
                  <button
                    onClick={() => onScreenChange('SHELL')}
                    className="w-full flex items-center gap-3 p-3 text-left cursor-pointer"
                    style={{ borderRadius: pal.corner, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                  >
                    <Terminal className="w-5 h-5" style={{ color: pal.purple }} />
                    <span className="text-[14px] font-bold flex-1">ADB Shell / Terminal</span>
                    <ChevronRight className="w-4 h-4" style={{ color: pal.muted }} />
                  </button>
                </div>
              </div>
            )}

            {/* CLEAN UP TV (AutoComplete Dark Dropdown bug showcase!) */}
            {screen === 'CLEAN' && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">Clean Up TV</h2>
                <p className="text-[12.5px] leading-relaxed" style={{ color: pal.muted }}>
                  Debloat app lists are taken from CleanYourTV. Review suggestions for your TV model.
                </p>
                <div className="relative">
                  <input
                    value={profileQuery}
                    onFocus={() => setProfileOpen(true)}
                    onChange={(e) => {
                      setProfileQuery(e.target.value);
                      setProfileOpen(true);
                    }}
                    placeholder="Search device profiles"
                    className="w-full px-3 py-2.5 rounded-[12px] text-[13px] font-mono outline-none"
                    style={{
                      backgroundColor: pal.field,
                      color: pal.ink,
                      border: `1px solid ${profileOpen ? pal.accent : pal.edge}`
                    }}
                  />
                  {profileOpen && (
                    <div
                      className="mt-1 rounded-[12px] overflow-hidden shadow-lg"
                      style={{
                        // BROKEN: renders stark white Light dropdown inside Dark Mode!
                        backgroundColor: isBroken && isDark ? '#FFFFFF' : pal.field,
                        border: `1px solid ${isBroken && isDark ? '#EF4444' : pal.edge}`
                      }}
                    >
                      {CLEANUP_PROFILES.filter((p) =>
                        p.toLowerCase().includes(profileQuery.toLowerCase().slice(0, 5))
                      )
                        .slice(0, 4)
                        .map((prof) => (
                          <button
                            key={prof}
                            onClick={() => {
                              setProfileQuery(prof);
                              setProfileOpen(false);
                              showNotice(`Loaded profile ${prof}.`);
                            }}
                            className="w-full text-left px-3 py-2 text-[12px] font-mono cursor-pointer border-b last:border-b-0"
                            style={{
                              color: isBroken && isDark ? '#94A3B8' : pal.ink,
                              borderColor: isBroken && isDark ? '#E2E8F0' : pal.edge
                            }}
                          >
                            {prof}
                          </button>
                        ))}
                    </div>
                  )}
                </div>

                <button
                  onClick={() => setProfileOpen(false)}
                  className="w-full h-11 rounded-[14px] font-bold text-[14px] cursor-pointer"
                  style={{ backgroundColor: pal.accent, color: pal.onAccent }}
                >
                  Review this profile
                </button>

                <div className="text-[13.5px] font-bold pt-1">3 suggestions found on this TV</div>
                {apps
                  .filter((a) => a.system && a.id !== 'com.google.android.tvlauncher')
                  .map((app) => (
                    <button
                      key={app.id}
                      onClick={() => handleToggleApp(app)}
                      className="w-full p-3 text-left flex items-center justify-between cursor-pointer"
                      style={{ borderRadius: pal.corner, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                    >
                      <div className="min-w-0">
                        <div className="text-[12.5px] font-mono truncate">{app.id}</div>
                        <div className="text-[11px]" style={{ color: app.enabled ? pal.muted : pal.green }}>
                          {app.enabled ? 'Tap to turn off safely' : 'Disabled · Recorded in Restore'}
                        </div>
                      </div>
                      <Trash2 className="w-4 h-4 shrink-0" style={{ color: pal.muted }} />
                    </button>
                  ))}
              </div>
            )}

            {/* CHECK MY TV / RESTORE / SPACE */}
            {(screen === 'CHECK' || screen === 'RESTORE' || screen === 'SPACE') && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">
                  {screen === 'CHECK' ? 'Check my TV' : screen === 'RESTORE' ? 'Restore' : 'Free up space'}
                </h2>
                <div
                  className="p-4 rounded-[18px] text-[13.5px] leading-relaxed"
                  style={{ backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                >
                  {screen === 'CHECK' && (
                    <>
                      <div className="font-bold">Cleaned by A4 TV Bridge</div>
                      <div style={{ color: pal.muted }}>{disabledCount} recorded app(s) still off</div>
                    </>
                  )}
                  {screen === 'RESTORE' && (
                    <div>
                      {apps.filter((a) => !a.enabled).length === 0
                        ? 'No recorded apps are currently disabled.'
                        : `${apps.filter((a) => !a.enabled).length} disabled app(s) ready to restore.`}
                    </div>
                  )}
                  {screen === 'SPACE' && (
                    <div className="tabular-nums font-semibold">
                      {isBroken ? '— free · — total (df line wrap)' : '9.2 GB free · 16.0 GB total'}
                    </div>
                  )}
                </div>

                {screen === 'RESTORE' &&
                  apps
                    .filter((a) => !a.enabled)
                    .map((a) => (
                      <button
                        key={a.id}
                        onClick={() => handleToggleApp(a)}
                        className="w-full flex items-center justify-between p-3 text-left cursor-pointer"
                        style={{ borderRadius: pal.corner, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                      >
                        <div>
                          <div className="text-[13.5px] font-bold">{a.name}</div>
                          <div className="text-[11px]" style={{ color: pal.muted }}>
                            Turn back on
                          </div>
                        </div>
                        <RotateCcw className="w-4 h-4" style={{ color: pal.accent }} />
                      </button>
                    ))}

                {screen === 'SPACE' && (
                  <button
                    onClick={() => showNotice('Cache trim requested (pm trim-caches 999G).')}
                    className="w-full h-11 rounded-[14px] font-bold text-[14px] cursor-pointer"
                    style={{ backgroundColor: pal.accent, color: pal.onAccent }}
                  >
                    Clear app caches
                  </button>
                )}
              </div>
            )}

            {/* DEVICE INFO MONITOR */}
            {screen === 'INFO' && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">Device Info</h2>
                <div
                  className="grid grid-cols-4 gap-1 p-1 rounded-[13px]"
                  style={{ backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                >
                  {(['Overview', 'CPU', 'Memory', 'Network'] as const).map((t) => {
                    const active = monitorTab === t;
                    return (
                      <button
                        key={t}
                        onClick={() => setMonitorTab(t)}
                        className="h-9 rounded-[9px] text-[11.5px] font-semibold cursor-pointer"
                        style={{
                          backgroundColor: active ? pal.tint : 'transparent',
                          color: active ? pal.accent : pal.muted
                        }}
                      >
                        {t}
                      </button>
                    );
                  })}
                </div>

                <div
                  className="p-3.5 rounded-[18px] space-y-3"
                  style={{ backgroundColor: pal.field, border: `1.5px solid ${pal.edge}` }}
                >
                  {monitorTab === 'Overview' && (
                    <div className="grid grid-cols-3 gap-2 text-center">
                      {[
                        { name: 'CPU', val: cpu, col: pal.accent },
                        { name: 'Memory', val: ram, col: pal.purple },
                        { name: 'Storage', val: isBroken ? 0 : 43, col: pal.green }
                      ].map((g) => (
                        <div key={g.name} className="flex flex-col items-center">
                          <svg className="w-16 h-16 -rotate-90" viewBox="0 0 64 64">
                            <circle cx="32" cy="32" r="26" fill="none" stroke={pal.edge} strokeWidth="5" />
                            {g.val > 0 && (
                              <circle
                                cx="32"
                                cy="32"
                                r="26"
                                fill="none"
                                stroke={g.col}
                                strokeWidth="5"
                                strokeLinecap="round"
                                strokeDasharray={`${(g.val / 100) * 163.3} 163.3`}
                              />
                            )}
                          </svg>
                          <div className="-mt-11 mb-5 text-[13px] font-bold tabular-nums">
                            {g.val > 0 ? `${g.val}%` : '—'}
                          </div>
                          <div className="text-[12px] font-bold">{g.name}</div>
                        </div>
                      ))}
                    </div>
                  )}

                  {monitorTab === 'CPU' && (
                    <div>
                      <div className="text-[22px] font-bold tabular-nums">{cpu}%</div>
                      <div className="text-[12px]" style={{ color: pal.muted }}>
                        4 CPU cores · ARMv8 Cortex-A55
                      </div>
                    </div>
                  )}

                  {monitorTab === 'Memory' && (
                    <div>
                      <div className="text-[22px] font-bold tabular-nums">{ram}%</div>
                      <div className="text-[12px] tabular-nums" style={{ color: pal.muted }}>
                        1.2 GB of 2.0 GB · PSS breakdown active
                      </div>
                    </div>
                  )}

                  {monitorTab === 'Network' && (
                    <div className="grid grid-cols-2 gap-2">
                      <div>
                        <div className="text-[18px] font-bold tabular-nums" style={{ color: pal.accent }}>
                          {netDown} KB/s
                        </div>
                        <div className="text-[11.5px]" style={{ color: pal.muted }}>
                          ↓ Download
                        </div>
                      </div>
                      <div>
                        <div className="text-[18px] font-bold tabular-nums" style={{ color: pal.orange }}>
                          {netUp} KB/s
                        </div>
                        <div className="text-[11.5px]" style={{ color: pal.muted }}>
                          ↑ Upload
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Sparkline */}
                  <svg className="w-full h-20" viewBox="0 0 260 72">
                    {[18, 36, 54].map((y) => (
                      <line key={y} x1="0" y1={y} x2="260" y2={y} stroke={pal.edge} strokeWidth="1" />
                    ))}
                    <polyline
                      fill="none"
                      stroke={monitorTab === 'Memory' ? pal.purple : pal.accent}
                      strokeWidth="2.5"
                      strokeLinejoin="round"
                      points={trend
                        .map((v, idx) => `${(idx / Math.max(1, trend.length - 1)) * 260},${68 - (v / 100) * 60}`)
                        .join(' ')}
                    />
                  </svg>
                </div>
              </div>
            )}

            {/* APPEARANCE */}
            {screen === 'APPEARANCE' && (
              <div className="space-y-3">
                <h2 className="text-[20px] font-bold">Appearance</h2>
                {(
                  [
                    { id: 'system', label: 'System Default', desc: 'Follow Android’s light or dark setting.', darkSample: true },
                    { id: 'light', label: 'Light', desc: 'White surfaces with neutral gray borders.', darkSample: false },
                    { id: 'dark', label: 'Dark', desc: 'True black with ice-blue controls.', darkSample: true }
                  ] as const
                ).map((choice) => {
                  const active = theme === choice.id;
                  return (
                    <button
                      key={choice.id}
                      onClick={() => onThemeChange(choice.id)}
                      className="w-full flex items-center gap-3 p-3.5 text-left cursor-pointer"
                      style={{
                        borderRadius: pal.corner,
                        backgroundColor: !isBroken && active ? pal.tint : pal.field,
                        border: `${!isBroken && active ? 2 : 1}px solid ${active ? pal.accent : pal.edge}`
                      }}
                    >
                      <div
                        className="w-8 h-8 rounded-full flex items-center justify-center shrink-0"
                        style={{ backgroundColor: choice.darkSample ? '#142835' : '#DDF3FC' }}
                      >
                        <div
                          className="w-3.5 h-3.5 rounded-full"
                          style={{ backgroundColor: choice.darkSample ? '#B0DEFF' : '#00587B' }}
                        />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="text-[15px] font-bold">{choice.label}</div>
                        <div
                          className={isBroken ? 'text-[15px] leading-snug mt-0.5' : 'text-[12.5px] leading-snug mt-0.5'}
                          style={{ color: pal.muted }}
                        >
                          {choice.desc}
                        </div>
                      </div>
                      <div
                        className="w-5 h-5 rounded-full border-2 flex items-center justify-center shrink-0"
                        style={{ borderColor: active ? pal.accent : pal.muted }}
                      >
                        {active && (
                          <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: pal.accent }} />
                        )}
                      </div>
                    </button>
                  );
                })}
                <button
                  onClick={() => setSheet('about')}
                  className="text-[14px] font-semibold pt-2 cursor-pointer"
                  style={{ color: pal.accent }}
                >
                  About A4 TV Bridge
                </button>
              </div>
            )}
          </div>

          {/* BOTTOM DOCK */}
          {showDock && (
            <div className="px-3 pb-3 pt-1 shrink-0">
              <div
                className="relative grid grid-cols-5 items-end px-1 py-1.5"
                style={{
                  borderRadius: pal.corner,
                  backgroundColor: pal.field,
                  border: `1px solid ${pal.edge}`
                }}
              >
                {[
                  { id: 'DASHBOARD' as ScreenId, label: 'Dashboard', icon: Sliders },
                  { id: 'LIBRARY' as ScreenId, label: 'Apps', icon: AppWindow },
                  { id: null, label: 'Control', icon: Tv },
                  { id: 'SHELL' as ScreenId, label: isBroken ? 'ADB Shell /\nTerm..' : 'Terminal', icon: Terminal },
                  { id: 'CARE' as ScreenId, label: 'TV Care', icon: Shield }
                ].map((tab) => {
                  const Icon = tab.icon;
                  const isControl = tab.id === null;
                  const active = tab.id === screen;
                  return (
                    <button
                      key={tab.label}
                      onClick={() => {
                        if (isControl) setSheet('control');
                        else onScreenChange(tab.id!);
                      }}
                      className="flex flex-col items-center justify-center py-1 rounded-[12px] cursor-pointer"
                      style={{
                        backgroundColor: active ? pal.tint : 'transparent',
                        color: active || isControl ? pal.accent : pal.muted
                      }}
                    >
                      {isControl ? (
                        <div
                          className="w-10 h-10 -mt-5 mb-0.5 rounded-full flex items-center justify-center shadow-md"
                          style={{ backgroundColor: pal.accent, color: pal.onAccent }}
                        >
                          <Icon className="w-5 h-5" />
                        </div>
                      ) : (
                        <Icon className="w-4 h-4 mb-0.5" />
                      )}
                      <span className="text-[9.5px] font-bold leading-tight text-center whitespace-nowrap">
                        {tab.label.split('\n')[0]}
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* Gesture pill */}
          <div className="pb-2 pt-0.5 flex justify-center shrink-0">
            <div className="w-28 h-1 rounded-full opacity-35" style={{ backgroundColor: pal.muted }} />
          </div>

          {/* BOTTOM SHEETS (Connect / Control / About / DNS / Keyboard) */}
          {sheet !== 'none' && (
            <div
              className="absolute inset-0 z-20 flex flex-col justify-end"
              style={{ backgroundColor: 'rgba(0,0,0,0.45)' }}
              onClick={() => setSheet('none')}
            >
              <div
                onClick={(e) => e.stopPropagation()}
                className="p-4 pt-3 space-y-3"
                style={{
                  backgroundColor: pal.surface,
                  // BROKEN: rounds bottom corners too, exposing ugly bottom gaps!
                  borderRadius: isBroken ? '22px' : '22px 22px 0 0',
                  marginBottom: isBroken ? '6px' : '0px',
                  borderTop: `1px solid ${pal.edge}`
                }}
              >
                <div className="w-8 h-1 rounded-full mx-auto opacity-60" style={{ backgroundColor: pal.muted }} />
                <div className="flex items-center justify-between">
                  <div className="text-[18px] font-bold">
                    {sheet === 'connect' && 'Connect your TV'}
                    {sheet === 'control' && 'Control and capture'}
                    {sheet === 'about' && 'About A4 TV Bridge'}
                    {sheet === 'dns' && 'Block Ads · Private DNS'}
                    {sheet === 'keyboard' && 'Send text to TV'}
                  </div>
                  <button
                    onClick={() => setSheet('none')}
                    className="p-1 rounded-full cursor-pointer"
                    style={{ color: pal.muted }}
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>

                {sheet === 'control' && (
                  <div className="grid grid-cols-2 gap-2.5 pb-1">
                    {[
                      { title: 'Remote', icon: Tv, col: pal.purple, sel: true, go: () => { setSheet('none'); onScreenChange('REMOTE'); } },
                      { title: 'Screen Mirror', icon: Activity, col: pal.accent, sel: false, go: () => { setSheet('none'); onScreenChange('MIRROR'); } },
                      { title: 'Screenshot', icon: Camera, col: pal.accent, sel: false, go: () => { setSheet('none'); showNotice('Saved TV screenshot PNG.'); } },
                      { title: 'Screen Record', icon: Video, col: pal.purple, sel: false, go: () => { setSheet('none'); showNotice(isBroken ? 'screenrecord failed (bitrate error).' : 'Saved 15s MP4 recording.'); } }
                    ].map((tile) => {
                      const Icon = tile.icon;
                      return (
                        <button
                          key={tile.title}
                          onClick={tile.go}
                          className="flex items-center gap-2.5 p-3 text-left cursor-pointer"
                          style={{
                            borderRadius: pal.corner,
                            backgroundColor: tile.sel ? pal.tint : pal.field,
                            border: `1px solid ${pal.edge}`
                          }}
                        >
                          <Icon className="w-5 h-5 shrink-0" style={{ color: tile.col }} />
                          <span className="text-[13px] font-bold">{tile.title}</span>
                        </button>
                      );
                    })}
                  </div>
                )}

                {sheet === 'connect' && (
                  <div className="space-y-2.5">
                    <p className="text-[12px]" style={{ color: pal.muted }}>
                      Keep your phone and TV on the same network. Turn on ADB debugging on the TV.
                    </p>
                    <button
                      onClick={() => {
                        setScanningLan(true);
                        window.setTimeout(() => {
                          setScanningLan(false);
                          setDiscoveredTvs([
                            { name: 'Xiaomi Mi TV (AXSO2)', address: '192.168.1.42:5555' },
                            { name: 'Google TV Streamer', address: '192.168.1.88:37421' }
                          ]);
                        }, 1200);
                      }}
                      className="w-full flex items-center gap-2.5 p-3 text-left cursor-pointer"
                      style={{ borderRadius: pal.corner, backgroundColor: pal.tint, border: `1px solid ${pal.edge}` }}
                    >
                      <RefreshCw className={`w-4 h-4 ${scanningLan ? 'animate-spin' : ''}`} style={{ color: pal.accent }} />
                      <span className="text-[13.5px] font-bold">
                        {scanningLan ? 'Scanning local network…' : 'Find TVs on this network'}
                      </span>
                    </button>

                    <div
                      className="space-y-1.5 overflow-y-auto thin-scroll"
                      style={{ maxHeight: isBroken ? 64 : 156 }}
                    >
                      {discoveredTvs.map((tv) => (
                        <button
                          key={tv.address}
                          onClick={() => {
                            if (isBroken && scanningLan) {
                              showNotice('A connection or TV command is already in progress.');
                              return;
                            }
                            setScanningLan(false);
                            setTvName(tv.name);
                            onConnectedChange(true);
                            onScreenChange('DASHBOARD');
                            setSheet('none');
                            showNotice(`Connected to ${tv.name}.`);
                          }}
                          className="w-full flex items-center justify-between p-2.5 text-left cursor-pointer"
                          style={{ borderRadius: 12, backgroundColor: pal.field, border: `1px solid ${pal.edge}` }}
                        >
                          <div>
                            <div className="text-[13px] font-bold">{tv.name}</div>
                            <div className="text-[11px] font-mono" style={{ color: pal.muted }}>
                              {tv.address}
                            </div>
                          </div>
                          <ChevronRight className="w-4 h-4" style={{ color: pal.muted }} />
                        </button>
                      ))}
                    </div>

                    {connected && (
                      <button
                        onClick={() => {
                          onConnectedChange(false);
                          onScreenChange('WELCOME');
                          setSheet('none');
                        }}
                        className="w-full py-2 text-[13px] font-semibold cursor-pointer text-center"
                        style={{ color: '#F87171' }}
                      >
                        Disconnect current TV
                      </button>
                    )}
                  </div>
                )}

                {sheet === 'about' && (
                  <div className="space-y-3">
                    <div className="flex items-center gap-3">
                      <img
                        src="/src/assets/a4_mark.webp"
                        alt="A4 mark"
                        className="w-11 h-11 rounded-xl object-cover"
                        referrerPolicy="no-referrer"
                      />
                      <div>
                        <div className="text-[14px] font-bold">Version 1.0.0</div>
                        <div className="text-[12px]" style={{ color: pal.muted }}>
                          RatherAsif04 / A4-TV-Bridge-Test
                        </div>
                      </div>
                    </div>
                    <div
                      className="p-3 rounded-[16px] space-y-2 text-[13px] font-semibold"
                      style={{ backgroundColor: pal.field, border: `1px solid ${pal.edge}`, color: pal.accent }}
                    >
                      <div className="flex items-center justify-between">
                        <span>RatherAsif04 on GitHub</span>
                        <ExternalLink className="w-3.5 h-3.5" style={{ color: pal.muted }} />
                      </div>
                      <div className="flex items-center justify-between">
                        <span>Open-source notices (MIT / GPL-3.0)</span>
                        <ChevronRight className="w-3.5 h-3.5" style={{ color: pal.muted }} />
                      </div>
                    </div>
                    <button
                      onClick={() => setSheet('none')}
                      className="w-full h-11 rounded-[14px] font-bold text-[14px] cursor-pointer"
                      style={{ backgroundColor: pal.accent, color: pal.onAccent }}
                    >
                      Close
                    </button>
                  </div>
                )}

                {sheet === 'dns' && (
                  <div className="space-y-3">
                    <input
                      value={dnsHost}
                      onChange={(e) => setDnsHost(e.target.value)}
                      className="w-full px-3 py-2 rounded-[10px] text-[13px] font-mono outline-none"
                      style={{ backgroundColor: pal.field, color: pal.ink, border: `1px solid ${pal.edge}` }}
                    />
                    <div className="grid grid-cols-2 gap-2">
                      <button
                        onClick={() => {
                          setDnsActive(true);
                          setSheet('none');
                          showNotice(`Private DNS set to ${dnsHost}.`);
                        }}
                        className="h-10 rounded-[12px] font-bold text-[13px] cursor-pointer"
                        style={{ backgroundColor: pal.accent, color: pal.onAccent }}
                      >
                        Apply DNS
                      </button>
                      <button
                        onClick={() => {
                          setDnsActive(false);
                          setSheet('none');
                          showNotice('Private DNS restored to previous settings.');
                        }}
                        className="h-10 rounded-[12px] font-semibold text-[13px] cursor-pointer"
                        style={{ backgroundColor: pal.field, color: pal.ink, border: `1px solid ${pal.edge}` }}
                      >
                        Restore
                      </button>
                    </div>
                  </div>
                )}

                {sheet === 'keyboard' && (
                  <div className="space-y-3">
                    <input
                      value={keyboardText}
                      onChange={(e) => setKeyboardText(e.target.value)}
                      placeholder="Type search query or URL…"
                      className="w-full px-3 py-2 rounded-[10px] text-[13px] outline-none"
                      style={{ backgroundColor: pal.field, color: pal.ink, border: `1px solid ${pal.edge}` }}
                    />
                    <button
                      onClick={() => {
                        if (!keyboardText.trim()) return;
                        setSheet('none');
                        showNotice(
                          isBroken && keyboardText.includes('%')
                            ? 'input text failed on special character (%).'
                            : `Sent "${keyboardText.trim()}" to TV.`
                        );
                        setKeyboardText('');
                      }}
                      className="w-full h-10 rounded-[12px] font-bold text-[13.5px] cursor-pointer"
                      style={{ backgroundColor: pal.accent, color: pal.onAccent }}
                    >
                      Send text to TV
                    </button>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
