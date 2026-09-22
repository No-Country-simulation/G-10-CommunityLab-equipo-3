import { AssetStatus, AssetType, ContentRoute, InteractionSource, Sentiment } from './api/api.models';

export type Severity = 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast';

export const SOURCE_ICON: Record<InteractionSource, string> = {
  Discord: 'pi pi-discord',
  Slack: 'pi pi-slack',
  GitHub: 'pi pi-github',
  Foro: 'pi pi-comments',
  Formulario: 'pi pi-list-check',
};

export const SOURCE_TINT: Record<InteractionSource, string> = {
  Discord: 'bg-blue-50 text-blue-600 dark:bg-blue-500/10 dark:text-blue-300',
  Slack: 'bg-rose-50 text-rose-600 dark:bg-rose-500/10 dark:text-rose-300',
  GitHub: 'bg-slate-100 text-slate-700 dark:bg-slate-700/40 dark:text-slate-200',
  Foro: 'bg-amber-50 text-amber-600 dark:bg-amber-500/10 dark:text-amber-300',
  Formulario: 'bg-teal-50 text-teal-600 dark:bg-teal-500/10 dark:text-teal-300',
};

export const ASSET_TYPE_LABEL: Record<AssetType, string> = {
  linkedin_post: 'Post LinkedIn',
  x_post: 'Post X',
  newsletter_highlight: 'Newsletter',
  faq: 'FAQ',
  success_story: 'Caso de éxito',
};

export const ASSET_TYPE_ICON: Record<AssetType, string> = {
  linkedin_post: 'pi pi-linkedin',
  x_post: 'pi pi-twitter',
  newsletter_highlight: 'pi pi-envelope',
  faq: 'pi pi-question-circle',
  success_story: 'pi pi-star',
};

export const ASSET_TYPE_TINT: Record<AssetType, string> = {
  linkedin_post: 'bg-sky-50 text-sky-700 dark:bg-sky-500/10 dark:text-sky-300',
  x_post: 'bg-slate-100 text-slate-800 dark:bg-slate-700/40 dark:text-slate-100',
  newsletter_highlight: 'bg-amber-50 text-amber-700 dark:bg-amber-500/10 dark:text-amber-300',
  faq: 'bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300',
  success_story: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300',
};

export const ASSET_STATUS_LABEL: Record<AssetStatus, string> = {
  draft: 'Borrador',
  in_review: 'En revisión',
  approved: 'Aprobado',
  published: 'Publicado',
  rejected: 'Rechazado',
};

export const ASSET_STATUS_SEVERITY: Record<AssetStatus, Severity> = {
  draft: 'secondary',
  in_review: 'warn',
  approved: 'info',
  published: 'success',
  rejected: 'danger',
};

export const ASSET_STATUS_DOT: Record<AssetStatus, string> = {
  draft: 'bg-slate-400',
  in_review: 'bg-amber-500',
  approved: 'bg-sky-500',
  published: 'bg-emerald-500',
  rejected: 'bg-rose-500',
};

export const ROUTE_LABEL: Record<ContentRoute, string> = {
  success_story: 'Caso de éxito',
  testimonial: 'Testimonio',
  faq: 'FAQ / Tip',
  alert: 'Alerta a CM',
  discard: 'Descartado',
};

export const ROUTE_TINT: Record<ContentRoute, string> = {
  success_story: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300',
  testimonial: 'bg-teal-50 text-teal-700 dark:bg-teal-500/10 dark:text-teal-300',
  faq: 'bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300',
  alert: 'bg-rose-50 text-rose-700 dark:bg-rose-500/10 dark:text-rose-300',
  discard: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400',
};

export const ROUTE_ICON: Record<ContentRoute, string> = {
  success_story: 'pi pi-trophy',
  testimonial: 'pi pi-heart',
  faq: 'pi pi-question-circle',
  alert: 'pi pi-exclamation-triangle',
  discard: 'pi pi-minus-circle',
};

export const SENTIMENT_TINT: Record<Sentiment, string> = {
  positivo: 'text-emerald-600 dark:text-emerald-400',
  neutral: 'text-slate-500 dark:text-slate-400',
  negativo: 'text-rose-600 dark:text-rose-400',
};

export const SENTIMENT_ICON: Record<Sentiment, string> = {
  positivo: 'pi pi-face-smile',
  neutral: 'pi pi-minus',
  negativo: 'pi pi-exclamation-circle',
};
