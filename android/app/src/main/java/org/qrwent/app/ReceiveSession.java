package org.qrwent.app;

import java.io.*;
import java.util.BitSet;

/** One bounded disk-backed transfer. All calls belong to the single IO executor. */
public final class ReceiveSession implements Closeable {
    public final QrProtocol.Manifest manifest;
    private final File dir, part, state;
    private RandomAccessFile file;
    private final BitSet seen;
    private volatile int received;
    private int dirty;
    public ReceiveSession(File dir,QrProtocol.Manifest m,boolean restore) throws Exception {
        this.dir=dir;this.manifest=m;part=new File(dir,"data.part");state=new File(dir,"state.bin");seen=new BitSet(m.count);
        if(!dir.exists() && !dir.mkdirs()) throw new IOException("The temporary directory could not be created.");
        if(!restore && dir.getUsableSpace()<m.size*2+8*1024*1024) throw new IOException("Not enough storage space.");
        try {
            if(restore) {
                if(!part.isFile() || part.length()!=m.size || state.length()>9000) throw new IOException("The saved session is corrupt.");
                try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(state)))) {
                    if(in.readInt()!=0x514C5331 || !in.readUTF().equals(m.id)) throw new IOException("The saved session header is corrupt.");
                    int n=in.readInt();if(n<0 || n>(m.count+7)/8)throw new IOException("Saved session limit exceeded.");
                    byte[] bits=new byte[n];in.readFully(bits);seen.or(BitSet.valueOf(bits));
                    if(seen.length()>m.count || in.read()!=-1)throw new IOException("The saved session size is invalid.");
                    received=seen.cardinality();
                }
            }
            file=new RandomAccessFile(part,"rw");file.setLength(m.size);
            if(!restore) {
                try(FileOutputStream out=new FileOutputStream(new File(dir,"manifest.qr"))) { out.write(QrProtocol.manifestFrame(m).getBytes("US-ASCII"));out.getFD().sync(); }
                checkpoint();
            }
        }catch(Exception e){if(file!=null)file.close();throw e;}
    }
    public static ReceiveSession restore(File dir) throws Exception {
        File meta=new File(dir,"manifest.qr");
        if(!meta.isFile())return null;
        if(meta.length()>QrProtocol.MAX_TEXT)throw new IOException("Header limit exceeded.");
        byte[] bytes=new byte[(int)meta.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(meta))){in.readFully(bytes);}
        return new ReceiveSession(dir,QrProtocol.parse(new String(bytes,"US-ASCII")).manifest,true);
    }
    public boolean accept(QrProtocol.Packet p) throws Exception {
        if(file==null) throw new IOException("The session is closed.");
        if(!p.id.equals(manifest.id) || p.manifest!=null)return false;
        if(p.index<0 || p.index>=manifest.count || p.data.length!=QrProtocol.expectedLength(manifest,p.index))throw new IOException("The chunk does not belong to this file.");
        if(seen.get(p.index))return false;
        file.seek((long)p.index*manifest.chunk);file.write(p.data);
        seen.set(p.index);received++;dirty++;
        if(dirty>=32)checkpoint();
        return true;
    }
    public int received(){return received;}
    public boolean complete(){return received==manifest.count;}
    public File verify() throws Exception {
        if(!complete())throw new IOException("Some chunks are missing.");
        checkpoint();
        if(!manifest.sha256.equals(QrProtocol.hash(part,QrProtocol.deadline())))throw new IOException("SHA-256 verification failed. Reset the transfer and receive the file again.");
        return part;
    }
    public String missingRanges() {
        StringBuilder b=new StringBuilder();int i=0,ranges=0;
        while(i<manifest.count && ranges<128 && b.length()<1800) {
            i=seen.nextClearBit(i);if(i>=manifest.count)break;
            int end=seen.nextSetBit(i);if(end<0 || end>manifest.count)end=manifest.count;
            if(b.length()>0)b.append(',');b.append(i+1);if(end>i+1)b.append('-').append(end);
            i=end;ranges++;
        }
        return b.toString();
    }
    public void checkpoint() throws IOException {
        if(file==null)return;file.getFD().sync();
        File next=new File(dir,"state.next");
        try(FileOutputStream raw=new FileOutputStream(next);DataOutputStream out=new DataOutputStream(raw)) {
            out.writeInt(0x514C5331);out.writeUTF(manifest.id);byte[] bits=seen.toByteArray();out.writeInt(bits.length);out.write(bits);out.flush();raw.getFD().sync();
        }
        if(!next.renameTo(state))throw new IOException("Progress could not be saved.");dirty=0;
    }
    @Override public void close() throws IOException {if(file!=null){try{checkpoint();}finally{file.close();file=null;}}}
    public void delete() throws IOException {close();clear(dir);}
    public static void clear(File dir) { for(String name:new String[]{"data.part","state.bin","state.next","manifest.qr"}) {File f=new File(dir,name);if(f.exists())f.delete();} }
}
