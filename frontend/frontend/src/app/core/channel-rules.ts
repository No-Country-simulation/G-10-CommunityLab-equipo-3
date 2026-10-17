import { AssetType } from './api/api.models';

/** Platforms a post can be moved to (the backend's `channelPost` values) */
export const PUBLISHING_PLATFORMS: AssetType[] = ['linkedin_post', 'x_post', 'newsletter_highlight', 'faq'];

/** Call to action per platform: LinkedIn requires one, FAQ forbids it, the rest may have one */
export type CtaRule = 'required' | 'optional' | 'forbidden';

interface ChannelRules {
  /** Text length, in characters (or words for the newsletter) */
  text: { unit: 'chars' | 'words'; min?: number; max: number };
  hashtags: { min?: number; max: number };
  cta: CtaRule;
  titleRequired: boolean;
}

/** Same limits the backend enforces (EnrichedComment.validatePost); a post that breaks them gets a 400. */
export const CHANNEL_RULES: Partial<Record<AssetType, ChannelRules>> = {
  linkedin_post: { text: { unit: 'chars', min: 80, max: 600 }, hashtags: { min: 2, max: 5 }, cta: 'required', titleRequired: false },
  x_post: { text: { unit: 'chars', max: 280 }, hashtags: { min: 1, max: 2 }, cta: 'optional', titleRequired: false },
  newsletter_highlight: { text: { unit: 'words', min: 100, max: 400 }, hashtags: { max: 5 }, cta: 'optional', titleRequired: false },
  faq: { text: { unit: 'chars', min: 20, max: 500 }, hashtags: { max: 5 }, cta: 'forbidden', titleRequired: true },
};

export interface PostDraft {
  type: AssetType;
  title: string;
  /** The post text without its call to action */
  content: string;
  cta: string;
  hashtags: string[];
}

/** One requirement of the chosen platform, ready to show as ✓ / ✗ (`key` + `params` are i18n) */
export interface RuleCheck {
  key: string;
  params: Record<string, string | number>;
  ok: boolean;
}

export const wordCount = (text: string) => text.trim().split(/\s+/).filter(Boolean).length;

/** Requirements of the draft's platform and whether the draft meets each of them */
export function checkPost(draft: PostDraft): RuleCheck[] {
  const rules = CHANNEL_RULES[draft.type];
  if (!rules) return [];
  const checks: RuleCheck[] = [];

  const { unit, min, max } = rules.text;
  const length = unit === 'words' ? wordCount(draft.content) : draft.content.trim().length;
  checks.push({
    key: `content.rules.${unit}${min ? 'Range' : 'Max'}`,
    params: { min: min ?? 0, max, n: length },
    ok: length >= (min ?? 0) && length <= max,
  });

  const tags = draft.hashtags.length;
  const t = rules.hashtags;
  checks.push({
    key: t.min ? 'content.rules.tagsRange' : 'content.rules.tagsMax',
    params: { min: t.min ?? 0, max: t.max, n: tags },
    ok: tags >= (t.min ?? 0) && tags <= t.max,
  });

  if (rules.cta === 'required') checks.push({ key: 'content.rules.ctaRequired', params: {}, ok: !!draft.cta.trim() });
  if (rules.cta === 'forbidden') checks.push({ key: 'content.rules.ctaForbidden', params: {}, ok: !draft.cta.trim() });
  if (rules.titleRequired) checks.push({ key: 'content.rules.titleRequired', params: {}, ok: !!draft.title.trim() });

  return checks;
}
