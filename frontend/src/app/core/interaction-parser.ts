import { Interaction, InteractionSource } from './api/api.models';

/** Accepted aliases per field, so exports from Discord/Slack/forms load without reshaping. */
const FIELDS: Record<keyof Omit<Interaction, 'id'>, string[]> = {
  author: ['author', 'autor', 'user', 'usuario', 'username', 'name', 'nombre'],
  content: ['content', 'contenido', 'message', 'mensaje', 'text', 'texto', 'body'],
  source: ['source', 'fuente', 'platform', 'plataforma', 'origen'],
  channel: ['channel', 'canal', 'room', 'thread'],
  timestamp: ['timestamp', 'date', 'fecha', 'created_at', 'createdAt'],
};

const SOURCES: InteractionSource[] = ['Discord', 'Slack', 'GitHub', 'Foro', 'Formulario'];

export class ParseError extends Error {}

export function parseInteractions(text: string, fileName = ''): Interaction[] {
  const trimmed = text.trim();
  if (!trimmed) throw new ParseError('El contenido está vacío.');
  const isJson = fileName.endsWith('.json') || /^[\[{]/.test(trimmed);
  const rows = isJson ? parseJson(trimmed) : parseCsv(trimmed);
  const interactions = rows.map(normalize).filter((i): i is Interaction => !!i);
  if (!interactions.length) {
    throw new ParseError('No se encontró ningún mensaje con un campo "content" / "mensaje".');
  }
  return interactions;
}

function parseJson(text: string): Record<string, unknown>[] {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch (e) {
    throw new ParseError(`JSON inválido: ${(e as Error).message}`);
  }
  if (Array.isArray(data)) return data as Record<string, unknown>[];
  const obj = data as Record<string, unknown>;
  const list = obj['interactions'] ?? obj['messages'] ?? obj['mensajes'] ?? obj['data'];
  if (Array.isArray(list)) return list as Record<string, unknown>[];
  return [obj];
}

function parseCsv(text: string): Record<string, unknown>[] {
  const rows = splitCsv(text);
  if (rows.length < 2) throw new ParseError('El CSV necesita una fila de encabezados y al menos un mensaje.');
  const headers = rows[0].map((h) => h.trim());
  return rows
    .slice(1)
    .filter((r) => r.some((c) => c.trim()))
    .map((r) => Object.fromEntries(headers.map((h, i) => [h, r[i] ?? ''])));
}

/** RFC 4180-ish splitter: quoted fields, escaped quotes, commas or semicolons. */
function splitCsv(text: string): string[][] {
  const firstLine = text.split(/\r?\n/, 1)[0];
  const sep = (firstLine.match(/;/g)?.length ?? 0) > (firstLine.match(/,/g)?.length ?? 0) ? ';' : ',';
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;

  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === sep) {
      row.push(field);
      field = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else {
      field += ch;
    }
  }
  row.push(field);
  rows.push(row);
  return rows;
}

function normalize(raw: Record<string, unknown>, index: number): Interaction | null {
  const pick = (key: keyof typeof FIELDS) => {
    const entry = Object.entries(raw).find(([k]) => FIELDS[key].includes(k.trim().toLowerCase()) || FIELDS[key].includes(k.trim()));
    return entry ? String(entry[1] ?? '').trim() : '';
  };
  const content = pick('content');
  if (!content) return null;

  const rawSource = pick('source').toLowerCase();
  const source = SOURCES.find((s) => s.toLowerCase() === rawSource) ?? 'Formulario';

  return {
    id: String(raw['id'] ?? `msg-${Date.now().toString(36)}-${index}`),
    author: pick('author') || 'Anónimo',
    content,
    source,
    channel: pick('channel') || 'general',
    timestamp: pick('timestamp') || new Date().toISOString(),
  };
}
