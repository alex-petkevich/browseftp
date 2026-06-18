import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface FileItem {
  name: string;
  path: string;
  directory: boolean;
  size: number;
  lastModified: number;
}

export interface ListResponse {
  path: string;
  items: FileItem[];
}

/** A name collision discovered by a preflight check before a transfer starts. */
export interface Conflict {
  name: string;
  srcPath: string;
  dstPath: string;
  /** -1 when unknown / a directory. */
  srcSize: number;
  dstSize: number;
  directory: boolean;
}

/** Action for the user to pick when there are collisions. */
export type OverwritePolicy = 'replace' | 'rename' | 'resume' | 'skip';

@Injectable({ providedIn: 'root' })
export class FileService {
  private readonly base = '/api/files';

  constructor(private http: HttpClient) {}

  root(): Observable<{ root: string }> {
    return this.http.get<{ root: string }>(`${this.base}/root`);
  }

  list(path: string): Observable<ListResponse> {
    return this.http.get<ListResponse>(`${this.base}/list`, { params: { path } });
  }

  mkdir(parent: string, name: string): Observable<FileItem> {
    return this.http.post<FileItem>(`${this.base}/mkdir`, { parent, name });
  }

  rename(path: string, newName: string): Observable<FileItem> {
    return this.http.post<FileItem>(`${this.base}/rename`, { path, newName });
  }

  move(paths: string[], destination: string, overwrite?: OverwritePolicy): Observable<void> {
    return this.http.post<void>(`${this.base}/move`, { paths, destination, overwrite });
  }

  copy(paths: string[], destination: string, overwrite?: OverwritePolicy): Observable<void> {
    return this.http.post<void>(`${this.base}/copy`, { paths, destination, overwrite });
  }

  /** Preflight: returns collisions at {@code destination} for the given paths. */
  preflight(paths: string[], destination: string): Observable<{ conflicts: Conflict[] }> {
    return this.http.post<{ conflicts: Conflict[] }>(`${this.base}/preflight`, { paths, destination });
  }

  delete(paths: string[]): Observable<void> {
    return this.http.post<void>(`${this.base}/delete`, { paths });
  }

  raw(path: string): Observable<Blob> {
    return this.http.get(`${this.base}/raw`, { params: { path }, responseType: 'blob' });
  }

  content(path: string): Observable<{ path: string; name: string; size: number; content: string }> {
    return this.http.get<{ path: string; name: string; size: number; content: string }>(
      `${this.base}/content`, { params: { path } });
  }
}

