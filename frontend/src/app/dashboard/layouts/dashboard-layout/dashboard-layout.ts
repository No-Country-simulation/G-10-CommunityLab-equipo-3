import { Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastModule } from 'primeng/toast';
import { Sidebar } from '../../components/sidebar/sidebar';
import { Topbar } from '../../components/topbar/topbar';

const COLLAPSED_KEY = 'sidebar-collapsed';

@Component({
  selector: 'app-dashboard-layout',
  imports: [RouterOutlet, ToastModule, Sidebar, Topbar],
  templateUrl: './dashboard-layout.html',
  styleUrl: './dashboard-layout.css',
})
export class DashboardLayout {
  protected readonly sidebarOpen = signal(false);
  protected readonly sidebarCollapsed = signal(readCollapsed());

  protected toggleCollapsed() {
    this.sidebarCollapsed.update((v) => !v);
    try {
      localStorage.setItem(COLLAPSED_KEY, String(this.sidebarCollapsed()));
    } catch {
      // preference just won't persist
    }
  }
}

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === 'true';
  } catch {
    return false;
  }
}
