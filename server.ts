import 'dotenv/config';
import express from 'express';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { GoogleGenAI, ThinkingLevel, type Content, type GenerateContentResponse } from '@google/genai';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

type AllowedModel = 'gemini-4-hydrogen' | 'gemini-3.1-flash-lite';
type RoleId = 'adb-troubleshooter' | 'shell-helper' | 'kotlin-auditor';

interface ChatHistoryTurn {
  role: 'user' | 'model';
  text: string;
}

interface RoleConfig {
  label: string;
  defaultModel: AllowedModel;
  thinkingLevel?: ThinkingLevel;
  systemInstruction: string;
}

const ALLOWED_MODELS = new Set<AllowedModel>([
  'gemini-4-hydrogen',
  'gemini-3.1-flash-lite',
]);

const ROLE_CONFIGS: Record<RoleId, RoleConfig> = {
  'adb-troubleshooter': {
    label: 'ADB & TV Troubleshooter',
    defaultModel: 'gemini-4-hydrogen',
    thinkingLevel: ThinkingLevel.HIGH,
    systemInstruction: [
      'You are the A4 TV Bridge Android TV & ADB Troubleshooter.',
      'Help users diagnose wireless ADB pairing, LAN discovery, Android 8–15 Toybox shell quirks, CleanYourTV debloat safety, Private DNS ad blocking, and screen mirror/capture issues.',
      'Give concrete, safe, step-by-step troubleshooting advice tailored to Android TV, Google TV, and Fire TV devices.',
      'Keep answers crisp, well-structured, and warn clearly before suggesting destructive package removals (never advise disabling android, SystemUI, Settings, Play Services, or the active launcher/keyboard).',
    ].join(' '),
  },
  'shell-helper': {
    label: 'Quick ADB Command Helper',
    defaultModel: 'gemini-3.1-flash-lite',
    systemInstruction: [
      'You are a fast, precise ADB Shell command assistant for A4 TV Bridge.',
      'Provide short, copy-paste-ready shell commands compatible with Android 8–15 Toybox (avoid GNU-only flags like mv -n or realpath without fallback).',
      'Lead directly with the exact command inside a fenced code block followed by a brief 1–2 sentence explanation of what it does.',
    ].join(' '),
  },
  'kotlin-auditor': {
    label: 'Kotlin & APK Build Advisor',
    defaultModel: 'gemini-4-hydrogen',
    thinkingLevel: ThinkingLevel.HIGH,
    systemInstruction: [
      'You are a senior Android Kotlin & Gradle engineer specializing in the A4 TV Bridge codebase (RatherAsif04/A4-TV-Bridge).',
      'Help with Kotlin Views UI, Dark/Light theme styling, kadb (2.1.1) ADB protocol code, Android SDK 36 / AGP 9.1.1 / Gradle 9.3.1 configuration, Robolectric unit tests, and PKCS12 (.p12) / JKS APK signing in GitHub Actions.',
      'Be accurate, concise, and avoid hallucinating unavailable APIs or deprecated flags.',
    ].join(' '),
  },
};

function getAiClient(): GoogleGenAI | null {
  const apiKey = process.env.GEMINI_API_KEY?.trim();
  if (!apiKey) return null;
  return new GoogleGenAI({
    apiKey,
    httpOptions: {
      headers: {
        'User-Agent': 'aistudio-build',
      },
    },
  });
}

function formatUpstreamError(err: unknown): { status: number; message: string } {
  const raw = err instanceof Error ? err.message : String(err);
  const lower = raw.toLowerCase();

  if (lower.includes('api_key_invalid') || lower.includes('permission_denied') || lower.includes('403')) {
    return {
      status: 403,
      message: 'Gemini API key was rejected. Please verify your API key in Settings > Secrets.',
    };
  }
  if (lower.includes('resource_exhausted') || lower.includes('429') || lower.includes('quota')) {
    return {
      status: 429,
      message: 'Rate limit or quota reached for this model. Try again shortly or switch to Fast mode (gemini-3.1-flash-lite).',
    };
  }
  if (lower.includes('not_found') || lower.includes('404')) {
    return {
      status: 404,
      message: 'The selected Gemini model is unavailable right now. Try switching models.',
    };
  }
  return {
    status: 500,
    message: raw.slice(0, 300) || 'Gemini request failed. Please try again.',
  };
}

async function startServer() {
  const app = express();
  const port = Number(process.env.PORT) || 3000;

  app.use(express.json({ limit: '1mb' }));

  app.get('/api/health', (_req, res) => {
    res.json({ ok: true });
  });

  app.post('/api/chat/stream', async (req, res) => {
    const body = req.body as {
      roleId?: string;
      model?: string;
      history?: unknown;
      message?: unknown;
    };

    const roleId: RoleId =
      body.roleId && body.roleId in ROLE_CONFIGS
        ? (body.roleId as RoleId)
        : 'adb-troubleshooter';
    const roleConfig = ROLE_CONFIGS[roleId];

    const requestedModel =
      typeof body.model === 'string' && ALLOWED_MODELS.has(body.model as AllowedModel)
        ? (body.model as AllowedModel)
        : roleConfig.defaultModel;

    const message = typeof body.message === 'string' ? body.message.trim() : '';
    if (!message || message.length > 8000) {
      res.status(400).json({ error: 'Message must be between 1 and 8,000 characters.' });
      return;
    }

    const rawHistory = Array.isArray(body.history) ? body.history : [];
    const safeHistory: Content[] = [];
    for (const item of rawHistory.slice(-24)) {
      if (
        item &&
        typeof item === 'object' &&
        ('role' in item) &&
        ('text' in item) &&
        ((item as ChatHistoryTurn).role === 'user' || (item as ChatHistoryTurn).role === 'model') &&
        typeof (item as ChatHistoryTurn).text === 'string'
      ) {
        const trimmed = (item as ChatHistoryTurn).text.trim();
        if (trimmed) {
          safeHistory.push({
            role: (item as ChatHistoryTurn).role,
            parts: [{ text: trimmed.slice(0, 8000) }],
          });
        }
      }
    }

    const ai = getAiClient();
    if (!ai) {
      res.status(503).json({
        error: 'Gemini API key is not configured on the server. Add GEMINI_API_KEY in Settings > Secrets.',
      });
      return;
    }

    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    res.setHeader('Cache-Control', 'no-cache, no-transform');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders?.();

    const writeEvent = (payload: Record<string, unknown>) => {
      if (!res.writableEnded) {
        res.write(`data: ${JSON.stringify(payload)}\n\n`);
      }
    };

    try {
      const config: {
        systemInstruction: string;
        thinkingConfig?: { thinkingLevel: ThinkingLevel };
      } = {
        systemInstruction: roleConfig.systemInstruction,
      };

      if (requestedModel === 'gemini-4-hydrogen' && roleConfig.thinkingLevel) {
        config.thinkingConfig = { thinkingLevel: roleConfig.thinkingLevel };
      }

      const chat = ai.chats.create({
        model: requestedModel,
        history: safeHistory,
        config,
      });

      const stream = await chat.sendMessageStream({ message });
      for await (const chunk of stream) {
        if (res.writableEnded) break;
        const c = chunk as GenerateContentResponse;
        if (c.text) {
          writeEvent({ text: c.text });
        }
      }
      writeEvent({ done: true, model: requestedModel });
    } catch (err) {
      const formatted = formatUpstreamError(err);
      writeEvent({ error: formatted.message });
    } finally {
      if (!res.writableEnded) {
        res.end();
      }
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(__dirname, 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      const indexFile = path.join(distDir, 'index.html');
      if (fs.existsSync(indexFile)) {
        res.sendFile(indexFile);
      } else {
        res.status(404).send('Build output not found.');
      }
    });
  }

  app.listen(port, '0.0.0.0', () => {
    console.log(`A4 TV Bridge server running on http://0.0.0.0:${port}`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
