import { GeneratedAsset } from './api/api.models';
import { I18n } from './i18n/i18n.service';
import { assetTitle } from './i18n/translate.pipe';

/** Public address printed on every banner, so a reader of the image knows where to go */
export const BANNER_SITE = 'ukuku.studio';

/** Longest headline the editor accepts: beyond this a banner stops being readable at feed size */
export const BANNER_HEADLINE_MAX = 140;

/** Output sizes: LinkedIn/X link-preview ratio (1.91:1) and the square post every network accepts */
export const BANNER_FORMATS = {
  landscape: { width: 1200, height: 627 },
  square: { width: 1080, height: 1080 },
} as const;

export type BannerFormat = keyof typeof BANNER_FORMATS;

export interface BannerOptions {
  format: BannerFormat;
  /** Main text, editable by the community manager; empty falls back to the default */
  headline: string;
  /** Show the member's name; off keeps them anonymous ("Community member") */
  showAuthor: boolean;
  /** Brand mark as a data URL: an SVG shown as an image cannot load other files */
  logo?: string;
}

/**
 * Whether a post can get a banner: complaints and negative messages are answered, never promoted,
 * and rejected posts will not be published.
 */
export function canHaveBanner(asset: GeneratedAsset): boolean {
  return (
    asset.status !== 'rejected' &&
    asset.origin.route !== 'alert' &&
    asset.origin.route !== 'discard' &&
    (asset.type === 'linkedin_post' || asset.type === 'x_post' || asset.type === 'faq')
  );
}

/** Per-kind accent: strong tone for shapes, light tone for text on the dark background */
const KINDS = {
  success_story: { slug: 'logro', accent: '#f59e0b', light: '#fcd34d', label: 'banner.success', icon: '★' },
  testimonial: { slug: 'testimonio', accent: '#10b981', light: '#6ee7b7', label: 'banner.testimonial', icon: '❝' },
  faq: { slug: 'faq', accent: '#38bdf8', light: '#7dd3fc', label: 'banner.faq', icon: '?' },
} as const;

const kindOf = (asset: GeneratedAsset) => {
  const route = asset.origin.route;
  return KINDS[route === 'faq' || route === 'testimonial' ? route : 'success_story'];
};

/** Geometry of each format: the same composition, scaled and with room for more lines when square */
const LAYOUTS = {
  landscape: {
    pad: 72, logo: 48, headerY: 56, textWidth: 920, sizes: [64, 56, 48, 44, 40], maxLines: 3,
    centerY: 283, authorY: 488, ruleY: 552, footY: 590, glyph: 340, ui: 1,
  },
  square: {
    pad: 80, logo: 64, headerY: 80, textWidth: 920, sizes: [100, 88, 76, 66, 56, 48], maxLines: 5,
    centerY: 490, authorY: 846, ruleY: 950, footY: 1008, glyph: 560, ui: 1.3,
  },
} as const;

const FONT = "'Segoe UI', Inter, 'Helvetica Neue', Arial, sans-serif";

/** What the banner says by default: the member's own words for a testimonial, the question or the achievement otherwise */
export function defaultBannerHeadline(asset: GeneratedAsset, i18n: I18n): string {
  const text = asset.origin.route === 'testimonial' ? asset.origin.excerpt : assetTitle(asset, i18n) || asset.origin.excerpt;
  return truncate(text.replace(/\s+/g, ' ').trim(), BANNER_HEADLINE_MAX);
}

/**
 * Social banner generated client-side as SVG, in the UI language. Information hierarchy, in reading order:
 * brand and category (context) → one headline (the message) → who said it (social proof) → call to action and site.
 * Hashtags are left to the post text: on an image they cannot be clicked.
 */
export function buildBannerSvg(asset: GeneratedAsset, i18n: I18n, options: BannerOptions): string {
  const { width: W, height: H } = BANNER_FORMATS[options.format];
  const L = LAYOUTS[options.format];
  const kind = kindOf(asset);
  const isFaq = asset.origin.route === 'faq';
  const isQuote = asset.origin.route === 'testimonial';
  const label = i18n.t(kind.label).toUpperCase();

  const text = options.headline.trim() || defaultBannerHeadline(asset, i18n);
  const { lines, size } = fit(text, L.textWidth, L.sizes, L.maxLines);
  const lineH = Math.round(size * 1.18);
  // Baseline of the first line, so the block is centred between the header and the author row
  const firstBaseline = Math.round(L.centerY - ((lines.length - 1) * lineH) / 2 + size * 0.35);
  const headline = lines
    .map((l, i) => {
      const t = isQuote ? (i === 0 ? '“' : '') + l + (i === lines.length - 1 ? '”' : '') : l;
      return `<text x="${L.pad}" y="${firstBaseline + i * lineH}" font-family="${FONT}" font-size="${size}" font-weight="700" letter-spacing="-0.5" fill="#fafaf9">${esc(t)}</text>`;
    })
    .join('\n  ');

  // Author row: social proof; anonymous when the member's name should not be published
  const source = asset.origin.source;
  const named = options.showAuthor && !!asset.origin.author;
  const name = named ? asset.origin.author : i18n.t('banner.member');
  const role = named ? `${i18n.t('banner.member')} · ${source}` : i18n.t('banner.communityOn', { source });
  const initial = named ? asset.origin.author.charAt(0).toUpperCase() : '';
  // `u` scales the supporting text with the format, so it keeps the same weight next to the headline
  const u = L.ui;
  const r = Math.round(28 * u);
  const authorRow = isFaq
    ? `<text x="${L.pad}" y="${L.authorY + 9 * u}" font-family="${FONT}" font-size="${26 * u}" font-weight="600" fill="${kind.light}">${esc(i18n.t('banner.faqAnswer'))}  →</text>`
    : `<circle cx="${L.pad + r}" cy="${L.authorY}" r="${r}" fill="${kind.accent}"/>
  ${
    initial
      ? `<text x="${L.pad + r}" y="${L.authorY + 9 * u}" text-anchor="middle" font-family="${FONT}" font-size="${25 * u}" font-weight="700" fill="#1c1917">${esc(initial)}</text>`
      : // Anonymous: a neutral person silhouette
        `<circle cx="${L.pad + r}" cy="${L.authorY - 6 * u}" r="${7 * u}" fill="#1c1917"/>
  <path d="M ${L.pad + r - 13 * u} ${L.authorY + 15 * u} a ${13 * u} ${11 * u} 0 0 1 ${26 * u} 0 z" fill="#1c1917"/>`
  }
  <text x="${L.pad + r * 2 + 18 * u}" y="${L.authorY - 5 * u}" font-family="${FONT}" font-size="${25 * u}" font-weight="700" fill="#fafaf9">${esc(truncate(name, 40))}</text>
  <text x="${L.pad + r * 2 + 18 * u}" y="${L.authorY + 24 * u}" font-family="${FONT}" font-size="${19 * u}" fill="#a8a29e">${esc(role)}</text>`;

  // Footer: the post's call to action (what to do next) and where to find the community
  const cta = asset.cta?.trim();
  const footLeft = cta
    ? `<text x="${L.pad}" y="${L.footY}" font-family="${FONT}" font-size="${20 * u}" font-weight="600" fill="${kind.light}">${esc(truncate(cta, options.format === 'square' ? 46 : 70))}  →</text>`
    : `<text x="${L.pad}" y="${L.footY}" font-family="${FONT}" font-size="${19 * u}" fill="#a8a29e">${esc(i18n.t('brand.tagline'))}</text>`;

  const pillH = Math.round(40 * u);
  const pillW = Math.round((label.length * 13 + 56) * u);
  const pillY = L.headerY + (L.logo - pillH) / 2;
  const brandMark = options.logo
    ? `<image href="${options.logo}" x="${L.pad}" y="${L.headerY}" width="${L.logo}" height="${L.logo}" clip-path="url(#logoClip)" preserveAspectRatio="xMidYMid slice"/>
  <rect x="${L.pad}" y="${L.headerY}" width="${L.logo}" height="${L.logo}" rx="14" fill="none" stroke="${kind.accent}" stroke-opacity=".5"/>`
    : `<rect x="${L.pad}" y="${L.headerY}" width="${L.logo}" height="${L.logo}" rx="14" fill="${kind.accent}"/>`;

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#1c1917"/><stop offset="1" stop-color="#0c0a09"/>
    </linearGradient>
    <radialGradient id="glow" cx="1" cy="0" r="0.75">
      <stop offset="0" stop-color="${kind.accent}" stop-opacity=".42"/>
      <stop offset="0.55" stop-color="${kind.accent}" stop-opacity=".08"/>
      <stop offset="1" stop-color="${kind.accent}" stop-opacity="0"/>
    </radialGradient>
    <pattern id="dots" width="28" height="28" patternUnits="userSpaceOnUse">
      <circle cx="2" cy="2" r="1.6" fill="#fff" opacity=".07"/>
    </pattern>
    <linearGradient id="fade" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#fff" stop-opacity="1"/>
    </linearGradient>
    <mask id="dotsMask"><rect width="${W}" height="${H}" fill="url(#fade)"/></mask>
    <clipPath id="logoClip"><rect x="${L.pad}" y="${L.headerY}" width="${L.logo}" height="${L.logo}" rx="14"/></clipPath>
  </defs>

  <rect width="${W}" height="${H}" fill="url(#bg)"/>
  <rect width="${W}" height="${H}" fill="url(#glow)"/>
  <rect x="${W / 2}" width="${W / 2}" height="${H}" fill="url(#dots)" mask="url(#dotsMask)"/>
  <text x="${W - 40}" y="${L.ruleY - 75}" text-anchor="end" font-family="${FONT}" font-size="${L.glyph}" font-weight="800" fill="${kind.accent}" opacity=".09">${kind.icon}</text>
  <rect width="6" height="${H}" fill="${kind.accent}"/>

  ${brandMark}
  <text x="${L.pad + L.logo + 16 * u}" y="${L.headerY + L.logo / 2 + 9 * u}" font-family="${FONT}" font-size="${26 * u}" font-weight="800" fill="#fafaf9">Ukuku <tspan fill="${kind.light}">AI</tspan></text>

  <rect x="${W - L.pad - pillW}" y="${pillY}" width="${pillW}" height="${pillH}" rx="${pillH / 2}" fill="${kind.accent}" fill-opacity=".14" stroke="${kind.accent}" stroke-opacity=".55"/>
  <circle cx="${W - L.pad - pillW + 24 * u}" cy="${pillY + pillH / 2}" r="${5 * u}" fill="${kind.light}"/>
  <text x="${W - L.pad - pillW + 40 * u}" y="${pillY + pillH / 2 + 6 * u}" font-family="${FONT}" font-size="${16 * u}" font-weight="700" letter-spacing="${2 * u}" fill="${kind.light}">${esc(label)}</text>

  ${headline}

  ${authorRow}

  <rect x="${L.pad}" y="${L.ruleY}" width="${W - L.pad * 2}" height="1" fill="#fff" opacity=".12"/>
  ${footLeft}
  <text x="${W - L.pad}" y="${L.footY}" text-anchor="end" font-family="${FONT}" font-size="${19 * u}" font-weight="600" fill="#d6d3d1">${BANNER_SITE}</text>
</svg>`;
}

/** Download name that says what the file is: ukuku-logro-maria-consiguio-su-primera.png */
export function bannerFileName(asset: GeneratedAsset, headline: string, format: BannerFormat): string {
  const slug = headline
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
    .replace(/-+$/, '');
  return `ukuku-${kindOf(asset).slug}-${slug || asset.id}${format === 'square' ? '-1080x1080' : '-1200x627'}.png`;
}

let logoDataUrl: Promise<string | undefined> | undefined;

/** The bear mark as a data URL, fetched once; undefined if it cannot be loaded (the banner draws a plain tile). */
export function loadBannerLogo(): Promise<string | undefined> {
  logoDataUrl ??= fetch('image/logo/oso-head.png')
    .then((r) => (r.ok ? r.blob() : Promise.reject()))
    .then(
      (blob) =>
        new Promise<string>((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(reader.result as string);
          reader.onerror = reject;
          reader.readAsDataURL(blob);
        }),
    )
    .catch(() => {
      logoDataUrl = undefined;
      return undefined;
    });
  return logoDataUrl;
}

export function svgDataUrl(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

/** Rasterises the banner at its exact output size. */
export async function renderBannerPng(svg: string, format: BannerFormat): Promise<Blob> {
  const { width, height } = BANNER_FORMATS[format];
  const img = new Image();
  img.src = svgDataUrl(svg);
  await img.decode();
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  canvas.getContext('2d')!.drawImage(img, 0, 0, width, height);
  const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/png'));
  if (!blob) throw new Error('PNG encoding failed');
  return blob;
}

export function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  // Some browsers start the download asynchronously: revoking at once can cancel it
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Largest font size at which the text fits in `maxLines` lines of `width` px; longer text is cut with "…". */
function fit(text: string, width: number, sizes: readonly number[], maxLines: number): { lines: string[]; size: number } {
  const clean = text.replace(/\s+/g, ' ').trim();
  for (const size of sizes) {
    // Average glyph width of bold Segoe UI / Inter is about 0.5 em
    const lines = wrap(clean, Math.floor(width / (size * 0.5)));
    if (lines.length <= maxLines) return { lines, size };
  }
  const size = sizes[sizes.length - 1];
  const perLine = Math.floor(width / (size * 0.5));
  const lines = wrap(clean, perLine);
  return { lines: [...lines.slice(0, maxLines - 1), truncate(lines.slice(maxLines - 1).join(' '), perLine)], size };
}

function wrap(text: string, max: number): string[] {
  const out: string[] = [];
  let line = '';
  for (const word of text.split(/\s+/)) {
    if ((line + ' ' + word).trim().length > max && line) {
      out.push(line);
      line = word;
    } else {
      line = (line + ' ' + word).trim();
    }
  }
  if (line) out.push(line);
  return out;
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n - 1).trimEnd() + '…' : s;
}

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
