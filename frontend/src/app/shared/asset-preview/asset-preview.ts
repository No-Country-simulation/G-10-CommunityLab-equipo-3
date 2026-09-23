import { Component, computed, input } from '@angular/core';
import { GeneratedAsset } from '../../core/api/api.models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { RichTextPipe } from '../rich-text.pipe';

/** Shows a generated asset the way it would look on its destination channel. */
@Component({
  selector: 'app-asset-preview',
  imports: [RichTextPipe, TranslatePipe],
  templateUrl: './asset-preview.html',
  host: { class: 'block' },
})
export class AssetPreview {
  readonly asset = input.required<GeneratedAsset>();
  readonly compact = input(false);

  protected readonly xLength = computed(() => {
    const a = this.asset();
    return (a.body + (a.hashtags.length ? ' ' + a.hashtags.join(' ') : '')).length;
  });
}
