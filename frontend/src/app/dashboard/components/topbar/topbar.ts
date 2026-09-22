import { Component, inject, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { MenuItem } from 'primeng/api';
import { AvatarModule } from 'primeng/avatar';
import { MenuModule } from 'primeng/menu';
import { TooltipModule } from 'primeng/tooltip';
import { API_CONFIG } from '../../../core/api/community-lab.api';
import { MOCK_STORAGE_KEY } from '../../../core/api/mock-community-lab.api';
import { ThemeService } from '../../../core/theme.service';

@Component({
  selector: 'app-topbar',
  imports: [AvatarModule, MenuModule, TooltipModule],
  templateUrl: './topbar.html',
})
export class Topbar {
  private readonly router = inject(Router);
  protected readonly theme = inject(ThemeService);
  private readonly api = inject(API_CONFIG);

  readonly toggleSidebar = output<void>();

  protected readonly page = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => {
        let r = this.router.routerState.snapshot.root;
        while (r.firstChild) r = r.firstChild;
        return {
          title: (r.data['title'] as string | undefined) ?? 'Resumen',
          icon: (r.data['icon'] as string | undefined) ?? 'pi pi-th-large',
        };
      }),
    ),
    { initialValue: { title: 'Resumen', icon: 'pi pi-th-large' } },
  );

  protected readonly userMenu: MenuItem[] = this.api.useMocks
    ? [
        {
          label: 'Restablecer datos demo',
          icon: 'pi pi-replay',
          command: () => {
            try {
              localStorage.removeItem(MOCK_STORAGE_KEY);
            } catch {
              // nothing persisted
            }
            location.reload();
          },
        },
      ]
    : [{ label: 'Cerrar sesión', icon: 'pi pi-sign-out' }];
}
