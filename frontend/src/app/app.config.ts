import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { MessageService } from 'primeng/api';
import { providePrimeNG } from 'primeng/config';
import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

import { routes } from './app.routes';
import { provideCommunityLabApi } from './core/api/provide-api';
import { I18N_DICTIONARIES } from './core/i18n/i18n.tokens';
import { EN, ES, PT } from './core/i18n/messages';
import { LANDING_EN } from './features/landing/i18n/landing.en';
import { LANDING_ES } from './features/landing/i18n/landing.es';
import { LANDING_PT } from './features/landing/i18n/landing.pt';

const CommunityLabPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{amber.50}',
      100: '{amber.100}',
      200: '{amber.200}',
      300: '{amber.300}',
      400: '{amber.400}',
      500: '{amber.500}',
      600: '{amber.600}',
      700: '{amber.700}',
      800: '{amber.800}',
      900: '{amber.900}',
      950: '{amber.950}',
    },
    // Warm neutrals (stone) instead of the default cool slate/zinc
    colorScheme: {
      light: {
        surface: {
          0: '#ffffff',
          50: '{stone.50}',
          100: '{stone.100}',
          200: '{stone.200}',
          300: '{stone.300}',
          400: '{stone.400}',
          500: '{stone.500}',
          600: '{stone.600}',
          700: '{stone.700}',
          800: '{stone.800}',
          900: '{stone.900}',
          950: '{stone.950}',
        },
      },
      dark: {
        surface: {
          0: '#ffffff',
          50: '{stone.50}',
          100: '{stone.100}',
          200: '{stone.200}',
          300: '{stone.300}',
          400: '{stone.400}',
          500: '{stone.500}',
          600: '{stone.600}',
          700: '{stone.700}',
          800: '{stone.800}',
          900: '{stone.900}',
          950: '{stone.950}',
        },
      },
    },
  },
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      withComponentInputBinding(),
      // New page starts at the top instead of inheriting the previous scroll
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),
    provideAnimationsAsync(),
    provideHttpClient(withFetch()),
    {
      provide: I18N_DICTIONARIES,
      useValue: {
        es: { ...ES, ...LANDING_ES },
        en: { ...EN, ...LANDING_EN },
        pt: { ...PT, ...LANDING_PT },
      },
    },
    MessageService,
    // Real backend. It only exposes the SSE streams for now: until the REST endpoints of
    // API_CONTRACT.md exist, asset and storage lists load empty.
    // Set useMocks to true for the simulated demo.
    // '/api' is proxied to http://localhost:8080 by proxy.conf.json (ng serve).
    provideCommunityLabApi({
      baseUrl: '/api/v1',
      useMocks: false,
      liveFeeds: {
        Discord: '/api/v1/discord/messages',
        Telegram: '/api/v1/telegram/messages',
      },
    }),
    providePrimeNG({
      ripple: true,
      theme: {
        preset: CommunityLabPreset,
        options: {
          darkModeSelector: '.dark',
        },
      },
    }),
  ],
};
