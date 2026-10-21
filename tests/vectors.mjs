import fs from 'node:fs';
import {createHash,randomBytes} from 'node:crypto';
import assert from 'node:assert/strict';
import {manifestFrame,dataFrame,parseRanges,safeName,MAX_FILE} from '../web/protocol.js';
const fixtures=[];
for(const size of [0,1,777,8193]){const data=randomBytes(size),hash=createHash('sha256').update(data).digest('hex');const m={id:'0123456789abcdef',name:'unicode_\u00e9\u4e2d.txt',mime:'application/octet-stream',size,chunk:512,count:Math.ceil(size/512),sha256:hash};fixtures.push({manifest:manifestFrame(m),frames:Array.from({length:m.count},(_,i)=>dataFrame(m,i,data.subarray(i*512,Math.min(size,(i+1)*512)))),hash});}
assert.deepEqual(parseRanges('1-3,5,3',6),[0,1,2,4]);assert.equal(parseRanges('',20),null);assert.throws(()=>parseRanges('0',3));assert.throws(()=>parseRanges('2-1',4));assert.throws(()=>parseRanges('1-99999',4));assert.throws(()=>parseRanges('1,'.repeat(2000),4));assert.equal(safeName('../../x\\y'),'_.._x_y');assert.equal(MAX_FILE,16777216);
fs.writeFileSync(new URL('./vectors.json',import.meta.url),JSON.stringify({fixtures}));console.log('PASS: JavaScript vectors and range validation.');
