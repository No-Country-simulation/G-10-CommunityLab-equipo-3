import { afterNextRender, Component, ElementRef, output, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Reveal } from '../../../../shared/reveal.directive';

@Component({
  selector: 'app-landing-hero',
  imports: [RouterLink, TranslatePipe, Reveal],
  templateUrl: './hero.html',
})
export class LandingHero {
  readonly navigate = output<{ id: string; event?: Event }>();
  readonly heroVideo = viewChild<ElementRef<HTMLVideoElement>>('heroVideo');

  constructor() {
    afterNextRender(() => {
      const video = this.heroVideo()?.nativeElement;
      if (video) {
        video.muted = true;
        video.loop = false;
        video.play().catch(() => {
          // Permite que la reproducción automática inicie sin errores si el navegador restringe políticas
        });
      }
    });
  }
}