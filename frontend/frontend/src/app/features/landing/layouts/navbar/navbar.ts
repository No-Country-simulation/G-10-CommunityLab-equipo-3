import { Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18n, Lang, LangOption } from '../../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { ThemeService } from '../../../../core/theme.service';

interface Section {
  id: string;
  key: string;
}

@Component({
  selector: 'app-landing-navbar',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './navbar.html',
})
export class LandingNavbar {
  readonly i18n = input.required<I18n>();
  readonly theme = input.required<ThemeService>();
  readonly langs = input.required<readonly LangOption[]>();
  readonly sections = input.required<readonly Section[]>();
  readonly active = input.required<string>();
  readonly open = input(false);
  readonly scrolled = input(false);
  readonly navigate = output<{ id: string; event?: Event }>();
  readonly toggleMenu = output<void>();
  readonly setLanguage = output<Lang>();
}