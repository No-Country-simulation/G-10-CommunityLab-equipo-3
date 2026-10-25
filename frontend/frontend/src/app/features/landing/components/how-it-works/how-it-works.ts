import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Bear } from '../../../../shared/bear/bear';
import { Reveal } from '../../../../shared/reveal.directive';

@Component({ selector: 'app-landing-how-it-works', imports: [RouterLink, TranslatePipe, Bear, Reveal], templateUrl: './how-it-works.html' })
export class LandingHowItWorks { readonly steps = input.required<readonly any[]>(); }