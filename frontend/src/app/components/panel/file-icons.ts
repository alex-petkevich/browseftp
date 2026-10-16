/**
 * File-type icons based on the file extension, using Bootstrap Icons
 * (MIT, already bundled with the app — no extra download).
 *
 * Each extension maps to a category, which decides the icon shape and colour
 * (CSS classes ft-*). In the large icons view, extensions that have a
 * dedicated labelled "filetype-xxx" glyph use that instead, so e.g. JS / PDF /
 * MP3 are recognisable at a glance.
 */

type Category =
  | 'image' | 'video' | 'audio' | 'archive' | 'pdf' | 'word' | 'excel' | 'ppt'
  | 'code' | 'text' | 'font' | 'binary' | 'key' | 'diff';

/** Generic (small-size friendly) icon per category. */
const CATEGORY_ICON: Record<Category, string> = {
  image: 'bi-file-earmark-image',
  video: 'bi-file-earmark-play',
  audio: 'bi-file-earmark-music',
  archive: 'bi-file-earmark-zip',
  pdf: 'bi-file-earmark-pdf',
  word: 'bi-file-earmark-word',
  excel: 'bi-file-earmark-excel',
  ppt: 'bi-file-earmark-ppt',
  code: 'bi-file-earmark-code',
  text: 'bi-file-earmark-text',
  font: 'bi-file-earmark-font',
  binary: 'bi-file-earmark-binary',
  key: 'bi-file-earmark-lock',
  diff: 'bi-file-earmark-diff'
};

const EXTENSIONS: Record<Category, string[]> = {
  image: ['png', 'jpg', 'jpeg', 'jfif', 'gif', 'bmp', 'webp', 'avif', 'svg', 'ico', 'tif', 'tiff',
          'heic', 'heif', 'raw', 'cr2', 'nef', 'arw', 'dng', 'psd', 'ai', 'eps', 'xcf'],
  video: ['mp4', 'm4v', 'mkv', 'avi', 'mov', 'wmv', 'webm', 'mpg', 'mpeg', 'flv', '3gp', 'm2ts', 'mts', 'vob'],
  audio: ['mp3', 'wav', 'flac', 'aac', 'ogg', 'oga', 'opus', 'm4a', 'm4p', 'wma', 'mid', 'midi', 'aiff', 'ape'],
  archive: ['zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'tbz2', 'xz', 'txz', 'zst', 'lz', 'lzma', 'cab',
            'iso', 'img', 'jar', 'war', 'ear', 'deb', 'rpm', 'apk'],
  pdf: ['pdf'],
  word: ['doc', 'docx', 'odt', 'rtf'],
  excel: ['xls', 'xlsx', 'xlsm', 'ods', 'csv', 'tsv'],
  ppt: ['ppt', 'pptx', 'odp'],
  code: ['js', 'mjs', 'cjs', 'jsx', 'ts', 'mts', 'cts', 'tsx', 'java', 'kt', 'kts', 'scala', 'groovy', 'gradle',
         'cs', 'c', 'h', 'cpp', 'cc', 'hpp', 'go', 'rs', 'py', 'rb', 'php', 'pl', 'lua', 'r', 'swift', 'dart',
         'sh', 'bash', 'zsh', 'ps1', 'bat', 'cmd', 'sql', 'html', 'htm', 'xhtml', 'css', 'scss', 'sass', 'less',
         'vue', 'svelte', 'xml', 'xsd', 'xsl', 'json', 'jsonc', 'yml', 'yaml', 'toml', 'ini', 'conf', 'cfg',
         'properties', 'env', 'service', 'dockerfile', 'makefile', 'mdx'],
  text: ['txt', 'md', 'markdown', 'log', 'rst', 'nfo', 'readme'],
  font: ['ttf', 'otf', 'woff', 'woff2', 'eot'],
  binary: ['exe', 'dll', 'so', 'dylib', 'bin', 'msi', 'dmg', 'app', 'class', 'o', 'obj', 'pyc'],
  key: ['pem', 'crt', 'cer', 'der', 'key', 'p12', 'pfx', 'jks', 'gpg', 'pgp', 'asc', 'kdbx'],
  diff: ['diff', 'patch']
};

/** Extensions with a dedicated labelled Bootstrap "filetype-xxx" icon. */
const FILETYPE_GLYPHS = new Set([
  'aac', 'ai', 'bmp', 'cs', 'css', 'csv', 'doc', 'docx', 'exe', 'gif', 'heic', 'html', 'java', 'jpg', 'js',
  'json', 'jsx', 'key', 'm4p', 'md', 'mdx', 'mov', 'mp3', 'mp4', 'otf', 'pdf', 'php', 'png', 'ppt', 'pptx',
  'psd', 'py', 'raw', 'rb', 'sass', 'scss', 'sh', 'sql', 'svg', 'tiff', 'tsx', 'ttf', 'txt', 'wav', 'woff',
  'xls', 'xlsx', 'xml', 'yml'
]);

/** Spellings that share a filetype glyph. */
const GLYPH_ALIASES: Record<string, string> = {
  jpeg: 'jpg', jfif: 'jpg', htm: 'html', xhtml: 'html', tif: 'tiff', heif: 'heic', yaml: 'yml', markdown: 'md'
};

const CATEGORY_BY_EXT = new Map<string, Category>();
for (const [cat, exts] of Object.entries(EXTENSIONS) as [Category, string[]][]) {
  for (const e of exts) {
    if (!CATEGORY_BY_EXT.has(e)) {
      CATEGORY_BY_EXT.set(e, cat);
    }
  }
}

/** Extension used for the icon; extension-less well-known names count too. */
function iconExt(name: string): string {
  const lower = name.toLowerCase();
  const dot = lower.lastIndexOf('.');
  if (dot > 0 && dot < lower.length - 1) {
    return lower.substring(dot + 1);
  }
  // "Dockerfile", "Makefile", "README" ...
  return dot === 0 ? lower.substring(1) : lower;
}

/**
 * Bootstrap Icons classes for a file. large=true (icons view) prefers the
 * labelled filetype glyphs where one exists.
 */
export function fileIconClass(name: string, large = false): string {
  const ext = iconExt(name);
  const cat = CATEGORY_BY_EXT.get(ext);
  if (!cat) {
    return 'bi-file-earmark file-icon';
  }
  const glyph = GLYPH_ALIASES[ext] ?? ext;
  const icon = large && FILETYPE_GLYPHS.has(glyph) ? `bi-filetype-${glyph}` : CATEGORY_ICON[cat];
  return `${icon} ft-${cat}`;
}

