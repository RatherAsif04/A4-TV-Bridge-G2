import { GIFEncoder, quantize, applyPalette } from 'gifenc';

export type GifPresetId =
  | 'theme-light-dark'
  | 'remote-control'
  | 'apps-files'
  | 'shell-navigation'
  | 'tv-care-monitor';

export interface GifPreset {
  id: GifPresetId;
  filename: string;
  title: string;
  subtitle: string;
  markdownAlt: string;
  highlights: string[];
}

export const GIF_PRESETS: GifPreset[] = [
  {
    id: 'theme-light-dark',
    filename: 'a4-theme-light-dark-fix.gif',
    title: 'Theme & Dark Mode Fix',
    subtitle: 'True Black dark theme, Welcome card, clean sheet corners, and dark dropdowns.',
    markdownAlt: 'A4 TV Bridge Light and Dark theme fixes comparison',
    highlights: ['True Black #000000 canvas', 'Top-only sheet radii', 'Themed Clean Up dropdown']
  },
  {
    id: 'remote-control',
    filename: 'a4-remote-control-fix.gif',
    title: 'Remote D-pad & Touchpad Queue',
    subtitle: 'Rapid D-pad inputs without "Wait for current operation" lockups or layout bounce.',
    markdownAlt: 'A4 TV Bridge non-blocking Remote D-pad and Touchpad demo',
    highlights: ['Queued input channel', 'Zero layout bounce', 'Dark-adapted D-pad palette']
  },
  {
    id: 'apps-files',
    filename: 'a4-apps-files-fix.gif',
    title: 'Apps Tabs, File Manager & 1-Click Install',
    subtitle: 'Active filter tab states, Toybox-safe rename/upload, and GitHub API headers.',
    markdownAlt: 'A4 TV Bridge Apps filter tabs, File Manager, and 1-Click Install fix',
    highlights: ['Active tab highlight', 'Toybox-safe mv/realpath', 'User-Agent & ABI matching']
  },
  {
    id: 'shell-navigation',
    filename: 'a4-shell-navigation-fix.gif',
    title: 'ADB Shell & Persistent Bottom Dock',
    subtitle: 'Bottom dock stays anchored in Terminal with clean POSIX df -Pk storage readout.',
    markdownAlt: 'A4 TV Bridge persistent bottom navigation dock on ADB Shell Terminal',
    highlights: ['Panel.SHELL in primaryPanels', 'Clean 2-line dock label', 'Live exit status output']
  },
  {
    id: 'tv-care-monitor',
    filename: 'a4-tv-care-monitor-fix.gif',
    title: 'TV Care Profile Picker & Device Monitor',
    subtitle: 'Auto-matched dark profile dropdown, safe rollback order, and live telemetry.',
    markdownAlt: 'A4 TV Bridge Clean Up TV dark dropdown and live Device Info monitor',
    highlights: ['Auto-matched TV profile', 'Dark dropdown contrast', 'Live CPU/RAM/Net gauges']
  }
];

export interface GifBuildOptions {
  mode: 'compare' | 'fixed';
  speed: 'normal' | 'fast';
}

export interface GeneratedGif {
  presetId: GifPresetId;
  url: string;
  bytes: number;
  width: number;
  height: number;
  frames: number;
}

interface Palette {
  surface: string;
  field: string;
  ink: string;
  muted: string;
  accent: string;
  onAccent: string;
  edge: string;
  tint: string;
  green: string;
  purple: string;
  orange: string;
}

const DARK: Palette = {
  surface: '#000000',
  field: '#111418',
  ink: '#EFF6FF',
  muted: '#ADB8C5',
  accent: '#B0DEFF',
  onAccent: '#0A1D2D',
  edge: '#303840',
  tint: '#142835',
  green: '#A5ECC8',
  purple: '#C5B4FF',
  orange: '#F7BD8C'
};

const LIGHT: Palette = {
  surface: '#FFFFFF',
  field: '#F8F9FB',
  ink: '#182238',
  muted: '#465469',
  accent: '#00587B',
  onAccent: '#FFFFFF',
  edge: '#DADDDF',
  tint: '#DDF3FC',
  green: '#15573E',
  purple: '#5C45A0',
  orange: '#9A4E13'
};

function rrect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number | [number, number, number, number]
) {
  const [tl, tr, br, bl] = typeof r === 'number' ? [r, r, r, r] : r;
  ctx.beginPath();
  ctx.moveTo(x + tl, y);
  ctx.lineTo(x + w - tr, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + tr);
  ctx.lineTo(x + w, y + h - br);
  ctx.quadraticCurveTo(x + w, y + h, x + w - br, y + h);
  ctx.lineTo(x + bl, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - bl);
  ctx.lineTo(x, y + tl);
  ctx.quadraticCurveTo(x, y, x + tl, y);
  ctx.closePath();
}

function drawPhoneFrame(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  pal: Palette,
  label: string,
  badgeColor: string,
  renderScreen: (sx: number, sy: number, sw: number, sh: number) => void
) {
  // Label above device
  ctx.fillStyle = badgeColor;
  ctx.font = '700 12px "Plus Jakarta Sans", system-ui, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText(label, x + w / 2, y - 10);

  // Outer bezel
  rrect(ctx, x, y, w, h, 26);
  ctx.fillStyle = '#1E293B';
  ctx.fill();
  ctx.strokeStyle = '#334155';
  ctx.lineWidth = 2;
  ctx.stroke();

  const inset = 6;
  const sx = x + inset;
  const sy = y + inset;
  const sw = w - inset * 2;
  const sh = h - inset * 2;

  ctx.save();
  rrect(ctx, sx, sy, sw, sh, 20);
  ctx.clip();

  ctx.fillStyle = pal.surface;
  ctx.fillRect(sx, sy, sw, sh);

  // Status bar
  ctx.fillStyle = pal.ink;
  ctx.font = '600 9px "DM Sans", system-ui, sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText('9:41', sx + 14, sy + 14);
  ctx.textAlign = 'right';
  ctx.fillText('Wi-Fi · 100%', sx + sw - 14, sy + 14);

  renderScreen(sx, sy + 20, sw, sh - 20);

  // Gesture home indicator
  rrect(ctx, sx + sw / 2 - 28, sy + sh - 7, 56, 3, 2);
  ctx.fillStyle = pal.muted;
  ctx.globalAlpha = 0.45;
  ctx.fill();
  ctx.globalAlpha = 1;

  ctx.restore();
}

function drawDock(
  ctx: CanvasRenderingContext2D,
  sx: number,
  sy: number,
  sw: number,
  sh: number,
  pal: Palette,
  activeIdx: number,
  broken: boolean
) {
  const dockH = 44;
  const dy = sy + sh - dockH - 12;
  rrect(ctx, sx + 10, dy, sw - 20, dockH, 13);
  ctx.fillStyle = pal.field;
  ctx.fill();
  ctx.strokeStyle = pal.edge;
  ctx.lineWidth = 1;
  ctx.stroke();

  const items = broken
    ? ['Home', 'Apps', 'Control', 'ADB Shell /\nTerm..', 'TV Care']
    : ['Home', 'Apps', 'Control', 'Terminal', 'TV Care'];
  const colW = (sw - 24) / 5;

  items.forEach((lbl, idx) => {
    const cx = sx + 12 + colW * idx + colW / 2;
    const isCenter = idx === 2;
    const isActive = idx === activeIdx;
    if (isActive && !isCenter) {
      rrect(ctx, sx + 12 + colW * idx + 3, dy + 4, colW - 6, dockH - 8, 9);
      ctx.fillStyle = pal.tint;
      ctx.fill();
    }
    if (isCenter) {
      ctx.beginPath();
      ctx.arc(cx, dy + 8, 15, 0, Math.PI * 2);
      ctx.fillStyle = pal.accent;
      ctx.fill();
      ctx.fillStyle = pal.onAccent;
      ctx.font = '700 10px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('TV', cx, dy + 12);
    } else {
      ctx.beginPath();
      ctx.arc(cx, dy + 15, 5, 0, Math.PI * 2);
      ctx.fillStyle = isActive ? pal.accent : pal.muted;
      ctx.fill();
    }
    ctx.fillStyle = isActive || isCenter ? pal.accent : pal.muted;
    ctx.font = '600 8px "DM Sans", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(lbl.split('\n')[0], cx, dy + 35);
  });
}

function renderScene(
  ctx: CanvasRenderingContext2D,
  preset: GifPresetId,
  frame: number,
  totalFrames: number,
  broken: boolean,
  sx: number,
  sy: number,
  sw: number,
  sh: number
) {
  const t = frame / totalFrames;
  const phase = Math.floor(t * 3); // 0, 1, 2
  const useLight = preset === 'theme-light-dark' && phase === 2;
  const pal = useLight ? LIGHT : DARK;

  // Fill background
  ctx.fillStyle = pal.surface;
  ctx.fillRect(sx, sy - 20, sw, sh + 20);

  if (preset === 'theme-light-dark') {
    // Phase 0: Welcome + Connect Sheet, Phase 1: Dark Dashboard + Clean Up Dropdown, Phase 2: Light Dashboard
    if (phase === 0) {
      // Header
      ctx.fillStyle = pal.accent;
      ctx.font = '600 10px "DM Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('Feedback   Appearance   Restore', sx + 14, sy + 14);

      // Welcome card
      rrect(ctx, sx + 12, sy + 26, sw - 24, 82, 16);
      ctx.fillStyle = broken ? '#0B1327' : pal.field;
      ctx.fill();
      if (!broken) {
        ctx.strokeStyle = pal.edge;
        ctx.lineWidth = 1;
        ctx.stroke();
      }
      ctx.fillStyle = '#FFFFFF';
      ctx.font = '700 15px "Plus Jakarta Sans", sans-serif';
      ctx.fillText('A4 TV Bridge', sx + 24, sy + 52);
      ctx.fillStyle = pal.muted;
      ctx.font = '400 9.5px "DM Sans", sans-serif';
      ctx.fillText('Connect to your TV to control', sx + 24, sy + 69);
      ctx.fillText('apps, remote, or send files.', sx + 24, sy + 82);

      // Connect button
      rrect(ctx, sx + 12, sy + 118, sw - 24, 34, 12);
      ctx.fillStyle = pal.accent;
      ctx.fill();
      ctx.fillStyle = pal.onAccent;
      ctx.font = '700 11px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('Connect your TV', sx + sw / 2, sy + 139);

      // Bottom sheet overlay demonstrating bottom corner bug vs fixed top-only corner radius
      ctx.fillStyle = 'rgba(0,0,0,0.45)';
      ctx.fillRect(sx, sy, sw, sh);
      const sheetH = 160;
      const sheetY = sy + sh - sheetH - (broken ? 6 : 0);
      rrect(
        ctx,
        sx,
        sheetY,
        sw,
        sheetH,
        broken ? [18, 18, 18, 18] : [18, 18, 0, 0]
      );
      ctx.fillStyle = pal.surface;
      ctx.fill();
      if (broken) {
        // Highlight exposed dark corner artifacts
        ctx.strokeStyle = '#EF4444';
        ctx.lineWidth = 1.5;
        ctx.strokeRect(sx + 2, sheetY + sheetH - 14, 14, 12);
        ctx.strokeRect(sx + sw - 16, sheetY + sheetH - 14, 14, 12);
      }
      rrect(ctx, sx + sw / 2 - 14, sheetY + 8, 28, 3, 2);
      ctx.fillStyle = pal.muted;
      ctx.fill();
      ctx.fillStyle = pal.ink;
      ctx.font = '700 13px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('Connect your TV', sx + 16, sheetY + 32);

      ['Find TVs on this network', 'Enter TV address', 'Pair with a code'].forEach((item, i) => {
        rrect(ctx, sx + 14, sheetY + 44 + i * 34, sw - 28, 28, 10);
        ctx.fillStyle = i === 0 ? pal.tint : pal.field;
        ctx.fill();
        ctx.strokeStyle = pal.edge;
        ctx.lineWidth = 1;
        ctx.stroke();
        ctx.fillStyle = pal.ink;
        ctx.font = '600 10px "DM Sans", sans-serif';
        ctx.fillText(item, sx + 24, sheetY + 62 + i * 34);
      });
    } else if (phase === 1) {
      // Clean Up TV AutoComplete Dropdown bug vs fix
      ctx.fillStyle = pal.accent;
      ctx.font = '600 10px "DM Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('‹ My TV', sx + 14, sy + 14);
      ctx.fillStyle = pal.ink;
      ctx.font = '700 16px "Plus Jakarta Sans", sans-serif';
      ctx.fillText('Clean Up TV', sx + 14, sy + 40);

      // Search box
      rrect(ctx, sx + 14, sy + 52, sw - 28, 30, 10);
      ctx.fillStyle = pal.field;
      ctx.fill();
      ctx.strokeStyle = pal.accent;
      ctx.lineWidth = 1;
      ctx.stroke();
      ctx.fillStyle = pal.ink;
      ctx.font = '500 10px "JetBrains Mono", monospace';
      ctx.fillText('xiaomi-mi-tv-', sx + 22, sy + 70);

      // Dropdown menu (BROKEN: stark white Light popup in Dark Mode!)
      rrect(ctx, sx + 14, sy + 85, sw - 28, 118, 10);
      ctx.fillStyle = broken ? '#FFFFFF' : pal.field;
      ctx.fill();
      ctx.strokeStyle = broken ? '#EF4444' : pal.edge;
      ctx.lineWidth = broken ? 1.5 : 1;
      ctx.stroke();

      const items = [
        'xiaomi-mi-tv-4a',
        'xiaomi-mitv-axso2',
        'xiaomi-tv-a43-2025',
        'xiaomi-tv-a-pro-2026'
      ];
      items.forEach((p, idx) => {
        ctx.fillStyle = broken ? '#94A3B8' : pal.ink;
        ctx.font = '500 9.5px "JetBrains Mono", monospace';
        ctx.fillText(p, sx + 24, sy + 106 + idx * 25);
        if (idx < items.length - 1) {
          ctx.fillStyle = broken ? '#E2E8F0' : pal.edge;
          ctx.fillRect(sx + 20, sy + 114 + idx * 25, sw - 40, 1);
        }
      });

      ctx.fillStyle = broken ? '#F87171' : pal.green;
      ctx.font = '600 9.5px "DM Sans", sans-serif';
      ctx.fillText(
        broken ? '✕ White Light-theme popup in Dark Mode' : '✓ Dark surface + high-contrast text',
        sx + 14,
        sy + 224
      );
    } else {
      // Light mode Dashboard metric tiles contrast
      ctx.fillStyle = pal.ink;
      ctx.font = '700 13px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('A4 TV Bridge', sx + 14, sy + 16);

      rrect(ctx, sx + 12, sy + 26, sw - 24, 152, 16);
      ctx.fillStyle = pal.field;
      ctx.fill();
      ctx.strokeStyle = pal.edge;
      ctx.lineWidth = 1.5;
      ctx.stroke();

      ctx.fillStyle = pal.ink;
      ctx.font = '700 15px "Plus Jakarta Sans", sans-serif';
      ctx.fillText('Living Room TV', sx + 24, sy + 48);
      ctx.fillStyle = pal.green;
      ctx.font = '700 9.5px "DM Sans", sans-serif';
      ctx.fillText('Connected TV', sx + 24, sy + 63);

      // Storage bar
      ctx.fillStyle = pal.muted;
      ctx.font = '600 9px "DM Sans", sans-serif';
      ctx.fillText('Storage used', sx + 24, sy + 85);
      ctx.textAlign = 'right';
      ctx.fillStyle = pal.ink;
      ctx.fillText(broken ? '—' : '6.8 GB / 16.0 GB', sx + sw - 24, sy + 85);
      ctx.textAlign = 'left';

      rrect(ctx, sx + 24, sy + 92, sw - 48, 7, 4);
      ctx.fillStyle = pal.edge;
      ctx.fill();
      if (!broken) {
        rrect(ctx, sx + 24, sy + 92, (sw - 48) * 0.43, 7, 4);
        ctx.fillStyle = pal.accent;
        ctx.fill();
      }

      // 3 metric sub-cards
      const mW = (sw - 58) / 3;
      const metrics = [
        ['CPU', '24%'],
        ['RAM', '61%'],
        ['Network', '420 KB/s']
      ];
      metrics.forEach(([k, v], i) => {
        const mx = sx + 24 + i * (mW + 5);
        rrect(ctx, mx, sy + 112, mW, 50, 9);
        ctx.fillStyle = pal.surface;
        ctx.fill();
        if (!broken) {
          ctx.strokeStyle = pal.edge;
          ctx.lineWidth = 1;
          ctx.stroke();
        }
        ctx.fillStyle = pal.muted;
        ctx.font = '500 8.5px "DM Sans", sans-serif';
        ctx.fillText(k, mx + 7, sy + 129);
        ctx.fillStyle = pal.ink;
        ctx.font = '700 11px "Plus Jakarta Sans", sans-serif';
        ctx.fillText(v, mx + 7, sy + 148);
      });

      drawDock(ctx, sx, sy, sw, sh, pal, 0, broken);
    }
    return;
  }

  if (preset === 'remote-control') {
    const pressIdx = frame % 4;
    const isTouch = phase === 2;
    // Notice bounce bug when broken!
    const bounceOffset = broken && frame % 2 === 1 ? 18 : 0;

    ctx.fillStyle = pal.accent;
    ctx.font = '600 10px "DM Sans", sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText('‹ Back', sx + 14, sy + 14);
    ctx.textAlign = 'right';
    ctx.fillStyle = pal.ink;
    ctx.fillText('Xiaomi TV', sx + sw - 14, sy + 14);

    if (broken && frame % 2 === 1) {
      ctx.fillStyle = '#F87171';
      ctx.font = '600 9px "DM Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('Wait for the current operation.', sx + 14, sy + 30);
    }

    const topY = sy + 26 + bounceOffset;

    // Segmented D-pad / Touchpad switch
    rrect(ctx, sx + 38, topY, sw - 76, 28, 14);
    ctx.fillStyle = pal.tint;
    ctx.fill();

    // Active pill
    rrect(ctx, sx + (isTouch ? sw / 2 : 41), topY + 3, (sw - 82) / 2, 22, 11);
    ctx.fillStyle = pal.surface;
    ctx.fill();

    ctx.font = '600 10px "DM Sans", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillStyle = !isTouch || broken ? pal.accent : pal.muted;
    ctx.fillText('D-pad', sx + 38 + (sw - 76) * 0.25, topY + 18);
    ctx.fillStyle = isTouch || broken ? pal.accent : pal.muted;
    ctx.fillText('Touchpad', sx + 38 + (sw - 76) * 0.75, topY + 18);

    // RemotePad surface
    const padY = topY + 38;
    const padH = 158;
    const grad = ctx.createLinearGradient(sx + 14, padY, sx + sw - 14, padY + padH);
    if (broken) {
      grad.addColorStop(0, '#00587B');
      grad.addColorStop(1, '#1C49CB');
    } else {
      grad.addColorStop(0, '#0E2230');
      grad.addColorStop(1, '#15324A');
    }
    rrect(ctx, sx + 14, padY, sw - 28, padH, 22);
    ctx.fillStyle = grad;
    ctx.fill();
    if (!broken) {
      ctx.strokeStyle = pal.edge;
      ctx.lineWidth = 1;
      ctx.stroke();
    }

    const cx = sx + sw / 2;
    const cy = padY + padH / 2;
    if (isTouch) {
      ctx.fillStyle = '#EFF6FF';
      ctx.font = '600 10.5px "DM Sans", sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('Swipe to move · Tap to select', cx, cy);
      // Animated swipe ripple
      const rx = cx - 24 + ((frame * 14) % 48);
      ctx.beginPath();
      ctx.arc(rx, cy + 26, 8, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(176, 222, 255, 0.35)';
      ctx.fill();
    } else {
      // D-pad sectors
      ctx.beginPath();
      ctx.arc(cx, cy, 56, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(255,255,255,0.12)';
      ctx.fill();

      // Active direction highlight
      if (!broken) {
        const angles = [-Math.PI / 2, 0, Math.PI / 2, Math.PI];
        const a = angles[pressIdx];
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.arc(cx, cy, 56, a - 0.65, a + 0.65);
        ctx.closePath();
        ctx.fillStyle = 'rgba(176, 222, 255, 0.32)';
        ctx.fill();
      }

      // Center OK circle
      ctx.beginPath();
      ctx.arc(cx, cy, 24, 0, Math.PI * 2);
      ctx.fillStyle = pal.surface;
      ctx.fill();
      ctx.fillStyle = pal.accent;
      ctx.font = '700 11px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('OK', cx, cy + 4);

      ctx.fillStyle = '#FFFFFF';
      ctx.font = '700 11px sans-serif';
      ctx.fillText('▲', cx, cy - 36);
      ctx.fillText('▼', cx, cy + 44);
      ctx.fillText('◀', cx - 40, cy + 4);
      ctx.fillText('▶', cx + 40, cy + 4);
    }

    // Bottom status feedback
    const rowY = padY + padH + 14;
    ['Back', 'Home', 'Menu'].forEach((k, i) => {
      const bw = (sw - 40) / 3;
      rrect(ctx, sx + 14 + i * (bw + 6), rowY, bw, 30, 10);
      ctx.fillStyle = pal.tint;
      ctx.fill();
      ctx.fillStyle = pal.accent;
      ctx.font = '600 10px "DM Sans", sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(k, sx + 14 + i * (bw + 6) + bw / 2, rowY + 19);
    });

    ctx.textAlign = 'left';
    ctx.fillStyle = broken ? '#F87171' : pal.green;
    ctx.font = '600 9.5px "DM Sans", sans-serif';
    ctx.fillText(
      broken ? '✕ Drops rapid keys & shifts screen' : `✓ Queued keyevent #${pressIdx + 19} · 0ms jitter`,
      sx + 14,
      sy + sh - 18
    );
    return;
  }

  if (preset === 'apps-files') {
    if (phase === 0) {
      // Apps filter tabs
      const activeTab = frame % 3;
      ctx.fillStyle = pal.ink;
      ctx.font = '700 15px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('Apps', sx + 14, sy + 24);

      const tabs = ['Installed', 'Built-in', 'Running'];
      const tw = (sw - 36) / 3;
      if (!broken) {
        rrect(ctx, sx + 14, sy + 36, sw - 28, 32, 11);
        ctx.fillStyle = pal.field;
        ctx.fill();
        ctx.strokeStyle = pal.edge;
        ctx.lineWidth = 1;
        ctx.stroke();
      }
      tabs.forEach((tab, i) => {
        const tx = sx + 17 + i * (tw + 3);
        const active = i === activeTab;
        rrect(ctx, tx, sy + 40, tw - 2, 24, 8);
        ctx.fillStyle = broken ? pal.field : active ? pal.tint : 'transparent';
        ctx.fill();
        if (broken) {
          ctx.strokeStyle = pal.edge;
          ctx.lineWidth = 1;
          ctx.stroke();
        }
        ctx.fillStyle = broken ? pal.ink : active ? pal.accent : pal.muted;
        ctx.font = '600 9.5px "DM Sans", sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText(tab, tx + (tw - 2) / 2, sy + 55);
      });

      const appRows =
        activeTab === 2 && broken
          ? []
          : activeTab === 1
            ? ['Android TV Home', 'Google Play Services', 'TV Storage']
            : ['SmartTube', 'Jellyfin TV', 'Kodi', 'Aerial Views'];

      if (appRows.length === 0) {
        ctx.fillStyle = '#F87171';
        ctx.font = '500 10px "DM Sans", sans-serif';
        ctx.textAlign = 'left';
        ctx.fillText('No matching apps. (ps -A -o failed)', sx + 14, sy + 96);
      } else {
        appRows.forEach((name, idx) => {
          rrect(ctx, sx + 14, sy + 78 + idx * 44, sw - 28, 36, 11);
          ctx.fillStyle = pal.field;
          ctx.fill();
          ctx.strokeStyle = pal.edge;
          ctx.lineWidth = 1;
          ctx.stroke();
          ctx.fillStyle = pal.ink;
          ctx.font = '600 11px "Plus Jakarta Sans", sans-serif';
          ctx.textAlign = 'left';
          ctx.fillText(name, sx + 26, sy + 100 + idx * 44);
        });
      }
    } else if (phase === 1) {
      // File Manager rename/upload
      ctx.fillStyle = pal.ink;
      ctx.font = '700 15px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('File Manager', sx + 14, sy + 24);
      rrect(ctx, sx + 14, sy + 36, sw - 28, 46, 12);
      ctx.fillStyle = pal.field;
      ctx.fill();
      ctx.strokeStyle = pal.edge;
      ctx.stroke();
      ctx.fillStyle = pal.ink;
      ctx.font = '600 10.5px "DM Sans", sans-serif';
      ctx.fillText('TV storage / Download', sx + 24, sy + 55);
      ctx.fillStyle = pal.accent;
      ctx.font = '600 9px "DM Sans", sans-serif';
      ctx.fillText('Parent   Go to   Refresh   New folder', sx + 24, sy + 72);

      rrect(ctx, sx + 14, sy + 96, sw - 28, 42, 11);
      ctx.fillStyle = pal.field;
      ctx.fill();
      ctx.strokeStyle = pal.edge;
      ctx.stroke();
      ctx.fillStyle = pal.ink;
      ctx.font = '600 11px "DM Sans", sans-serif';
      ctx.fillText('SmartTube_32.80.apk', sx + 26, sy + 121);

      rrect(ctx, sx + 14, sy + 150, sw - 28, 52, 11);
      ctx.fillStyle = broken ? '#2B1418' : '#112E25';
      ctx.fill();
      ctx.strokeStyle = broken ? '#EF4444' : pal.green;
      ctx.stroke();
      ctx.fillStyle = broken ? '#FCA5A5' : pal.green;
      ctx.font = '600 9.5px "JetBrains Mono", monospace';
      ctx.fillText(broken ? "mv: Unknown option 'n'" : '✓ Uploaded & renamed safely', sx + 24, sy + 172);
      ctx.font = '400 8.5px "DM Sans", sans-serif';
      ctx.fillText(broken ? 'Toybox Android 8–12 failure' : 'Portable readlink -f + mv --', sx + 24, sy + 190);
    } else {
      // 1-Click Install
      ctx.fillStyle = pal.ink;
      ctx.font = '700 15px "Plus Jakarta Sans", sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText('1 Click App Install', sx + 14, sy + 24);
      const catalog = ['SmartTube', 'Jellyfin for Android TV', 'Aerial Views'];
      catalog.forEach((app, i) => {
        const y = sy + 40 + i * 48;
        rrect(ctx, sx + 14, y, sw - 28, 40, 11);
        ctx.fillStyle = pal.field;
        ctx.fill();
        ctx.strokeStyle = pal.edge;
        ctx.stroke();
        ctx.fillStyle = pal.ink;
        ctx.font = '600 10.5px "DM Sans", sans-serif';
        ctx.fillText(app, sx + 24, y + 24);
        rrect(ctx, sx + sw - 78, y + 8, 56, 24, 12);
        ctx.fillStyle = pal.tint;
        ctx.fill();
        ctx.fillStyle = pal.accent;
        ctx.font = '700 9.5px "DM Sans", sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('Install', sx + sw - 50, y + 23);
        ctx.textAlign = 'left';
      });
      ctx.fillStyle = broken ? '#FCA5A5' : pal.green;
      ctx.font = '600 9.5px "DM Sans", sans-serif';
      ctx.fillText(
        broken ? '✕ HTTP 403 / No verified APK' : '✓ Verified SHA-256 · Installed SmartTube',
        sx + 14,
        sy + 206
      );
    }
    return;
  }

  if (preset === 'shell-navigation') {
    // Header
    ctx.fillStyle = pal.ink;
    ctx.font = '700 13px "Plus Jakarta Sans", sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText(broken ? '‹ My TV' : 'A4 TV Bridge', sx + 14, sy + 16);

    ctx.fillStyle = pal.ink;
    ctx.font = '700 15px "Plus Jakarta Sans", sans-serif';
    ctx.fillText('ADB Shell / Terminal', sx + 14, sy + 42);

    // Command box
    rrect(ctx, sx + 14, sy + 52, sw - 28, 46, 8);
    ctx.fillStyle = pal.surface;
    ctx.fill();
    ctx.strokeStyle = pal.edge;
    ctx.stroke();
    ctx.fillStyle = pal.muted;
    ctx.font = '500 8.5px "DM Sans", sans-serif';
    ctx.fillText('Command', sx + 22, sy + 65);
    const cmds = ['df -Pk /data', 'getprop ro.product.model', 'pm list packages -3'];
    ctx.fillStyle = pal.ink;
    ctx.font = '500 10px "JetBrains Mono", monospace';
    ctx.fillText(cmds[phase], sx + 22, sy + 84);

    // Run button
    rrect(ctx, sx + 14, sy + 106, sw - 28, 30, 10);
    ctx.fillStyle = pal.accent;
    ctx.fill();
    ctx.fillStyle = pal.onAccent;
    ctx.font = '700 10.5px "Plus Jakarta Sans", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('Run command', sx + sw / 2, sy + 125);

    // Output box
    rrect(ctx, sx + 14, sy + 146, sw - 28, 86, 12);
    ctx.fillStyle = pal.field;
    ctx.fill();
    ctx.strokeStyle = pal.edge;
    ctx.stroke();
    ctx.textAlign = 'left';
    ctx.fillStyle = pal.ink;
    ctx.font = '400 8.5px "JetBrains Mono", monospace';
    const outputs = [
      ['/dev/block/dm-3 16777216', '7129040 9648176 43% /data', '', 'Exit code 0'],
      ['MiTV-AXSO2 (Android 11)', 'arm64-v8a,armeabi-v7a', '', 'Exit code 0'],
      ['package:org.smarttube.stable', 'package:org.jellyfin.tv', '', 'Exit code 0']
    ];
    outputs[phase].forEach((line, i) => {
      ctx.fillStyle = line.startsWith('Exit') ? pal.green : pal.ink;
      ctx.fillText(line, sx + 22, sy + 165 + i * 16);
    });

    if (broken) {
      ctx.fillStyle = '#F87171';
      ctx.font = '600 9.5px "DM Sans", sans-serif';
      ctx.fillText('✕ Bottom dock missing on Panel.SHELL!', sx + 14, sy + sh - 22);
    } else {
      drawDock(ctx, sx, sy, sw, sh, pal, 3, false);
    }
    return;
  }

  // tv-care-monitor
  ctx.fillStyle = pal.accent;
  ctx.font = '600 10px "DM Sans", sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText('‹ My TV', sx + 14, sy + 14);
  ctx.fillStyle = pal.ink;
  ctx.font = '700 15px "Plus Jakarta Sans", sans-serif';
  ctx.fillText('Device Info', sx + 14, sy + 38);

  // Gauges card
  rrect(ctx, sx + 12, sy + 48, sw - 24, 152, 14);
  ctx.fillStyle = pal.field;
  ctx.fill();
  ctx.strokeStyle = pal.edge;
  ctx.stroke();

  const cpuVal = 22 + Math.round(Math.sin(t * Math.PI * 2) * 10 + 10);
  const ramVal = 62;
  const diskVal = broken ? 0 : 43;
  const gaugeData: [string, number, string][] = [
    ['CPU', cpuVal, pal.accent],
    ['RAM', ramVal, pal.purple],
    ['Disk', diskVal, pal.green]
  ];
  gaugeData.forEach(([lbl, val, col], idx) => {
    const gx = sx + 48 + idx * 66;
    const gy = sy + 88;
    ctx.beginPath();
    ctx.arc(gx, gy, 21, 0, Math.PI * 2);
    ctx.strokeStyle = pal.edge;
    ctx.lineWidth = 4;
    ctx.stroke();

    if (val > 0) {
      ctx.beginPath();
      ctx.arc(gx, gy, 21, -Math.PI / 2, -Math.PI / 2 + (val / 100) * Math.PI * 2);
      ctx.strokeStyle = col;
      ctx.lineWidth = 4;
      ctx.stroke();
    }
    ctx.fillStyle = pal.ink;
    ctx.font = '700 10px "Plus Jakarta Sans", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(val > 0 ? `${val}%` : '—', gx, gy + 4);
    ctx.fillStyle = pal.muted;
    ctx.font = '600 9px "DM Sans", sans-serif';
    ctx.fillText(lbl, gx, gy + 34);
  });

  // Live sparkline
  ctx.beginPath();
  for (let i = 0; i <= 18; i++) {
    const px = sx + 22 + (i / 18) * (sw - 44);
    const wave = Math.sin((i + frame * 1.4) * 0.6) * 12 + Math.cos(i * 0.4) * 6;
    const py = sy + 168 - wave;
    if (i === 0) ctx.moveTo(px, py);
    else ctx.lineTo(px, py);
  }
  ctx.strokeStyle = pal.accent;
  ctx.lineWidth = 2;
  ctx.stroke();

  // Status footer card
  rrect(ctx, sx + 12, sy + 210, sw - 24, 62, 12);
  ctx.fillStyle = pal.field;
  ctx.fill();
  ctx.strokeStyle = pal.edge;
  ctx.stroke();
  ctx.textAlign = 'left';
  ctx.fillStyle = pal.ink;
  ctx.font = '700 10.5px "Plus Jakarta Sans", sans-serif';
  ctx.fillText('TV Care · Restore Safety', sx + 22, sy + 230);
  ctx.fillStyle = broken ? '#FCA5A5' : pal.green;
  ctx.font = '500 9px "DM Sans", sans-serif';
  ctx.fillText(
    broken ? '✕ Storage — & false restore entries' : '✓ df -Pk parsed · Atomic restore log',
    sx + 22,
    sy + 248
  );
  ctx.fillStyle = pal.muted;
  ctx.fillText('Xiaomi Mi TV · Android 11', sx + 22, sy + 263);
}

export async function generateDemoGif(
  presetId: GifPresetId,
  options: GifBuildOptions
): Promise<GeneratedGif> {
  const isCompare = options.mode === 'compare';
  const width = isCompare ? 560 : 300;
  const height = 430;
  const totalFrames = 18;
  const delayMs = options.speed === 'fast' ? 220 : 380;

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) throw new Error('Canvas 2D unavailable.');

  const gif = GIFEncoder();

  for (let frame = 0; frame < totalFrames; frame++) {
    // Stage background
    ctx.fillStyle = '#0F172A';
    ctx.fillRect(0, 0, width, height);

    // Top banner caption
    const presetMeta = GIF_PRESETS.find((p) => p.id === presetId)!;
    ctx.fillStyle = '#F8FAFC';
    ctx.font = '700 13px "Plus Jakarta Sans", system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(`A4 TV Bridge — ${presetMeta.title}`, width / 2, 24);

    if (isCompare) {
      drawPhoneFrame(ctx, 22, 54, 244, 356, DARK, 'BEFORE (v1.0.0 Broken)', '#FCA5A5', (sx, sy, sw, sh) => {
        renderScene(ctx, presetId, frame, totalFrames, true, sx, sy, sw, sh);
      });
      drawPhoneFrame(ctx, 294, 54, 244, 356, DARK, 'AFTER (Fixed)', '#86EFAC', (sx, sy, sw, sh) => {
        renderScene(ctx, presetId, frame, totalFrames, false, sx, sy, sw, sh);
      });
    } else {
      drawPhoneFrame(ctx, 28, 54, 244, 356, DARK, 'A4 TV Bridge · Fixed', '#86EFAC', (sx, sy, sw, sh) => {
        renderScene(ctx, presetId, frame, totalFrames, false, sx, sy, sw, sh);
      });
    }

    const { data } = ctx.getImageData(0, 0, width, height);
    const palette = quantize(data, 128);
    const index = applyPalette(data, palette);
    gif.writeFrame(index, width, height, {
      palette,
      delay: delayMs,
      repeat: 0
    });

    // Yield briefly every 6 frames so browser UI stays responsive
    if (frame % 6 === 5) {
      await new Promise((r) => setTimeout(r, 0));
    }
  }

  gif.finish();
  const bytes = gif.bytes();
  const blob = new Blob([new Uint8Array(bytes)], { type: 'image/gif' });
  const url = URL.createObjectURL(blob);

  return {
    presetId,
    url,
    bytes: bytes.byteLength,
    width,
    height,
    frames: totalFrames
  };
}
