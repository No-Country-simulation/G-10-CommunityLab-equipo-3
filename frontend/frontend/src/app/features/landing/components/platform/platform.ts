import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Bear } from '../../../../shared/bear/bear';
import { Reveal } from '../../../../shared/reveal.directive';

@Component({ selector: 'app-landing-platform', imports: [RouterLink, TranslatePipe, Bear, Reveal], templateUrl: './platform.html' })
export class LandingPlatform { readonly stats = input.required<readonly any[]>(); readonly features = input.required<readonly any[]>(); }