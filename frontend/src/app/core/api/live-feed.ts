import { Injectable, NgZone, OnDestroy, inject, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import {
  AssetType,
  ContentRoute,
  GeneratedAsset,
  INTERACTION_SOURCES,
  InteractionSource,
  Sentiment,
} from './api.models';
import { API_CONFIG } from './community-lab.api';

/**
 * Payload of the `asset.created` SSE event emitted by the Spring Boot backend
 * (`ResponseClient` record in the backend, serialized as-is). Same contract for both
 * streams; the SSE event id is `messageId`.
 */
export interface BackendPost {
  /** Visible author name */
  authorName: string | null;
  /** Original message after minimal curation (keeps the author's words) */
  messageAuthor: string | null;
  /** Native message id, also the SSE event id */
  messageId: string;
  /** uuid of the open batch */
  messageBatchId: string | null;
  sentiment: 'POSITIVO' | 'NEGATIVO' | 'NEUTRAL' | null;
  language: 'ES' | 'EN' | 'PT' | 'OTHER' | null;
  messageType: 'DUDA' | 'TESTIMONIO' | 'COMENTARIO' | 'QUEJA' | 'SUGERENCIA' | 'LOGRO' | 'OTRO' | null;
  /** 0 … 5 topics */
  topics: string[] | null;
  /** 0 … 100 */
  relevance: number;
  /** null normally, "LLM_FALLBACK" when the AI call failed */
  flag: string | null;
  /** ISO-8601 Instant (epoch seconds tolerated in case Jackson is reconfigured) */
  sentTime: string | number | null;
  source: 'DISCORD' | 'TELEGRAM' | null;
  channelPost: 'LINKEDIN' | 'X' | 'NEWSLETTER' | 'FAQ' | null;
  /** Required only for FAQ */
  titlePost: string | null;
  outputContentProcessed: string | null;
  /** LinkedIn 2-5, X 1-2, FAQ/Newsletter 0-5 */
  hashtags: string[] | null;
  /** Required for LinkedIn, forbidden for FAQ */
  cta: string | null;
}

export type LiveFeedStatus = 'off' | 'connecting' | 'open' | 'error';

const RETRY_MS = 5000;

/** Prefix for asset ids that come from the live feed (they only exist in the browser). */
export const LIVE_ASSET_PREFIX = 'live-';

/**
 * Subscribes to one SSE stream per network (`GET /api/v1/discord/messages`,
 * `GET /api/v1/telegram/messages`, event `asset.created`). The backend routes each post
 * to the stream of its source. EventSource reconnects on its own and sends Last-Event-ID,
 * so the backend replays missed posts.
 */
@Injectable({ providedIn: 'root' })
export class LiveFeed implements OnDestroy {
  private readonly feeds = inject(API_CONFIG).liveFeeds ?? {};
  private readonly zone = inject(NgZone);
  private readonly assets$ = new Subject<GeneratedAsset>();
  private readonly streams = new Map<InteractionSource, EventSource>();
  private readonly retryTimers = new Map<InteractionSource, ReturnType<typeof setTimeout>>();

  /** Connection state of each network's stream. */
  readonly status = signal(
    Object.fromEntries(INTERACTION_SOURCES.map((s) => [s, 'off'])) as Record<InteractionSource, LiveFeedStatus>,
  );

  /** Assets pushed by the backend from every network, already mapped to the frontend contract. */
  readonly assets: Observable<GeneratedAsset> = this.assets$.asObservable();

  /** Stream URL of a network, if it has one. */
  urlOf(source: InteractionSource): string | undefined {
    return this.feeds[source];
  }

  connect() {
    for (const source of INTERACTION_SOURCES) this.open(source);
  }

  ngOnDestroy() {
    this.retryTimers.forEach((t) => clearTimeout(t));
    this.streams.forEach((es) => es.close());
    this.assets$.complete();
  }

  private open(source: InteractionSource) {
    const url = this.feeds[source];
    if (!url || this.streams.has(source) || typeof EventSource === 'undefined') return;
    this.setStatus(source, 'connecting');
    const es = new EventSource(url);
    this.streams.set(source, es);
    es.onopen = () => this.zone.run(() => this.setStatus(source, 'open'));
    // readyState CONNECTING: the browser retries by itself. CLOSED (backend down, proxy 5xx):
    // the browser gives up, so retry manually until the backend comes up.
    es.onerror = () =>
      this.zone.run(() => {
        if (es.readyState !== EventSource.CLOSED) return this.setStatus(source, 'connecting');
        this.setStatus(source, 'error');
        es.close();
        this.streams.delete(source);
        this.retryTimers.set(source, setTimeout(() => this.open(source), RETRY_MS));
      });
    es.addEventListener('asset.created', (e) => {
      try {
        const asset = toGeneratedAsset(JSON.parse((e as MessageEvent<string>).data), source);
        this.zone.run(() => this.assets$.next(asset));
      } catch (err) {
        console.warn(`[live-feed:${source}] evento descartado`, err);
      }
    });
  }

  private setStatus(source: InteractionSource, value: LiveFeedStatus) {
    this.status.update((s) => ({ ...s, [source]: value }));
  }
}

const SENTIMENT: Record<string, Sentiment> = {
  POSITIVO: 'positive',
  NEUTRAL: 'neutral',
  NEGATIVO: 'negative',
};

const SOURCE: Record<string, InteractionSource> = {
  DISCORD: 'Discord',
  TELEGRAM: 'Telegram',
};

/** Where each network's posts are read from, shown next to the author. */
const CHANNEL: Record<InteractionSource, string> = {
  Discord: '#listen',
  Telegram: 'chat',
};

const TYPE: Record<string, AssetType> = {
  LINKEDIN: 'linkedin_post',
  X: 'x_post',
  NEWSLETTER: 'newsletter_highlight',
  FAQ: 'faq',
};

const TONE: Record<AssetType, string> = {
  linkedin_post: 'Inspirador',
  x_post: 'Conciso',
  newsletter_highlight: 'Cercano',
  faq: 'Didáctico',
  success_story: 'Inspirador',
};

const DEFAULT_TITLE: Record<AssetType, string> = {
  linkedin_post: 'Post para LinkedIn',
  x_post: 'Post para X',
  newsletter_highlight: 'Destacado de newsletter',
  faq: 'Pregunta frecuente',
  success_story: 'Historia de éxito',
};

function toRoute(post: BackendPost): ContentRoute {
  if (post.messageType === 'QUEJA' || post.sentiment === 'NEGATIVO') return 'alert';
  if (post.messageType === 'LOGRO') return 'success_story';
  if (post.messageType === 'DUDA') return 'faq';
  return 'testimonial';
}

function toIso(value: BackendPost['sentTime']): string {
  if (value == null) return new Date().toISOString();
  // Jackson may write Instant as epoch seconds (with nanos as decimals)
  if (typeof value === 'number') return new Date(value * 1000).toISOString();
  return value;
}

/**
 * @param stream network of the stream the event arrived on; used when the payload has no
 *               `source`, so a Telegram post is never filed under Discord.
 */
export function toGeneratedAsset(post: BackendPost, stream: InteractionSource): GeneratedAsset {
  const source = SOURCE[post.source ?? ''] ?? stream;
  const type = TYPE[post.channelPost ?? ''] ?? 'faq';
  const content = post.outputContentProcessed?.trim() ?? '';
  const body = post.cta ? `${content}\n\n${post.cta}` : content;
  return {
    // Source in the id: Discord and Telegram ids are independent and could collide
    id: `${LIVE_ASSET_PREFIX}${source.toLowerCase()}-${post.messageId}`,
    interactionId: post.messageId,
    batchId: post.messageBatchId ?? '',
    origin: {
      author: post.authorName ?? 'Anónimo',
      source,
      channel: CHANNEL[source],
      excerpt: post.messageAuthor ?? content,
      sentiment: SENTIMENT[post.sentiment ?? ''] ?? 'neutral',
      relevance: Math.max(0, Math.min(1, (post.relevance ?? 0) / 100)),
      route: toRoute(post),
    },
    type,
    tone: TONE[type],
    title: post.titlePost?.trim() || DEFAULT_TITLE[type],
    body,
    hashtags: post.hashtags ?? [],
    // LLM fallback records are traces, not publishable content
    status: post.flag ? 'draft' : 'in_review',
    createdAt: toIso(post.sentTime),
  };
}
