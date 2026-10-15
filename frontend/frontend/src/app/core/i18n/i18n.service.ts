import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeEs from '@angular/common/locales/es';
import localePt from '@angular/common/locales/pt';
import { EN, ES, MessageKey, PT } from './messages';
import { I18N_DICTIONARIES } from './i18n.tokens';

export type Lang = 'es' | 'en' | 'pt';

export interface LangOption {
  code: Lang;
  label: string;
  short: string;
  /** Flag shown in the language switchers (public/image/flags/) */
  flag: string;
}

export const LANGS: readonly LangOption[] = [
  { code: 'es', label: 'Español', short: 'ES', flag: 'image/flags/es.svg' },
  { code: 'en', label: 'English', short: 'EN', flag: 'image/flags/us.svg' },
  { code: 'pt', label: 'Português', short: 'PT', flag: 'image/flags/br.svg' },
];

/** Locale ids for Angular's date formatting */
const LOCALES: Record<Lang, string> = { es: 'es', en: 'en-US', pt: 'pt' };
const LANG_KEY = 'lang';

registerLocaleData(localeEs);
registerLocaleData(localePt);

/** UI language (Spanish, English, Portuguese). Everything reads `lang()`, so switching re-renders in place. */
@Injectable({ providedIn: 'root' })
export class I18n {
  private readonly dictionaries = inject(I18N_DICTIONARIES);
  private readonly _lang = signal<Lang>(readLang());

  readonly lang = this._lang.asReadonly();
  readonly locale = computed(() => LOCALES[this._lang()]);

  constructor() {
    effect(() => {
      document.documentElement.lang = this._lang();
    });
  }

  set(lang: Lang) {
    this._lang.set(lang);
    try {
      localStorage.setItem(LANG_KEY, lang);
    } catch {
      // choice just won't persist
    }
  }

  /** Like `t`, but null when no dictionary has the key (for values that may or may not be translatable). */
  find(key: string): string | null {
    return this.dictionaries[this._lang()][key as MessageKey] ?? this.dictionaries.es[key as MessageKey] ?? null;
  }

  /** Translates a key, filling `{name}` placeholders. Unknown keys fall back to the key itself. */
  t(key: MessageKey | string, params?: Record<string, string | number | null>): string {
    const text = this.dictionaries[this._lang()][key as MessageKey] ?? this.dictionaries.es[key as MessageKey] ?? key;
    return params ? text.replace(/\{(\w+)\}/g, (_, name) => String(params[name] ?? '')) : text;
  }
}

function readLang(): Lang {
  try {
    const saved = localStorage.getItem(LANG_KEY);
    if (saved === 'es' || saved === 'en' || saved === 'pt') return saved;
  } catch {
    // storage unavailable
  }
  const browser = (navigator.language || 'es').slice(0, 2);
  return browser === 'en' || browser === 'pt' ? browser : 'es';
}
