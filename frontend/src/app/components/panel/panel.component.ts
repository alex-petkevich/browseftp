import { Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FileItem, DiskSpace } from '../../services/file.service';
import { I18nService } from '../../i18n/i18n.service';
import { TranslatePipe } from '../../i18n/translate.pipe';
import { fileIconClass } from './file-icons';

export type ViewMode = 'brief' | 'full' | 'icons';

/** Column the panel listing is sorted by. */
export type SortKey = 'name' | 'date' | 'size';

/** Payload the panel emits when something is dropped on it. */
export interface PanelDropEvent {
  /** When dropped on a folder row, the folder; otherwise the panel background. */
  target: FileItem | null;
  /** True when Shift was held during the drop (move semantics). */
  move: boolean;
}

/**
 * A single FileZilla-like panel: a toolbar, the current path, the directory
 * listing rendered in one of three view modes, and a status bar.
 */
@Component({
  selector: 'app-panel',
  standalone: true,
  imports: [CommonModule, TranslatePipe],
  templateUrl: './panel.component.html'
})
export class PanelComponent {
  constructor(private i18n: I18nService) {}

  @Input() title = 'Panel';
  @Input() path = '';
  @Input() items: FileItem[] = [];
  @Input() selected = new Set<string>();
  @Input() active = false;
  @Input() viewMode: ViewMode = 'full';
  @Input() loading = false;
  @Input() sortKey: SortKey = 'name';
  @Input() sortAsc = true;
  /** Keyboard cursor: item index, -2 for the [..] row, -1 for none. */
  @Input() cursor = -1;
  /** Disk usage for the status bar; null/undefined hides it (e.g. FTP). */
  @Input() space: DiskSpace | null | undefined = null;

  /** Percentage of the disk in use (0-100). */
  get spaceUsedPct(): number {
    const s = this.space;
    return s && s.total > 0 ? Math.min(100, Math.round((s.used / s.total) * 100)) : 0;
  }

  /** Formats a byte count as B / KB / MB / GB / TB. */
  formatBytes(n: number): string {
    const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    let v = n;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    return `${i === 0 ? v : v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`;
  }

  @Output() activate = new EventEmitter<void>();
  @Output() openDir = new EventEmitter<FileItem>();
  @Output() goUp = new EventEmitter<void>();
  @Output() goHome = new EventEmitter<void>();
  @Output() refresh = new EventEmitter<void>();
  @Output() toggleSelect = new EventEmitter<{ item: FileItem; additive: boolean; range: boolean }>();
  /** Emitted when the [..] row is clicked (puts the cursor on it). */
  @Output() parentClick = new EventEmitter<void>();
  @Output() viewModeChange = new EventEmitter<ViewMode>();
  @Output() sortChange = new EventEmitter<SortKey>();
  @Output() download = new EventEmitter<void>();
  @Output() selectAll = new EventEmitter<void>();
  @Output() deselectAll = new EventEmitter<void>();
  @Output() openSettings = new EventEmitter<void>();
  @Output() dragStartItem = new EventEmitter<FileItem>();
  @Output() itemDrop = new EventEmitter<PanelDropEvent>();
  /** Emitted with the files the user picked for upload into the current folder. */
  @Output() upload = new EventEmitter<File[]>();

  /** Called when the hidden file input changes; emits the picked files. */
  onUploadPicked(input: HTMLInputElement): void {
    const files = input.files ? Array.from(input.files) : [];
    // Reset so picking the same file again still fires (change).
    input.value = '';
    if (files.length) {
      this.upload.emit(files);
    }
  }
  /** Emitted with the raw text when the user submits an edited path. */
  @Output() pathSubmit = new EventEmitter<string>();

  @ViewChild('pathInput') pathInput?: ElementRef<HTMLInputElement>;

  /** True while the path bar is in edit mode. */
  editingPath = false;

  /** Path as shown in the path bar (always with a leading slash). */
  get displayPath(): string {
    return this.path.startsWith('/') ? this.path : '/' + this.path;
  }

  startEditPath(): void {
    this.activate.emit();
    this.editingPath = true;
    // Focus and select the text once the input has been rendered.
    setTimeout(() => {
      const el = this.pathInput?.nativeElement;
      if (el) {
        el.focus();
        el.select();
      }
    });
  }

  commitEditPath(value: string): void {
    this.editingPath = false;
    const v = (value ?? '').trim();
    if (v && v !== this.displayPath) {
      this.pathSubmit.emit(v);
    }
  }

  cancelEditPath(): void {
    this.editingPath = false;
  }

  /** True while a drag is hovering over the panel background (visual feedback). */
  panelDragOver = false;
  /** Path of the folder row currently being hovered during a drag (for highlight). */
  rowDragOverPath: string | null = null;

  get atRoot(): boolean {
    return !this.path || this.path === '/';
  }

  onRowClick(item: FileItem, event: MouseEvent): void {
    this.activate.emit();
    this.toggleSelect.emit({ item, additive: event.ctrlKey || event.metaKey, range: event.shiftKey });
  }

  onRowDblClick(item: FileItem): void {
    if (item.directory) {
      this.openDir.emit(item);
    }
  }

  setView(mode: ViewMode): void {
    this.viewModeChange.emit(mode);
  }

  setSort(key: SortKey): void {
    this.sortChange.emit(key);
  }

  /** Bootstrap icon class for the sort indicator on a given column. */
  sortIcon(key: SortKey): string {
    if (this.sortKey !== key) return 'bi-filter';
    return this.sortAsc ? 'bi-sort-down-alt' : 'bi-sort-up-alt';
  }

  isSelected(item: FileItem): boolean {
    return this.selected.has(item.path);
  }

  // ---- Drag & drop ----

  onRowDragStart(item: FileItem, event: DragEvent): void {
    this.activate.emit();
    this.dragStartItem.emit(item);
    if (event.dataTransfer) {
      // Some browsers require *some* data to be set, otherwise the drag
      // never starts. The actual payload is held in the parent component;
      // this is just a marker so other apps can ignore the drag.
      event.dataTransfer.setData('application/x-ftpclient', '1');
      event.dataTransfer.effectAllowed = 'copyMove';
    }
  }

  /** dragover on the panel background (and rows) — accept the drop. */
  onPanelDragOver(event: DragEvent): void {
    if (!this.hasInternalDrag(event)) return;
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = event.shiftKey ? 'move' : 'copy';
    }
    this.panelDragOver = true;
  }

  onPanelDragLeave(event: DragEvent): void {
    // Only clear if we're really leaving the panel, not just moving onto a child.
    const related = event.relatedTarget as Node | null;
    const current = event.currentTarget as HTMLElement;
    if (!related || !current.contains(related)) {
      this.panelDragOver = false;
      this.rowDragOverPath = null;
    }
  }

  onPanelDrop(event: DragEvent): void {
    if (!this.hasInternalDrag(event)) return;
    event.preventDefault();
    this.panelDragOver = false;
    this.rowDragOverPath = null;
    this.itemDrop.emit({ target: null, move: event.shiftKey });
  }

  onRowDragOver(item: FileItem, event: DragEvent): void {
    if (!this.hasInternalDrag(event)) return;
    if (!item.directory) {
      // Not a folder — let the panel-level handler take it (drop into current dir).
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = event.shiftKey ? 'move' : 'copy';
    }
    this.rowDragOverPath = item.path;
    this.panelDragOver = false;
  }

  onRowDrop(item: FileItem, event: DragEvent): void {
    if (!this.hasInternalDrag(event)) return;
    if (!item.directory) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    this.rowDragOverPath = null;
    this.panelDragOver = false;
    this.itemDrop.emit({ target: item, move: event.shiftKey });
  }

  /** Only accept drags initiated from another panel of this app. */
  private hasInternalDrag(event: DragEvent): boolean {
    const types = event.dataTransfer?.types;
    return !!types && Array.from(types).indexOf('application/x-ftpclient') >= 0;
  }

  formatSize(item: FileItem): string {
    if (item.directory) {
      return this.i18n.t('panel.dirMarker');
    }
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let size = item.size;
    let i = 0;
    while (size >= 1024 && i < units.length - 1) {
      size /= 1024;
      i++;
    }
    return `${i === 0 ? size : size.toFixed(1)} ${units[i]}`;
  }

  formatDate(item: FileItem): string {
    if (!item.lastModified) {
      return '';
    }
    return new Date(item.lastModified).toLocaleString(this.i18n.locale);
  }

  iconClass(item: FileItem): string {
    return item.directory ? 'bi-folder-fill dir-icon' : fileIconClass(item.name, this.viewMode === 'icons');
  }
}

