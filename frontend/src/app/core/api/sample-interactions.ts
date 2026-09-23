import { Interaction } from './api.models';

/** Examples from the challenge brief plus extra cases that exercise every branch. */
export const SAMPLE_INTERACTIONS: Interaction[] = [
  {
    id: 'msg-001',
    author: 'Mariana López',
    content:
      '¡Hola a todos! Quería contarles que me contrataron como Dev Jr de IA. En la entrevista les encantó mi proyecto con LangChain y OCI que armé en el hackathon. ¡Gracias comunidad!',
    source: 'Discord',
    channel: '#logros',
    timestamp: '2026-09-22T14:05:00Z',
  },
  {
    id: 'msg-002',
    author: 'Lucas Fernández',
    content: '¿Alguien sabe cómo hacer nodos de reintento en LangGraph? Mi agente falla cuando la API externa tarda demasiado.',
    source: 'Discord',
    channel: '#ayuda-ia',
    timestamp: '2026-09-22T14:12:00Z',
  },
  {
    id: 'msg-003',
    author: 'Camila Rojas',
    content:
      'Las mentorías de los jueves me cambiaron la forma de estudiar. Aprendí más de Java y Spring en un mes que en todo el año. Recomiendo muchísimo esta comunidad.',
    source: 'Telegram',
    channel: 'Grupo Devs LATAM',
    timestamp: '2026-09-22T15:30:00Z',
  },
  {
    id: 'msg-004',
    author: 'Diego Martínez',
    content: '¿Cómo configuro un bucket de OCI Object Storage para guardar los JSON sin salirme del Always Free?',
    source: 'Discord',
    channel: '#oci',
    timestamp: '2026-09-22T16:02:00Z',
  },
  {
    id: 'msg-005',
    author: 'Valentina Gómez',
    content: 'Estoy frustrada, el enlace de la clase grabada no funciona desde ayer y nadie responde.',
    source: 'Telegram',
    channel: 'Soporte',
    timestamp: '2026-09-22T16:40:00Z',
  },
  {
    id: 'msg-007',
    author: 'Paula Ríos',
    content: '¿Cómo conecto un bot de Telegram a un flujo de n8n para responder preguntas frecuentes del grupo?',
    source: 'Telegram',
    channel: 'Grupo Devs LATAM',
    timestamp: '2026-09-22T16:55:00Z',
  },
  {
    id: 'msg-006',
    author: 'Tomás Herrera',
    content: 'Buenas, me sumo al canal 👋',
    source: 'Discord',
    channel: '#general',
    timestamp: '2026-09-22T17:01:00Z',
  },
];

/** Earlier batches, used to seed the curation panel and the OCI bucket listing. */
export const SEED_BATCHES: { processedAt: string; interactions: Interaction[] }[] = [
  {
    processedAt: '2026-09-18T19:00:00Z',
    interactions: [
      {
        id: 'seed-101',
        author: 'Andrés Castillo',
        content:
          'Después de 6 meses en el programa conseguí mi primer trabajo como desarrollador backend con Java y Spring Boot. ¡No lo hubiera logrado sin las revisiones de código de la comunidad!',
        source: 'Telegram',
        channel: 'Grupo Devs LATAM',
        timestamp: '2026-09-18T12:00:00Z',
      },
      {
        id: 'seed-102',
        author: 'Sofía Méndez',
        content: '¿Cómo despliego n8n en una VM Always Free de OCI con Docker?',
        source: 'Discord',
        channel: '#devops',
        timestamp: '2026-09-18T13:20:00Z',
      },
    ],
  },
  {
    processedAt: '2026-09-20T19:00:00Z',
    interactions: [
      {
        id: 'seed-201',
        author: 'Julián Pardo',
        content: 'Gracias a la comunidad aprendí Angular desde cero, el material y el acompañamiento son increíbles. ¡Lo recomiendo!',
        source: 'Discord',
        channel: '#frontend',
        timestamp: '2026-09-20T10:00:00Z',
      },
      {
        id: 'seed-202',
        author: 'Renata Silva',
        content: '¿Alguien sabe cómo usar Python para leer un CSV de mensajes y mandarlo a Gemini?',
        source: 'Telegram',
        channel: 'Dudas técnicas',
        timestamp: '2026-09-20T11:45:00Z',
      },
      {
        id: 'seed-203',
        author: 'Martín Vega',
        content:
          '¡Aprobé la certificación OCI Foundations gracias a los simulacros que compartieron en el grupo! Mil gracias a todos por el apoyo.',
        source: 'Telegram',
        channel: 'Grupo Devs LATAM',
        timestamp: '2026-09-20T12:30:00Z',
      },
    ],
  },
];
