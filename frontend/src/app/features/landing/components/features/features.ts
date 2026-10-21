import { Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Reveal } from '../../../../shared/reveal.directive';

export interface IngestSample {
  channel: string;
  author: string;
  role: string;
  time: string;
  text: string;
  reactions: { emoji: string; count: number }[];
  category: string;
  route: string;
  relevance: number;
  sentiment: string;
  tags: string[];
  reason: string;
}

export interface LlmAnalysisCase {
  id: 'win' | 'faq' | 'noise';
  label: string;
  icon: string;
  relevance: number;
  statusLabel: string;
  statusKind: 'publish' | 'faq' | 'discard';
  sentiment: string;
  sentimentScore: number;
  tags: string[];
  reason: string;
  route: string;
}

@Component({
  selector: 'app-landing-features',
  imports: [RouterLink, TranslatePipe, Reveal],
  templateUrl: './features.html',
})
export class LandingFeatures {
  readonly features = input<readonly any[]>();

  protected readonly activeChannel = signal<'discord' | 'telegram'>('discord');
  protected readonly discordIdx = signal(0);
  protected readonly telegramIdx = signal(0);
  protected readonly ingestedCount = signal(128);
  protected readonly activeAnalysisId = signal<'win' | 'faq' | 'noise'>('win');

  protected readonly discordSamples: readonly IngestSample[] = [
    {
      channel: '#logros',
      author: 'Mariana',
      role: 'Dev Jr IA',
      time: 'Ahora mismo',
      text: '«¡Muchachos, acabo de firmar mi primer contrato dev gracias al feedback del reto en la comunidad! 🚀»',
      reactions: [
        { emoji: '🎉', count: 28 },
        { emoji: '❤️', count: 19 },
        { emoji: '👏', count: 14 },
      ],
      category: 'Logro / Empleo',
      route: 'Post LinkedIn + Hilo X',
      relevance: 96,
      sentiment: 'Positivo · Entusiasmo',
      tags: ['#PrimerEmpleo', '#CasoDeÉxito', '#ComunidadDev'],
      reason: 'Hito de empleabilidad real con alto impacto inspiracional para redes.',
    },
    {
      channel: '#showcase',
      author: 'Sofía',
      role: 'Backend Dev',
      time: 'Hace 1 min',
      text: '«Desplegamos el conector de Spring Boot con OCI Always Free en menos de 10 minutos. ¡Les dejo la guía!»',
      reactions: [
        { emoji: '🚀', count: 34 },
        { emoji: '🔥', count: 21 },
        { emoji: '🙌', count: 15 },
      ],
      category: 'Testimonio / Proyecto',
      route: 'Destacado en Newsletter',
      relevance: 94,
      sentiment: 'Positivo · Orgullo técnico',
      tags: ['#SpringBoot', '#OracleCloud', '#OpenSource'],
      reason: 'Aporte técnico práctico con alta validación de otros miembros.',
    },
  ];

  protected readonly telegramSamples: readonly IngestSample[] = [
    {
      channel: '@community_lab',
      author: 'Lucas',
      role: 'Miembro activo',
      time: 'Hace 15 s',
      text: '«¿Cómo estructuramos el prompt del clasificador para distinguir entre una duda técnica y un caso de éxito?»',
      reactions: [
        { emoji: '💡', count: 16 },
        { emoji: '👀', count: 11 },
        { emoji: '🔥', count: 9 },
      ],
      category: 'Duda recurrente',
      route: 'Entrada FAQ automática',
      relevance: 91,
      sentiment: 'Constructivo · Consulta',
      tags: ['#PromptEngineering', '#ClasificadorIA', '#FAQ'],
      reason: 'Duda técnica frecuente en la semana; ideal para documentar en FAQ.',
    },
    {
      channel: '@devs_latam',
      author: 'Mateo',
      role: 'Fullstack',
      time: 'Ahora mismo',
      text: '«El webhook de Telegram quedó recibiendo mensajes en tiempo real sin perder ni un hilo del grupo 💪»',
      reactions: [
        { emoji: '⚡', count: 24 },
        { emoji: '❤️', count: 17 },
        { emoji: '👏', count: 12 },
      ],
      category: 'Caso de éxito',
      route: 'Post X + LinkedIn',
      relevance: 93,
      sentiment: 'Positivo · Logro técnico',
      tags: ['#TelegramBot', '#TiempoReal', '#Automatización'],
      reason: 'Validación real de arquitectura en producción lista para difundir.',
    },
  ];

  protected readonly analysisCases: readonly LlmAnalysisCase[] = [
    {
      id: 'win',
      label: 'Logro / Éxito',
      icon: 'pi pi-trophy',
      relevance: 96,
      statusLabel: 'Apto para publicar',
      statusKind: 'publish',
      sentiment: 'Positivo · Entusiasmo (98%)',
      sentimentScore: 98,
      tags: ['#PrimerEmpleo', '#CasoDeÉxito', '#ComunidadDev'],
      reason: '«Detectado hito de empleabilidad real con alta validación comunitaria. Se recomienda post en LinkedIn e hilo en X.»',
      route: 'LinkedIn + X + Newsletter',
    },
    {
      id: 'faq',
      label: 'Duda técnica',
      icon: 'pi pi-question-circle',
      relevance: 91,
      statusLabel: 'Convertir en FAQ',
      statusKind: 'faq',
      sentiment: 'Constructivo · Consulta (89%)',
      sentimentScore: 89,
      tags: ['#PromptEngineering', '#ArquitecturaIA', '#GuíaTécnica'],
      reason: '«Consulta técnica repetida por varios miembros. El LLM sintetiza pregunta y respuesta para la base de conocimiento.»',
      route: 'Base de conocimiento (FAQ)',
    },
    {
      id: 'noise',
      label: 'Ruido casual',
      icon: 'pi pi-filter-slash',
      relevance: 12,
      statusLabel: 'Ruido descartado',
      statusKind: 'discard',
      sentiment: 'Neutral · Charla casual (20%)',
      sentimentScore: 20,
      tags: ['#OffTopic', '#SaludoCotidiano'],
      reason: '«Conversación casual ("¿alguien para jugar hoy?") sin valor editorial. Filtrada automáticamente para ahorrar tiempo al CM.»',
      route: 'Ignorado (No genera borradores)',
    },
  ];

  protected readonly currentDiscord = computed(() => this.discordSamples[this.discordIdx()]);
  protected readonly currentTelegram = computed(() => this.telegramSamples[this.telegramIdx()]);
  protected readonly selectedSample = computed(() =>
    this.activeChannel() === 'discord' ? this.currentDiscord() : this.currentTelegram(),
  );
  protected readonly currentAnalysis = computed(
    () => this.analysisCases.find((c) => c.id === this.activeAnalysisId()) ?? this.analysisCases[0],
  );

  // Card 3: Social distribution state (X vs LinkedIn)
  protected readonly activeSocial = signal<'x' | 'linkedin'>('x');
  protected readonly socialPublished = signal(false);

  // Card 4: Living FAQ state
  protected readonly activeFaqIdx = signal(0);
  protected readonly faqCopied = signal(false);
  protected readonly faqItems = [
    {
      badge: 'Duda recurrente (x12)',
      source: 'Telegram · @community_lab',
      question: '¿Cómo distingue la IA entre charla casual y un logro publicable?',
      answer: 'Evalúa contexto, sentimiento y palabras clave de impacto, filtrando el 95% del ruido automáticamente.',
    },
    {
      badge: 'Duda técnica (x8)',
      source: 'Discord · #ayuda-ia',
      question: '¿Se publica solo o el Community Manager aprueba antes?',
      answer: 'Cada pieza queda como borrador editable en la consola para aprobarse y publicarse en un solo clic.',
    },
  ] as const;
  protected readonly currentFaq = computed(() => this.faqItems[this.activeFaqIdx()]);

  // Card 5: Traceable History & Community Memory state
  protected readonly activeHistoryIdx = signal(0);
  protected readonly historyItems = [
    {
      id: 'HIST-104',
      date: 'Hoy · 14:20',
      title: 'Contratación Jr IA · Mariana',
      origin: 'Discord #logros',
      outputs: 'Post LinkedIn + Hilo X',
      status: 'Publicado y respaldado',
    },
    {
      id: 'HIST-103',
      date: 'Ayer · 18:05',
      title: 'Guía clasificadores LLM · Lucas',
      origin: 'Telegram @community_lab',
      outputs: 'Entrada FAQ #24',
      status: 'Sincronizado en base',
    },
    {
      id: 'HIST-102',
      date: 'Lun · 09:30',
      title: 'Resumen semanal de la comunidad',
      origin: '14 interacciones clave',
      outputs: 'Newsletter + 3 Posts',
      status: 'Archivado en nube',
    },
  ] as const;
  protected readonly currentHistory = computed(() => this.historyItems[this.activeHistoryIdx()]);

  // Card 6: Interactive Curation Console (Split-View Workbench)
  protected readonly consoleIdx = signal(0);
  protected readonly consoleTone = signal<'inspirador' | 'conciso' | 'didactico'>('inspirador');
  protected readonly consoleShowBanner = signal(false);
  protected readonly consoleStatuses = signal<Record<number, 'in_review' | 'approved' | 'published'>>({
    0: 'in_review',
    1: 'approved',
    2: 'published',
  });

  protected readonly consoleItems = [
    {
      id: 'AST-201',
      title: 'Primer empleo Dev IA · Mariana',
      typeLabel: 'LinkedIn & X',
      typeIcon: 'pi pi-linkedin',
      typeTint: 'bg-[#0a66c2]/10 text-[#0a66c2] dark:bg-[#0a66c2]/20 dark:text-[#70b5f9]',
      sourceIcon: 'pi pi-discord',
      sourceColor: 'text-[#5865f2]',
      sourceLabel: 'Discord · #logros',
      author: 'Mariana',
      relevance: '96%',
      originExcerpt: '«¡Muchachos, acabo de firmar mi primer contrato dev gracias al feedback del reto en la comunidad! 🚀»',
      hashtags: '#CasoDeÉxito #ComunidadDev #Empleabilidad #IA',
      tones: {
        inspirador:
          '✨ Cuando una comunidad comparte conocimiento real, los resultados llegan. Hoy celebramos que Mariana firmó su primer contrato como Desarrolladora IA tras semanas de práctica colectiva. ¡Felicidades! 🚀',
        conciso:
          '🚀 Nuevo hito en la comunidad: Mariana acaba de conseguir su primer empleo como Dev IA aplicando lo aprendido en los retos semanales. ¡Enhorabuena!',
        didactico:
          '💡 ¿Cómo pasar de proyectos de práctica a tu primer empleo tech? Mariana lo logró iterando su portafolio con feedback en vivo de la comunidad.',
      },
    },
    {
      id: 'AST-202',
      title: 'Cómo filtrar ruido en chats con IA',
      typeLabel: 'Entrada FAQ',
      typeIcon: 'pi pi-question-circle',
      typeTint: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
      sourceIcon: 'pi pi-telegram',
      sourceColor: 'text-[#229ed9]',
      sourceLabel: 'Telegram · @community_lab',
      author: 'Lucas',
      relevance: '91%',
      originExcerpt: '«¿Cómo estructuramos el prompt del clasificador para distinguir entre una duda técnica y un caso de éxito?»',
      hashtags: '#FAQ #PromptEngineering #KnowledgeBase',
      tones: {
        inspirador:
          '📚 Cada duda de un miembro es una oportunidad para documentar conocimiento colectivo. Así clasifica Ukuku AI las preguntas frecuentes automáticamente.',
        conciso:
          '❓ FAQ #24: El clasificador evalúa sentimiento, contexto técnico y recurrencia para separar dudas clave del ruido casual del chat.',
        didactico:
          '🛠️ Paso a paso: 1) Captura del mensaje en Telegram, 2) Scoring de relevancia (>85%), 3) Síntesis automática en formato Pregunta/Respuesta.',
      },
    },
    {
      id: 'AST-203',
      title: 'Resumen semanal: 3 lanzamientos',
      typeLabel: 'Newsletter',
      typeIcon: 'pi pi-envelope',
      typeTint: 'bg-amber-500/10 text-amber-700 dark:text-amber-300',
      sourceIcon: 'pi pi-discord',
      sourceColor: 'text-[#5865f2]',
      sourceLabel: 'Discord · #showcase',
      author: 'Sofía',
      relevance: '94%',
      originExcerpt: '«Desplegamos el conector de Spring Boot en menos de 10 minutos. ¡Les dejo la guía completa para todos!»',
      hashtags: '#Newsletter #ResumenSemanal #OpenSource',
      tones: {
        inspirador:
          '🔥 Esta semana la comunidad superó todas las expectativas: nuevos despliegues en producción, guías abiertas y contrataciones que nos llenan de orgullo.',
        conciso:
          '📬 Resumen semanal: 1 guía técnica de Spring Boot por Sofía, 1 nueva contratación dev y 12 dudas resueltas en nuestra base de conocimiento.',
        didactico:
          '📖 Lo mejor de la semana para estudiar el fin de semana: revisa la guía de arquitectura compartida por Sofía en el canal #showcase.',
      },
    },
  ] as const;

  protected readonly currentConsoleItem = computed(() => this.consoleItems[this.consoleIdx()]);
  protected readonly currentConsoleBody = computed(
    () => this.currentConsoleItem().tones[this.consoleTone()],
  );
  protected readonly currentConsoleStatus = computed(
    () => this.consoleStatuses()[this.consoleIdx()] ?? 'in_review',
  );

  protected selectChannel(channel: 'discord' | 'telegram'): void {
    this.activeChannel.set(channel);
    this.activeAnalysisId.set(channel === 'discord' ? 'win' : 'faq');
  }

  protected selectAnalysis(id: 'win' | 'faq' | 'noise'): void {
    this.activeAnalysisId.set(id);
    if (id === 'win') this.activeChannel.set('discord');
    if (id === 'faq') this.activeChannel.set('telegram');
  }

  protected simulateIngest(): void {
    if (this.activeChannel() === 'discord') {
      this.telegramIdx.update((i) => (i + 1) % this.telegramSamples.length);
      this.activeChannel.set('telegram');
      this.activeAnalysisId.set('faq');
    } else {
      this.discordIdx.update((i) => (i + 1) % this.discordSamples.length);
      this.activeChannel.set('discord');
      this.activeAnalysisId.set('win');
    }
    this.ingestedCount.update((n) => n + 1);
  }

  protected selectSocial(network: 'x' | 'linkedin'): void {
    this.activeSocial.set(network);
    this.socialPublished.set(false);
  }

  protected triggerPublish(): void {
    this.socialPublished.update((v) => !v);
  }

  protected nextFaq(idx: number): void {
    this.activeFaqIdx.set(idx);
    this.faqCopied.set(false);
  }

  protected copyFaq(): void {
    this.faqCopied.set(true);
  }

  protected selectHistory(idx: number): void {
    this.activeHistoryIdx.set(idx);
  }

  protected selectConsoleItem(idx: number): void {
    this.consoleIdx.set(idx);
    this.consoleShowBanner.set(false);
  }

  protected setConsoleTone(tone: 'inspirador' | 'conciso' | 'didactico'): void {
    this.consoleTone.set(tone);
  }

  protected updateConsoleStatus(status: 'in_review' | 'approved' | 'published'): void {
    const idx = this.consoleIdx();
    this.consoleStatuses.update((map) => ({ ...map, [idx]: status }));
  }

  protected toggleConsoleBanner(): void {
    this.consoleShowBanner.update((v) => !v);
  }
}