import { Component, input } from '@angular/core';
import { CountUp } from '../count-up.directive';

export interface StatItem {
  label: string;
  value: number | string;
  icon: string;
  link?: string;
}

@Component({
  selector: 'app-stat-card',
  imports: [CountUp],
  templateUrl: './stat-card.html',
  host: { class: 'block' },
})
export class StatCard {
  readonly stat = input.required<StatItem>();
  readonly color = input('#3b82f6');
}
