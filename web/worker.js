import QRCode from 'qrcode';
import {sha256} from '@noble/hashes/sha256';
import {MAX_FILE,safeName,manifestFrame,dataFrame} from './protocol.js';
let file=null,manifest=null,busy=false;
self.onmessage=async({data:request})=>{
 if(busy){self.postMessage({type:'error',message:'The previous operation is still running.'});return;}busy=true;
 try{
  if(request.type==='prepare'){
   const f=request.file,chunk=request.chunk;
   if(!(f instanceof Blob)||f.size>MAX_FILE||![256,512,768].includes(chunk)||!/^[a-f0-9]{16}$/.test(request.id))throw Error('Invalid file or chunk limit.');
   const digest=sha256.create(),deadline=performance.now()+30000;
   try{for(let offset=0;offset<f.size;offset+=65536){if(performance.now()>deadline)throw Error('Preparation timed out. Choose a smaller file.');digest.update(new Uint8Array(await f.slice(offset,offset+65536).arrayBuffer()));if(offset%1048576===0)self.postMessage({type:'hashProgress',bytes:Math.min(offset+65536,f.size)});}const hash=Array.from(digest.digest(),x=>x.toString(16).padStart(2,'0')).join('');file=f;manifest={id:request.id,name:safeName(request.name),mime:/^[A-Za-z0-9.+_-]+\/[A-Za-z0-9.+_-]+$/.test(request.mime||'')&&request.mime.length<=96?request.mime:'application/octet-stream',size:f.size,chunk,count:Math.ceil(f.size/chunk),sha256:hash};self.postMessage({type:'prepared',manifest});}finally{digest.destroy();}
  }else if(request.type==='frame'){
   if(!file||!manifest)throw Error('The file is not ready.');const index=request.index;
   const text=index<0?manifestFrame(manifest):dataFrame(manifest,index,new Uint8Array(await file.slice(index*manifest.chunk,Math.min(file.size,(index+1)*manifest.chunk)).arrayBuffer()));
   const options={errorCorrectionLevel:'M'};if(Number.isInteger(request.mask)&&request.mask>=0&&request.mask<8)options.maskPattern=request.mask;
   const qr=QRCode.create([{data:text,mode:'byte'}],options);const modules=new Uint8Array(qr.modules.data);
   self.postMessage({type:'frame',index,epoch:request.epoch,dim:qr.modules.size,modules,text},[modules.buffer]);
  }
 }catch(e){self.postMessage({type:'error',message:String(e.message||'The operation failed.').slice(0,180)});}finally{busy=false;}
};
