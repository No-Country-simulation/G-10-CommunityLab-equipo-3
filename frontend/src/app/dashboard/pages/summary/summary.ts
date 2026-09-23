import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { GeneratedAsset } from '../../../core/api/api.models';
import { I18n } from '../../../core/i18n/i18n.service';
import { LocalizedDatePipe, TranslatePipe } from '../../../core/i18n/translate.pipe';
import { ASSET_TYPE_ICON, ASSET_TYPE_TINT } from '../../../core/ui-maps';
import { WorkspaceStore } from '../../../core/workspace.store';
import { StatCard, StatItem } from '../../../shared/stat-card/stat-card';

@Component({
  selector: 'app-summary',
  imports: [ButtonModule, RouterLink, StatCard, TranslatePipe, LocalizedDatePipe],
  templateUrl: './summary.html',
  styleUrl: './summary.css',
})
export class Summary {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);
  private readonly i18n = inject(I18n);

  protected readonly typeIcon = ASSET_TYPE_ICON;
  protected readonly typeTint = ASSET_TYPE_TINT;

  /** The four steps the challenge asks for, each linked to where it happens in the app (title/text are i18n keys). */
  protected readonly steps = [
    {
      title: 'step.ingest',
      text: 'summary.step.ingest',
      icon: 'pi pi-inbox',
      link: '/ingest',
      color: '#d97706',
      colorTo: '#b45309',
    },
    {
      title: 'step.analysis',
      text: 'summary.step.analysis',
      icon: 'pi pi-sparkles',
      link: '/ingest',
      color: '#8b5cf6',
      colorTo: '#6d28d9',
    },
    {
      title: 'step.orchestration',
      text: 'summary.step.orchestration',
      icon: 'pi pi-sitemap',
      link: '/content',
      color: '#10b981',
      colorTo: '#047857',
    },
    {
      title: 'step.storage',
      text: 'summary.step.storage',
      icon: 'pi pi-cloud-upload',
      link: '/storage',
      color: '#e0654f',
      colorTo: '#c74634',
    },
  ];

  /** Greeting of the welcome card, by local time of day (i18n key). */
  protected readonly greetingKey = (() => {
    const h = new Date().getHours();
    return h < 12 ? 'summary.hero.morning' : h < 19 ? 'summary.hero.afternoon' : 'summary.hero.evening';
  })();

  protected readonly statColors = ['#d97706', '#10b981', '#8b5cf6', '#c74634'];

  protected readonly stats = computed<StatItem[]>(() => {
    const objects = this.store.objects();
    const t = (key: string) => this.i18n.t(key);
    return [
      { label: t('summary.stat.analyzed'), value: objects.reduce((acc, o) => acc + o.interactions, 0), icon: 'pi pi-comments', link: '/ingest' },
      { label: t('summary.stat.generated'), value: this.store.assets().length, icon: 'pi pi-file-edit', link: '/content' },
      { label: t('summary.stat.pending'), value: this.store.pendingReview(), icon: 'pi pi-eye', link: '/content' },
      { label: t('summary.stat.packages'), value: objects.length, icon: 'pi pi-cloud', link: '/storage' },
    ];
  });

  protected readonly pending = computed(() =>
    this.store
      .assets()
      .filter((a) => a.status === 'draft' || a.status === 'in_review')
      .slice(0, 5),
  );

  protected readonly latestObjects = computed(() => this.store.objects().slice(0, 4));

  /** Items animating out of the pending list before the store update removes them. */
  protected readonly leaving = signal<ReadonlySet<string>>(new Set());

  approve(a: GeneratedAsset) {
    this.leaving.update((s) => new Set(s).add(a.id));
    setTimeout(() => {
      this.store.update(a.id, { status: 'approved' });
      this.leaving.update((s) => {
        const next = new Set(s);
        next.delete(a.id);
        return next;
      });
    }, 280);
    this.toast.add({ severity: 'success', summary: this.i18n.t('summary.toast.approved'), detail: a.title, life: 2500 });
  }

  fileName(objectName: string) {
    return objectName.split('/').pop();
  }
}
