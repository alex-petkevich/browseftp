import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type JobStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED';

export interface JobSummary {
  id: string;
  kind: string;
  description: string;
  status: JobStatus;
  cancelRequested?: boolean;
  total: number;
  processed: number;
  currentItem: string;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface JobDetail extends JobSummary {
  log: string[];
}

@Injectable({ providedIn: 'root' })
export class JobsService {
  private readonly base = '/api/jobs';

  constructor(private http: HttpClient) {}

  list(max = 20): Observable<JobSummary[]> {
    return this.http.get<JobSummary[]>(this.base, { params: { max } });
  }

  get(id: string): Observable<JobDetail> {
    return this.http.get<JobDetail>(`${this.base}/${id}`);
  }

  /** Requests cancellation of a running/pending job. */
  cancel(id: string): Observable<{ cancelled: boolean }> {
    return this.http.post<{ cancelled: boolean }>(`${this.base}/${id}/cancel`, {});
  }

  /** Removes all completed (DONE/FAILED/CANCELLED) jobs from the server list. */
  clearCompleted(): Observable<{ removed: number }> {
    return this.http.delete<{ removed: number }>(`${this.base}/completed`);
  }
}

