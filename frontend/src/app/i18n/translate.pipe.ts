import { Pipe, PipeTransform } from '@angular/core';
import { I18nService } from './i18n.service';

/**
 * Template helper: {{ 'key' | t }} or {{ 'key' | t:{ n: 3 } }}.
 * Impure so the text updates immediately when the language changes
 * (lookups are cheap dictionary reads).
 */
@Pipe({ name: 't', standalone: true, pure: false })
export class TranslatePipe implements PipeTransform {
  constructor(private i18n: I18nService) {}

  transform(key: string, params?: Record<string, unknown>): string {
    return this.i18n.t(key, params);
  }
}

