import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Bear } from '../../../../shared/bear/bear';
import { Reveal } from '../../../../shared/reveal.directive';

@Component({ selector: 'app-landing-cta-banner', imports: [RouterLink, TranslatePipe, Bear, Reveal], templateUrl: './cta-banner.html' })
export class LandingCtaBanner {}