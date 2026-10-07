import { ContentRoute, Sentiment } from './api/api.models';

/**
 * Expressions of the mascot in public/image/osos/ (see LEEME.md there).
 * The LLM returns exactly one of these labels and the file is `NN-label.svg`.
 */
export const OSOS = {
  neutral: '01-neutral', contento: '02-contento', risa: '03-risa', celebrando: '04-celebrando',
  encantado: '05-encantado', impresionado: '06-impresionado', guino: '07-guino', relajado: '08-relajado',
  tranquilo: '09-tranquilo', pensativo: '10-pensativo', escuchando: '11-escuchando', analizando: '12-analizando',
  procesando: '13-procesando', cargando: '14-cargando', esperando: '15-esperando', dormido: '16-dormido',
  curioso: '17-curioso', sorprendido: '18-sorprendido', confundido: '19-confundido', preocupado: '20-preocupado',
  nervioso: '21-nervioso', avergonzado: '22-avergonzado', molesto: '23-molesto', frustrado: '24-frustrado',
  enojado: '25-enojado', furioso: '26-furioso', decepcionado: '27-decepcionado', triste: '28-triste',
  llorando: '29-llorando', desanimado: '30-desanimado', desconectado: '31-desconectado', 'sin-resultados': '32-sin-resultados',
} as const;

export type Expression = keyof typeof OSOS;

export const DEFAULT_EXPRESSION: Expression = 'neutral';

/**
 * Turns whatever the model sent into a known label, or null.
 * Tolerates casing, accents, spaces/underscores and file-style values: "Guiño", "sin resultados", "05-encantado.svg".
 */
export function toExpression(raw: string | null | undefined): Expression | null {
  if (!raw) return null;
  const key = raw
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim()
    .replace(/\.svg$/, '')
    .replace(/^\d+-/, '')
    .replace(/[\s_]+/g, '-');
  return key in OSOS ? (key as Expression) : null;
}

/** Image path for a label; anything outside the vocabulary shows the neutral bear. */
export const expressionSrc = (raw: string | null | undefined) =>
  `image/osos/${OSOS[toExpression(raw) ?? DEFAULT_EXPRESSION]}.svg`;

/** "sin-resultados" → "Sin resultados", "guino" → "Guiño" */
export function expressionLabel(raw: string | null | undefined): string {
  const e = toExpression(raw) ?? DEFAULT_EXPRESSION;
  const text = e === 'guino' ? 'guiño' : e.replace(/-/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Used when the backend sends no label (older batches): pick one from the analysis. */
export const ROUTE_EXPRESSION: Record<ContentRoute, Expression> = {
  success_story: 'celebrando',
  testimonial: 'encantado',
  faq: 'pensativo',
  alert: 'preocupado',
  discard: 'sin-resultados',
};

export const SENTIMENT_EXPRESSION: Record<Sentiment, Expression> = {
  positive: 'contento',
  neutral: 'neutral',
  negative: 'triste',
};
