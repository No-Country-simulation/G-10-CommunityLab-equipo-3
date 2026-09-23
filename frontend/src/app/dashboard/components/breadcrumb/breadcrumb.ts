import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CurrentPage } from '../../../core/current-page';
import { TranslatePipe } from '../../../core/i18n/translate.pipe';

/** "Inicio › Página actual", shown above every page title. */
@Component({
  selector: 'app-breadcrumb',
  imports: [RouterLink, TranslatePipe],
  host: { class: 'block' },
  template: `
    @let p = current.page();
    <nav class="breadcrumb" [attr.aria-label]="'breadcrumb.aria' | t">
      <ol>
        <li>
          <a routerLink="/" class="breadcrumb-link">
            <i class="pi pi-home text-[11px]" aria-hidden="true"></i>{{ 'breadcrumb.home' | t }}
          </a>
        </li>
        <li aria-hidden="true"><i class="pi pi-angle-right breadcrumb-sep"></i></li>
        <li><span class="breadcrumb-current" aria-current="page">{{ p.titleKey | t }}</span></li>
      </ol>
    </nav>
  `,
})
export class Breadcrumb {
  protected readonly current = inject(CurrentPage);
}
