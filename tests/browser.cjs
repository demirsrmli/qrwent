const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),assert=require('node:assert/strict'),crypto=require('crypto');
(async()=>{
 const root=path.resolve(__dirname,'..'),out=path.resolve(root,'qa');fs.mkdirSync(out,{recursive:true});
 const browser=await chromium.launch({headless:true,executablePath:process.env.QRWENT_CHROME||undefined,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:1280,height:1000}});await context.setOffline(true);const page=await context.newPage();const errors=[],network=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});page.on('request',r=>{if(/^https?:/.test(r.url()))network.push(r.url());});
 await page.goto('file://'+path.join(root,'web/qrwent-PC.html'));await page.waitForFunction(()=>window.qrwentProtocol);
 assert.equal(await page.locator('#start').isDisabled(),true);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 const bytes=Buffer.alloc(8193);for(let i=0;i<bytes.length;i++)bytes[i]=(i*29+3)&255;const hash=crypto.createHash('sha256').update(bytes).digest('hex');
 await page.locator('#file').setInputFiles({name:'unicode_\u00e9\u4e2d.bin',mimeType:'application/octet-stream',buffer:bytes});await page.waitForFunction(()=>window.qrwentCurrentFrame?.startsWith('QL1|M|'));
 const manifestRaw=await page.evaluate(()=>window.qrwentCurrentFrame),meta=JSON.parse(Buffer.from(manifestRaw.split('|')[3],'base64').toString());assert.equal(meta.sha256,hash);assert.equal(meta.size,bytes.length);
 await page.locator('#qr').screenshot({path:path.join(out,'browser-qr.png')});
 await page.locator('#start').click();const seen=new Map();let deadline=Date.now()+15000;
 while(seen.size<meta.count&&Date.now()<deadline){const raw=await page.evaluate(()=>window.qrwentCurrentFrame);const fields=raw.split('|');if(fields[1]==='D')seen.set(Number(fields[3]),raw);await page.waitForTimeout(40);}
 await page.locator('#start').click();assert.equal(seen.size,meta.count,'all data chunks emitted');const received=Buffer.concat(Array.from({length:meta.count},(_,i)=>Buffer.from(seen.get(i).split('|')[4],'base64')));assert.deepEqual(received,bytes);
 await page.locator('summary').click();await page.locator('#missing').fill('1-3,17');await page.locator('#applyMissing').click();assert.match(await page.locator('#status').textContent(),/4 missing chunks/);
 await page.locator('#missing').fill('0,99999');await page.locator('#applyMissing').click();assert.equal(await page.locator('#status').getAttribute('role'),'alert');await page.locator('#missing').fill('');await page.locator('#applyMissing').click();
 await page.locator('#reset').click();await page.locator('#start').click();await page.waitForTimeout(180);await page.locator('#start').click();await page.locator('#start').click();await page.waitForTimeout(600);assert.match(await page.locator('#caption').textContent(),/Chunk|header/);await page.locator('#start').click();
 await page.screenshot({path:path.join(out,'desktop.png'),fullPage:true});
 for(const width of [320,390,736,1280]){await page.setViewportSize({width,height:1000});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'no overflow '+width);}
 await page.setViewportSize({width:390,height:1000});await page.screenshot({path:path.join(out,'mobile-web.png'),fullPage:true});await page.emulateMedia({colorScheme:'dark'});await page.screenshot({path:path.join(out,'dark-web.png'),fullPage:true});
 await page.locator('#file').setInputFiles({name:'large.bin',mimeType:'application/octet-stream',buffer:Buffer.alloc(16*1024*1024+1)});assert.match(await page.locator('#status').textContent(),/limit/);assert.equal(await page.locator('#start').isDisabled(),true);
 await page.locator('#file').setInputFiles({name:'empty.bin',mimeType:'application/octet-stream',buffer:Buffer.alloc(0)});await page.waitForFunction(()=>window.qrwentCurrentFrame?.startsWith('QL1|M|')&&JSON.parse(atob(window.qrwentCurrentFrame.split('|')[3])).size===0);assert.equal(await page.locator('#start').isEnabled(),true);
 assert.deepEqual(errors,[],'runtime/CSP errors');assert.deepEqual(network,[],'offline no HTTP requests');
 fs.writeFileSync(path.join(out,'browser-transfer.json'),JSON.stringify({manifest:manifestRaw,frames:[...seen.values()],hash}));fs.writeFileSync(path.join(out,'browser-results.json'),JSON.stringify({passed:true,checks:['offline file URL','worker/CSP','SHA256','all chunks/byte exact','missing ranges','rapid pause/resume','16MiB cap','empty file','320/390/736/1280 widths','light/dark'],errors,network},null,2));
 await browser.close();console.log('PASS: offline browser transfer, byte-exact data, limits, layouts, missing ranges; zero network requests and runtime errors.');
})().catch(e=>{console.error(e);process.exit(1)});
