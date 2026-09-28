import { InjectionToken } from '@angular/core';
import { EN, ES, MessageKey, PT } from './messages';

export type Dictionary = Record<MessageKey, string>;
export type Dictionaries = Record<'es' | 'en' | 'pt', Dictionary>;

export const I18N_DICTIONARIES = new InjectionToken<Dictionaries>('I18N_DICTIONARIES', {
  providedIn: 'root',
  factory: () => ({ es: ES, en: EN, pt: PT }),
});
