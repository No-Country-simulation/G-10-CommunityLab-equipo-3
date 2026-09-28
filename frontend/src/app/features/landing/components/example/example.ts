import { Component, input } from '@angular/core';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Reveal } from '../../../../shared/reveal.directive';

@Component({ selector: 'app-landing-example', imports: [TranslatePipe, Reveal], templateUrl: './example.html' })
export class LandingExample { readonly outputs = input.required<readonly any[]>(); }