import { Component, computed, effect, inject, signal } from '@angular/core';
import { PercentPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DomSanitizer } from '@angular/platform-browser';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TextareaModule } from 'primeng/textarea';
import { TooltipModule } from 'primeng/tooltip';
import { AssetPatch, AssetStatus, AssetType, GeneratedAsset } from '../../../../core/api/api.models';
import { TONE } from '../../../../core/api/live-feed';
import { CHANNEL_RULES, PUBLISHING_PLATFORMS, checkPost } from '../../../../core/channel-rules';
import {
  BANNER_FORMATS,
  BANNER_HEADLINE_MAX,
  BannerFormat,
  bannerFileName,
  buildBannerSvg,
  canHaveBanner,
  defaultBannerHeadline,
  downloadBlob,
  loadBannerLogo,
  renderBannerPng,
  svgDataUrl,
} from '../../../../core/banner';
import { confetti, originOf } from '../../../../core/confetti';
import { I18n } from '../../../../core/i18n/i18n.service';
import { AssetTitlePipe, LocalizedDatePipe, TonePipe, TranslatePipe, assetTitle } from '../../../../core/i18n/translate.pipe';
import {
  ASSET_STATUS_DOT,
  ASSET_STATUS_SEVERITY,
  ASSET_TYPE_ICON,
  ASSET_TYPE_TINT,
  ROUTE_TINT,
  SENTIMENT_TINT,
  SOURCE_ICON,
  SOURCE_TINT,
} from '../../../../core/ui-maps';
import { WorkspaceStore } from '../../../../core/workspace.store';
import { AssetPreview } from '../../../../shared/asset-preview/asset-preview';
import { Mascot } from '../../../../shared/mascot/mascot';
import { SENTIMENT_EXPRESSION } from '../../../../core/mascot';

type Filter = 'pending' | 'approved' | 'published' | 'rejected' | 'all';

const FILTER_STATUSES: Record<Filter, AssetStatus[] | null> = {
  pending: ['draft', 'in_review'],
  approved: ['approved'],
  published: ['published'],
  rejected: ['rejected'],
  all: null,
};

@Component({
  selector: 'app-content',
  imports: [
    ButtonModule,
    DialogModule,
    SelectButtonModule,
    TagModule,
    TextareaModule,
    ToggleSwitchModule,
    TooltipModule,
    FormsModule,
    PercentPipe,
    AssetPreview,
    Mascot,
    TranslatePipe,
    LocalizedDatePipe,
    AssetTitlePipe,
    TonePipe,
  ],
  templateUrl: './content.html',
  styleUrl: './content.css',
})
export class Content {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly i18n = inject(I18n);

  protected readonly typeIcon = ASSET_TYPE_ICON;
  protected readonly typeTint = ASSET_TYPE_TINT;
  protected readonly statusSeverity = ASSET_STATUS_SEVERITY;
  protected readonly statusDot = ASSET_STATUS_DOT;
  protected readonly routeTint = ROUTE_TINT;
  protected readonly sentimentTint = SENTIMENT_TINT;
  protected readonly sentimentExpression = SENTIMENT_EXPRESSION;
  protected readonly sourceIcon = SOURCE_ICON;
  protected readonly sourceTint = SOURCE_TINT;

  /** `label` is an i18n key */
  protected readonly filterTabs: { value: Filter; label: string; dot?: string }[] = [
    { value: 'pending', label: 'summary.stat.pending', dot: 'bg-amber-500' },
    { value: 'approved', label: 'content.filter.approved', dot: 'bg-sky-500' },
    { value: 'published', label: 'content.filter.published', dot: 'bg-emerald-500' },
    { value: 'rejected', label: 'content.filter.rejected', dot: 'bg-rose-500' },
    { value: 'all', label: 'content.filter.all' },
  ];

  protected readonly query = signal('');
  protected readonly status = signal<Filter>('pending');
  protected readonly selectedId = signal<string | null>(null);

  protected readonly viewModes = computed(() => [
    { label: this.i18n.t('content.view.preview'), value: 'preview' },
    { label: this.i18n.t('content.view.edit'), value: 'edit' },
  ]);
  protected readonly view = signal<'preview' | 'edit'>('preview');
  /** Editor state: `body` is the post text WITHOUT its call to action, which has its own field */
  protected draft = emptyDraft();

  protected readonly platforms = PUBLISHING_PLATFORMS;
  protected readonly channelRules = CHANNEL_RULES;

  // ── Banner studio: format, editable headline and author privacy, previewed live ──
  protected readonly bannerOpen = signal(false);
  protected readonly bannerAsset = signal<GeneratedAsset | null>(null);
  protected readonly bannerFormat = signal<BannerFormat>('landscape');
  protected readonly bannerHeadline = signal('');
  protected readonly bannerShowAuthor = signal(true);
  protected readonly bannerBusy = signal<'copy' | 'download' | null>(null);
  private readonly bannerLogo = signal<string | undefined>(undefined);
  protected readonly bannerHeadlineMax = BANNER_HEADLINE_MAX;
  protected readonly bannerFormats = computed(() =>
    (Object.keys(BANNER_FORMATS) as BannerFormat[]).map((f) => ({
      value: f,
      label: this.i18n.t(`banner.format.${f}`),
      size: `${BANNER_FORMATS[f].width} × ${BANNER_FORMATS[f].height}`,
    })),
  );
  /** Image clipboard is missing in some browsers (Firefox without flags): the button is hidden there */
  protected readonly canCopyImage = typeof ClipboardItem !== 'undefined' && !!navigator.clipboard?.write;
  protected readonly bannerSvg = computed(() => {
    const a = this.bannerAsset();
    return a
      ? buildBannerSvg(a, this.i18n, {
          format: this.bannerFormat(),
          headline: this.bannerHeadline(),
          showAuthor: this.bannerShowAuthor(),
          logo: this.bannerLogo(),
        })
      : '';
  });
  // Our own SVG with escaped text; Angular would otherwise block data:image/svg+xml
  protected readonly bannerUrl = computed(() =>
    this.bannerSvg() ? this.sanitizer.bypassSecurityTrustUrl(svgDataUrl(this.bannerSvg())) : null,
  );

  protected readonly counts = computed(() => {
    const assets = this.store.assets();
    const counts = {} as Record<Filter, number>;
    for (const f of Object.keys(FILTER_STATUSES) as Filter[]) {
      const statuses = FILTER_STATUSES[f];
      counts[f] = statuses ? assets.filter((a) => statuses.includes(a.status)).length : assets.length;
    }
    return counts;
  });

  protected readonly filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    const statuses = FILTER_STATUSES[this.status()];
    return this.store.assets().filter(
      (a) =>
        (!statuses || statuses.includes(a.status)) &&
        (!q || `${assetTitle(a, this.i18n)} ${a.body} ${a.origin.author} ${a.origin.channel}`.toLowerCase().includes(q)),
    );
  });

  protected readonly selected = computed<GeneratedAsset | null>(() => {
    const list = this.filtered();
    return list.find((a) => a.id === this.selectedId()) ?? list[0] ?? null;
  });

  protected readonly approvedCount = computed(() => this.counts().approved);

  constructor() {
    // Reset the editor whenever another asset is selected
    effect(() => {
      const a = this.selected();
      this.draft = a ? toDraft(a) : emptyDraft();
    });
  }

  protected select(a: GeneratedAsset) {
    this.selectedId.set(a.id);
    this.view.set('preview');
  }

  protected dirty(a: GeneratedAsset) {
    const saved = toDraft(a);
    return (Object.keys(saved) as (keyof Draft)[]).some((k) => this.draft[k] !== saved[k]);
  }

  /** Requirements of the platform chosen in the editor, checked live against the draft */
  protected rules() {
    return checkPost({
      type: this.draft.type,
      title: this.draft.title,
      content: this.draft.body,
      cta: this.draft.cta,
      hashtags: parseHashtags(this.draft.hashtags),
    });
  }

  /** The draft breaks a rule of its platform: the backend would refuse it, so it cannot be saved */
  protected blocked() {
    return this.rules().some((r) => !r.ok);
  }

  /** Switching platform: FAQ cannot carry a call to action, so it is cleared */
  protected setPlatform(type: AssetType) {
    this.draft.type = type;
    if (CHANNEL_RULES[type]?.cta === 'forbidden') this.draft.cta = '';
  }

  protected save(a: GeneratedAsset) {
    const content = this.draft.body.trim();
    const cta = this.draft.cta.trim();
    const patch: AssetPatch = {
      title: this.draft.title.trim(),
      // The UI keeps the call to action as the body's last paragraph
      body: cta ? `${content}\n\n${cta}` : content,
      cta,
      hashtags: parseHashtags(this.draft.hashtags),
    };
    if (this.draft.type !== a.type) Object.assign(patch, { type: this.draft.type, tone: TONE[this.draft.type] });
    this.view.set('preview');
    this.store
      .update(a.id, patch)
      // "pending" and "failed" already have their own message from the store
      .then((outcome) => outcome === 'saved' && this.toast.add({ severity: 'success', summary: this.i18n.t('content.toast.saved'), life: 2000 }));
  }

  protected setStatus(a: GeneratedAsset, status: AssetStatus) {
    this.store.update(a.id, { status }).then(
      (outcome) =>
        outcome === 'saved' &&
        this.toast.add({
          severity: 'info',
          summary: this.i18n.t('content.toast.marked', { status: this.i18n.t(`status.${status}`).toLowerCase() }),
          life: 2000,
        }),
    );
  }

  protected publish(a: GeneratedAsset, event?: Event) {
    confetti(originOf(event));
    this.store.publish(a.id).then(
      (outcome) =>
        outcome === 'saved' &&
        this.toast.add({
          severity: 'success',
          summary: this.i18n.t('content.toast.published'),
          detail: this.i18n.t('content.toast.publishedDetail', { type: this.i18n.t(`assetType.${a.type}`), author: a.origin.author }),
          life: 3000,
        }),
    );
  }

  protected publishAllApproved(event?: Event) {
    const approved = this.store.assets().filter((a) => a.status === 'approved');
    approved.forEach((a) => this.store.publish(a.id));
    confetti(originOf(event));
    this.toast.add({ severity: 'success', summary: this.i18n.t('content.toast.publishedMany', { n: approved.length }), life: 3000 });
  }

  /** Post whose text was just copied: its button shows "Copied" for a moment */
  protected readonly copiedId = signal<string | null>(null);
  private copiedTimer?: ReturnType<typeof setTimeout>;

  protected async copy(a: GeneratedAsset) {
    const text = a.body + (a.hashtags.length ? `\n\n${a.hashtags.join(' ')}` : '');
    if (!(await writeClipboard(text))) {
      this.toast.add({ severity: 'warn', summary: this.i18n.t('common.copyFailed'), life: 3000 });
      return;
    }
    this.copiedId.set(a.id);
    clearTimeout(this.copiedTimer);
    this.copiedTimer = setTimeout(() => this.copiedId.set(null), 2000);
  }

  protected readonly canHaveBanner = canHaveBanner;

  protected openBanner(a: GeneratedAsset) {
    if (!canHaveBanner(a)) return;
    this.bannerAsset.set(a);
    this.bannerHeadline.set(defaultBannerHeadline(a, this.i18n));
    this.bannerOpen.set(true);
    // The preview shows at once; the logo joins it as soon as it is loaded (cached afterwards)
    loadBannerLogo().then((logo) => this.bannerLogo.set(logo));
  }

  protected resetBannerHeadline() {
    const a = this.bannerAsset();
    if (a) this.bannerHeadline.set(defaultBannerHeadline(a, this.i18n));
  }

  protected bannerHeadlineIsDefault() {
    const a = this.bannerAsset();
    return !a || this.bannerHeadline() === defaultBannerHeadline(a, this.i18n);
  }

  protected bannerSize() {
    const { width, height } = BANNER_FORMATS[this.bannerFormat()];
    return { width, height };
  }

  protected async downloadBanner() {
    const a = this.bannerAsset();
    if (!a || this.bannerBusy()) return;
    this.bannerBusy.set('download');
    try {
      const format = this.bannerFormat();
      const blob = await renderBannerPng(this.bannerSvg(), format);
      downloadBlob(blob, bannerFileName(a, this.bannerHeadline() || defaultBannerHeadline(a, this.i18n), format));
      this.toast.add({ severity: 'success', summary: this.i18n.t('banner.toast.downloaded'), life: 2000 });
    } catch {
      this.toast.add({ severity: 'error', summary: this.i18n.t('banner.toast.failed'), life: 3000 });
    } finally {
      this.bannerBusy.set(null);
    }
  }

  /** Copies the PNG itself, ready to paste into LinkedIn, X or a chat */
  protected async copyBanner() {
    if (!this.bannerAsset() || this.bannerBusy()) return;
    this.bannerBusy.set('copy');
    try {
      // The promise form keeps the user gesture alive while the PNG is rendered (Safari requires it)
      const png = renderBannerPng(this.bannerSvg(), this.bannerFormat());
      await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })]);
      this.toast.add({ severity: 'success', summary: this.i18n.t('banner.toast.copied'), life: 2000 });
    } catch {
      this.toast.add({ severity: 'error', summary: this.i18n.t('banner.toast.copyFailed'), life: 3000 });
    } finally {
      this.bannerBusy.set(null);
    }
  }
}

/**
 * Copies text to the clipboard. The Clipboard API needs a secure, focused page and can be blocked by the
 * browser; the hidden-textarea fallback covers those cases. False when neither worked.
 */
async function writeClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.cssText = 'position:fixed;top:0;left:0;opacity:0';
    document.body.appendChild(area);
    area.select();
    try {
      return document.execCommand('copy');
    } catch {
      return false;
    } finally {
      area.remove();
    }
  }
}

interface Draft {
  type: AssetType;
  title: string;
  body: string;
  cta: string;
  hashtags: string;
}

function emptyDraft(): Draft {
  return { type: 'linkedin_post', title: '', body: '', cta: '', hashtags: '' };
}

/** Editor fields from a post: the call to action is split off the body's last paragraph */
function toDraft(a: GeneratedAsset): Draft {
  const cta = a.cta ?? '';
  const suffix = cta ? `\n\n${cta}` : '';
  const body = suffix && a.body.endsWith(suffix) ? a.body.slice(0, -suffix.length) : a.body;
  return { type: a.type, title: a.title, body, cta, hashtags: a.hashtags.join(' ') };
}

function parseHashtags(text: string): string[] {
  return text
    .split(/[\s,]+/)
    .filter(Boolean)
    .map((h) => (h.startsWith('#') ? h : `#${h}`));
}
