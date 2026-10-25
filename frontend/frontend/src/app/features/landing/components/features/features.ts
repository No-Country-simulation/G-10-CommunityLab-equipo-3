import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Reveal } from '../../../../shared/reveal.directive';

@Component({ selector: 'app-landing-features', imports: [RouterLink, TranslatePipe, Reveal], templateUrl: './features.html' })
export class LandingFeatures { readonly features = input.required<readonly any[]>(); }