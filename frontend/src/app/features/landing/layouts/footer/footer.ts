import { Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';

interface Section { id: string; key: string; }

@Component({
  selector: 'app-landing-footer',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './footer.html',
})
export class LandingFooter {
  readonly sections = input.required<readonly Section[]>();
  readonly navigate = output<{ id: string; event?: Event }>();
}