/**
 * Contract between the Angular frontend and the CommunityLab backend.
 * The mock API returns exactly these shapes, so the backend only has to match them.
 */

export const INTERACTION_SOURCES = ['Discord', 'Telegram'] as const;

export type InteractionSource = (typeof INTERACTION_SOURCES)[number];

/** A raw community message, as ingested from JSON / CSV / webhook. */
export interface Interaction {
  id: string;
  author: string;
  content: string;
  source: InteractionSource;
  channel: string;
  timestamp: string; // ISO-8601
}

export type Sentiment = 'positive' | 'neutral' | 'negative';

/** Conditional branch chosen by the orchestrator for each message. */
export type ContentRoute = 'success_story' | 'testimonial' | 'faq' | 'alert' | 'discard';

export interface InteractionAnalysis {
  sentiment: Sentiment;
  /** -1 (very negative) … 1 (very positive) */
  sentimentScore: number;
  topics: string[];
  /** 0 … 1: how worth it is to turn this message into content */
  relevance: number;
  route: ContentRoute;
  /** Short LLM explanation of the decision */
  reason: string;
}

export interface AnalyzedInteraction extends Interaction {
  analysis: InteractionAnalysis;
}

export type AssetType = 'linkedin_post' | 'x_post' | 'newsletter_highlight' | 'faq' | 'success_story';

export type AssetStatus = 'draft' | 'in_review' | 'approved' | 'published' | 'rejected';

/** Denormalized info about the message an asset came from, for the curation panel. */
export interface AssetOrigin {
  author: string;
  source: InteractionSource;
  channel: string;
  excerpt: string;
  sentiment: Sentiment;
  relevance: number;
  route: ContentRoute;
}

export interface GeneratedAsset {
  id: string;
  interactionId: string;
  batchId: string;
  origin: AssetOrigin;
  type: AssetType;
  /** Tone used by the generator: inspirador, conciso, didáctico… */
  tone: string;
  title: string;
  body: string;
  hashtags: string[];
  status: AssetStatus;
  createdAt: string;
  publishedAt?: string;
}

export interface StorageReceipt {
  provider: 'OCI Object Storage';
  namespace: string;
  bucket: string;
  region: string;
  objectName: string;
  sizeBytes: number;
  etag: string;
  url: string;
  storedAt: string;
}

export interface BatchSummary {
  totalInteractions: number;
  relevantInteractions: number;
  discardedInteractions: number;
  /** e.g. "Altamente Positivo", "Mixto", "Negativo" */
  overallSentiment: string;
  averageSentimentScore: number;
  mainTopics: string[];
}

/** Response of POST /api/v1/interactions/process — the heart of the MVP. */
export interface ProcessResult {
  batchId: string;
  processedAt: string;
  model: string;
  summary: BatchSummary;
  interactions: AnalyzedInteraction[];
  assets: GeneratedAsset[];
  storage: StorageReceipt;
}

export interface ProcessRequest {
  interactions: Interaction[];
  /** Asset types the orchestrator should produce when the route allows it. */
  formats?: AssetType[];
}

/** An object listed from the OCI bucket (one per processed batch). */
export interface StoredObject {
  objectName: string;
  batchId: string;
  sizeBytes: number;
  storedAt: string;
  interactions: number;
  assets: number;
  url: string;
}

export interface AssetPatch {
  title?: string;
  body?: string;
  hashtags?: string[];
  status?: AssetStatus;
}
