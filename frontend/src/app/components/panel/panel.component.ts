import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FileItem } from '../../services/file.service';

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
  imports: [CommonModule],
  templateUrl: './panel.component.html'
})
export class PanelComponent {
  @Input() title = 'Panel';
  @Input() path = '';
  @Input() items: FileItem[] = [];
  @Input() selected = new Set<string>();
  @Input() active = false;
  @Input() viewMode: ViewMode = 'full';
  @Input() loading = false;
  @Input() sortKey: SortKey = 'name';
  @Input() sortAsc = true;

  @Output() activate = new EventEmitter<void>();
  @Output() openDir = new EventEmitter<FileItem>();
  @Output() goUp = new EventEmitter<void>();
  @Output() goHome = new EventEmitter<void>();
  @Output() refresh = new EventEmitter<void>();
  @Output() toggleSelect = new EventEmitter<{ item: FileItem; additive: boolean }>();
  @Output() viewModeChange = new EventEmitter<ViewMode>();
  @Output() sortChange = new EventEmitter<SortKey>();
  @Output() selectAll = new EventEmitter<void>();
  @Output() deselectAll = new EventEmitter<void>();
  @Output() openSettings = new EventEmitter<void>();
  @Output() dragStartItem = new EventEmitter<FileItem>();
  @Output() itemDrop = new EventEmitter<PanelDropEvent>();

  /** True while a drag is hovering over the panel background (visual feedback). */
  panelDragOver = false;
  /** Path of the folder row currently being hovered during a drag (for highlight). */
  rowDragOverPath: string | null = null;

  get atRoot(): boolean {
    return !this.path;
  }

  onRowClick(item: FileItem, event: MouseEvent): void {
    this.activate.emit();
    this.toggleSelect.emit({ item, additive: event.ctrlKey || event.metaKey });
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
      return '<DIR>';
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
    return new Date(item.lastModified).toLocaleString();
  }

  iconClass(item: FileItem): string {
    return item.directory ? 'bi-folder-fill dir-icon' : 'bi-file-earmark file-icon';
  }
}

