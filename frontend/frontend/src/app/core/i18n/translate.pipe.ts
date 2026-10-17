import { Pipe, PipeTransform, inject } from '@angular/core';
import { formatDate } from '@angular/common';
import { GeneratedAsset } from '../api/api.models';
import { I18n } from './i18n.service';

/** `{{ 'nav.summary' | t }}` · `{{ 'common.contentCount.other' | t: { n: 2 } }}` — re-evaluates when the language changes. */
@Pipe({ name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(key: string, params?: Record<string, string | number | null>): string {
    return this.i18n.t(key, params);
  }
}

/**
 * Date pipe that follows the selected UI language: `{{ value | ldate }}`, `{{ value | ldate: 'dateTime' }}`,
 * `{{ value | ldate: 'mediumDate' : 'UTC' }}`. 'dateTime' is the app's date + time: "20 sept 2026, 14:00" (no seconds).
 */
@Pipe({ name: 'ldate', pure: false })
export class LocalizedDatePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(value: string | number | Date | null | undefined, format = 'mediumDate', timezone?: string): string {
    if (value == null || value === '') return '';
    if (format === 'dateTime') {
      return new Intl.DateTimeFormat(this.i18n.locale(), { dateStyle: 'medium', timeStyle: 'short', timeZone: timezone }).format(new Date(value));
    }
    return formatDate(value, format, this.i18n.locale(), timezone);
  }
}

/** Number pipe that follows the selected UI language: `{{ 50000 | lnum }}` → 50.000 / 50,000; `{{ x | lnum: 1 }}` keeps one decimal. */
@Pipe({ name: 'lnum', pure: false })
export class LocalizedNumberPipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(value: number | null | undefined, maximumFractionDigits = 0): string {
    return value == null ? '' : this.i18n.number(value, maximumFractionDigits);
  }
}

/** Asset title, or the translated default for its type when the generator gave none: `{{ asset | assetTitle }}`. */
@Pipe({ name: 'assetTitle', pure: false })
export class AssetTitlePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(asset: Pick<GeneratedAsset, 'title' | 'type'>): string {
    return assetTitle(asset, this.i18n);
  }
}

export function assetTitle(asset: Pick<GeneratedAsset, 'title' | 'type'>, i18n: I18n): string {
  return asset.title?.trim() || i18n.t(`assetTitle.${asset.type}`);
}

/** Generator tone in the UI language; tones outside the known set are shown as sent: `{{ asset.tone | tone }}`. */
@Pipe({ name: 'tone', pure: false })
export class TonePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(tone: string | null | undefined): string {
    if (!tone) return '';
    const key = tone.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase().trim();
    return this.i18n.find(`tone.${key}`) ?? tone;
  }
}
