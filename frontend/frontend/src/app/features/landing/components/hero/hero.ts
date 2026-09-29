import { Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Bear } from '../../../../shared/bear/bear';
import { Reveal } from '../../../../shared/reveal.directive';

@Component({ selector: 'app-landing-hero', imports: [RouterLink, TranslatePipe, Bear, Reveal], templateUrl: './hero.html' })
export class LandingHero {
  readonly navigate = output<{ id: string; event?: Event }>();
}