import { Injectable, computed, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly _mode = signal<ThemeMode>(
    document.documentElement.classList.contains('dark') ? 'dark' : 'light',
  );

  readonly mode = this._mode.asReadonly();
  readonly isDark = computed(() => this._mode() === 'dark');

  toggle() {
    this.set(this.isDark() ? 'light' : 'dark');
  }

  set(mode: ThemeMode) {
    this._mode.set(mode);
    document.documentElement.classList.toggle('dark', mode === 'dark');
    try {
      localStorage.setItem('theme', mode);
    } catch {
      // storage unavailable (private mode); theme still applies for this session
    }
  }
}
