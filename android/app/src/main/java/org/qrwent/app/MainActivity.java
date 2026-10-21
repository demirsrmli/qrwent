package org.qrwent.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native, foreground-only app. Camera and file IO never run on the UI thread. */
public final class MainActivity extends Activity {
    private static final int PICK=10, CAMERA=11;
    private static final long SESSION_MS=30*60*1000L;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"qrwent-io");t.setPriority(Thread.MIN_PRIORITY);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean frameBusy=new AtomicBoolean(), sendBusy=new AtomicBoolean(), prepareBusy=new AtomicBoolean(), manifestPending=new AtomicBoolean();
    private volatile boolean foreground, destroyed, scanning, sending;
    private volatile int sendGeneration;
    private volatile CancellationSignal providerCancel;
    private volatile ReceiveSession receive;
    private CameraScanner scanner;
    private TextureView cameraView;
    private LinearLayout root, content, tabs;
    private TextView status, progressText, qrCaption, senderInfo;
    private ProgressBar progress;
    private ImageView qrView;
    private Bitmap qrBitmap;
    private Button scanButton, sendButton, pickButton;
    private QrProtocol.Manifest sendManifest;
    private File sendFile;
    private volatile String rejectedId="";
    private volatile int mode;
    private int position=0, round=1, fps=4;
    private int downloadsPage;
    private long lastReceiveUi;
    private Typeface font;
    private AlertDialog activeDialog;
    private PowerManager power;
    private PowerManager.OnThermalStatusChangedListener thermal;
    private int surface, text, muted, accent, tint, line;
    private final Runnable scanDeadline=()->{if(scanning){stopScan();setStatus("The 30-minute session has paused. Progress is saved; you can resume.");}};
    private final Runnable sendDeadline=()->{if(sending){stopSend();setStatus("The 30-minute session has paused. Select Resume to continue.");}};
    private final Runnable sendTick=()->sendNext();
    private final Runnable providerDeadline=()->{CancellationSignal c=providerCancel;if(c!=null)c.cancel();};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);foreground=true;
        boolean dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        surface=Color.parseColor(dark?"#151B19":"#F8FAF9");text=Color.parseColor(dark?"#ECF3F0":"#18241F");muted=Color.parseColor(dark?"#ADBDB5":"#55665E");accent=Color.parseColor(dark?"#91DCC6":"#19735F");tint=Color.parseColor(dark?"#263D34":"#E9F5F0");line=Color.parseColor(dark?"#3D4A44":"#DCE5DF");
        try{font=Typeface.createFromAsset(getAssets(),"Inter.ttf");}catch(Exception ignored){font=Typeface.create("sans-serif",Typeface.NORMAL);}
        getWindow().setStatusBarColor(surface);getWindow().setNavigationBarColor(surface);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(surface);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left+dp(20),i.top+dp(16),i.right+dp(20),i.bottom+dp(12));}else v.setPadding(insets.getSystemWindowInsetLeft()+dp(20),insets.getSystemWindowInsetTop()+dp(16),insets.getSystemWindowInsetRight()+dp(20),insets.getSystemWindowInsetBottom()+dp(12));return insets;});
        setContentView(root);
        ui.post(()->{if(android.os.Build.VERSION.SDK_INT>=30 && getWindow().getInsetsController()!=null)getWindow().getInsetsController().setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);});
        root.addView(label("qrwent",28,text));root.addView(label("Offline file transfer",14,muted));
        tabs=new LinearLayout(this);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(20);tp.bottomMargin=dp(16);root.addView(tabs,tp);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        scanner=new CameraScanner(this,new CameraScanner.Listener(){public void onText(String raw){readFrame(raw);}public void onProblem(String message){stopScan();setStatus(message);}});
        power=(PowerManager)getSystemService(POWER_SERVICE);
        thermal=value->{if(value>=PowerManager.THERMAL_STATUS_SEVERE)post(()->{stopScan();stopSend();setStatus("The device is overheating. Transfer stopped; resume after it cools down.");});};
        power.addThermalStatusListener(getMainExecutor(),thermal);
        showMode(state==null?0:Math.max(0,Math.min(2,state.getInt("mode",0))));
        task(()->{
            File dir=incoming();File meta=new File(dir,"manifest.qr");
            if(meta.exists() && System.currentTimeMillis()-meta.lastModified()>24*60*60*1000L)ReceiveSession.clear(dir);
            try{receive=ReceiveSession.restore(dir);post(()->refreshReceive());}catch(Exception e){ReceiveSession.clear(dir);post(()->setStatus("The old temporary transfer was cleared. Start a new transfer."));}
            if(state!=null && state.getString("sender_manifest")!=null){
                try{QrProtocol.Manifest sm=QrProtocol.parse(state.getString("sender_manifest")).manifest;File ready=new File(getCacheDir(),"sender.ready");if(ready.isFile() && ready.length()==sm.size && QrProtocol.hash(ready,QrProtocol.deadline()).equals(sm.sha256))post(()->{sendManifest=sm;sendFile=ready;position=Math.max(0,Math.min(SenderSequence.length(sm.count)-1,state.getInt("sender_position",0)));round=Math.max(1,state.getInt("sender_round",1));if(senderInfo!=null)senderInfo.setText(sm.name+"\n"+formatSize(sm.size));if(sendButton!=null)sendButton.setEnabled(true);setStatus("Sending paused. You can resume.");});}catch(Exception ignored){}
            }
            recoverPendingDownloads();
        });
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private final class SafeDialogBuilder extends AlertDialog.Builder {
        SafeDialogBuilder(){super(MainActivity.this);}
        @Override public AlertDialog show(){
            if(destroyed || !foreground)return null;
            if(activeDialog!=null && activeDialog.isShowing())activeDialog.dismiss();
            AlertDialog dialog=create();activeDialog=dialog;
            dialog.setOnDismissListener(d->{if(activeDialog==dialog)activeDialog=null;manifestPending.set(false);});
            dialog.show();return dialog;
        }
    }
    private TextView label(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(font);t.setPadding(0,dp(5),0,dp(5));return t;}
    private GradientDrawable bg(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private Button button(String value,boolean primary){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(14);b.setTypeface(font);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setElevation(0);b.setStateListAnimator(null);b.setPadding(dp(12),dp(8),dp(12),dp(8));b.setTextColor(primary?(accent==Color.parseColor("#19735F")?Color.WHITE:Color.parseColor("#0D3327")):text);b.setBackground(bg(primary?accent:tint,0));return b;}
    private void add(View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);content.addView(v,p);}
    private void showMode(int selected){
        stopScan();stopSend();mode=selected;downloadsPage=0;
        if(cameraView!=null)cameraView.setSurfaceTextureListener(null);cameraView=null;
        if(qrView!=null)qrView.setImageDrawable(null);qrView=null;qrBitmap=null;
        status=null;progress=null;progressText=null;senderInfo=null;qrCaption=null;scanButton=null;sendButton=null;pickButton=null;
        content.removeAllViews();tabs.removeAllViews();String[] names={"Receive","Send","Downloads"};
        for(int i=0;i<3;i++){final int target=i;Button b=button(names[i],selected==i);b.setContentDescription(names[i]+(selected==i?", selected":""));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(6);tabs.addView(b,p);b.setOnClickListener(v->showMode(target));}
        if(mode==0)buildReceive();else if(mode==1)buildSend();else buildDownloads();
    }
    private void buildReceive(){
        add(label("Scan QR codes",22,text));add(label("Keep the QR area on the computer or other phone centered in the camera.",14,muted));
        FrameLayout frame=new FrameLayout(this);frame.setBackground(bg(tint,line));
        cameraView=new TextureView(this);frame.addView(cameraView,new FrameLayout.LayoutParams(-1,-1));
        TextView guide=label("Camera preview",14,accent);guide.setGravity(Gravity.CENTER);guide.setBackgroundColor(Color.TRANSPARENT);guide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);frame.addView(guide,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,dp(280));fp.topMargin=dp(16);content.addView(frame,fp);
        cameraView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){if(scanning && foreground)scanner.start(cameraView);}
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s){scanner.stop();return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture s){}
        });
        progressText=label("Waiting for a file",14,text);add(progressText);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));add(progress);
        status=label("The camera runs only while scanning.",14,muted);add(status);
        scanButton=button("Start scanning",true);add(scanButton);scanButton.setOnClickListener(v->{if(scanning)stopScan();else startScan();});
        LinearLayout actions=new LinearLayout(this);Button missing=button("Missing chunks",false),reset=button("Reset transfer",false);
        actions.addView(missing,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1);rp.leftMargin=dp(8);actions.addView(reset,rp);add(actions);
        missing.setOnClickListener(v->task(()->{String ranges=receive==null?"":receive.missingRanges();post(()->new SafeDialogBuilder().setTitle("Missing chunks").setMessage(ranges.isEmpty()?"There are no missing chunks, or the transfer has not started.":ranges+"\n\nEnter these numbers in the Missing chunks field on the computer. Up to 128 ranges are listed.").setPositiveButton("Copy",(d,w)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Missing chunks",ranges));}).setNegativeButton("Close",null).show());}));
        reset.setOnClickListener(v->new SafeDialogBuilder().setTitle("Reset transfer").setMessage("The incomplete file and its progress will be deleted.").setPositiveButton("Reset",(d,w)->{stopScan();task(()->{if(receive!=null){receive.delete();receive=null;}else ReceiveSession.clear(incoming());rejectedId="";post(()->{refreshReceive();setStatus("Ready for a new transfer.");});});}).setNegativeButton("Cancel",null).show());
        refreshReceive();
    }
    private void startScan(){
        if(!foreground || mode!=0 || scanning)return;
        if(!resourcesAvailable())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA);return;}
        scanning=true;if(scanButton!=null)scanButton.setText("Stop scanning");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setStatus("Waiting for QR codes. Keep the entire code in the frame.");
        if(cameraView!=null && cameraView.isAvailable())scanner.start(cameraView);
        ui.removeCallbacks(scanDeadline);ui.postDelayed(scanDeadline,SESSION_MS);
        task(()->{if(receive!=null && receive.complete())completeReceive();});
    }
    private void stopScan(){scanning=false;if(scanner!=null)scanner.stop();ui.removeCallbacks(scanDeadline);if(scanButton!=null)scanButton.setText("Start scanning");if(!sending)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==CAMERA){if(g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED)startScan();else setStatus("Camera permission was denied. Enable it in system settings.");}}
    private void readFrame(String raw){
        if(!foreground || !scanning || mode!=0 || !frameBusy.compareAndSet(false,true))return;
        boolean accepted=task(()->{try{
            QrProtocol.Packet p=QrProtocol.parse(raw);
            if(!foreground || !scanning)return;
            if(receive==null){
                if(p.manifest!=null && !p.id.equals(rejectedId) && manifestPending.compareAndSet(false,true))post(()->offer(p.manifest));
                return;
            }
            if(!p.id.equals(receive.manifest.id))return;
            if(p.manifest!=null){if(!receive.manifest.same(p.manifest))throw new IOException("The session header does not match.");return;}
            boolean changed=receive.accept(p);
            long now=SystemClock.elapsedRealtime();if(changed && (now-lastReceiveUi>300 || receive.complete())){lastReceiveUi=now;post(this::refreshReceive);}
            if(receive.complete())completeReceive();
        }catch(IllegalArgumentException ignored){
        }catch(OutOfMemoryError e){post(()->{stopScan();setStatus("Not enough memory; transfer stopped. Close other apps and resume.");});
        }catch(Exception e){post(()->{stopScan();setStatus("Transfer stopped: "+safeError(e));});}
        finally{frameBusy.set(false);}});
        if(!accepted)frameBusy.set(false);
    }
    private void offer(QrProtocol.Manifest m){
        if(!foreground || mode!=0 || !scanning){manifestPending.set(false);return;}
        new SafeDialogBuilder().setTitle("Receive file").setMessage(m.name+"\n"+formatSize(m.size)+" · "+m.count+" chunks\n\nThe file will be saved to Downloads after verification.")
            .setPositiveButton("Receive",(d,w)->{manifestPending.set(false);task(()->{try{if(receive==null){receive=new ReceiveSession(incoming(),m,false);post(this::refreshReceive);if(receive.complete())completeReceive();}}catch(Exception e){post(()->setStatus(safeError(e)));}});})
            .setNegativeButton("Decline",(d,w)->{rejectedId=m.id;manifestPending.set(false);})
            .setOnCancelListener(d->{rejectedId=m.id;manifestPending.set(false);}).show();
    }
    private void refreshReceive(){
        if(mode!=0 || progressText==null)return;
        ReceiveSession r=receive;
        if(r==null){progressText.setText("Waiting for a file");progress.setProgress(0);return;}
        progressText.setText(r.manifest.name+"\n"+r.received()+" / "+r.manifest.count+" chunks · "+formatSize(r.manifest.size));
        progress.setProgress(r.manifest.count==0?1000:(int)(1000L*r.received()/r.manifest.count));
    }
    private void completeReceive() throws Exception {
        ReceiveSession r=receive;if(r==null)return;
        post(()->{stopScan();setStatus("Verifying SHA-256 and saving the file…");});
        File verified=r.verify();Uri saved=saveDownload(verified,r.manifest);
        String name=r.manifest.name;r.delete();receive=null;
        post(()->{refreshReceive();setStatus(name+" received. SHA-256 verified. Saved in Download/qrwent.");new SafeDialogBuilder().setTitle("File received").setMessage(name+"\n"+formatSize(r.manifest.size)).setPositiveButton("Downloads",(d,w)->showMode(2)).setNegativeButton("Close",null).show();});
    }
    private Uri saveDownload(File file,QrProtocol.Manifest m) throws Exception {
        ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,m.name);values.put(MediaStore.Downloads.MIME_TYPE,m.mime);values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/qrwent");values.put(MediaStore.Downloads.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("The Downloads folder could not be opened.");
        getPreferences(MODE_PRIVATE).edit().putString("pending_download",uri.toString()).commit();boolean ok=false;
        try(InputStream in=new BufferedInputStream(new FileInputStream(file));OutputStream out=getContentResolver().openOutputStream(uri,"w")) {
            if(out==null)throw new IOException("The file could not be written.");byte[] block=new byte[65536];int n;long deadline=QrProtocol.deadline();
            while((n=in.read(block))!=-1){QrProtocol.checkDeadline(deadline);out.write(block,0,n);}out.flush();
            values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);if(getContentResolver().update(uri,values,null,null)!=1)throw new IOException("The file could not be finalized.");ok=true;return uri;
        }finally{if(!ok)getContentResolver().delete(uri,null,null);getPreferences(MODE_PRIVATE).edit().remove("pending_download").commit();}
    }
    private void recoverPendingDownloads(){String value=getPreferences(MODE_PRIVATE).getString("pending_download",null);if(value!=null){try{Uri u=Uri.parse(value);try(Cursor c=getContentResolver().query(u,new String[]{MediaStore.Downloads.IS_PENDING},null,null,null)){if(c!=null && c.moveToFirst() && c.getInt(0)==1)getContentResolver().delete(u,null,null);}}catch(Exception ignored){}getPreferences(MODE_PRIVATE).edit().remove("pending_download").commit();}}
    private void buildSend(){
        add(label("Send with QR codes",22,text));add(label("On the other phone, open qrwent → Receive and scan the QR area.",14,muted));
        senderInfo=label(sendManifest==null?"No file selected":sendManifest.name+"\n"+formatSize(sendManifest.size),14,text);add(senderInfo);
        qrView=new ImageView(this);qrView.setBackgroundColor(Color.WHITE);qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);qrView.setContentDescription("Transfer QR code");LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-1,dp(300));q.topMargin=dp(16);content.addView(qrView,q);
        qrCaption=label("Waiting for a QR code",14,muted);add(qrCaption);
        pickButton=button("Choose file",false);add(pickButton);pickButton.setOnClickListener(v->{if(prepareBusy.get())return;stopSend();Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(pick,PICK);}catch(ActivityNotFoundException e){setStatus("No system file picker is available.");}});
        Spinner speed=new Spinner(this);speed.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"4 QR/s · Balanced","2 QR/s · Easy scanning","6 QR/s · Fast"}));speed.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int i,long id){fps=new int[]{4,2,6}[i];}public void onNothingSelected(android.widget.AdapterView<?> p){}});add(speed);
        sendButton=button("Start sending",true);sendButton.setEnabled(sendManifest!=null);add(sendButton);sendButton.setOnClickListener(v->{if(sending)stopSend();else startSend();});
        status=label("512 bytes per chunk. Missed chunks repeat on the next pass. Maximum 16 MiB.",14,muted);add(status);
    }
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK && result==RESULT_OK && data!=null && data.getData()!=null)prepareSend(data.getData());}
    private void prepareSend(Uri uri){
        if(!prepareBusy.compareAndSet(false,true))return;stopSend();int generation=++sendGeneration;
        if(pickButton!=null)pickButton.setEnabled(false);if(sendButton!=null)sendButton.setEnabled(false);setStatus("Preparing the file on disk…");
        CancellationSignal cancel=new CancellationSignal();providerCancel=cancel;ui.removeCallbacks(providerDeadline);ui.postDelayed(providerDeadline,30000);
        boolean queued=task(()->{File spool=new File(getCacheDir(),"sender.next");try{
            String name="file.bin",mime="application/octet-stream";
            try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null,cancel)){
                if(c!=null && c.moveToFirst()){name=c.getString(0);if(!c.isNull(1) && c.getLong(1)>QrProtocol.MAX_FILE)throw new IOException("The file exceeds the 16 MiB limit.");}
            }
            String type=getContentResolver().getType(uri);if(type!=null)mime=type;
            long deadline=QrProtocol.deadline(),size=0;
            try(android.content.res.AssetFileDescriptor afd=getContentResolver().openAssetFileDescriptor(uri,"r",cancel)){
                if(afd==null)throw new IOException("The file could not be opened.");
                try(InputStream in=afd.createInputStream();FileOutputStream out=new FileOutputStream(spool)){
                    byte[] block=new byte[65536];int n;
                    while((n=in.read(block))!=-1){if(cancel.isCanceled())throw new IOException("File selection was canceled.");QrProtocol.checkDeadline(deadline);size+=n;if(size>QrProtocol.MAX_FILE)throw new IOException("The file exceeds the 16 MiB limit.");out.write(block,0,n);}out.getFD().sync();
                }
            }
            byte[] id=new byte[8];new SecureRandom().nextBytes(id);
            QrProtocol.Manifest m=new QrProtocol.Manifest(QrProtocol.hex(id),name,mime,size,512,QrProtocol.hash(spool,deadline));
            if(cancel.isCanceled() || generation!=sendGeneration || destroyed)throw new IOException("Preparation was canceled.");
            File ready=new File(getCacheDir(),"sender.ready");if(!spool.renameTo(ready))throw new IOException("The file could not be prepared.");
            post(()->{if(generation==sendGeneration){sendManifest=m;sendFile=ready;position=0;round=1;if(senderInfo!=null)senderInfo.setText(m.name+"\n"+formatSize(m.size));if(sendButton!=null)sendButton.setEnabled(true);setStatus("Ready. Point the receiving phone camera at the QR area.");}});
        }catch(Exception e){spool.delete();post(()->{if(generation==sendGeneration)setStatus(safeError(e));});}
        finally{prepareBusy.set(false);providerCancel=null;ui.removeCallbacks(providerDeadline);post(()->{if(pickButton!=null)pickButton.setEnabled(true);if(sendButton!=null)sendButton.setEnabled(sendManifest!=null);});}});
        if(!queued){prepareBusy.set(false);providerCancel=null;cancel.cancel();if(pickButton!=null)pickButton.setEnabled(true);}
    }
    private void startSend(){if(!foreground || sending || mode!=1 || sendManifest==null || prepareBusy.get() || !resourcesAvailable())return;sending=true;sendGeneration++;if(sendButton!=null)sendButton.setText("Pause");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);ui.removeCallbacks(sendDeadline);ui.postDelayed(sendDeadline,SESSION_MS);sendNext();}
    private void stopSend(){sending=false;sendGeneration++;ui.removeCallbacks(sendTick);ui.removeCallbacks(sendDeadline);if(sendButton!=null)sendButton.setText("Start sending");if(!scanning)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void sendNext(){
        if(!sending || !foreground || mode!=1 || sendManifest==null || !sendBusy.compareAndSet(false,true))return;
        final int gen=sendGeneration,index=position;final QrProtocol.Manifest m=sendManifest;final File source=sendFile;
        boolean queued=task(()->{Bitmap bitmap=null;try{
            String frame;
            int dataIndex=SenderSequence.dataIndex(index,m.count);
            if(dataIndex<0)frame=QrProtocol.manifestFrame(m);
            else{byte[] bytes=new byte[QrProtocol.expectedLength(m,dataIndex)];try(RandomAccessFile in=new RandomAccessFile(source,"r")){in.seek((long)dataIndex*m.chunk);in.readFully(bytes);}frame=QrProtocol.dataFrame(m,dataIndex,bytes);}
            Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M);hints.put(EncodeHintType.MARGIN,4);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");
            // A different mask on repeat passes prevents a systematic camera/decoder miss.
            if(round>1)hints.put(EncodeHintType.QR_MASK_PATTERN,(round-2)&7);
            else if(dataIndex<0 && index>0)hints.put(EncodeHintType.QR_MASK_PATTERN,(index/21-1)&7);
            BitMatrix matrix=new QRCodeWriter().encode(frame,BarcodeFormat.QR_CODE,600,600,hints);int[] pixels=new int[600*600];for(int y=0;y<600;y++)for(int x=0;x<600;x++)pixels[y*600+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
            bitmap=Bitmap.createBitmap(pixels,600,600,Bitmap.Config.RGB_565);final Bitmap result=bitmap;
            if(destroyed){result.recycle();sendBusy.set(false);return;}
            post(()->{sendBusy.set(false);if(!sending || gen!=sendGeneration || qrView==null){result.recycle();if(sending)ui.post(sendTick);return;}qrBitmap=result;qrView.setImageBitmap(result);if(qrCaption!=null)qrCaption.setText("Pass "+round+" · "+(dataIndex<0?"File header":"Chunk "+(dataIndex+1)+" / "+m.count)+" · "+fps+" QR/s");
                position++;int sequence=SenderSequence.length(m.count);if(position>=sequence){position=0;round++;}ui.postDelayed(sendTick,1000/fps);});
        }catch(OutOfMemoryError e){if(bitmap!=null)bitmap.recycle();sendBusy.set(false);post(()->{stopSend();setStatus("Not enough memory. Sending stopped.");});
        }catch(Exception e){if(bitmap!=null)bitmap.recycle();sendBusy.set(false);post(()->{stopSend();setStatus(safeError(e));});}});
        if(!queued)sendBusy.set(false);
    }
    private void buildDownloads(){
        add(label("Downloads",22,text));add(label("Download / qrwent",14,muted));status=label("Loading files…",14,muted);add(status);loadDownloads();
    }
    private void loadDownloads(){
        int page=downloadsPage;task(()->{ArrayList<DownloadItem> rows=new ArrayList<>();boolean more=false;
            try{
                Bundle query=new Bundle();query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,MediaStore.Downloads.RELATIVE_PATH+"=? AND "+MediaStore.Downloads.IS_PENDING+"=0");query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{Environment.DIRECTORY_DOWNLOADS+"/qrwent/"});query.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,new String[]{MediaStore.Downloads._ID});query.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);query.putInt(ContentResolver.QUERY_ARG_LIMIT,21);query.putInt(ContentResolver.QUERY_ARG_OFFSET,page*20);
                try(Cursor c=getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.SIZE,MediaStore.Downloads.MIME_TYPE},query,null)){
                    if(c!=null)while(rows.size()<21 && c.moveToNext())rows.add(new DownloadItem(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0)),c.getString(1),c.getLong(2),c.getString(3)));
                }
                more=rows.size()>20;if(more)rows.remove(20);final boolean hasMore=more;
                post(()->{if(mode!=2 || downloadsPage!=page)return;while(content.getChildCount()>3)content.removeViewAt(3);setStatus(rows.isEmpty()?"No files received yet.":"Page "+(page+1)+" · "+rows.size()+" files");
                    for(DownloadItem item:rows){Button b=button(item.name+"\n"+formatSize(item.size),false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);add(b);b.setOnClickListener(v->downloadActions(item));}
                    LinearLayout nav=new LinearLayout(this);if(page>0){Button prev=button("Previous",false);nav.addView(prev,new LinearLayout.LayoutParams(0,-2,1));prev.setOnClickListener(v->{downloadsPage--;loadDownloads();});}if(hasMore){Button next=button("Next",false);nav.addView(next,new LinearLayout.LayoutParams(0,-2,1));next.setOnClickListener(v->{downloadsPage++;loadDownloads();});}if(nav.getChildCount()>0)add(nav);
                });
            }catch(Exception e){post(()->setStatus("Could not list files: "+safeError(e)));}
        });
    }
    private static final class DownloadItem{final Uri uri;final String name,mime;final long size;DownloadItem(Uri u,String n,long s,String m){uri=u;name=n;size=s;mime=m==null?"application/octet-stream":m;}}
    private void downloadActions(DownloadItem item){new SafeDialogBuilder().setTitle(item.name).setItems(new String[]{"Open","Share","Delete"},(d,which)->{
        if(which==0){Intent open=new Intent(Intent.ACTION_VIEW).setDataAndType(item.uri,item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(open);}catch(ActivityNotFoundException e){setStatus("No app can open this file. Use Share instead.");}}
        else if(which==1){Intent share=new Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM,item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri(item.name,item.uri));try{startActivity(Intent.createChooser(share,"Share file"));}catch(Exception e){setStatus("The file could not be shared.");}}
        else new SafeDialogBuilder().setTitle("Delete file").setMessage(item.name+" — delete this file?").setPositiveButton("Delete",(a,w)->task(()->{try{getContentResolver().delete(item.uri,null,null);post(this::loadDownloads);}catch(Exception e){post(()->setStatus(safeError(e)));}})).setNegativeButton("Cancel",null).show();
    }).show();}
    private File incoming(){return new File(getFilesDir(),"incoming");}
    private boolean resourcesAvailable(){
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        if(memory.lowMemory){setStatus("System memory is low. Close other apps and resume.");return false;}
        if(power!=null && power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE){setStatus("The device is hot. Resume after it cools down.");return false;}
        Intent battery=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(battery!=null){int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100),plug=battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0);if(level>=0 && scale>0 && level*100/scale<10 && plug==0){setStatus("Battery is below 10%. Connect a charger and resume.");return false;}}
        return true;
    }
    private static String formatSize(long n){if(n<1024)return n+" B";if(n<1024*1024)return String.format(Locale.ROOT,"%.1f KiB",n/1024.0);return String.format(Locale.ROOT,"%.2f MiB",n/(1024.0*1024));}
    private static String safeError(Throwable e){String s=e.getMessage();return s==null?"The operation could not be completed. Try again.":s.length()>180?s.substring(0,180):s;}
    private void setStatus(String s){if(status!=null)status.setText(s);}
    private void post(Runnable r){if(!destroyed)ui.post(()->{if(!destroyed)r.run();});}
    private interface IoTask {void run() throws Exception;}
    private boolean task(IoTask r){if(destroyed)return false;try{io.execute(()->{try{r.run();}catch(OutOfMemoryError e){post(()->{stopScan();stopSend();setStatus("Not enough memory. The operation stopped.");});}catch(Exception e){post(()->setStatus(safeError(e)));}});return true;}catch(RejectedExecutionException e){post(()->setStatus("An operation is in progress. Wait for it to finish."));return false;}}
    @Override public void onResume(){super.onResume();foreground=true;}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("mode",mode);if(sendManifest!=null){try{out.putString("sender_manifest",QrProtocol.manifestFrame(sendManifest));out.putInt("sender_position",position);out.putInt("sender_round",round);}catch(Exception ignored){}}}
    @Override public void onPause(){foreground=false;stopScan();stopSend();if(activeDialog!=null){activeDialog.dismiss();activeDialog=null;}manifestPending.set(false);CancellationSignal c=providerCancel;if(c!=null)c.cancel();task(()->{if(receive!=null)receive.checkpoint();});super.onPause();}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW){stopScan();stopSend();if(qrView!=null)qrView.setImageDrawable(null);qrBitmap=null;setStatus("Low-memory warning: transfer stopped. Progress is saved.");}}
    @Override public void onDestroy(){
        foreground=false;stopScan();stopSend();if(activeDialog!=null){activeDialog.dismiss();activeDialog=null;}CancellationSignal c=providerCancel;if(c!=null)c.cancel();if(power!=null && thermal!=null)power.removeThermalStatusListener(thermal);if(scanner!=null)scanner.dispose();if(cameraView!=null)cameraView.setSurfaceTextureListener(null);
        final boolean changing=isChangingConfigurations();
        try{io.execute(()->{try{if(receive!=null){receive.close();receive=null;}}catch(Exception ignored){}new File(getCacheDir(),"sender.next").delete();if(!changing)new File(getCacheDir(),"sender.ready").delete();});}catch(RejectedExecutionException ignored){}
        destroyed=true;io.shutdown();ui.removeCallbacksAndMessages(null);if(qrView!=null)qrView.setImageDrawable(null);qrBitmap=null;super.onDestroy();
    }
}
