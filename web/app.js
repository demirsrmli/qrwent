import {MAX_FILE,parseRanges} from './protocol.js';
const $=id=>document.getElementById(id);
let worker=null,file=null,manifest=null,running=false,preparing=false,busy=false,timer=0,prepareTimer=0,sessionTimer=0,sequence=0,lastHeader=-1,round=1,selected=null,generation=0,streamEpoch=0;
const units=n=>n<1024?`${n} B`:n<1048576?`${(n/1024).toFixed(1)} KiB`:`${(n/1048576).toFixed(2)} MiB`;
function status(text,error=false){$('status').textContent=text;$('status').classList.toggle('error',error);$('status').setAttribute('role',error?'alert':'status');}
function stop(reason){running=false;streamEpoch++;clearTimeout(timer);clearTimeout(sessionTimer);$('start').textContent=manifest?'Resume':'Start transfer';$('speed').disabled=false;$('missing').disabled=false;$('applyMissing').disabled=!manifest;$('chunk').disabled=false;if(reason)status(reason);}
function tearDown(){stop();generation++;preparing=false;busy=false;clearTimeout(prepareTimer);if(worker)worker.terminate();worker=null;manifest=null;$('start').disabled=true;$('reset').disabled=true;$('applyMissing').disabled=true;$('file').disabled=false;$('chunk').disabled=false;}
function duration(){if(!manifest)return;const count=selected?selected.length:manifest.count;const frames=Math.max(1,count+Math.ceil(count/20));const secs=Math.ceil(frames/Number($('speed').value));$('estimate').textContent=secs<60?`${secs} seconds`:`${Math.ceil(secs/60)} minutes`;$('total').textContent=`${manifest.count.toLocaleString('en-US')} chunks`;$('size').textContent=units(manifest.size);}
function updateControls(){const ready=!!manifest&&!preparing;$('start').disabled=!ready;$('reset').disabled=!ready;$('applyMissing').disabled=!ready||running;duration();}
async function choose(f){
 tearDown();window.qrwentCurrentFrame=null;file=null;selected=null;sequence=0;lastHeader=-1;round=1;$('progress').value=0;$('filename').textContent='No file selected';$('caption').textContent='The QR area is prepared when a file is selected.';$('empty').hidden=false;$('qr').hidden=true;$('size').textContent='—';$('total').textContent='—';$('estimate').textContent='—';
 if(!f)return;if(f.size>MAX_FILE){status('The file exceeds the 16 MiB limit. Choose a smaller file.',true);return;}
 if(!window.Worker){status('This browser does not support workers. Use a current Chrome or Edge release.',true);return;}
 file=f;preparing=true;$('file').disabled=true;$('chunk').disabled=true;$('filename').textContent=f.name;status('Calculating SHA-256…');
 const gen=generation;const blob=new Blob([WORKER_SOURCE],{type:'text/javascript'}),url=URL.createObjectURL(blob);
 try{worker=new Worker(url);}catch(e){URL.revokeObjectURL(url);tearDown();status('The worker could not start. Open the HTML file in current Chrome or Edge.',true);return;}URL.revokeObjectURL(url);
 worker.onerror=()=>{if(gen===generation){tearDown();status('The worker stopped. Select the file again.',true);}};
 worker.onmessage=({data:message})=>{
  if(gen!==generation)return;
  if(message.type==='hashProgress')status(`Preparing file · ${units(message.bytes)} / ${units(f.size)}`);
  else if(message.type==='prepared'){clearTimeout(prepareTimer);preparing=false;manifest=message.manifest;$('file').disabled=false;$('chunk').disabled=false;$('filename').textContent=manifest.name;duration();updateControls();status('Ready. On your phone: qrwent → Receive → Start scanning.');requestFrame(-1);}
  else if(message.type==='frame'){
   busy=false;if(message.epoch!==streamEpoch){if(running)next();else requestFrame(-1);return;}draw(message.modules,message.dim);$('empty').hidden=true;$('qr').hidden=false;
   // Exposes only the currently displayed optical packet, for reproducible interoperability tests.
   window.qrwentCurrentFrame=message.text;
   $('caption').textContent=message.index<0?`File header · Pass ${round}`:`Chunk ${message.index+1} / ${manifest.count} · Pass ${round}`;
   if(running){if(message.index<0)lastHeader=sequence;else sequence++;$('progress').value=manifest.count===0?100:Math.min(100,100*sequence/(selected?selected.length:manifest.count));$('displayed').textContent=manifest.count===0?'Empty file header':`${sequence} / ${selected?selected.length:manifest.count} chunks displayed`;
    const count=selected?selected.length:manifest.count;if(count>0&&sequence>=count){sequence=0;lastHeader=-1;round++;}timer=setTimeout(next,1000/Number($('speed').value));}
  }else if(message.type==='error'){clearTimeout(prepareTimer);preparing=false;busy=false;$('file').disabled=false;stop();status(message.message,true);updateControls();}
 };
 const id=Array.from(crypto.getRandomValues(new Uint8Array(8)),x=>x.toString(16).padStart(2,'0')).join('');
 worker.postMessage({type:'prepare',file:f,name:f.name,mime:f.type,id,chunk:Number($('chunk').value)});
 prepareTimer=setTimeout(()=>{if(preparing){tearDown();status('The 30-second preparation limit was reached. Choose a smaller file.',true);}},31000);
}
function draw(modules,dim){const canvas=$('qr'),scale=4,size=(dim+8)*scale;canvas.width=size;canvas.height=size;const ctx=canvas.getContext('2d',{alpha:false});ctx.fillStyle='#ffffff';ctx.fillRect(0,0,size,size);ctx.fillStyle='#000000';for(let y=0;y<dim;y++)for(let x=0;x<dim;x++)if(modules[y*dim+x])ctx.fillRect((x+4)*scale,(y+4)*scale,scale,scale);}
function requestFrame(index){if(!worker||!manifest||busy)return;busy=true;const mask=round>1?(round-2)&7:index<0&&sequence>0?(Math.floor(sequence/20)-1)&7:undefined;worker.postMessage({type:'frame',index,epoch:streamEpoch,mask});}
function next(){if(!running||busy||document.hidden)return;const count=selected?selected.length:manifest.count;requestFrame(count===0||sequence%20===0&&lastHeader!==sequence?-1:selected?selected[sequence]:sequence);}
function start(){if(!manifest||preparing)return;if(running){stop('Paused. Progress on the receiver is saved.');return;}if(document.hidden)return;running=true;$('start').textContent='Pause';$('missing').disabled=true;$('applyMissing').disabled=true;$('chunk').disabled=true;status('Displaying QR codes. Stop when the phone shows “File received”.');sessionTimer=setTimeout(()=>stop('The 30-minute session has paused. Select Resume to continue.'),1800000);if(!busy)next();}
$('file').addEventListener('change',e=>choose(e.target.files[0]));$('start').addEventListener('click',start);
$('reset').addEventListener('click',()=>{stop();sequence=0;lastHeader=-1;round=1;$('progress').value=0;requestFrame(-1);status('Ready to display the same file from the beginning.');});
$('speed').addEventListener('change',duration);$('chunk').addEventListener('change',()=>{if(file)choose(file);});
$('applyMissing').addEventListener('click',()=>{if(!manifest||running)return;try{selected=parseRanges($('missing').value,manifest.count);streamEpoch++;sequence=0;lastHeader=-1;round=1;duration();status(selected?`${selected.length} missing chunks selected. Select Resume to display them.`:'All chunks selected.');}catch(e){status(e.message,true);}});
const drop=$('drop');drop.addEventListener('dragover',e=>{e.preventDefault();drop.classList.add('dragging');});drop.addEventListener('dragleave',()=>drop.classList.remove('dragging'));drop.addEventListener('drop',e=>{e.preventDefault();drop.classList.remove('dragging');if(e.dataTransfer.files.length!==1){status('Choose one file at a time.',true);return;}if(!preparing)choose(e.dataTransfer.files[0]);});
document.addEventListener('visibilitychange',()=>{if(document.hidden){if(running)stop('Paused because the tab moved to the background.');if(preparing){tearDown();status('Preparation stopped. Select the file again.');}}});
window.addEventListener('pagehide',tearDown);
window.qrwentProtocol=Object.freeze({parseRanges,MAX_FILE});
status('Choose your file. No internet or shared Wi-Fi network is needed.');
