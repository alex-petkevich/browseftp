import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ListResponse, Conflict, OverwritePolicy, UploadResult } from './file.service';

export interface FtpSettings {
  host: string;
  port: number;
  user: string;
  password: string;
  passive: boolean;
}

/** A named, saved FTP connection (stored in localStorage). */
export interface FtpConnectionProfile extends FtpSettings {
  name: string;
}

/** Connect response includes the protocol log and an optional error. */
export interface FtpConnectResponse extends ListResponse {
  log?: string[];
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class FtpService {
  private readonly base = '/api/ftp';

  constructor(private http: HttpClient) {}

  connect(conn: FtpSettings, path = ''): Observable<FtpConnectResponse> {
    return this.http.post<FtpConnectResponse>(`${this.base}/connect`, { conn, path });
  }

  list(conn: FtpSettings, path: string): Observable<ListResponse> {
    return this.http.post<ListResponse>(`${this.base}/list`, { conn, path });
  }

  mkdir(conn: FtpSettings, parent: string, name: string): Observable<void> {
    return this.http.post<void>(`${this.base}/mkdir`, { conn, parent, name });
  }

  rename(conn: FtpSettings, path: string, newName: string): Observable<void> {
    return this.http.post<void>(`${this.base}/rename`, { conn, path, newName });
  }

  delete(conn: FtpSettings, paths: string[]): Observable<{ jobId: string }> {
    return this.http.post<{ jobId: string }>(`${this.base}/delete`, { conn, paths });
  }

  raw(conn: FtpSettings, path: string): Observable<Blob> {
    return this.http.post(`${this.base}/raw`, { conn, path }, { responseType: 'blob' });
  }

  /** Reads a remote text file; truncated = the file is larger than the 2 MB limit. */
  content(conn: FtpSettings, path: string)
      : Observable<{ name: string; path: string; content: string; truncated?: boolean }> {
    return this.http.post<{ name: string; path: string; content: string; truncated?: boolean }>(
      `${this.base}/content`, { conn, path });
  }

  /** Saves edited text (UTF-8) into a remote file. */
  save(conn: FtpSettings, path: string, content: string): Observable<void> {
    return this.http.post<void>(`${this.base}/save`, { conn, path, content });
  }

  /** Downloads a single remote file to the browser (streamed through the server). */
  downloadFile(conn: FtpSettings, path: string): Observable<Blob> {
    return this.http.post(`${this.base}/download-file`, { conn, path }, { responseType: 'blob' });
  }

  /** Downloads several remote files/folders to the browser as a ZIP. */
  downloadZip(conn: FtpSettings, paths: string[]): Observable<Blob> {
    return this.http.post(`${this.base}/download-zip`, { conn, paths }, { responseType: 'blob' });
  }

  /** Uploads browser files straight into a remote directory; emits progress events. */
  uploadFiles(conn: FtpSettings, path: string, files: File[],
              overwrite?: OverwritePolicy): Observable<HttpEvent<UploadResult>> {
    const form = new FormData();
    form.append('conn', new Blob([JSON.stringify(conn)], { type: 'application/json' }));
    form.append('path', path || '/');
    if (overwrite) form.append('overwrite', overwrite);
    files.forEach(f => form.append('files', f, f.name));
    return this.http.post<UploadResult>(`${this.base}/upload-files`, form, { reportProgress: true, observe: 'events' });
  }

  /** local -> ftp */
  upload(conn: FtpSettings, localPaths: string[], remoteDir: string,
         overwrite?: OverwritePolicy): Observable<{ jobId: string }> {
    return this.http.post<{ jobId: string }>(`${this.base}/upload`,
      { conn, localPaths, remoteDir, overwrite });
  }

  /** ftp -> local */
  download(conn: FtpSettings, remotePaths: string[], localDir: string,
           overwrite?: OverwritePolicy): Observable<{ jobId: string }> {
    return this.http.post<{ jobId: string }>(`${this.base}/download`,
      { conn, remotePaths, localDir, overwrite });
  }

  /** ftp -> ftp */
  copyRemote(srcConn: FtpSettings, destConn: FtpSettings, remotePaths: string[],
             remoteDir: string, overwrite?: OverwritePolicy): Observable<{ jobId: string }> {
    return this.http.post<{ jobId: string }>(`${this.base}/copy-remote`,
      { srcConn, destConn, remotePaths, remoteDir, overwrite });
  }

  /** Preflight a local -> ftp upload. */
  uploadPreflight(conn: FtpSettings, localPaths: string[], remoteDir: string)
      : Observable<{ conflicts: Conflict[] }> {
    return this.http.post<{ conflicts: Conflict[] }>(
      `${this.base}/upload/preflight`, { conn, localPaths, remoteDir });
  }

  /** Preflight an ftp -> local download. */
  downloadPreflight(conn: FtpSettings, remotePaths: string[], localDir: string)
      : Observable<{ conflicts: Conflict[] }> {
    return this.http.post<{ conflicts: Conflict[] }>(
      `${this.base}/download/preflight`, { conn, remotePaths, localDir });
  }

  /** Preflight an ftp -> ftp copy. */
  copyRemotePreflight(srcConn: FtpSettings, destConn: FtpSettings, remotePaths: string[],
                      remoteDir: string): Observable<{ conflicts: Conflict[] }> {
    return this.http.post<{ conflicts: Conflict[] }>(
      `${this.base}/copy-remote/preflight`, { srcConn, destConn, remotePaths, remoteDir });
  }
}

