import { Component, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, PercentPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DomSanitizer } from '@angular/platform-browser';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { TooltipModule } from 'primeng/tooltip';
import { AssetStatus, GeneratedAsset } from '../../../core/api/api.models';
import { buildBannerSvg, downloadBannerPng, svgDataUrl } from '../../../core/banner';
import { confetti, originOf } from '../../../core/confetti';
import {
  ASSET_STATUS_DOT,
  ASSET_STATUS_LABEL,
  ASSET_STATUS_SEVERITY,
  ASSET_TYPE_ICON,
  ASSET_TYPE_LABEL,
  ASSET_TYPE_TINT,
  ROUTE_LABEL,
  ROUTE_TINT,
  SENTIMENT_TINT,
  SOURCE_ICON,
  SOURCE_TINT,
} from '../../../core/ui-maps';
import { WorkspaceStore } from '../../../core/workspace.store';
import { AssetPreview } from '../../../shared/asset-preview/asset-preview';

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
    TooltipModule,
    FormsModule,
    DatePipe,
    PercentPipe,
    AssetPreview,
  ],
  templateUrl: './content.html',
  styleUrl: './content.css',
})
export class Content {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);
  private readonly sanitizer = inject(DomSanitizer);

  protected readonly typeLabel = ASSET_TYPE_LABEL;
  protected readonly typeIcon = ASSET_TYPE_ICON;
  protected readonly typeTint = ASSET_TYPE_TINT;
  protected readonly statusLabel = ASSET_STATUS_LABEL;
  protected readonly statusSeverity = ASSET_STATUS_SEVERITY;
  protected readonly statusDot = ASSET_STATUS_DOT;
  protected readonly routeLabel = ROUTE_LABEL;
  protected readonly routeTint = ROUTE_TINT;
  protected readonly sentimentTint = SENTIMENT_TINT;
  protected readonly sourceIcon = SOURCE_ICON;
  protected readonly sourceTint = SOURCE_TINT;

  protected readonly filterTabs: { value: Filter; label: string; dot?: string }[] = [
    { value: 'pending', label: 'Por revisar', dot: 'bg-amber-500' },
    { value: 'approved', label: 'Aprobados', dot: 'bg-sky-500' },
    { value: 'published', label: 'Publicados', dot: 'bg-emerald-500' },
    { value: 'rejected', label: 'Rechazados', dot: 'bg-rose-500' },
    { value: 'all', label: 'Todos' },
  ];

  protected readonly query = signal('');
  protected readonly status = signal<Filter>('pending');
  protected readonly selectedId = signal<string | null>(null);

  protected readonly viewModes = [
    { label: 'Vista previa', value: 'preview' },
    { label: 'Editar', value: 'edit' },
  ];
  protected readonly view = signal<'preview' | 'edit'>('preview');
  protected draft = { title: '', body: '', hashtags: '' };

  protected readonly bannerOpen = signal(false);
  protected readonly bannerSvg = signal('');
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
        (!q || `${a.title} ${a.body} ${a.origin.author} ${a.origin.channel}`.toLowerCase().includes(q)),
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
      this.draft = a ? { title: a.title, body: a.body, hashtags: a.hashtags.join(' ') } : { title: '', body: '', hashtags: '' };
    });
  }

  protected select(a: GeneratedAsset) {
    this.selectedId.set(a.id);
    this.view.set('preview');
  }

  protected dirty(a: GeneratedAsset) {
    return this.draft.title !== a.title || this.draft.body !== a.body || this.draft.hashtags !== a.hashtags.join(' ');
  }

  protected save(a: GeneratedAsset) {
    this.store.update(a.id, {
      title: this.draft.title.trim(),
      body: this.draft.body.trim(),
      hashtags: this.draft.hashtags
        .split(/[\s,]+/)
        .filter(Boolean)
        .map((h) => (h.startsWith('#') ? h : `#${h}`)),
    });
    this.view.set('preview');
    this.toast.add({ severity: 'success', summary: 'Cambios guardados', life: 2000 });
  }

  protected setStatus(a: GeneratedAsset, status: AssetStatus) {
    this.store.update(a.id, { status });
    this.toast.add({ severity: 'info', summary: `Marcado como ${ASSET_STATUS_LABEL[status].toLowerCase()}`, life: 2000 });
  }

  protected publish(a: GeneratedAsset, event?: Event) {
    this.store.publish(a.id);
    confetti(originOf(event));
    this.toast.add({
      severity: 'success',
      summary: 'Publicado',
      detail: `${ASSET_TYPE_LABEL[a.type]} de ${a.origin.author}`,
      life: 3000,
    });
  }

  protected publishAllApproved(event?: Event) {
    const approved = this.store.assets().filter((a) => a.status === 'approved');
    approved.forEach((a) => this.store.publish(a.id));
    confetti(originOf(event));
    this.toast.add({ severity: 'success', summary: `${approved.length} contenidos publicados`, life: 3000 });
  }

  protected copy(a: GeneratedAsset) {
    const text = a.body + (a.hashtags.length ? `\n\n${a.hashtags.join(' ')}` : '');
    navigator.clipboard?.writeText(text).then(() =>
      this.toast.add({ severity: 'info', summary: 'Texto copiado', life: 2000 }),
    );
  }

  protected openBanner(a: GeneratedAsset) {
    this.bannerSvg.set(buildBannerSvg(a));
    this.bannerOpen.set(true);
  }

  protected downloadBanner() {
    const a = this.selected();
    if (a) downloadBannerPng(this.bannerSvg(), `banner-${a.id}.png`);
  }
}
