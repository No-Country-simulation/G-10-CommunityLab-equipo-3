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

/** Date pipe that follows the selected UI language: `{{ value | ldate: 'medium' }}`. */
@Pipe({ name: 'ldate', pure: false })
export class LocalizedDatePipe implements PipeTransform {
  private readonly i18n = inject(I18n);

  transform(value: string | number | Date | null | undefined, format = 'mediumDate'): string {
    return value == null || value === '' ? '' : formatDate(value, format, this.i18n.locale());
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
