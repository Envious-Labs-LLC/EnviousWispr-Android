// Renders every shot in shots.json through frame.html into 1080x1920 PNGs beside this file.
//   node docs/play-listing/screenshots/v2/render.mjs [shot name ...]
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../../..');
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || '/Users/m4pro_sv/Developer/EnviousLabs/envious-reels-ads/node_modules/playwright/index.mjs');
const MIME = { '.html': 'text/html', '.png': 'image/png', '.ttf': 'font/ttf', '.json': 'application/json' };
// serve the repo root so the frame can load the app's own font files
const srv = http.createServer((q, s) => {
  const fp = path.join(ROOT, decodeURIComponent(new URL(q.url, 'http://x').pathname));
  if (!fp.startsWith(ROOT) || !fs.existsSync(fp) || !fs.statSync(fp).isFile()) { s.writeHead(404); s.end(); return; }
  s.writeHead(200, { 'content-type': MIME[path.extname(fp)] || 'application/octet-stream' }); fs.createReadStream(fp).pipe(s);
}).listen(0, '127.0.0.1');
await new Promise((r) => srv.on('listening', r));
const base = `http://127.0.0.1:${srv.address().port}/${path.relative(ROOT, HERE)}/`;
const shots = JSON.parse(fs.readFileSync(path.join(HERE, 'shots.json'), 'utf8'));
const only = new Set(process.argv.slice(2));
const b = await chromium.launch({ headless: true, channel: 'chrome' });
for (const s of shots) {
  if (only.size && !only.has(s.name)) continue;
  if (!fs.existsSync(path.join(HERE, s.screen))) throw new Error(`${s.name}: missing screen ${s.screen}`);
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  await p.goto(base + 'frame.html', { waitUntil: 'networkidle' });
  await p.evaluate(() => document.fonts.ready);
  await p.evaluate((s) => window.draw(s), s);
  await p.waitForFunction(() => window.READY);
  await p.locator('#shot').screenshot({ path: path.join(HERE, `${s.name}.png`) });
  console.log('wrote', s.name + '.png');
  await p.close();
}
await b.close(); srv.close();
