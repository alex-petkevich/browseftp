import {
  AfterViewInit, Component, ElementRef, EventEmitter, Input, OnDestroy, Output, ViewChild
} from '@angular/core';
import type { EditorState, Extension } from '@codemirror/state';
import type { EditorView } from '@codemirror/view';

/** Cursor position reported to the host (1-based). */
export interface EditorCursor {
  line: number;
  col: number;
}

/**
 * Thin Angular wrapper around a CodeMirror 6 editor. CodeMirror and the
 * language packages are lazy-loaded (dynamic import), so they only download
 * the first time a file is opened for editing.
 *
 * The host passes the initial text once; it reads the edited text back with
 * {@link getValue} and calls {@link markSaved} after a successful save.
 */
@Component({
  selector: 'app-code-editor',
  standalone: true,
  template: `<div #host class="code-editor-host"></div>`
})
export class CodeEditorComponent implements AfterViewInit, OnDestroy {
  /** Initial document text. */
  @Input() content = '';
  /** File name; its extension selects the syntax highlighting. */
  @Input() fileName = '';
  /** Use the dark theme. */
  @Input() dark = false;

  /** Emits true on the first change after load/save, false after markSaved(). */
  @Output() dirtyChange = new EventEmitter<boolean>();
  @Output() cursorChange = new EventEmitter<EditorCursor>();
  /** Emits true once the editor is shown, or false if CodeMirror failed to load. */
  @Output() ready = new EventEmitter<boolean>();

  @ViewChild('host', { static: true }) host!: ElementRef<HTMLElement>;

  private view: EditorView | null = null;
  /** Line ending of the original file; restored when the text is read back. */
  private eol = '\n';
  private dirty = false;
  private destroyed = false;

  async ngAfterViewInit(): Promise<void> {
    try {
      const [cm, state, view, commands] = await Promise.all([
        import('codemirror'),
        import('@codemirror/state'),
        import('@codemirror/view'),
        import('@codemirror/commands')
      ]);
      const language = await this.languageFor(this.fileName);
      const theme = this.dark ? (await import('@codemirror/theme-one-dark')).oneDark : null;
      if (this.destroyed) {
        return;
      }
      // CodeMirror splits lines on \n, \r\n and \r and keeps them as "\n"
      // internally; remember CRLF files so saving doesn't convert them.
      this.eol = this.content.includes('\r\n') ? '\r\n' : '\n';

      const extensions: Extension[] = [
        cm.basicSetup,
        view.keymap.of([commands.indentWithTab]),
        view.EditorView.theme({
          '&': { height: '100%' },
          '.cm-scroller': { fontFamily: 'var(--bs-font-monospace, monospace)', fontSize: '0.85rem' }
        }),
        view.EditorView.updateListener.of(u => {
          if (u.docChanged && !this.dirty) {
            this.dirty = true;
            this.dirtyChange.emit(true);
          }
          if (u.docChanged || u.selectionSet) {
            this.emitCursor(u.state);
          }
        })
      ];
      if (language) {
        extensions.push(language);
      }
      if (theme) {
        extensions.push(theme);
      }
      this.view = new view.EditorView({
        state: state.EditorState.create({ doc: this.content, extensions }),
        parent: this.host.nativeElement
      });
      this.emitCursor(this.view.state);
      this.view.focus();
      this.ready.emit(true);
    } catch (e) {
      console.error('Failed to load the editor', e);
      if (!this.destroyed) {
        this.ready.emit(false);
      }
    }
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.view?.destroy();
    this.view = null;
  }

  /** True once CodeMirror is loaded and showing the document. */
  get isReady(): boolean {
    return this.view !== null;
  }

  /** Current text, using the original file's line endings. */
  getValue(): string {
    if (!this.view) {
      return this.content;
    }
    const doc = this.view.state.doc;
    return doc.sliceString(0, doc.length, this.eol);
  }

  /** Clears the dirty flag (call after the text was saved). */
  markSaved(): void {
    this.dirty = false;
    this.dirtyChange.emit(false);
  }

  focus(): void {
    this.view?.focus();
  }

  private emitCursor(s: EditorState): void {
    const head = s.selection.main.head;
    const line = s.doc.lineAt(head);
    this.cursorChange.emit({ line: line.number, col: head - line.from + 1 });
  }

  /** Picks a syntax-highlighting extension from the file extension (null = plain text). */
  private async languageFor(name: string): Promise<Extension | null> {
    const ext = (name.includes('.') ? name.split('.').pop() ?? '' : '').toLowerCase();
    switch (ext) {
      case 'js': case 'mjs': case 'cjs':
        return (await import('@codemirror/lang-javascript')).javascript();
      case 'jsx':
        return (await import('@codemirror/lang-javascript')).javascript({ jsx: true });
      case 'ts': case 'mts': case 'cts':
        return (await import('@codemirror/lang-javascript')).javascript({ typescript: true });
      case 'tsx':
        return (await import('@codemirror/lang-javascript')).javascript({ typescript: true, jsx: true });
      case 'json': case 'jsonc': case 'webmanifest':
        return (await import('@codemirror/lang-json')).json();
      case 'md': case 'markdown':
        return (await import('@codemirror/lang-markdown')).markdown();
      case 'html': case 'htm': case 'xhtml':
        return (await import('@codemirror/lang-html')).html();
      case 'css': case 'scss': case 'less':
        return (await import('@codemirror/lang-css')).css();
      case 'xml': case 'xsd': case 'xsl': case 'xslt': case 'svg': case 'pom': case 'plist': case 'csproj':
        return (await import('@codemirror/lang-xml')).xml();
      case 'py': case 'pyw':
        return (await import('@codemirror/lang-python')).python();
      case 'java':
        return (await import('@codemirror/lang-java')).java();
      case 'yml': case 'yaml':
        return (await import('@codemirror/lang-yaml')).yaml();
      case 'sql':
        return (await import('@codemirror/lang-sql')).sql();
      default:
        return null;
    }
  }
}

