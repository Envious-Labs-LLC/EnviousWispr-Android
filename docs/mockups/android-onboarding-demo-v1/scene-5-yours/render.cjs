// One scene, one deterministic set of 15 fps PNGs. No video generation service.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const args = process.argv.slice(2);
const theme = args.includes('--theme=light') ? 'light' : 'dark';
const spriteArg = args.find(a => a.startsWith('--sprite='));
let chromium;
try { ({chromium} = require('playwright')); }
catch { ({chromium} = require('/Users/m4pro_sv/Developer/EnviousLabs/EnviousStaging/portal/node_modules/playwright')); }
const fps = 15, duration = 3;
(async () => {
  const frames = path.join(__dirname, theme === 'light' ? 'frames-light' : 'frames');
  fs.mkdirSync(frames, {recursive:true});
  // Remove numbered frames from a previous duration so ffmpeg cannot append stale frames.
  for (const name of fs.readdirSync(frames)) if (/^\d{4}\.png$/.test(name)) fs.unlinkSync(path.join(frames,name));
  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage({viewport:{width:720,height:1280},deviceScaleFactor:1});
    const url = pathToFileURL(path.join(__dirname,'index.html'));
    url.searchParams.set('theme', theme);
    url.searchParams.set('capture','1');
    await page.goto(url.href);
    await page.evaluate(() => window.ready);
    if (spriteArg) {
      const bytes = fs.readFileSync(path.resolve(__dirname,spriteArg.slice(9)));
      await page.evaluate(url => window.setHandSprite(url), 'data:image/png;base64,'+bytes.toString('base64'));
    }
    for (let i=0;i<Math.round(duration*fps);i++) {
      await page.evaluate(t => window.frame(t), i/fps);
      await page.screenshot({path:path.join(frames,String(i).padStart(4,'0')+'.png')});
    }
    console.log(`Rendered ${Math.round(duration*fps)} ${theme} frames to ${frames}`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode=1; });
