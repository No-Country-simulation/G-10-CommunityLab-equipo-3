import {
  AnalyzedInteraction,
  AssetType,
  ContentRoute,
  GeneratedAsset,
  Interaction,
  InteractionAnalysis,
  ProcessResult,
  Sentiment,
  StorageReceipt,
} from './api.models';

/**
 * Deterministic stand-in for the LLM + orchestrator. It reproduces the behaviour the
 * backend must have (analysis → conditional branch → per-channel content → OCI) with
 * keyword heuristics, so the demo works offline with any message the user types.
 */

const TOPICS: Record<string, string[]> = {
  LangChain: ['langchain'],
  LangGraph: ['langgraph'],
  OCI: ['oci', 'oracle cloud', 'object storage', 'bucket', 'always free'],
  'IA Generativa': [' ia', 'llm', 'gpt', 'gemini', 'claude', 'inteligencia artificial', 'agente'],
  Empleabilidad: ['contrat', 'trabajo', 'empleo', 'entrevista', 'oferta', 'dev jr', 'junior'],
  'Java / Spring': ['java', 'spring'],
  Angular: ['angular'],
  Python: ['python'],
  n8n: ['n8n'],
  Docker: ['docker', 'contenedor'],
  Mentorías: ['mentor', 'acompañamiento', 'revisiones de código'],
  Soporte: ['enlace', 'no funciona', 'nadie responde', 'error'],
};

const POSITIVE: [RegExp, number][] = [
  [/me contrataron|consegu[ií] (mi )?(primer )?(trabajo|empleo)|me eligieron|nuevo trabajo/, 0.9],
  [/gracias/, 0.35],
  [/recomiendo|me encant|les encant|incre[ií]ble|excelente|genial|feliz/, 0.45],
  [/aprend[ií]|logr[eé]|cambiaron|lo logr/, 0.3],
  [/[!¡]{1}/, 0.05],
];

const NEGATIVE: [RegExp, number][] = [
  [/frustrad|molest|enojad|decepcion/, 0.6],
  [/no funciona|no sirve|roto|ca[ií]do|nadie responde/, 0.45],
  [/p[eé]sim|horrible|malo/, 0.5],
];

const QUESTION = /\?|c[oó]mo (hago|puedo|se|configuro|despliego|uso)|alguien sabe|tengo una duda/;
const HIRED = /me contrataron|consegu[ií] (mi )?(primer )?(trabajo|empleo)|me eligieron|nuevo trabajo/;
const TESTIMONIAL = /gracias|recomiendo|me cambi|aprend[ií]|mentor/;

/** Topic-specific snippets so FAQ answers read like a real didactic reply. */
const KNOWLEDGE: Record<string, string[]> = {
  LangGraph: [
    'Define una `RetryPolicy` al registrar el nodo: `graph.add_node("llamar_api", fn, retry=RetryPolicy(max_attempts=3, backoff_factor=2))`.',
    'Para errores de timeout, lanza una excepción desde el nodo: LangGraph solo reintenta cuando el nodo falla.',
    'Si necesitas lógica propia, usa una arista condicional que vuelva al mismo nodo mientras `state["intentos"] < 3`.',
  ],
  OCI: [
    'Crea el bucket en *Storage → Buckets* con tier *Standard*: el Always Free incluye 20 GB en Object Storage.',
    'Genera una API Key para tu usuario y usa el SDK (`oci.object_storage.ObjectStorageClient`) o una *Pre-Authenticated Request* para subir los JSON.',
    'Organiza los objetos por fecha (`batches/AAAA/MM/DD/…`) y activa *Object Lifecycle* para no acumular versiones.',
  ],
  n8n: [
    'Crea una VM *VM.Standard.A1.Flex* (Ampere, Always Free) con Ubuntu.',
    'Instala Docker y levanta n8n: `docker run -d -p 5678:5678 -v n8n_data:/home/node/.n8n n8nio/n8n`.',
    'Abre el puerto 5678 en la *Security List* de la VCN y protege el acceso con usuario y contraseña.',
  ],
  Python: [
    'Lee el archivo con `pandas.read_csv("mensajes.csv")` o con el módulo `csv` de la librería estándar.',
    'Agrupa los mensajes en lotes y envíalos al modelo con un prompt que pida la respuesta en JSON.',
    'Valida la salida con `pydantic` antes de guardarla para evitar respuestas mal formadas.',
  ],
};

const GENERIC_STEPS = [
  'Revisa la documentación oficial y el canal de recursos fijados de la comunidad.',
  'Comparte un ejemplo mínimo reproducible para que otros puedan ayudarte más rápido.',
  'Si la solución funciona, respóndela en el hilo: se convertirá en parte del FAQ.',
];

const TYPE_TONE: Record<AssetType, string> = {
  linkedin_post: 'Inspirador',
  x_post: 'Conciso',
  newsletter_highlight: 'Cercano',
  faq: 'Didáctico',
  success_story: 'Narrativo',
};

export const ROUTE_FORMATS: Record<ContentRoute, AssetType[]> = {
  success_story: ['linkedin_post', 'newsletter_highlight', 'x_post'],
  testimonial: ['linkedin_post', 'newsletter_highlight'],
  faq: ['faq', 'x_post'],
  alert: [],
  discard: [],
};

export function analyze(interaction: Interaction): InteractionAnalysis {
  const text = ` ${interaction.content.toLowerCase()} `;

  let score = 0;
  for (const [re, w] of POSITIVE) if (re.test(text)) score += w;
  for (const [re, w] of NEGATIVE) if (re.test(text)) score -= w;
  score = Math.max(-1, Math.min(1, score));

  const sentiment: Sentiment = score > 0.2 ? 'positive' : score < -0.2 ? 'negative' : 'neutral';
  const topics = Object.entries(TOPICS)
    .filter(([, keys]) => keys.some((k) => text.includes(k)))
    .map(([topic]) => topic);

  let route: ContentRoute;
  let reason: string;
  if (HIRED.test(text) && score > 0) {
    route = 'success_story';
    reason = 'Logro profesional con sentimiento muy positivo: ideal para caso de éxito y LinkedIn.';
  } else if (QUESTION.test(text) && topics.length > 0) {
    route = 'faq';
    reason = 'Duda técnica concreta y reutilizable: se transforma en entrada de FAQ y tip.';
  } else if (TESTIMONIAL.test(text) && score > 0.3) {
    route = 'testimonial';
    reason = 'Testimonio positivo sobre la comunidad: sirve como prueba social.';
  } else if (score < -0.2) {
    route = 'alert';
    reason = 'Sentimiento negativo: se deriva al equipo de community management, no se publica.';
  } else {
    route = 'discard';
    reason = 'Mensaje sin información accionable para contenido.';
  }

  const base = { success_story: 0.9, faq: 0.78, testimonial: 0.82, alert: 0.6, discard: 0.12 }[route];
  const relevance = Math.min(0.99, base + topics.length * 0.03 + Math.abs(score) * 0.05);

  return {
    sentiment,
    sentimentScore: round(score),
    topics,
    relevance: round(relevance),
    route,
    reason,
  };
}

export function process(
  interactions: Interaction[],
  formats?: AssetType[],
  processedAt = new Date().toISOString(),
): ProcessResult {
  const batchId = `batch-${processedAt.slice(0, 10).replace(/-/g, '')}-${hash(processedAt + interactions.length).slice(0, 6)}`;
  const analyzed: AnalyzedInteraction[] = interactions.map((i) => ({ ...i, analysis: analyze(i) }));

  const assets = analyzed.flatMap((i) =>
    ROUTE_FORMATS[i.analysis.route]
      .filter((type) => !formats || formats.includes(type))
      .map((type, idx) => generateAsset(i, type, batchId, idx, processedAt)),
  );

  const relevant = analyzed.filter((i) => i.analysis.route !== 'discard');
  const avg = analyzed.reduce((acc, i) => acc + i.analysis.sentimentScore, 0) / Math.max(analyzed.length, 1);
  const topicCount = new Map<string, number>();
  analyzed.forEach((i) => i.analysis.topics.forEach((t) => topicCount.set(t, (topicCount.get(t) ?? 0) + 1)));

  const result: ProcessResult = {
    batchId,
    processedAt,
    model: 'gemini-2.0-flash (simulado)',
    summary: {
      totalInteractions: analyzed.length,
      relevantInteractions: relevant.length,
      discardedInteractions: analyzed.length - relevant.length,
      overallSentiment: sentimentLabel(avg, analyzed),
      averageSentimentScore: round(avg),
      mainTopics: [...topicCount.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5).map(([t]) => t),
    },
    interactions: analyzed,
    assets,
    storage: undefined as unknown as StorageReceipt,
  };
  result.storage = receipt(result);
  return result;
}

function generateAsset(
  i: AnalyzedInteraction,
  type: AssetType,
  batchId: string,
  idx: number,
  createdAt: string,
): GeneratedAsset {
  const name = i.author.split(' ')[0];
  const topics = i.analysis.topics.filter((t) => t !== 'Empleabilidad' && t !== 'Soporte');
  const stack = topics.length ? joinEs(topics) : 'lo aprendido en la comunidad';
  const tags = ['#Kora', ...topics.map(toHashtag), '#AluraLatam', '#OracleONE'];
  const { title, body, hashtags } = (() => {
    switch (i.analysis.route) {
      case 'success_story':
        return successCopy(type, name, i.author, stack, i.content, tags);
      case 'testimonial':
        return testimonialCopy(type, name, i.author, i.content, tags);
      default:
        return faqCopy(type, i.content, topics, tags);
    }
  })();

  return {
    id: `ast-${hash(i.id + type + batchId).slice(0, 8)}`,
    interactionId: i.id,
    batchId,
    origin: {
      author: i.author,
      source: i.source,
      channel: i.channel,
      excerpt: i.content,
      sentiment: i.analysis.sentiment,
      relevance: i.analysis.relevance,
      route: i.analysis.route,
    },
    type,
    tone: TYPE_TONE[type],
    title,
    body,
    hashtags,
    status: idx === 0 ? 'in_review' : 'draft',
    createdAt,
  };
}

function successCopy(type: AssetType, name: string, author: string, stack: string, quote: string, tags: string[]) {
  switch (type) {
    case 'linkedin_post':
      return {
        title: `Celebramos a ${author}`,
        body:
          `🎉 ¡Celebramos a ${name}!\n\n` +
          `${name} acaba de conseguir su nuevo rol en tecnología, y lo hizo con un proyecto construido con ${stack}.\n\n` +
          `Detrás de cada logro así hay horas de práctica, preguntas valientes y una comunidad que responde. ` +
          `Este triunfo es de ${name}, pero también de cada persona que compartió una respuesta, revisó un PR o dio ánimo en el momento justo. 💜\n\n` +
          `¿Cuál es el próximo paso que vas a dar tú?`,
        hashtags: [...tags, '#PrimerEmpleo'],
      };
    case 'newsletter_highlight':
      return {
        title: `🏆 Logro de la Semana: ${author}`,
        body:
          `Esta semana celebramos a ${author}, que consiguió su nuevo puesto gracias a su proyecto con ${stack}.\n\n` +
          `En sus palabras: “${quote}”\n\n` +
          `¿Tienes un logro para compartir? Cuéntalo en #logros y podrías aparecer en la próxima edición.`,
        hashtags: [],
      };
    default:
      return {
        title: `Logro: ${author}`,
        body: `🚀 ${name} consiguió su nuevo rol en tech con un proyecto de ${stack}. Aprender en comunidad sí transforma carreras. 👏`,
        hashtags: tags.slice(0, 3),
      };
  }
}

function testimonialCopy(type: AssetType, name: string, author: string, quote: string, tags: string[]) {
  if (type === 'linkedin_post') {
    return {
      title: `Testimonio de ${author}`,
      body:
        `💬 Lo que dice nuestra comunidad\n\n“${quote}”\n— ${author}\n\n` +
        `Historias como la de ${name} son la razón por la que existe esta comunidad: aprender acompañados llega más lejos. ` +
        `Gracias por compartirlo. 🙌`,
      hashtags: [...tags, '#Testimonio'],
    };
  }
  return {
    title: `💬 Voz de la comunidad: ${author}`,
    body: `“${quote}”\n\n${name} se suma a la lista de miembros que recomiendan las mentorías. ¿Todavía no participas? Inscríbete en el canal #mentorias.`,
    hashtags: [],
  };
}

function faqCopy(type: AssetType, question: string, topics: string[], tags: string[]) {
  const clean = question.replace(/^(buenas|hola)[,!\s]*/i, '').replace(/^¿?alguien sabe\s*/i, '');
  const q = `¿${capitalize(clean.replace(/^¿/, '').split('?')[0].trim())}?`;
  // Most specific topic wins (a question about n8n on OCI is about n8n)
  const known = ['LangGraph', 'n8n', 'Python', 'OCI'].find((t) => topics.includes(t));
  const steps = known ? KNOWLEDGE[known] : GENERIC_STEPS;
  const main = known ?? topics[0] ?? 'la comunidad';

  if (type === 'faq') {
    return {
      title: q,
      body:
        `**Pregunta frecuente — ${main}**\n\n${q}\n\n**Respuesta:**\n` +
        steps.map((s, n) => `${n + 1}. ${s}`).join('\n') +
        `\n\n💡 Esta entrada se generó a partir de una duda real de la comunidad.`,
      hashtags: [],
    };
  }
  return {
    title: `Tip: ${main}`,
    body: `💡 Tip de ${main}: ${steps[0].replace(/`/g, '')}\n\nDuda real de la comunidad, respuesta en nuestro FAQ. 👇`,
    hashtags: tags.slice(0, 3),
  };
}

function receipt(result: ProcessResult): StorageReceipt {
  const namespace = 'axqlc3m1hy2k';
  const bucket = 'kora-assets';
  const region = 'sa-saopaulo-1';
  const d = result.processedAt.slice(0, 10).split('-');
  const objectName = `batches/${d[0]}/${d[1]}/${d[2]}/${result.batchId}.json`;
  return {
    provider: 'OCI Object Storage',
    namespace,
    bucket,
    region,
    objectName,
    sizeBytes: new Blob([JSON.stringify({ ...result, storage: null })]).size,
    etag: hash(result.batchId + 'etag'),
    url: `https://objectstorage.${region}.oraclecloud.com/n/${namespace}/b/${bucket}/o/${encodeURIComponent(objectName)}`,
    storedAt: result.processedAt,
  };
}

function sentimentLabel(avg: number, list: AnalyzedInteraction[]): string {
  const hasNeg = list.some((i) => i.analysis.sentiment === 'negative');
  const hasPos = list.some((i) => i.analysis.sentiment === 'positive');
  if (avg >= 0.5) return 'Altamente Positivo';
  if (avg > 0.15) return hasNeg ? 'Mayormente Positivo' : 'Positivo';
  if (avg < -0.3) return 'Negativo';
  return hasNeg && hasPos ? 'Mixto' : 'Neutral';
}

function toHashtag(topic: string): string {
  return '#' + topic.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-zA-Z0-9]/g, '');
}

function joinEs(items: string[]): string {
  return items.length < 2 ? items.join('') : `${items.slice(0, -1).join(', ')} y ${items.at(-1)}`;
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function round(n: number): number {
  return Math.round(n * 100) / 100;
}

function hash(s: string): string {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return (h >>> 0).toString(16).padStart(8, '0') + ((h * 31) >>> 0).toString(16).padStart(8, '0');
}
