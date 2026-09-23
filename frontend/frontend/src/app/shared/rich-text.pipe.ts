import { Pipe, PipeTransform } from '@angular/core';

/**
 * Renders the small markdown subset the generator emits (**bold**, *italic*, `code`)
 * as HTML. Input is escaped first, so it is safe to bind to [innerHTML].
 */
@Pipe({ name: 'richText' })
export class RichTextPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) return '';
    return value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/`([^`]+)`/g, '<code class="rounded bg-slate-100 px-1 py-0.5 text-[0.85em] dark:bg-slate-800">$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\*([^*]+)\*/g, '<em>$1</em>');
  }
}
