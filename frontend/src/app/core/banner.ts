import { GeneratedAsset } from './api/api.models';

/** Social banner (1200×627, LinkedIn's recommended size) generated client-side as SVG. */
export function buildBannerSvg(asset: GeneratedAsset): string {
  const route = asset.origin.route;
  const theme =
    route === 'faq'
      ? { from: '#7c3aed', to: '#4f46e5', label: 'PREGUNTA FRECUENTE', icon: '?' }
      : route === 'testimonial'
        ? { from: '#0d9488', to: '#0284c7', label: 'VOZ DE LA COMUNIDAD', icon: '♥' }
        : { from: '#4f46e5', to: '#db2777', label: 'LOGRO DE LA COMUNIDAD', icon: '★' };

  const headline = route === 'faq' ? asset.title : asset.origin.author;
  const sub =
    route === 'faq'
      ? 'Respuesta completa en nuestro FAQ'
      : route === 'testimonial'
        ? `“${truncate(asset.origin.excerpt, 90)}”`
        : '¡Consiguió su nuevo rol en tecnología gracias a la comunidad!';

  const lines = wrap(headline, route === 'faq' ? 30 : 22).slice(0, 3);
  const size = route === 'faq' ? 56 : 76;
  const startY = 300 - ((lines.length - 1) * size * 1.1) / 2;

  return `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="627" viewBox="0 0 1200 627">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="${theme.from}"/><stop offset="1" stop-color="${theme.to}"/>
    </linearGradient>
  </defs>
  <rect width="1200" height="627" fill="url(#g)"/>
  <circle cx="1080" cy="90" r="220" fill="#fff" opacity=".07"/>
  <circle cx="1130" cy="560" r="140" fill="#fff" opacity=".06"/>
  <text x="1010" y="380" font-family="Inter, Segoe UI, sans-serif" font-size="260" font-weight="700" fill="#fff" opacity=".12">${theme.icon}</text>
  <rect x="80" y="80" width="${theme.label.length * 13 + 48}" height="44" rx="22" fill="#fff" opacity=".18"/>
  <text x="104" y="109" font-family="Inter, Segoe UI, sans-serif" font-size="18" font-weight="700" letter-spacing="2" fill="#fff">${theme.label}</text>
  ${lines
    .map(
      (l, i) =>
        `<text x="80" y="${startY + i * size * 1.1}" font-family="Inter, Segoe UI, sans-serif" font-size="${size}" font-weight="700" fill="#fff">${esc(l)}</text>`,
    )
    .join('\n  ')}
  <text x="80" y="${startY + lines.length * size * 1.1 + 20}" font-family="Inter, Segoe UI, sans-serif" font-size="28" fill="#fff" opacity=".85">${esc(sub)}</text>
  <text x="80" y="560" font-family="Inter, Segoe UI, sans-serif" font-size="24" font-weight="700" fill="#fff">Kora · <tspan font-weight="400" opacity=".85">Tu comunidad habla. Kora publica.</tspan></text>
  <text x="1120" y="560" text-anchor="end" font-family="Inter, Segoe UI, sans-serif" font-size="20" fill="#fff" opacity=".8">${esc(asset.hashtags.slice(0, 3).join('  '))}</text>
</svg>`;
}

export function svgDataUrl(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

export async function downloadBannerPng(svg: string, fileName: string): Promise<void> {
  const img = new Image();
  img.src = svgDataUrl(svg);
  await img.decode();
  const canvas = document.createElement('canvas');
  canvas.width = 1200;
  canvas.height = 627;
  canvas.getContext('2d')!.drawImage(img, 0, 0);
  const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/png'));
  if (blob) downloadBlob(blob, fileName);
}

export function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
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
