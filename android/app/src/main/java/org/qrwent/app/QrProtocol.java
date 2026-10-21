package org.qrwent.app;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.CRC32;

/** Bounded, versioned optical transport; no URL, network or executable payload handling. */
public final class QrProtocol {
    public static final long MAX_FILE = 16L * 1024 * 1024;
    public static final int MAX_TEXT = 1800, MAX_CHUNKS = 65536;
    public static final long OP_TIMEOUT_MS = 30000;
    private QrProtocol() {}
    public static final class Manifest {
        public final String id, name, mime, sha256;
        public final long size;
        public final int chunk, count;
        public Manifest(String id, String name, String mime, long size, int chunk, String hash) {
            if (id == null || !id.matches("[a-f0-9]{16}")) throw new IllegalArgumentException("Invalid session ID.");
            if (size < 0 || size > MAX_FILE || chunk < 256 || chunk > 768) throw new IllegalArgumentException("File or chunk limit exceeded.");
            if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid SHA-256 hash.");
            this.name = safeName(name);
            this.mime = mime != null && mime.length() <= 96 && mime.matches("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+") ? mime : "application/octet-stream";
            this.id = id; this.size = size; this.chunk = chunk; this.sha256 = hash;
            this.count = (int)((size + chunk - 1) / chunk);
            if (count > MAX_CHUNKS) throw new IllegalArgumentException("Too many chunks.");
        }
        public String json() throws Exception {
            return new JSONObject().put("name", name).put("mime", mime).put("size", size).put("chunk", chunk).put("count", count).put("sha256", sha256).toString();
        }
        public boolean same(Manifest other) {
            return other != null && id.equals(other.id) && size == other.size && chunk == other.chunk && sha256.equals(other.sha256) && name.equals(other.name) && mime.equals(other.mime);
        }
    }
    public static final class Packet {
        public final String id; public final Manifest manifest;
        public final int index; public final byte[] data;
        Packet(String id, Manifest manifest, int index, byte[] data) { this.id=id; this.manifest=manifest; this.index=index; this.data=data; }
    }
    public static String safeName(String name) {
        if (name == null) name = "file.bin";
        StringBuilder b = new StringBuilder(); int bytes=0;
        for (int i=0; i<name.length() && b.length()<100;) {
            int cp=name.codePointAt(i); i+=Character.charCount(cp);
            if (Character.isISOControl(cp) || cp=='/' || cp=='\\' || cp==':' || cp=='*' || cp=='?' || cp=='"' || cp=='<' || cp=='>' || cp=='|' || cp==0x202E || cp==0x202D || (cp>=0x2066 && cp<=0x2069)) cp='_';
            String s=new String(Character.toChars(cp)); int n=s.getBytes(StandardCharsets.UTF_8).length;
            if (bytes+n>180) break; b.append(s); bytes+=n;
        }
        String s=b.toString().trim();
        while (s.startsWith(".")) s=s.substring(1);
        return s.isEmpty() ? "file.bin" : s;
    }
    private static String b64(byte[] data) { return Base64.getEncoder().encodeToString(data); }
    private static String checksum(String text) {
        CRC32 crc=new CRC32(); crc.update(text.getBytes(StandardCharsets.US_ASCII));
        return String.format(Locale.ROOT,"%08x",crc.getValue());
    }
    public static String manifestFrame(Manifest m) throws Exception {
        String prefix="QL1|M|"+m.id+"|"+b64(m.json().getBytes(StandardCharsets.UTF_8));
        return prefix+"|"+checksum(prefix);
    }
    public static String dataFrame(Manifest m, int index, byte[] bytes) {
        if(index<0 || index>=m.count || bytes.length!=expectedLength(m,index)) throw new IllegalArgumentException("Invalid chunk size.");
        String prefix="QL1|D|"+m.id+"|"+index+"|"+b64(bytes);
        return prefix+"|"+checksum(prefix);
    }
    public static int expectedLength(Manifest m,int index) { return (int)Math.min(m.chunk,m.size-(long)index*m.chunk); }
    public static Packet parse(String raw) throws Exception {
        if (raw==null || raw.length()<30 || raw.length()>MAX_TEXT || !raw.startsWith("QL1|")) throw new IllegalArgumentException("Not a qrwent code.");
        int last=raw.lastIndexOf('|');
        if (last<0 || raw.length()-last!=9 || !checksum(raw.substring(0,last)).equals(raw.substring(last+1))) throw new IllegalArgumentException("The QR chunk is corrupt.");
        String[] fields=raw.substring(0,last).split("\\|",-1);
        if (fields.length<4 || !fields[2].matches("[a-f0-9]{16}")) throw new IllegalArgumentException("Invalid QR header.");
        if (fields[1].equals("M") && fields.length==4) {
            if(fields[3].length()>1400) throw new IllegalArgumentException("The header is too long.");
            byte[] bytes=Base64.getDecoder().decode(fields[3]);
            if(bytes.length>1024) throw new IllegalArgumentException("Header limit exceeded.");
            String json=new String(bytes,StandardCharsets.UTF_8);
            boolean quoted=false,escaped=false;int depth=0;
            for(int i=0;i<json.length();i++){char c=json.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{' || c=='['){if(++depth>1)throw new IllegalArgumentException("Nested headers are not supported.");}else if(c=='}' || c==']')depth--;}
            JSONObject j=new JSONObject(json);
            Manifest m=new Manifest(fields[2],j.getString("name"),j.optString("mime"),j.getLong("size"),j.getInt("chunk"),j.getString("sha256"));
            if(j.getInt("count")!=m.count) throw new IllegalArgumentException("Chunk count mismatch.");
            return new Packet(m.id,m,-1,null);
        }
        if (fields[1].equals("D") && fields.length==5) {
            if (!fields[3].matches("[0-9]{1,5}") || fields[4].length()>1024) throw new IllegalArgumentException("Chunk limit exceeded.");
            int index=Integer.parseInt(fields[3]); byte[] data=Base64.getDecoder().decode(fields[4]);
            if(index>=MAX_CHUNKS || data.length==0 || data.length>768) throw new IllegalArgumentException("Invalid chunk.");
            return new Packet(fields[2],null,index,data);
        }
        throw new IllegalArgumentException("Unsupported QR version or type.");
    }
    public static String hash(File file, long deadline) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); long total=0;
        try(InputStream in=new BufferedInputStream(new FileInputStream(file),65536)) {
            byte[] block=new byte[65536]; int n;
            while((n=in.read(block))!=-1) {
                checkDeadline(deadline); total+=n;
                if(total>MAX_FILE) throw new IOException("The file exceeds the 16 MiB limit.");
                digest.update(block,0,n);
            }
        }
        return hex(digest.digest());
    }
    public static String hex(byte[] bytes) {
        StringBuilder b=new StringBuilder(bytes.length*2);
        for(byte v:bytes) b.append(String.format(Locale.ROOT,"%02x",v&255)); return b.toString();
    }
    public static void checkDeadline(long deadline) throws IOException {
        if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw new IOException("The operation was canceled or timed out.");
    }
    public static long deadline() { return System.nanoTime()+OP_TIMEOUT_MS*1000000; }
}
