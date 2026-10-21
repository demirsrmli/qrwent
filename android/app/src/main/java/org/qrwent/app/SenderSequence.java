package org.qrwent.app;

/** Insert a manifest before every group of twenty data chunks. */
public final class SenderSequence {
    private SenderSequence(){}
    public static int length(int count){if(count<0 || count>QrProtocol.MAX_CHUNKS)throw new IllegalArgumentException("Invalid chunk count.");return Math.max(1,count+(count+19)/20);}
    public static int dataIndex(int position,int count){if(position<0 || position>=length(count))throw new IllegalArgumentException("Invalid display position.");return position%21==0?-1:position-1-position/21;}
}
