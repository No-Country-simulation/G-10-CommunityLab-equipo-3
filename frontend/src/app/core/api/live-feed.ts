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
  /** true once the CM approved it (PATCH …/packages/{batchId}/messages/{messageId}) */
  approved?: boolean | null;
  /** Version stored in OCI; starts at 1, each saved revision bumps it */
  versionMessage?: number | null;
}

/** `package.completed`: a batch was stored in OCI; its JSON is readable through `parUrlBase` until `expiresAt`. */
export interface PackageNotice {
  source: InteractionSource;
  batchId: string;
  assetsCount: number;
  /** Pre-authenticated read URL of the package. A secret: never log it. */
  parUrlBase: string;
  expiresAt: string;
}

/** `message.revised`: someone approved or edited a stored post; the content is reloaded from its package. */
export interface RevisionNotice {
  source: InteractionSource;
  batchId: string;
  messageId: string;
  versionMessage: number;
  approved: boolean;
}

/** Envelope of `package.completed` and `message.revised` (`asset.created` is the bare post). */
interface BackendNotice<T> {
  type: string;
  batchId: string;
  payload: T;
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
  private readonly packages$ = new Subject<PackageNotice>();
  private readonly revisions$ = new Subject<RevisionNotice>();
  private readonly streams = new Map<InteractionSource, EventSource>();
  private readonly retryTimers = new Map<InteractionSource, ReturnType<typeof setTimeout>>();

  /** Connection state of each network's stream. */
  readonly status = signal(
    Object.fromEntries(INTERACTION_SOURCES.map((s) => [s, 'off'])) as Record<InteractionSource, LiveFeedStatus>,
  );

  /** Assets pushed by the backend from every network, already mapped to the frontend contract. */
  readonly assets: Observable<GeneratedAsset> = this.assets$.asObservable();
  /** Batches stored in OCI (`package.completed`). */
  readonly packages: Observable<PackageNotice> = this.packages$.asObservable();
  /** Posts approved or edited by someone (`message.revised`). */
  readonly revisions: Observable<RevisionNotice> = this.revisions$.asObservable();

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
    this.packages$.complete();
    this.revisions$.complete();
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
    // Any event proves the stream is open, even when the backend sends no headers before the first one
    const on = <T>(type: string, handle: (data: T) => void) =>
      es.addEventListener(type, (e) => {
        try {
          const data = JSON.parse((e as MessageEvent<string>).data) as T;
          this.zone.run(() => {
            this.setStatus(source, 'open');
            handle(data);
          });
        } catch (err) {
          // Only the event type: payloads may carry a PAR URL, which must never reach the logs
          console.warn(`[live-feed:${source}] ${type} descartado`, err instanceof Error ? err.message : err);
        }
      });
    on<BackendPost>('asset.created', (post) => this.assets$.next(toGeneratedAsset(post, source)));
    on<BackendNotice<{ assetsCount: number; parUrlBase: string; expiresAt: string }>>('package.completed', (n) => {
      if (!n.payload?.parUrlBase) throw new Error('package.completed sin parUrlBase');
      this.packages$.next({ source, batchId: n.batchId, ...n.payload });
    });
    on<BackendNotice<{ messageId: string; versionMessage: number; approved: boolean }>>('message.revised', (n) =>
      this.revisions$.next({ source, batchId: n.batchId, ...n.payload }),
    );
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

/** Tone per channel; translated in the UI by the `tone` pipe. */
const TONE: Record<AssetType, string> = {
  linkedin_post: 'Inspirador',
  x_post: 'Conciso',
  newsletter_highlight: 'Cercano',
  faq: 'Didáctico',
  success_story: 'Inspirador',
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

/** Id of a live post in the UI: the source keeps Discord and Telegram ids (independent) from colliding. */
export const liveAssetId = (source: InteractionSource, messageId: string) =>
  `${LIVE_ASSET_PREFIX}${source.toLowerCase()}-${messageId}`;

/** Body as shown and edited in the UI: the post plus its call to action as the last paragraph. */
export const withCta = (content: string, cta?: string | null) => (cta ? `${content}\n\n${cta}` : content);

/** Backend `channelPost` for each asset type (what PATCH expects). */
export const CHANNEL_POST: Partial<Record<AssetType, string>> = {
  linkedin_post: 'LINKEDIN',
  x_post: 'X',
  newsletter_highlight: 'NEWSLETTER',
  faq: 'FAQ',
};

/**
 * @param stream network of the stream the event arrived on; used when the payload has no
 *               `source`, so a Telegram post is never filed under Discord.
 * @param packageId batchId of the OCI package the post was read from, when it comes from one.
 */
export function toGeneratedAsset(post: BackendPost, stream: InteractionSource, packageId?: string): GeneratedAsset {
  const source = SOURCE[post.source ?? ''] ?? stream;
  const type = TYPE[post.channelPost ?? ''] ?? 'faq';
  const content = post.outputContentProcessed?.trim() ?? '';
  const body = withCta(content, post.cta);
  return {
    id: liveAssetId(source, post.messageId),
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
    // Empty when the backend sends none: the UI shows a translated default (assetTitle pipe)
    title: post.titlePost?.trim() ?? '',
    body,
    hashtags: post.hashtags ?? [],
    // LLM fallback records are traces, not publishable content
    status: post.flag ? 'draft' : post.approved ? 'approved' : 'in_review',
    createdAt: toIso(post.sentTime),
    cta: post.cta ?? undefined,
    versionMessage: post.versionMessage ?? 1,
    packageId,
  };
}
