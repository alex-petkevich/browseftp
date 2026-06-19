import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { FtpConnectionProfile } from './ftp.service';

/**
 * User settings persisted server-side (in the user's home directory) so they
 * survive across browsers and devices. The shape is owned by the front-end;
 * the backend stores it as an opaque JSON object.
 */
export interface AppSettings {
  theme?: 'light' | 'dark';
  ftpConnections?: FtpConnectionProfile[];
  /** Per-panel last-opened local directory ('' = root, null = was FTP/none). */
  lastPaths?: (string | null)[];
}

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly base = '/api/settings';

  constructor(private http: HttpClient) {}

  /** Loads the persisted settings (empty object when nothing is stored yet). */
  load(): Observable<AppSettings> {
    return this.http.get<AppSettings>(this.base);
  }

  /** Persists the full settings object, replacing the previous content. */
  save(settings: AppSettings): Observable<void> {
    return this.http.put<void>(this.base, settings);
  }
}

