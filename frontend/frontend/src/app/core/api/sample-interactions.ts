import { Interaction } from './api.models';

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
