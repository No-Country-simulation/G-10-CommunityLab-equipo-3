import { Injectable, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { PAGE_ICON } from './page-icons';

export interface PageInfo {
  /** Route path, e.g. '/ingest' */
  path: string;
  /** i18n key of the page title (route data `titleKey`) */
  titleKey: string;
  /** 3D page icon (route data `icon`) */
  icon: string;
}

const HOME: PageInfo = { path: '/', titleKey: 'nav.summary', icon: PAGE_ICON.summary };

/** The routed page currently on screen; shared by the breadcrumb and the browser tab title. */
@Injectable({ providedIn: 'root' })
export class CurrentPage {
  private readonly router = inject(Router);

  readonly page = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => this.read()),
    ),
    { initialValue: this.read() },
  );

  private read(): PageInfo {
    let r = this.router.routerState.snapshot.root;
    while (r.firstChild) r = r.firstChild;
    const path = '/' + (r.routeConfig?.path ?? '');
    return {
      path,
      titleKey: (r.data['titleKey'] as string | undefined) ?? HOME.titleKey,
      icon: (r.data['icon'] as string | undefined) ?? HOME.icon,
    };
  }
}
