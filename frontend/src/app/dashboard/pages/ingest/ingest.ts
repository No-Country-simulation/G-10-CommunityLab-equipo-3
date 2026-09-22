import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, LowerCasePipe, PercentPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription, interval, take } from 'rxjs';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { TooltipModule } from 'primeng/tooltip';
import { AnalyzedInteraction, GeneratedAsset, Interaction, InteractionSource } from '../../../core/api/api.models';
import { SAMPLE_INTERACTIONS } from '../../../core/api/sample-interactions';
import { downloadBlob } from '../../../core/banner';
import { ParseError, parseInteractions } from '../../../core/interaction-parser';
import {
  ASSET_STATUS_LABEL,
  ASSET_STATUS_SEVERITY,
  ASSET_TYPE_ICON,
  ASSET_TYPE_LABEL,
  ROUTE_ICON,
  ROUTE_LABEL,
  ROUTE_TINT,
  SENTIMENT_ICON,
  SENTIMENT_TINT,
  SOURCE_ICON,
  SOURCE_TINT,
} from '../../../core/ui-maps';
import { WorkspaceStore } from '../../../core/workspace.store';
import { AssetPreview } from '../../../shared/asset-preview/asset-preview';
import { CountUp } from '../../../shared/count-up.directive';

type InputMode = 'samples' | 'data' | 'manual';

const JSON_TEMPLATE = JSON.stringify(
  {
    interactions: [
      {
        author: 'Mariana López',
        content: 'Me contrataron como Dev Jr de IA gracias a mi proyecto con LangChain y OCI. ¡Gracias comunidad!',
        source: 'Discord',
        channel: '#logros',
      },
      {
        author: 'Lucas Fernández',
        content: '¿Cómo hago nodos de reintento en LangGraph?',
        source: 'Discord',
        channel: '#ayuda-ia',
      },
    ],
  },
  null,
  2,
);

const CSV_TEMPLATE =
  'author,content,source,channel,timestamp\n' +
  '"Mariana López","Me contrataron como Dev Jr de IA gracias a mi proyecto con LangChain y OCI. ¡Gracias!",Discord,#logros,2026-09-22T14:05:00Z\n' +
  '"Lucas Fernández","¿Cómo hago nodos de reintento en LangGraph?",Discord,#ayuda-ia,2026-09-22T14:12:00Z\n';

@Component({
  selector: 'app-ingest',
  imports: [
    ButtonModule,
    CheckboxModule,
    SelectModule,
    SelectButtonModule,
    TagModule,
    TextareaModule,
    TooltipModule,
    FormsModule,
    RouterLink,
    DatePipe,
    DecimalPipe,
    PercentPipe,
    LowerCasePipe,
    AssetPreview,
    CountUp,
  ],
  templateUrl: './ingest.html',
})
export class Ingest {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly sourceIcon = SOURCE_ICON;
  protected readonly sourceTint = SOURCE_TINT;
  protected readonly routeLabel = ROUTE_LABEL;
  protected readonly routeTint = ROUTE_TINT;
  protected readonly routeIcon = ROUTE_ICON;
  protected readonly sentimentTint = SENTIMENT_TINT;
  protected readonly sentimentIcon = SENTIMENT_ICON;
  protected readonly typeLabel = ASSET_TYPE_LABEL;
  protected readonly typeIcon = ASSET_TYPE_ICON;
  protected readonly statusLabel = ASSET_STATUS_LABEL;
  protected readonly statusSeverity = ASSET_STATUS_SEVERITY;

  protected readonly modes: { label: string; value: InputMode; icon: string }[] = [
    { label: 'Ejemplos del reto', value: 'samples', icon: 'pi pi-star' },
    { label: 'JSON / CSV', value: 'data', icon: 'pi pi-code' },
    { label: 'Escribir', value: 'manual', icon: 'pi pi-pencil' },
  ];
  protected readonly mode = signal<InputMode>('samples');

  /** Same steps and colors as "Cómo funciona" in Resumen: navy → blue → cyan → Oracle red. */
  protected readonly steps = [
    { title: 'Ingesta', detail: 'Normaliza JSON / CSV / webhook', icon: 'pi pi-inbox', color: '#1e40af', colorTo: '#1e3a8a' },
    { title: 'Análisis con LLM', detail: 'Sentimiento, temas y relevancia', icon: 'pi pi-sparkles', color: '#2563eb', colorTo: '#1d4ed8' },
    { title: 'Orquestación', detail: 'Bifurcación y tono por canal', icon: 'pi pi-sitemap', color: '#0891b2', colorTo: '#0e7490' },
    { title: 'OCI Object Storage', detail: 'Paquete JSON en el bucket', icon: 'pi pi-cloud-upload', color: '#e0654f', colorTo: '#c74634' },
  ];
  /** -1 idle · 0..3 running step · 4 done */
  protected readonly step = signal(this.store.lastResult() ? 4 : -1);
  protected readonly processing = computed(() => this.step() >= 0 && this.step() < 4);

  // Staged batch
  protected readonly queue = signal<Interaction[]>([]);

  // Samples
  protected readonly samples = SAMPLE_INTERACTIONS;
  protected readonly selectedSamples = signal<string[]>(SAMPLE_INTERACTIONS.slice(0, 3).map((s) => s.id));

  // JSON
  protected readonly jsonText = signal(JSON_TEMPLATE);
  protected readonly parseError = signal<string | null>(null);

  // File
  protected readonly dragOver = signal(false);

  // Manual
  protected readonly sourceOptions: InteractionSource[] = ['Discord', 'Slack', 'GitHub', 'Foro', 'Formulario'];
  protected manual = { author: '', source: 'Discord' as InteractionSource, channel: '', content: '' };

  protected readonly result = this.store.lastResult;
  protected readonly showJson = signal(false);
  protected readonly resultJson = computed(() => JSON.stringify(this.result(), null, 2));

  protected readonly assetsByInteraction = computed(() => {
    const map = new Map<string, GeneratedAsset[]>();
    for (const a of this.result()?.assets ?? []) {
      map.set(a.interactionId, [...(map.get(a.interactionId) ?? []), a]);
    }
    return map;
  });

  private stepTimer?: Subscription;

  // ── Input methods ──────────────────────────────────────────────

  toggleSample(id: string, checked: boolean) {
    this.selectedSamples.update((ids) => (checked ? [...ids, id] : ids.filter((x) => x !== id)));
  }

  addSamples() {
    const picked = this.samples.filter((s) => this.selectedSamples().includes(s.id));
    this.enqueue(picked, 'ejemplos');
  }

  loadJson() {
    this.tryParse(() => parseInteractions(this.jsonText()), 'texto pegado');
  }

  onFile(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) this.readFile(file);
    (event.target as HTMLInputElement).value = '';
  }

  onDrop(event: DragEvent) {
    event.preventDefault();
    this.dragOver.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.readFile(file);
  }

  addManual() {
    const { author, source, channel, content } = this.manual;
    if (!content.trim()) return;
    this.enqueue(
      [
        {
          id: `msg-${Date.now().toString(36)}`,
          author: author.trim() || 'Anónimo',
          source,
          channel: channel.trim() || 'general',
          content: content.trim(),
          timestamp: new Date().toISOString(),
        },
      ],
      'manual',
    );
    this.manual = { ...this.manual, author: '', content: '' };
  }

  downloadTemplate(kind: 'csv' | 'json') {
    const blob =
      kind === 'csv'
        ? new Blob([CSV_TEMPLATE], { type: 'text/csv;charset=utf-8' })
        : new Blob([JSON_TEMPLATE], { type: 'application/json' });
    downloadBlob(blob, `plantilla-interacciones.${kind}`);
  }

  removeFromQueue(id: string) {
    this.queue.update((q) => q.filter((i) => i.id !== id));
  }

  clearQueue() {
    this.queue.set([]);
  }

  // ── Processing ─────────────────────────────────────────────────

  process() {
    const batch = this.queue();
    if (!batch.length || this.processing()) return;

    this.step.set(0);
    this.stepTimer = interval(700)
      .pipe(take(3))
      .subscribe(() => this.step.update((s) => Math.min(s + 1, 3)));

    const sub = this.store.process(batch).subscribe({
      next: (res) => {
        this.stepTimer?.unsubscribe();
        this.step.set(4);
        this.queue.set([]);
        this.toast.add({
          severity: 'success',
          summary: 'Lote procesado',
          detail: `${res.assets.length} contenidos generados y guardados en ${res.storage.bucket}`,
          life: 4000,
        });
        setTimeout(() => document.getElementById('results')?.scrollIntoView({ behavior: 'smooth' }), 50);
      },
      error: (err: Error) => {
        this.stepTimer?.unsubscribe();
        this.step.set(-1);
        this.toast.add({ severity: 'error', summary: 'Error al procesar', detail: err.message, life: 6000 });
      },
    });
    this.destroyRef.onDestroy(() => {
      sub.unsubscribe();
      this.stepTimer?.unsubscribe();
    });
  }

  newBatch() {
    this.store.clearResult();
    this.step.set(-1);
  }

  copy(text: string, what = 'Contenido') {
    navigator.clipboard?.writeText(text).then(() =>
      this.toast.add({ severity: 'info', summary: `${what} copiado`, life: 2000 }),
    );
  }

  downloadResult() {
    const r = this.result();
    if (!r) return;
    downloadBlob(new Blob([this.resultJson()], { type: 'application/json' }), `${r.batchId}.json`);
  }

  assetsFor(i: AnalyzedInteraction) {
    return this.assetsByInteraction().get(i.id) ?? [];
  }

  scoreLabel(score: number) {
    return (score > 0 ? '+' : '') + score.toFixed(2);
  }

  // ── Helpers ────────────────────────────────────────────────────

  private readFile(file: File) {
    if (!/\.(json|csv)$/i.test(file.name)) {
      this.toast.add({ severity: 'warn', summary: 'Formato no soportado', detail: 'Usa un archivo .json o .csv' });
      return;
    }
    file.text().then((text) => this.tryParse(() => parseInteractions(text, file.name.toLowerCase()), file.name));
  }

  private tryParse(parse: () => Interaction[], origin: string) {
    try {
      const items = parse();
      this.parseError.set(null);
      this.enqueue(items, origin);
    } catch (e) {
      const msg = e instanceof ParseError ? e.message : 'No se pudo leer el contenido.';
      this.parseError.set(msg);
      this.toast.add({ severity: 'error', summary: 'Error de formato', detail: msg, life: 5000 });
    }
  }

  private enqueue(items: Interaction[], origin: string) {
    const existing = new Set(this.queue().map((i) => i.id));
    const fresh = items.filter((i) => !existing.has(i.id));
    this.queue.update((q) => [...q, ...fresh]);
    this.toast.add({
      severity: 'success',
      summary: `${fresh.length} interacción(es) agregadas`,
      detail: `Origen: ${origin}`,
      life: 2500,
    });
  }
}
