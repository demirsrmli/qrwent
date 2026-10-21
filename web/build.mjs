import {build} from 'esbuild';
import fs from 'node:fs';
const worker=await build({entryPoints:['worker.js'],bundle:true,write:false,platform:'browser',format:'iife',minify:true,legalComments:'inline'});
const app=await build({entryPoints:['app.js'],bundle:true,write:false,platform:'browser',format:'iife',minify:true,legalComments:'inline'});
const notices=fs.readFileSync('../android/app/src/main/assets/ThirdPartyNotices.txt','utf8').replaceAll('</script','<\\/script');
let html=fs.readFileSync('template.html','utf8').replace('__LICENSES__',notices).replace('__FONT__',fs.readFileSync('../android/app/src/main/assets/Inter.ttf').toString('base64')).replace('__WORKER__',JSON.stringify(worker.outputFiles[0].text).replaceAll('</script','<\\/script')).replace('__APP__',app.outputFiles[0].text.replaceAll('</script','<\\/script'));
fs.writeFileSync('qrwent-PC.html',html);
console.log('qrwent-PC.html',Buffer.byteLength(html),'bytes');
