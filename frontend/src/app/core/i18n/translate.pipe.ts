import { Pipe, PipeTransform, inject } from '@angular/core';
import { formatDate } from '@angular/common';
import { I18n } from './i18n.service';

/** `{{ 'nav.summary' | t }}` · `{{ 'ingest.step' | t: { n: 2 } }}` — re-evaluates when the language changes. */
@Pipe({ name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(key: string, params?: Record<string, string | number | null>): string {
    return this.i18n.t(key, params);
  }
}

/** Date pipe that follows the selected UI language: `{{ value | ldate: 'medium' }}`. */
@Pipe({ name: 'ldate', pure: false })
export class LocalizedDatePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(value: string | number | Date | null | undefined, format = 'mediumDate'): string {
    return value == null || value === '' ? '' : formatDate(value, format, this.i18n.locale());
  }
}
