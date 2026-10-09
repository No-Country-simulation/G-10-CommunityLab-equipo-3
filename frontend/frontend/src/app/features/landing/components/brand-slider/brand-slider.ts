import { Component } from '@angular/core';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Reveal } from '../../../../shared/reveal.directive';

@Component({
  selector: 'app-brand-slider',
  imports: [TranslatePipe, Reveal],
  templateUrl: './brand-slider.html',
})
export class BrandSlider {}
