import { Component, input, output } from '@angular/core';
import { I18n, Lang, LangOption } from '../../../../core/i18n/i18n.service';
import { ThemeService } from '../../../../core/theme.service';
import { LandingFooter } from '../footer/footer';
import { LandingNavbar } from '../navbar/navbar';

@Component({
  selector: 'app-landing-layout',
  imports: [LandingNavbar, LandingFooter],
  templateUrl: './landing-layout.html',
})
export class LandingLayout {
  readonly i18n = input.required<I18n>();
  readonly theme = input.required<ThemeService>();
  readonly langs = input.required<readonly LangOption[]>();
  readonly sections = input.required<readonly { id: string; key: string }[]>();
  readonly active = input.required<string>();
  readonly open = input(false);
  readonly scrolled = input(false);
  readonly navigate = output<{ id: string; event?: Event }>();
  readonly toggleMenu = output<void>();
  readonly setLanguage = output<Lang>();
}