package org.qrwent.app;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

public final class ProtocolTest {
    private static int assertions;
    private static int maskRecoveries;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private interface Checked{void run()throws Exception;}
    private static void reject(Checked run,String why)throws Exception{boolean rejected=false;try{run.run();}catch(Exception e){rejected=true;}check(rejected,why);}
    private static String wrap(String prefix){CRC32 crc=new CRC32();crc.update(prefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII));return prefix+"|"+String.format(Locale.ROOT,"%08x",crc.getValue());}
    public static void main(String[] args)throws Exception{
        File base=Files.createTempDirectory("qrwent-tests").toFile();
        byte[] source=new byte[8193];new Random(77).nextBytes(source);File original=new File(base,"original");Files.write(original.toPath(),source);
        String hash=QrProtocol.hash(original,QrProtocol.deadline());QrProtocol.Manifest m=new QrProtocol.Manifest("0123456789abcdef","unicode_\u00e9\u4e2d.pdf","application/pdf",source.length,512,hash);
        check(QrProtocol.parse(QrProtocol.manifestFrame(m)).manifest.same(m),"manifest roundtrip");
        ReceiveSession s=new ReceiveSession(new File(base,"incoming"),m,false);List<Integer> indices=new ArrayList<>();for(int i=0;i<m.count;i++)indices.add(i);Collections.shuffle(indices,new Random(11));
        for(int j=0;j<5;j++){int i=indices.get(j);byte[] chunk=Arrays.copyOfRange(source,i*512,Math.min(source.length,(i+1)*512));QrProtocol.Packet p=QrProtocol.parse(QrProtocol.dataFrame(m,i,chunk));check(s.accept(p),"out of order accept");check(!s.accept(p),"duplicate rejected");}
        check(!s.complete(),"incomplete");String missing=s.missingRanges();check(!missing.isEmpty(),"missing range reporting");s.close();s=ReceiveSession.restore(new File(base,"incoming"));check(s.received()==5,"durable resume");
        for(int i:indices){byte[] chunk=Arrays.copyOfRange(source,i*512,Math.min(source.length,(i+1)*512));s.accept(QrProtocol.parse(QrProtocol.dataFrame(m,i,chunk)));}
        check(s.complete(),"completion");check(Arrays.equals(Files.readAllBytes(s.verify().toPath()),source),"byte exact reassembly");
        final ReceiveSession full=s;File part=s.verify();try(RandomAccessFile f=new RandomAccessFile(part,"rw")){f.seek(20);f.write(4);}reject(full::verify,"tampered file rejected by SHA256");s.delete();
        File empty=new File(base,"empty");Files.write(empty.toPath(),new byte[0]);QrProtocol.Manifest zero=new QrProtocol.Manifest("fedcba9876543210","empty.bin","application/octet-stream",0,512,QrProtocol.hash(empty,QrProtocol.deadline()));
        ReceiveSession z=new ReceiveSession(new File(base,"zero"),zero,false);check(z.complete()&&z.verify().length()==0,"zero byte file");z.delete();
        reject(()->new QrProtocol.Manifest(m.id,"x","text/plain",QrProtocol.MAX_FILE+1,512,hash),"file cap");reject(()->new QrProtocol.Manifest(m.id,"x","text/plain",10,769,hash),"chunk cap");reject(()->new QrProtocol.Manifest("../../x","x","text/plain",1,512,hash),"session traversal");
        check(new QrProtocol.Manifest(m.id,"../../bad\\name.apk\u202e","bad",QrProtocol.MAX_FILE,256,hash).count==65536,"maximum chunk count");check(!QrProtocol.safeName("../..\\x\u202e").contains("/")&&!QrProtocol.safeName("../..\\x\u202e").contains("\\"),"filename sanitization");
        String good=QrProtocol.dataFrame(m,0,Arrays.copyOfRange(source,0,512));reject(()->QrProtocol.parse(good.substring(0,good.length()-1)+"z"),"bad CRC");reject(()->QrProtocol.parse(new String(new char[2000]).replace('\0','A')),"oversized QR");
        String nested="QL1|M|"+m.id+"|"+Base64.getEncoder().encodeToString("{\"x\":{\"y\":1}}".getBytes("UTF-8"));reject(()->QrProtocol.parse(wrap(nested)),"nested JSON rejected");
        for(int count:new int[]{0,1,19,20,21,39,40,41,65536}){BitSet found=new BitSet(count);int headers=0;for(int p=0;p<SenderSequence.length(count);p++){int idx=SenderSequence.dataIndex(p,count);if(idx<0)headers++;else{check(idx<count&&!found.get(idx),"sender emits valid unique chunks");found.set(idx);}}check(found.cardinality()==count,"sender covers all chunks");check(headers==Math.max(1,(count+19)/20),"periodic manifest");}
        Random fuzz=new Random(101);for(int i=0;i<3000;i++){int n=fuzz.nextInt(2000);byte[] b=new byte[n];fuzz.nextBytes(b);try{QrProtocol.parse(new String(b,"ISO-8859-1"));}catch(Exception expected){}}
        JSONObject vectors=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8"));JSONArray fixtures=vectors.getJSONArray("fixtures");
        if(args.length>2)fixtures.put(new JSONObject(new String(Files.readAllBytes(Paths.get(args[2])),"UTF-8")));
        for(int k=0;k<fixtures.length();k++){
            JSONObject v=fixtures.getJSONObject(k);QrProtocol.Manifest cm=QrProtocol.parse(v.getString("manifest")).manifest;ReceiveSession cross=new ReceiveSession(new File(base,"cross"+k),cm,false);JSONArray frames=v.getJSONArray("frames");
            for(int i=frames.length()-1;i>=0;i--){String text=frames.getString(i);cross.accept(QrProtocol.parse(text));try{check(decodeQR(text).equals(text),"JS-generated text survives native QR encoder/decoder");}catch(Exception e){throw new AssertionError("QR decode fixture="+k+" frame="+i+" chars="+text.length(),e);}}
            check(QrProtocol.hash(cross.verify(),QrProtocol.deadline()).equals(v.getString("hash")),"JS to Java interoperability");cross.delete();
        }
        if(args.length>1){BufferedImage img=ImageIO.read(new File(args[1]));int[] rgb=img.getRGB(0,0,img.getWidth(),img.getHeight(),null,0,img.getWidth());String raw=new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(img.getWidth(),img.getHeight(),rgb)))).getText();check(QrProtocol.parse(raw)!=null,"actual browser canvas QR decoded by Android ZXing library");}
        File big=new File(base,"big");byte[] block=new byte[65536];new Random(123).nextBytes(block);try(OutputStream out=new FileOutputStream(big)){for(int i=0;i<256;i++)out.write(block);}
        QrProtocol.Manifest bm=new QrProtocol.Manifest("0011223344556677","16MiB.bin","application/octet-stream",QrProtocol.MAX_FILE,768,QrProtocol.hash(big,QrProtocol.deadline()));ReceiveSession bigSession=new ReceiveSession(new File(base,"big-incoming"),bm,false);
        try(RandomAccessFile input=new RandomAccessFile(big,"r")){for(int i=0;i<bm.count;i++){byte[] b=new byte[QrProtocol.expectedLength(bm,i)];input.readFully(b);bigSession.accept(QrProtocol.parse(QrProtocol.dataFrame(bm,i,b)));}}
        check(bigSession.verify().length()==QrProtocol.MAX_FILE,"full 16MiB disk-backed transfer under bounded JVM heap");bigSession.delete();
        System.out.println("PASS: "+assertions+" assertions; 3000 malformed inputs; Java/JS frames, resume, duplicate handling, SHA-256, sender sequence, QR image, full 16MiB transfer; repeat-mask recoveries="+maskRecoveries+".");
    }
    private static String decodeQR(String text)throws Exception{
        // Same bounded repeat strategy as the native sender: auto mask, then masks 0..7.
        Exception last=null;
        for(int mask=-1;mask<8;mask++){
            Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.ERROR_CORRECTION,com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");if(mask>=0)hints.put(EncodeHintType.QR_MASK_PATTERN,mask);
            BitMatrix q=new QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,600,600,hints);int[] rgb=new int[360000];for(int y=0;y<600;y++)for(int x=0;x<600;x++)rgb[y*600+x]=q.get(x,y)?0xff000000:0xffffffff;
            try{String raw=new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(600,600,rgb)))).getText();if(mask>=0)maskRecoveries++;return raw;}catch(Exception e){last=e;}
        }
        throw last;
    }
}
