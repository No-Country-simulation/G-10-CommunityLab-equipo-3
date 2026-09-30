import { Injectable, NgZone, OnDestroy, inject, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { AssetType, ContentRoute, GeneratedAsset, InteractionSource, Sentiment } from './api.models';
import { API_CONFIG } from './community-lab.api';

/**
 * Payload of the `asset.created` SSE event emitted by the Spring Boot backend
 * (`ResponseClient` record in the backend, serialized as-is).
 */
export interface BackendPost {
  authorName: string | null;
  messageAuthor: string | null;
  messageId: string;
  messageBatchId: string;
  sentiment: 'POSITIVO' | 'NEGATIVO' | 'NEUTRAL' | null;
  language: 'ES' | 'EN' | 'PT' | 'OTHER' | null;
  messageType: 'DUDA' | 'TESTIMONIO' | 'COMENTARIO' | 'QUEJA' | 'SUGERENCIA' | 'LOGRO' | 'OTRO' | null;
  topics: string[] | null;
  /** 0 … 100 */
  relevance: number;
  flag: string | null;
  /** ISO-8601 string, or epoch seconds depending on Jackson config */
  sentTime: string | number | null;
  source: 'DISCORD' | 'TELEGRAM' | null;
  channelPost: 'LINKEDIN' | 'X' | 'NEWSLETTER' | 'FAQ' | null;
  titlePost: string | null;
  outputContentProcessed: string | null;
  hashtags: string[] | null;
  cta: string | null;
}

export type LiveFeedStatus = 'off' | 'connecting' | 'open' | 'error';

const RETRY_MS = 5000;

/** Prefix for asset ids that come from the live feed (they only exist in the browser). */
export const LIVE_ASSET_PREFIX = 'live-';

/**
 * Subscribes to the only public backend endpoint: the SSE stream of processed
 * Discord messages (`GET /api/v1/discord/messages`, event `asset.created`).
 * EventSource reconnects on its own and sends Last-Event-ID, so the backend replays missed posts.
 */
@Injectable({ providedIn: 'root' })
export class DiscordLiveFeed implements OnDestroy {
  private readonly feedUrl = inject(API_CONFIG).liveFeedUrl;
  private readonly zone = inject(NgZone);
  private readonly assets$ = new Subject<GeneratedAsset>();
  private source?: EventSource;
  private retryTimer?: ReturnType<typeof setTimeout>;

  readonly status = signal<LiveFeedStatus>('off');

  /** Assets pushed by the backend, already mapped to the frontend contract. */
  readonly assets: Observable<GeneratedAsset> = this.assets$.asObservable();

  connect() {
    if (!this.feedUrl || this.source || typeof EventSource === 'undefined') return;
    this.status.set('connecting');
    const es = new EventSource(this.feedUrl);
    this.source = es;
    es.onopen = () => this.zone.run(() => this.status.set('open'));
    // readyState CONNECTING: the browser retries by itself. CLOSED (backend down, proxy 5xx):
    // the browser gives up, so retry manually until the backend comes up.
    es.onerror = () =>
      this.zone.run(() => {
        if (es.readyState !== EventSource.CLOSED) return this.status.set('connecting');
        this.status.set('error');
        es.close();
        this.source = undefined;
        this.retryTimer = setTimeout(() => this.connect(), RETRY_MS);
      });
    es.addEventListener('asset.created', (e) => {
      try {
        const asset = toGeneratedAsset(JSON.parse((e as MessageEvent<string>).data));
        this.zone.run(() => this.assets$.next(asset));
      } catch (err) {
        console.warn('[live-feed] evento descartado', err);
      }
    });
  }

  ngOnDestroy() {
    clearTimeout(this.retryTimer);
    this.source?.close();
    this.assets$.complete();
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

export function toGeneratedAsset(post: BackendPost): GeneratedAsset {
  const type = TYPE[post.channelPost ?? ''] ?? 'faq';
  const content = post.outputContentProcessed?.trim() ?? '';
  const body = post.cta ? `${content}\n\n${post.cta}` : content;
  const createdAt = toIso(post.sentTime);
  return {
    id: `${LIVE_ASSET_PREFIX}${post.messageId}`,
    interactionId: post.messageId,
    batchId: post.messageBatchId,
    origin: {
      author: post.authorName ?? 'Anónimo',
      source: SOURCE[post.source ?? ''] ?? 'Discord',
      channel: '#listen',
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
    createdAt,
  };
}
