import { Injectable } from '@angular/core';
import { Lang, LANGUAGES, LOCALES, TRANSLATIONS } from './translations';

/**
 * Minimal runtime translation service. The current language is chosen in
 * Settings (persisted server-side); English is the default and the fallback
 * for any missing key.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  /** Currently active UI language. */
  lang: Lang = 'en';

  readonly languages = LANGUAGES;

  /** True if {@code value} is a supported language code. */
  static isLang(value: unknown): value is Lang {
    return value === 'en' || value === 'de' || value === 'ru';
  }

  /** Switches the UI language (unknown values fall back to English). */
  setLang(value: unknown): void {
    this.lang = I18nService.isLang(value) ? value : 'en';
    document.documentElement.lang = this.lang;
  }

  /** Locale for number/date formatting matching the UI language. */
  get locale(): string {
    return LOCALES[this.lang];
  }

  /**
   * Translates {@code key}, replacing {name} placeholders with values from
   * {@code params}. Falls back to English, then to the key itself.
   */
  t(key: string, params?: Record<string, unknown>): string {
    const text = TRANSLATIONS[this.lang][key] ?? TRANSLATIONS.en[key] ?? key;
    if (!params) {
      return text;
    }
    return text.replace(/\{(\w+)\}/g, (m, name) =>
      Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : m);
  }
}

