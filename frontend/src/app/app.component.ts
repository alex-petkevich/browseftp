import { Component, ElementRef, HostListener, OnDestroy, OnInit, QueryList, ViewChild, ViewChildren } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { FileItem, FileService, Conflict, OverwritePolicy } from './services/file.service';
import { FtpService, FtpSettings, FtpConnectionProfile } from './services/ftp.service';
import { JobsService, JobSummary, JobDetail } from './services/jobs.service';
import { SettingsService, AppSettings } from './services/settings.service';
import { PanelComponent, ViewMode, PanelDropEvent } from './components/panel/panel.component';
type PanelKind = 'local' | 'ftp';
type SortKey = 'name' | 'date' | 'size';
interface PanelState {
  title: string;
  path: string;
  /** "Home" directory used by the Home button (local root or FTP working dir). */
  home: string;
  items: FileItem[];
  selected: Set<string>;
  viewMode: ViewMode;
  loading: boolean;
  /** Index of the keyboard cursor within items (-1 = none). */
  cursor: number;
  /** Whether this panel shows the local filesystem or a remote FTP server. */
  kind: PanelKind;
  /** Current sort column. */
  sortKey: SortKey;
  /** Sort direction (true = ascending). */
  sortAsc: boolean;
  /** FTP connection settings when kind === 'ftp'. */
  ftp?: FtpSettings;
}
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, PanelComponent],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit, OnDestroy {
  root = '';
  error = '';
  /** Current year, used in the copyright notice. */
  readonly currentYear = new Date().getFullYear();
  showSettings = false;
  // Text viewer modal state
  showViewer = false;
  viewerLoading = false;
  viewerName = '';
  viewerContent = '';
  /** When set, the viewer shows this image instead of text. */
  viewerImageUrl: string | null = null;
  /** Relative path of the image currently shown (for prev/next + open-in-tab). */
  viewerImagePath: string | null = null;
  /** Blob of the current image, used to open it in a new tab (FTP case). */
  private viewerImageBlob: Blob | null = null;
  /** Panel the viewer is browsing (so prev/next fetch from the same source). */
  private viewerPanel: PanelState | null = null;
  /** Image paths in the viewer panel's directory, in displayed order. */
  viewerImageList: string[] = [];
  // FTP connection manager state
  showFtp = false;            // connection manager modal
  showFtpEditor = false;      // add/edit form modal
  ftpEditorMode: 'add' | 'edit' = 'add';
  ftpEditorIndex = -1;
  ftpConnecting = false;
  ftpTargetIndex = 0;
  ftpSelected = -1;
  ftpConnections: FtpConnectionProfile[] = [];
  ftpForm: FtpConnectionProfile = { name: '', host: '', port: 21, user: '', password: '', passive: true };
  ftpLog: string[] = [];

  // ---- Operations panel state ----
  jobs: JobSummary[] = [];
  expandedJob: string | null = null;
  expandedJobDetail: JobDetail | null = null;
  showOps = false;
  private pollHandle: any = null;
  /** Per-job transfer-speed samples (bytes/sec), derived from successive polls. */
  private _speedSamples: Record<string, { processed: number; time: number; bps: number }> = {};
  /** Width of the left panel as a percentage of the panels container. */
  leftWidth = 50;
  private dragging = false;
  @ViewChild('panelsContainer') panelsContainer!: ElementRef<HTMLElement>;
  @ViewChildren('opsLog') opsLogElements!: QueryList<ElementRef<HTMLElement>>;
  /** Tracks whether the user has scrolled the log up; when true, auto-scroll pauses. */
  private opsLogAutoStick = true;
  panels: PanelState[] = [
    { title: 'Left', path: '', home: '', items: [], selected: new Set(), viewMode: 'full', loading: false, cursor: -1, kind: 'local', sortKey: 'name', sortAsc: true },
    { title: 'Right', path: '', home: '', items: [], selected: new Set(), viewMode: 'full', loading: false, cursor: -1, kind: 'local', sortKey: 'name', sortAsc: true }
  ];
  activeIndex = 0;
  /** Settings persisted server-side (theme, FTP connections, last paths). */
  private settings: AppSettings = {};
  /** Debounce handle for persisting settings to the server. */
  private saveSettingsHandle: any = null;
  constructor(private fileService: FileService, private ftpService: FtpService,
              private jobsService: JobsService, private settingsService: SettingsService) {}
  ngOnInit(): void {
    // Load persisted settings from the server first, then start the UI. If the
    // server has none yet (or the request fails), migrate any values still in
    // localStorage so existing users keep their settings, then persist them.
    this.settingsService.load().subscribe({
      next: s => {
        const loaded = s && Object.keys(s).length ? s : this.migrateFromLocalStorage();
        this.startup(loaded);
        if (!s || !Object.keys(s).length) {
          this.persistSettings();
        }
      },
      error: () => {
        this.startup(this.migrateFromLocalStorage());
        this.persistSettings();
      }
    });
  }

  /** Applies loaded settings and kicks off the initial data loads. */
  private startup(s: AppSettings): void {
    this.settings = s || {};
    this.applyThemeFromSettings();
    this.loadConnections();
    this.fileService.root().subscribe({
      next: r => (this.root = r.root),
      error: e => this.handleError(e)
    });
    const lastPaths = this.loadLastPaths();
    this.load(0, lastPaths[0] ?? undefined, lastPaths[0] !== null);
    this.load(1, lastPaths[1] ?? undefined, lastPaths[1] !== null);
    this.refreshJobs();
    this.pollHandle = setInterval(() => this.refreshJobs(), 1000);
  }

  /** Builds a settings object from any values still in localStorage (migration). */
  private migrateFromLocalStorage(): AppSettings {
    const s: AppSettings = {};
    const theme = localStorage.getItem('theme');
    if (theme === 'dark' || theme === 'light') {
      s.theme = theme;
    }
    const conns = localStorage.getItem('ftpConnections');
    if (conns) {
      try { s.ftpConnections = JSON.parse(conns); } catch { /* ignore */ }
    }
    const lp = localStorage.getItem('lastPaths');
    if (lp) {
      try { s.lastPaths = JSON.parse(lp); } catch { /* ignore */ }
    }
    return s;
  }

  /** Debounced persist of the whole settings object to the server. */
  private persistSettings(): void {
    if (this.saveSettingsHandle) {
      clearTimeout(this.saveSettingsHandle);
    }
    this.saveSettingsHandle = setTimeout(() => {
      this.settingsService.save(this.settings).subscribe({ next: () => {}, error: () => {} });
    }, 300);
  }

  // ---- Theme (light / dark) ----
  /** Currently active theme; applied via the Bootstrap 5.3 [data-bs-theme] attribute. */
  theme: 'light' | 'dark' = 'light';

  /** Applies the theme from loaded settings (or the OS preference). */
  private applyThemeFromSettings(): void {
    const saved = this.settings.theme;
    if (saved === 'dark' || saved === 'light') {
      this.theme = saved;
    } else if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      this.theme = 'dark';
    }
    this.applyTheme();
  }

  /** Persists and applies the current theme on the <html> element. */
  private applyTheme(): void {
    document.documentElement.setAttribute('data-bs-theme', this.theme);
    this.settings.theme = this.theme;
    this.persistSettings();
  }

  /** Switches between light and dark and persists the choice. */
  toggleTheme(): void {
    this.theme = this.theme === 'dark' ? 'light' : 'dark';
    this.applyTheme();
  }

  ngOnDestroy(): void {
    if (this.pollHandle) clearInterval(this.pollHandle);
    if (this.saveSettingsHandle) {
      clearTimeout(this.saveSettingsHandle);
      // Flush any pending settings change immediately on teardown.
      this.settingsService.save(this.settings).subscribe({ next: () => {}, error: () => {} });
    }
  }

  // ---- Operations panel ----
  refreshJobs(): void {
    this.jobsService.list(20).subscribe({
      next: list => {
        this.updateSpeeds(list);
        this.jobs = list;
        if (this.expandedJob) {
          this.jobsService.get(this.expandedJob).subscribe({
            next: d => (this.expandedJobDetail = d),
            error: () => {}
          });
        }
        // If any running job just finished, refresh both panels once.
        const justFinished = list.find(j => (j.status === 'DONE' || j.status === 'FAILED') && this._lastSeen[j.id] === 'RUNNING');
        list.forEach(j => this._lastSeen[j.id] = j.status);
        if (justFinished) this.reloadBoth();
      },
      error: () => {}
    });
  }
  private _lastSeen: Record<string, string> = {};

  toggleOps(): void { this.showOps = !this.showOps; }

  expandJob(id: string): void {
    if (this.expandedJob === id) {
      this.expandedJob = null;
      this.expandedJobDetail = null;
      return;
    }
    this.expandedJob = id;
    this.expandedJobDetail = null;
    this.opsLogAutoStick = true;
    this.jobsService.get(id).subscribe({ next: d => { this.expandedJobDetail = d; this.scrollOpsLogToBottom(); } });
  }

  /** Scrolls the expanded job's log to the bottom, unless the user scrolled up. */
  private scrollOpsLogToBottom(): void {
    setTimeout(() => {
      const el = this.opsLogElements?.first?.nativeElement;
      if (!el) return;
      if (this.opsLogAutoStick) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }

  /** Called from the template on user scroll so we stop auto-sticking if they scroll up. */
  onOpsLogScroll(event: Event): void {
    const el = event.target as HTMLElement;
    // Consider "at bottom" if within 8px of the bottom (small tolerance).
    this.opsLogAutoStick = el.scrollHeight - el.scrollTop - el.clientHeight < 8;
  }

  get activeJobCount(): number {
    return this.jobs.filter(j => j.status === 'RUNNING' || j.status === 'PENDING').length;
  }

  jobProgressPct(j: JobSummary): number {
    if (!j.total) return j.status === 'DONE' ? 100 : 0;
    return Math.round((j.processed / j.total) * 100);
  }

  /**
   * Updates per-job transfer-speed samples from a fresh poll. Speed is the
   * change in {@code processed} bytes over the elapsed wall-clock time, lightly
   * smoothed (EMA) so the displayed value doesn't jump around. Only RUNNING
   * byte-transfer jobs are tracked; finished/removed jobs drop their samples.
   */
  private updateSpeeds(list: JobSummary[]): void {
    const now = Date.now();
    const live = new Set<string>();
    for (const j of list) {
      if (j.status !== 'RUNNING' || j.kind === 'ftp-delete') continue;
      live.add(j.id);
      const prev = this._speedSamples[j.id];
      if (prev) {
        const dt = (now - prev.time) / 1000;
        const db = j.processed - prev.processed;
        if (dt > 0 && db >= 0) {
          const inst = db / dt;
          const bps = prev.bps > 0 ? prev.bps * 0.6 + inst * 0.4 : inst;
          this._speedSamples[j.id] = { processed: j.processed, time: now, bps };
        } else {
          this._speedSamples[j.id] = { processed: j.processed, time: now, bps: prev.bps };
        }
      } else {
        this._speedSamples[j.id] = { processed: j.processed, time: now, bps: 0 };
      }
    }
    // Drop samples for jobs that are no longer running.
    for (const id of Object.keys(this._speedSamples)) {
      if (!live.has(id)) delete this._speedSamples[id];
    }
  }

  /** Current transfer speed (bytes/sec) for a running job, or 0 if unknown. */
  jobSpeed(j: JobSummary): number {
    const s = this._speedSamples[j.id];
    return s ? s.bps : 0;
  }

  /** Combined live speed (bytes/sec) across all currently running transfers. */
  get aggregateSpeed(): number {
    let bps = 0;
    for (const j of this.jobs) bps += this.jobSpeed(j);
    return bps;
  }

  /** True when a live speed can be shown for this job. */
  showSpeed(j: JobSummary): boolean {
    return j.status === 'RUNNING' && j.kind !== 'ftp-delete' && this.jobSpeed(j) > 0;
  }

  /** Estimated seconds remaining for a running job, or null if not computable. */
  jobEtaSeconds(j: JobSummary): number | null {
    const bps = this.jobSpeed(j);
    if (bps <= 0 || !j.total || j.total <= j.processed) return null;
    return (j.total - j.processed) / bps;
  }

  /** Formats a byte/sec rate, e.g. "1.2 MB/s". */
  formatSpeed(bps: number): string {
    return this.formatBytes(bps) + '/s';
  }

  /** Formats a duration in seconds as a compact "1h 02m", "3m 05s" or "12s". */
  formatDuration(seconds: number | null): string {
    if (seconds == null || !isFinite(seconds) || seconds < 0) return '';
    const s = Math.round(seconds);
    if (s < 60) return s + 's';
    const m = Math.floor(s / 60);
    const rs = s % 60;
    if (m < 60) return m + 'm ' + String(rs).padStart(2, '0') + 's';
    const h = Math.floor(m / 60);
    const rm = m % 60;
    return h + 'h ' + String(rm).padStart(2, '0') + 'm';
  }

  /**
   * Average speed (bytes/sec) of a finished byte-transfer job, computed from the
   * total processed bytes over its run duration. Returns 0 when not applicable.
   */
  jobAverageSpeed(j: JobSummary): number {
    if (j.status !== 'DONE' || j.kind === 'ftp-delete') return 0;
    if (!j.startedAt || !j.finishedAt || !j.processed) return 0;
    const secs = (new Date(j.finishedAt).getTime() - new Date(j.startedAt).getTime()) / 1000;
    return secs > 0 ? j.processed / secs : 0;
  }

  /** True when a final average speed can be shown for a completed job. */
  showAverageSpeed(j: JobSummary): boolean {
    return this.jobAverageSpeed(j) > 0;
  }

  /** True for statuses that allow cancellation. */
  canCancel(j: JobSummary): boolean {
    return (j.status === 'RUNNING' || j.status === 'PENDING') && !j.cancelRequested;
  }

  /** Formats a byte count using KB/MB/GB. Used by the operations panel. */
  formatBytes(n: number): string {
    if (n == null || isNaN(n)) return '0';
    if (n < 1024) return n + ' B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    let i = -1;
    let v = n;
    do { v /= 1024; i++; } while (v >= 1024 && i < units.length - 1);
    return v.toFixed(v >= 10 || i === 0 ? 0 : 1) + ' ' + units[i];
  }

  /** True if any job in the list is in a "completed" state. */
  get hasCompletedJobs(): boolean {
    return this.jobs.some(j => j.status === 'DONE' || j.status === 'FAILED' || j.status === 'CANCELLED');
  }

  /** Asks the server to interrupt the given job. */
  cancelJob(j: JobSummary, event?: Event): void {
    event?.stopPropagation();
    if (!this.canCancel(j)) return;
    this.jobsService.cancel(j.id).subscribe({
      next: () => this.refreshJobs(),
      error: e => this.handleError(e)
    });
  }

  /** Removes all completed/failed/cancelled jobs from the list. */
  clearCompletedJobs(): void {
    if (!this.hasCompletedJobs) return;
    this.jobsService.clearCompleted().subscribe({
      next: () => {
        if (this.expandedJob && !this.jobs.find(j => j.id === this.expandedJob && this.canCancel(j))) {
          this.expandedJob = null;
          this.expandedJobDetail = null;
        }
        this.refreshJobs();
      },
      error: e => this.handleError(e)
    });
  }
  get active(): PanelState {
    return this.panels[this.activeIndex];
  }
  get inactive(): PanelState {
    return this.panels[this.activeIndex === 0 ? 1 : 0];
  }
  setActive(index: number): void {
    this.activeIndex = index;
  }
  // ---- Keyboard hotkeys ----
  // Arrows: move selection; Tab: switch panel; F3 View, F5 Copy,
  // F6 Rename, F7 New folder, F8 Delete.
  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    // Viewer modal: Esc closes it; arrows step through images.
    if (this.showViewer) {
      if (event.key === 'Escape') {
        event.preventDefault();
        this.closeViewer();
      } else if (event.key === 'ArrowLeft' && this.hasPrevImage) {
        event.preventDefault();
        this.prevImage();
      } else if (event.key === 'ArrowRight' && this.hasNextImage) {
        event.preventDefault();
        this.nextImage();
      }
      return;
    }
    if (this.showSettings || this.showFtp || this.showFtpEditor || this.showConflict) {
      return;
    }
    const target = event.target as HTMLElement;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
      return;
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.moveCursor(1);
        return;
      case 'ArrowUp':
        event.preventDefault();
        this.moveCursor(-1);
        return;
      case 'Tab':
        event.preventDefault();
        this.setActive(this.activeIndex === 0 ? 1 : 0);
        return;
      case 'Enter': {
        event.preventDefault();
        const panel = this.active;
        const item = panel.items[panel.cursor];
        if (item && item.directory) {
          this.openDir(this.activeIndex, item);
        }
        return;
      }
      case 'F2':
        event.preventDefault();
        this.rename();
        return;
      case 'F3':
        event.preventDefault();
        this.view();
        return;
      case 'F5':
        event.preventDefault();
        this.copy();
        return;
      case 'F6':
        event.preventDefault();
        this.move();
        return;
      case 'F7':
        event.preventDefault();
        this.createDirectory();
        return;
      case 'F8':
        event.preventDefault();
        this.remove();
        return;
    }
  }
  /** Moves the keyboard cursor in the active panel and selects that item. */
  private moveCursor(delta: number): void {
    const panel = this.active;
    if (panel.items.length === 0) {
      return;
    }
    let idx = panel.cursor;
    idx = idx < 0 ? (delta > 0 ? 0 : panel.items.length - 1)
                  : Math.min(Math.max(idx + delta, 0), panel.items.length - 1);
    panel.cursor = idx;
    panel.selected.clear();
    panel.selected.add(panel.items[idx].path);
    this.scrollCursorIntoView();
  }
  private scrollCursorIntoView(): void {
    setTimeout(() => {
      const container = document.getElementById('panel-' + this.activeIndex);
      const el = container?.querySelector('.selected') as HTMLElement | null;
      el?.scrollIntoView({ block: 'nearest' });
    });
  }
  // ---- Resizable splitter between the two panels ----
  onSplitterDown(event: MouseEvent): void {
    event.preventDefault();
    this.dragging = true;
    const move = (e: MouseEvent) => {
      if (!this.dragging) {
        return;
      }
      const rect = this.panelsContainer.nativeElement.getBoundingClientRect();
      let pct = ((e.clientX - rect.left) / rect.width) * 100;
      pct = Math.max(15, Math.min(85, pct));
      this.leftWidth = pct;
    };
    const up = () => {
      this.dragging = false;
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      document.body.style.userSelect = '';
    };
    document.body.style.userSelect = 'none';
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
  }
  load(index: number, path?: string, isRestore = false): void {
    const panel = this.panels[index];
    if (path !== undefined) {
      panel.path = path;
    }
    panel.loading = true;
    const request = panel.kind === 'ftp' && panel.ftp
      ? this.ftpService.list(panel.ftp, panel.path)
      : this.fileService.list(panel.path);
    request.subscribe({
      next: res => {
        panel.items = res.items;
        if (res.path !== undefined && res.path !== null) {
          panel.path = res.path;
        }
        this.sortPanelItems(panel);
        panel.selected.clear();
        panel.cursor = -1;
        panel.loading = false;
        this.saveLastPaths();
      },
      error: e => {
        panel.loading = false;
        // A restored directory may no longer exist — fall back to the root
        // instead of leaving the panel broken/empty.
        if (isRestore && panel.kind === 'local' && panel.path) {
          this.load(index, '');
          return;
        }
        this.handleError(e);
      }
    });
  }
  openDir(index: number, item: FileItem): void {
    this.load(index, item.path);
  }
  goUp(index: number): void {
    const panel = this.panels[index];
    if (panel.kind === 'ftp') {
      if (!panel.path || panel.path === '/') {
        return;
      }
      const idx = panel.path.lastIndexOf('/');
      const parent = idx <= 0 ? '/' : panel.path.substring(0, idx);
      this.load(index, parent);
      return;
    }
    if (!panel.path) {
      return;
    }
    const parts = panel.path.split('/');
    parts.pop();
    this.load(index, parts.join('/'));
  }
  goHome(index: number): void {
    this.load(index, this.panels[index].home);
  }
  toggleSelect(index: number, payload: { item: FileItem; additive: boolean }): void {
    const panel = this.panels[index];
    panel.cursor = panel.items.indexOf(payload.item);
    if (!payload.additive) {
      // Single click selects the item and keeps it selected.
      panel.selected.clear();
      panel.selected.add(payload.item.path);
    } else if (panel.selected.has(payload.item.path)) {
      panel.selected.delete(payload.item.path);
    } else {
      panel.selected.add(payload.item.path);
    }
  }
  setViewMode(index: number, mode: ViewMode): void {
    this.panels[index].viewMode = mode;
  }

  /**
   * Changes the sort column for a panel. Clicking the current column toggles
   * the direction; switching column starts ascending. Directories always stay
   * grouped before files (orthodox file-manager convention).
   */
  setSort(index: number, key: SortKey): void {
    const panel = this.panels[index];
    if (panel.sortKey === key) {
      panel.sortAsc = !panel.sortAsc;
    } else {
      panel.sortKey = key;
      panel.sortAsc = true;
    }
    const cursorPath = panel.cursor >= 0 ? panel.items[panel.cursor]?.path : null;
    this.sortPanelItems(panel);
    panel.cursor = cursorPath ? panel.items.findIndex(i => i.path === cursorPath) : -1;
  }

  /** Sorts a panel's items in place by its current sort key/direction. */
  private sortPanelItems(panel: PanelState): void {
    const dir = panel.sortAsc ? 1 : -1;
    const byName = (a: FileItem, b: FileItem) =>
      a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
    panel.items.sort((a, b) => {
      // Folders first, regardless of direction.
      if (a.directory !== b.directory) return a.directory ? -1 : 1;
      let cmp = 0;
      switch (panel.sortKey) {
        case 'date': cmp = (a.lastModified || 0) - (b.lastModified || 0); break;
        case 'size': cmp = (a.size || 0) - (b.size || 0); break;
        default: cmp = byName(a, b); break;
      }
      if (cmp === 0) cmp = byName(a, b);
      return cmp * dir;
    });
  }
  selectAll(index: number): void {
    const panel = this.panels[index];
    panel.selected = new Set(panel.items.map(i => i.path));
  }
  deselectAll(index: number): void {
    this.panels[index].selected.clear();
  }
  // ---- Bottom-bar operations (act on the active panel) ----
  private selectedPaths(): string[] {
    return Array.from(this.active.selected);
  }
  private reloadBoth(): void {
    this.load(0);
    this.load(1);
  }

  createDirectory(): void {
    const name = window.prompt('New directory name:');
    if (!name) {
      return;
    }
    const panel = this.active;
    const request: Observable<unknown> = panel.kind === 'ftp' && panel.ftp
      ? this.ftpService.mkdir(panel.ftp, panel.path, name)
      : this.fileService.mkdir(panel.path, name);
    request.subscribe({
      next: () => this.load(this.activeIndex),
      error: e => this.handleError(e)
    });
  }
  rename(): void {
    const paths = this.selectedPaths();
    if (paths.length !== 1) {
      this.error = 'Select exactly one item to rename.';
      return;
    }
    const current = paths[0].split('/').pop() ?? '';
    const newName = window.prompt('New name:', current);
    if (!newName || newName === current) {
      return;
    }
    const panel = this.active;
    const request: Observable<unknown> = panel.kind === 'ftp' && panel.ftp
      ? this.ftpService.rename(panel.ftp, paths[0], newName)
      : this.fileService.rename(paths[0], newName);
    request.subscribe({
      next: () => this.load(this.activeIndex),
      error: e => this.handleError(e)
    });
  }
  view(): void {
    const paths = this.selectedPaths();
    if (paths.length !== 1) {
      this.error = 'Select exactly one file to view.';
      return;
    }
    const item = this.active.items.find(i => i.path === paths[0]);
    if (item && item.directory) {
      this.error = 'Cannot view a directory.';
      return;
    }
    const panel = this.active;
    this.viewerPanel = panel;
    this.viewerName = paths[0].split('/').pop() ?? paths[0];
    if (this.isImageFile(this.viewerName)) {
      // Collect every image in this directory (in displayed order) so Prev/Next
      // can step through them.
      this.viewerImageList = panel.items
        .filter(i => !i.directory && this.isImageFile(i.name))
        .map(i => i.path);
      this.showImage(paths[0]);
      return;
    }
    // Text file.
    this.viewerImageList = [];
    this.viewerImagePath = null;
    this.showViewer = true;
    this.viewerLoading = true;
    this.viewerContent = '';
    this.releaseViewerImage();
    const request: Observable<{ name: string; content: string }> = panel.kind === 'ftp' && panel.ftp
      ? this.ftpService.content(panel.ftp, paths[0])
      : this.fileService.content(paths[0]);
    request.subscribe({
      next: res => {
        this.viewerName = res.name;
        this.viewerContent = res.content;
        this.viewerLoading = false;
      },
      error: e => {
        this.showViewer = false;
        this.viewerLoading = false;
        this.handleError(e);
      }
    });
  }

  /** Loads and shows the given image path in the viewer (used by view + prev/next). */
  private showImage(path: string): void {
    const panel = this.viewerPanel ?? this.active;
    this.showViewer = true;
    this.viewerLoading = true;
    this.viewerContent = '';
    this.releaseViewerImage();
    this.viewerImagePath = path;
    this.viewerName = path.split('/').pop() ?? path;
    const blob$: Observable<Blob> = panel.kind === 'ftp' && panel.ftp
      ? this.ftpService.raw(panel.ftp, path)
      : this.fileService.raw(path);
    blob$.subscribe({
      next: blob => {
        if (this.isHeicFile(this.viewerName)) {
          // Browsers can't render HEIC natively — convert to JPEG client-side.
          this.convertHeic(blob);
          return;
        }
        this.viewerImageBlob = blob;
        this.viewerImageUrl = URL.createObjectURL(blob);
        this.viewerLoading = false;
      },
      error: e => {
        this.showViewer = false;
        this.viewerLoading = false;
        this.handleError(e);
      }
    });
  }

  /** Converts a HEIC/HEIF blob to a displayable JPEG (lazy-loads heic2any). */
  private convertHeic(blob: Blob): void {
    import('heic2any').then(mod => {
      const heic2any = (mod as any).default ?? mod;
      return heic2any({ blob, toType: 'image/jpeg', quality: 0.9 });
    }).then((out: Blob | Blob[]) => {
      const jpeg = Array.isArray(out) ? out[0] : out;
      this.viewerImageBlob = jpeg;
      this.viewerImageUrl = URL.createObjectURL(jpeg);
      this.viewerLoading = false;
    }).catch(err => {
      this.viewerLoading = false;
      this.showViewer = false;
      this.error = 'Cannot display HEIC image: ' + (err?.message ?? err);
    });
  }

  /** Index of the current image within the directory's image list (-1 if none). */
  get viewerImageIndex(): number {
    return this.viewerImagePath ? this.viewerImageList.indexOf(this.viewerImagePath) : -1;
  }
  get hasPrevImage(): boolean {
    return this.viewerImageIndex > 0;
  }
  get hasNextImage(): boolean {
    const i = this.viewerImageIndex;
    return i >= 0 && i < this.viewerImageList.length - 1;
  }
  prevImage(): void {
    const i = this.viewerImageIndex;
    if (i > 0) {
      this.showImage(this.viewerImageList[i - 1]);
    }
  }
  nextImage(): void {
    const i = this.viewerImageIndex;
    if (i >= 0 && i < this.viewerImageList.length - 1) {
      this.showImage(this.viewerImageList[i + 1]);
    }
  }

  /** Opens the current image at its original size in a new browser tab. */
  openImageInNewTab(): void {
    if (!this.viewerImagePath) {
      return;
    }
    const panel = this.viewerPanel ?? this.active;
    // HEIC has no browser-renderable original, so open the converted JPEG blob.
    if (this.isHeicFile(this.viewerName) || panel.kind === 'ftp') {
      const blob = this.viewerImageBlob;
      if (blob) {
        window.open(URL.createObjectURL(blob), '_blank');
      } else if (this.viewerImageUrl) {
        window.open(this.viewerImageUrl, '_blank');
      }
    } else {
      // Local files have a stable GET URL that serves the image inline.
      window.open('/api/files/raw?path=' + encodeURIComponent(this.viewerImagePath), '_blank');
    }
  }

  private isImageFile(name: string): boolean {
    return /\.(png|jpe?g|gif|bmp|webp|svg|ico|heic|heif)$/i.test(name);
  }

  /** True for HEIC/HEIF images, which need client-side conversion to display. */
  private isHeicFile(name: string): boolean {
    return /\.(heic|heif)$/i.test(name);
  }

  /**
   * Downloads the selected files/folders of the given panel to the user's
   * computer. Local downloads use a direct (streamed) URL; FTP downloads stream
   * through the server. A single file downloads as-is; multiple items (or a
   * folder) download as a ZIP archive.
   */
  downloadSelected(index: number): void {
    const panel = this.panels[index];
    const paths = Array.from(panel.selected);
    if (!paths.length) {
      this.error = 'Nothing selected to download.';
      return;
    }
    const singleFile = paths.length === 1
      && !(panel.items.find(i => i.path === paths[0])?.directory);

    if (panel.kind === 'ftp' && panel.ftp) {
      const blob$ = singleFile
        ? this.ftpService.downloadFile(panel.ftp, paths[0])
        : this.ftpService.downloadZip(panel.ftp, paths);
      const fallbackName = singleFile ? (paths[0].split('/').pop() || 'download') : 'download.zip';
      blob$.subscribe({
        next: blob => this.saveBlob(blob, fallbackName),
        error: e => this.handleError(e)
      });
      return;
    }
    // Local: navigate to the streaming URL via a temporary anchor.
    const url = singleFile
      ? this.fileService.downloadUrl(paths[0])
      : this.fileService.downloadZipUrl(paths);
    this.triggerDownload(url);
  }

  /** Triggers a browser download from a same-origin URL. */
  private triggerDownload(url: string): void {
    const a = document.createElement('a');
    a.href = url;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  }

  /** Saves an in-memory blob to disk with the given filename. */
  private saveBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  closeViewer(): void {
    this.releaseViewerImage();
    this.showViewer = false;
    this.viewerImagePath = null;
    this.viewerImageList = [];
    this.viewerPanel = null;
  }
  private releaseViewerImage(): void {
    if (this.viewerImageUrl) {
      URL.revokeObjectURL(this.viewerImageUrl);
      this.viewerImageUrl = null;
    }
    this.viewerImageBlob = null;
  }
  copy(): void {
    const paths = this.selectedPaths();
    if (paths.length === 0) {
      this.error = 'Nothing selected to copy.';
      return;
    }
    const src = this.active;
    const dst = this.inactive;
    const destLabel = (dst.kind === 'ftp' ? 'ftp:' : '') + (dst.path || '/');
    if (!window.confirm(`Copy ${paths.length} item(s) to ${destLabel} ?`)) {
      return;
    }
    const result = this.transfer(src, dst, paths);
    if (result === 'job') {
      // Async job: open the Operations panel so progress is visible.
      this.showOps = true;
      this.refreshJobs();
    }
  }
  move(): void {
    const paths = this.selectedPaths();
    if (paths.length === 0) {
      this.error = 'Nothing selected to move.';
      return;
    }
    const src = this.active;
    const dst = this.inactive;
    const destLabel = (dst.kind === 'ftp' ? 'ftp:' : '') + (dst.path || '/');
    if (!window.confirm(`Move ${paths.length} item(s) to ${destLabel} ?`)) {
      return;
    }
    // Move = transfer (possibly async job) to destination, then delete from source.
    const result = this.transfer(src, dst, paths);
    const afterTransfer = () => {
      if (src.kind === 'ftp' && src.ftp) {
        this.ftpService.delete(src.ftp, paths).subscribe({
          next: () => { this.showOps = true; this.refreshJobs(); },
          error: e => this.handleError(e)
        });
      } else {
        this.fileService.delete(paths).subscribe({
          next: () => this.reloadBoth(),
          error: e => this.handleError(e)
        });
      }
    };
    if (result === 'job') {
      // The transfer is an async job (involves FTP). Wait for it to finish.
      const jobId = this._lastJobId;
      this.showOps = true;
      const handle = setInterval(() => {
        this.jobsService.get(jobId).subscribe({
          next: j => {
            if (j.status === 'DONE') { clearInterval(handle); afterTransfer(); }
            else if (j.status === 'FAILED' || j.status === 'CANCELLED') { clearInterval(handle); }
          }
        });
      }, 800);
    } else if (result === 'sync') {
      afterTransfer();
    }
  }
  private _lastJobId = '';

  // ---- Drag-and-drop between panels ----
  /** Index of the panel a drag was started in, or null if no drag is in progress. */
  private dragSourceIndex: number | null = null;
  /** Paths being dragged (snapshot of the source panel's selection). */
  private dragSourcePaths: string[] = [];

  /** Called by app-panel when a row drag begins. Snapshots the selection. */
  onDragStartFromPanel(index: number, item: { path: string }): void {
    const panel = this.panels[index];
    // If the item being dragged isn't part of the current selection,
    // replace selection with just that item (matching native file-manager UX).
    if (!panel.selected.has(item.path)) {
      panel.selected.clear();
      panel.selected.add(item.path);
    }
    this.dragSourceIndex = index;
    this.dragSourcePaths = Array.from(panel.selected);
  }

  /** Called by app-panel when something is dropped on it. */
  onDropToPanel(dstIndex: number, ev: PanelDropEvent): void {
    const srcIndex = this.dragSourceIndex;
    const paths = this.dragSourcePaths;
    this.dragSourceIndex = null;
    this.dragSourcePaths = [];
    if (srcIndex === null || !paths.length) {
      return;
    }
    const src = this.panels[srcIndex];
    const dst = this.panels[dstIndex];
    // Resolve destination directory: drop on a folder row -> into that folder;
    // drop on the panel background -> the panel's current path.
    const destPath = ev.target && ev.target.directory ? ev.target.path : dst.path;
    // No-op: drop onto same panel and same destination directory.
    if (srcIndex === dstIndex && destPath === dst.path) {
      return;
    }
    // Block drop onto one of the dragged items (would be moving a folder into itself).
    if (ev.target && paths.indexOf(ev.target.path) >= 0) {
      return;
    }
    this.runTransfer(src, dst, paths, destPath, ev.move);
  }

  /**
   * Common entry point for Copy / Move / drop. Performs a preflight check
   * for destination collisions; if any are found, opens the conflict modal so
   * the user picks an {@link OverwritePolicy}. Once a policy is chosen (or no
   * conflicts exist) the transfer runs and, for moves, the source is deleted
   * after success.
   */
  runTransfer(src: PanelState, dst: PanelState, paths: string[], destPath: string, isMove: boolean): void {
    const verb = isMove ? 'Move' : 'Copy';
    const destLabel = (dst.kind === 'ftp' ? 'ftp:' : '') + (destPath || '/');
    const proceed = (policy: OverwritePolicy) => this.executeTransfer(src, dst, paths, destPath, isMove, policy);
    this.preflight(src, dst, paths, destPath).subscribe({
      next: conflicts => {
        if (!conflicts.length) {
          // No collisions — confirm with a simple prompt as before.
          if (!window.confirm(`${verb} ${paths.length} item(s) to ${destLabel} ?`)) {
            return;
          }
          proceed('replace');
          return;
        }
        // Open the conflict modal and let the user pick a policy.
        this.openConflictModal(verb, destLabel, conflicts, proceed);
      },
      error: e => this.handleError(e)
    });
  }

  /** Routes preflight to the right backend depending on src/dst kinds. */
  private preflight(src: PanelState, dst: PanelState, paths: string[], destPath: string)
      : Observable<Conflict[]> {
    const map = (o: Observable<{ conflicts: Conflict[] }>) =>
      o.pipe((source: any) => new Observable<Conflict[]>(sub => source.subscribe({
        next: (r: { conflicts: Conflict[] }) => sub.next(r.conflicts),
        error: (e: any) => sub.error(e),
        complete: () => sub.complete()
      })));
    if (src.kind === 'local' && dst.kind === 'local') {
      return map(this.fileService.preflight(paths, destPath));
    }
    if (src.kind === 'local' && dst.kind === 'ftp' && dst.ftp) {
      return map(this.ftpService.uploadPreflight(dst.ftp, paths, destPath));
    }
    if (src.kind === 'ftp' && dst.kind === 'local' && src.ftp) {
      return map(this.ftpService.downloadPreflight(src.ftp, paths, destPath));
    }
    if (src.kind === 'ftp' && dst.kind === 'ftp' && src.ftp && dst.ftp) {
      return map(this.ftpService.copyRemotePreflight(src.ftp, dst.ftp, paths, destPath));
    }
    // Should not happen; fall back to "no conflicts" so the transfer proceeds.
    return new Observable<Conflict[]>(sub => { sub.next([]); sub.complete(); });
  }

  /** Performs the actual transfer (and post-move delete) with the chosen policy. */
  private executeTransfer(src: PanelState, dst: PanelState, paths: string[],
                          destPath: string, isMove: boolean, policy: OverwritePolicy): void {
    const result = this.transfer(src, dst, paths, destPath, policy);
    const afterTransfer = () => {
      if (src.kind === 'ftp' && src.ftp) {
        this.ftpService.delete(src.ftp, paths).subscribe({
          next: () => { this.showOps = true; this.refreshJobs(); },
          error: e => this.handleError(e)
        });
      } else {
        this.fileService.delete(paths).subscribe({
          next: () => this.reloadBoth(),
          error: e => this.handleError(e)
        });
      }
    };
    if (isMove) {
      if (result === 'job') {
        const jobId = this._lastJobId;
        this.showOps = true;
        const handle = setInterval(() => {
          this.jobsService.get(jobId).subscribe({
            next: j => {
              if (j.status === 'DONE') { clearInterval(handle); afterTransfer(); }
              else if (j.status === 'FAILED' || j.status === 'CANCELLED') { clearInterval(handle); }
            }
          });
        }, 800);
      } else if (result === 'sync') {
        afterTransfer();
      }
    } else if (result === 'job') {
      this.showOps = true;
      this.refreshJobs();
    }
  }

  // ---- Conflict resolution modal ----
  /** Visible while the conflict modal is open. */
  showConflict = false;
  conflictVerb = '';
  conflictDestLabel = '';
  conflictList: Conflict[] = [];
  /** Currently selected policy (radio buttons in the modal). */
  conflictPolicy: OverwritePolicy = 'rename';
  private conflictResolver: ((p: OverwritePolicy) => void) | null = null;

  openConflictModal(verb: string, destLabel: string, conflicts: Conflict[],
                    onChoose: (p: OverwritePolicy) => void): void {
    this.conflictVerb = verb;
    this.conflictDestLabel = destLabel;
    this.conflictList = conflicts;
    this.conflictPolicy = 'rename';
    this.conflictResolver = onChoose;
    this.showConflict = true;
  }

  applyConflictPolicy(): void {
    const r = this.conflictResolver;
    this.conflictResolver = null;
    this.showConflict = false;
    if (r) r(this.conflictPolicy);
  }

  cancelConflict(): void {
    this.conflictResolver = null;
    this.showConflict = false;
  }

  /** Pretty-prints a byte count for the conflict modal. Mirrors the Operations panel formatter. */
  formatBytesConflict(n: number): string {
    if (n == null || n < 0) return '?';
    if (n < 1024) return n + ' B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    let i = -1;
    let v = n;
    do { v /= 1024; i++; } while (v >= 1024 && i < units.length - 1);
    return v.toFixed(v >= 10 || i === 0 ? 0 : 1) + ' ' + units[i];
  }

  private transfer(src: PanelState, dst: PanelState, paths: string[],
                   destPath?: string, policy: OverwritePolicy = 'replace'): 'sync' | 'job' {
    const dest = destPath !== undefined ? destPath : dst.path;
    if (src.kind === 'local' && dst.kind === 'local') {
      this.fileService.copy(paths, dest, policy).subscribe({
        next: () => this.reloadBoth(),
        error: e => this.handleError(e)
      });
      return 'sync';
    }
    let request: Observable<{ jobId: string }>;
    if (src.kind === 'local' && dst.kind === 'ftp' && dst.ftp) {
      request = this.ftpService.upload(dst.ftp, paths, dest, policy);
    } else if (src.kind === 'ftp' && dst.kind === 'local' && src.ftp) {
      request = this.ftpService.download(src.ftp, paths, dest, policy);
    } else {
      request = this.ftpService.copyRemote(src.ftp!, dst.ftp!, paths, dest, policy);
    }
    request.subscribe({
      next: r => { this._lastJobId = r.jobId; this.refreshJobs(); },
      error: e => this.handleError(e)
    });
    return 'job';
  }
  remove(): void {
    const paths = this.selectedPaths();
    if (paths.length === 0) {
      this.error = 'Nothing selected to delete.';
      return;
    }
    if (!window.confirm(`Delete ${paths.length} item(s)? This cannot be undone.`)) {
      return;
    }
    const panel = this.active;
    if (panel.kind === 'ftp' && panel.ftp) {
      this.ftpService.delete(panel.ftp, paths).subscribe({
        next: r => { this._lastJobId = r.jobId; this.showOps = true; this.refreshJobs(); },
        error: e => this.handleError(e)
      });
    } else {
      this.fileService.delete(paths).subscribe({
        next: () => this.load(this.activeIndex),
        error: e => this.handleError(e)
      });
    }
  }
  // ---- FTP connection ----
  openFtpModal(): void {
    this.ftpTargetIndex = this.activeIndex;
    this.loadConnections();
    this.ftpSelected = this.ftpConnections.length ? 0 : -1;
    this.ftpLog = [];
    this.showFtp = true;
  }
  private loadConnections(): void {
    let list: FtpConnectionProfile[] = this.settings.ftpConnections ?? [];
    if (!list.length) {
      // Migrate a very old single-connection localStorage entry if present.
      const legacy = localStorage.getItem('ftpSettings');
      if (legacy) {
        try {
          const o = JSON.parse(legacy);
          list = [{
            name: o.host || 'Default',
            host: o.host || '', port: o.port || 21, user: o.user || '',
            password: o.password || '', passive: o.passive !== false
          }];
        } catch {
          // ignore malformed legacy settings
        }
      }
    }
    this.ftpConnections = list;
  }
  private saveConnections(): void {
    this.settings.ftpConnections = this.ftpConnections;
    this.persistSettings();
  }

  // ---- Last-opened directory persistence ----
  /**
   * Persists each panel's current local directory so it can be restored on the
   * next app open. FTP connections are intentionally NOT persisted (no
   * credentials are stored); an FTP panel is saved as null so it falls back to
   * the local root next time.
   */
  private saveLastPaths(): void {
    this.settings.lastPaths = this.panels.map(p => (p.kind === 'local' ? (p.path ?? '') : null));
    this.persistSettings();
  }

  /**
   * Reads the persisted per-panel local directories. Returns a 2-element array
   * where each entry is the saved path ('' = root) or null when there is no
   * usable saved local path for that panel.
   */
  private loadLastPaths(): (string | null)[] {
    const arr = this.settings.lastPaths;
    if (!Array.isArray(arr)) {
      return [null, null];
    }
    return [0, 1].map(i => (typeof arr[i] === 'string' ? arr[i] as string : null));
  }
  selectConn(i: number): void {
    this.ftpSelected = i;
  }
  ftpAdd(): void {
    this.ftpEditorMode = 'add';
    this.ftpEditorIndex = -1;
    this.ftpForm = { name: '', host: '', port: 21, user: '', password: '', passive: true };
    this.showFtpEditor = true;
  }
  ftpEdit(): void {
    if (this.ftpSelected < 0) {
      this.error = 'Select a connection to edit.';
      return;
    }
    this.ftpEditorMode = 'edit';
    this.ftpEditorIndex = this.ftpSelected;
    this.ftpForm = { ...this.ftpConnections[this.ftpSelected] };
    this.showFtpEditor = true;
  }
  ftpDelete(): void {
    if (this.ftpSelected < 0) {
      this.error = 'Select a connection to delete.';
      return;
    }
    const name = this.ftpConnections[this.ftpSelected].name;
    if (!window.confirm('Delete connection "' + name + '"?')) {
      return;
    }
    this.ftpConnections.splice(this.ftpSelected, 1);
    this.saveConnections();
    this.ftpSelected = this.ftpConnections.length ? 0 : -1;
  }
  ftpSaveEditor(): void {
    if (!this.ftpForm.name) {
      this.error = 'Connection name is required.';
      return;
    }
    if (!this.ftpForm.host) {
      this.error = 'FTP host is required.';
      return;
    }
    const profile: FtpConnectionProfile = { ...this.ftpForm };
    if (this.ftpEditorMode === 'add') {
      this.ftpConnections.push(profile);
      this.ftpSelected = this.ftpConnections.length - 1;
    } else {
      this.ftpConnections[this.ftpEditorIndex] = profile;
      this.ftpSelected = this.ftpEditorIndex;
    }
    this.saveConnections();
    this.showFtpEditor = false;
  }
  connectFtp(): void {
    if (this.ftpSelected < 0 || this.ftpSelected >= this.ftpConnections.length) {
      this.error = 'Select a connection first.';
      return;
    }
    const profile = this.ftpConnections[this.ftpSelected];
    const settings: FtpSettings = {
      host: profile.host, port: profile.port, user: profile.user,
      password: profile.password, passive: profile.passive
    };
    const index = this.ftpTargetIndex;
    const panel = this.panels[index];
    this.ftpConnecting = true;
    this.ftpLog = ['Connecting to ' + settings.host + ':' + settings.port + ' ...'];
    this.ftpService.connect(settings, '').subscribe({
      next: res => {
        this.ftpConnecting = false;
        this.ftpLog = res.log && res.log.length ? res.log : this.ftpLog;
        if (res.error) {
          // Stay in the manager so the log explains what went wrong.
          this.error = res.error;
          return;
        }
        panel.kind = 'ftp';
        panel.ftp = settings;
        panel.title = profile.name || settings.host;
        panel.path = res.path ?? '/';
        panel.home = res.path ?? '/';
        panel.items = res.items;
        this.sortPanelItems(panel);
        panel.selected.clear();
        panel.cursor = -1;
        // FTP connections aren't persisted; record this panel as non-local so
        // it opens at the local root next time instead of a stale path.
        this.saveLastPaths();
        this.activeIndex = index;
        // On success, close the connection manager.
        this.showFtp = false;
      },
      error: e => {
        this.ftpConnecting = false;
        this.ftpLog = [...this.ftpLog, 'ERROR: ' + (e?.error?.error ?? e?.message ?? 'connection failed')];
        this.handleError(e);
      }
    });
  }
  disconnectFtp(index: number): void {
    const panel = this.panels[index];
    panel.kind = 'local';
    panel.ftp = undefined;
    panel.title = index === 0 ? 'Left' : 'Right';
    panel.home = '';
    this.showFtp = false;
    this.load(index, '');
  }
  dismissError(): void {
    this.error = '';
  }
  private handleError(e: any): void {
    this.error = e?.error?.error ?? e?.message ?? 'Unexpected error';
  }
}
