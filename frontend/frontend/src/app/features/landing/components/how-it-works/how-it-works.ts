import { Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { Reveal } from '../../../../shared/reveal.directive';

export interface StepItem {
  id: number;
  number: string;
  tabTitleKey: string;
  tabDescKey: string;
  mainTitleKey: string;
  mainSubtitleKey: string;
  descKey: string;
  chips: string[];
}

@Component({
  selector: 'app-landing-how-it-works',
  imports: [RouterLink, TranslatePipe, Reveal],
  templateUrl: './how-it-works.html',
})
export class LandingHowItWorks {
  readonly steps = input<readonly any[]>();

  protected readonly activeStep = signal<number>(0);

  protected readonly stepItems: readonly StepItem[] = [
    {
      id: 0,
      number: '1',
      tabTitleKey: 'landing.how.step1.tabTitle',
      tabDescKey: 'landing.how.step1.tabDesc',
      mainTitleKey: 'landing.how.step1.mainTitle',
      mainSubtitleKey: 'landing.how.step1.mainSubtitle',
      descKey: 'landing.how.step1.desc',
      chips: ['landing.how.step1.chip1', 'landing.how.step1.chip2', 'landing.how.step1.chip3'],
    },
    {
      id: 1,
      number: '2',
      tabTitleKey: 'landing.how.step2.tabTitle',
      tabDescKey: 'landing.how.step2.tabDesc',
      mainTitleKey: 'landing.how.step2.mainTitle',
      mainSubtitleKey: 'landing.how.step2.mainSubtitle',
      descKey: 'landing.how.step2.desc',
      chips: ['landing.how.step2.chip1', 'landing.how.step2.chip2', 'landing.how.step2.chip3'],
    },
    {
      id: 2,
      number: '3',
      tabTitleKey: 'landing.how.step3.tabTitle',
      tabDescKey: 'landing.how.step3.tabDesc',
      mainTitleKey: 'landing.how.step3.mainTitle',
      mainSubtitleKey: 'landing.how.step3.mainSubtitle',
      descKey: 'landing.how.step3.desc',
      chips: ['landing.how.step3.chip1', 'landing.how.step3.chip2', 'landing.how.step3.chip3'],
    },
    {
      id: 3,
      number: '4',
      tabTitleKey: 'landing.how.step4.tabTitle',
      tabDescKey: 'landing.how.step4.tabDesc',
      mainTitleKey: 'landing.how.step4.mainTitle',
      mainSubtitleKey: 'landing.how.step4.mainSubtitle',
      descKey: 'landing.how.step4.desc',
      chips: ['landing.how.step4.chip1', 'landing.how.step4.chip2', 'landing.how.step4.chip3'],
    },
  ];

  protected readonly currentStep = computed(() => this.stepItems[this.activeStep()]);

  protected readonly progressPercent = computed(() => {
    return (this.activeStep() + 1) * 25;
  });

  protected readonly progressClipPath = computed(() => {
    const cut = 100 - this.progressPercent();
    return `inset(0 ${cut}% 0 0 round 9999px)`;
  });

  protected setStep(index: number): void {
    if (index >= 0 && index < this.stepItems.length) {
      this.activeStep.set(index);
    }
  }

  protected prevStep(): void {
    const current = this.activeStep();
    this.activeStep.set(current > 0 ? current - 1 : this.stepItems.length - 1);
  }

  protected nextStep(): void {
    const current = this.activeStep();
    this.activeStep.set(current < this.stepItems.length - 1 ? current + 1 : 0);
  }
}