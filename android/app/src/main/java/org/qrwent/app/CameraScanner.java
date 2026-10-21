package org.qrwent.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.nio.ByteBuffer;
import java.util.*;

/** Latest image only, two camera buffers, bounded resolution and scan rate. */
public final class CameraScanner {
    public interface Listener { void onText(String text); void onProblem(String message); }
    private final Context app;
    private final HandlerThread thread=new HandlerThread("qrwent-camera",android.os.Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler worker, main=new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile boolean wanted, disposed;
    private volatile int generation;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface preview;
    private byte[] gray;
    private long lastDecode;
    private final QRCodeReader decoder=new QRCodeReader();
    private final Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
    public CameraScanner(Context context,Listener listener) {
        app=context.getApplicationContext();this.listener=listener;thread.start();worker=new Handler(thread.getLooper());
        hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET,"UTF-8");
    }
    public void start(TextureView view) {
        if(disposed || wanted)return;
        if(app.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){problem("Camera permission is required.");return;}
        SurfaceTexture texture=view.getSurfaceTexture();if(texture==null)return;
        wanted=true;final int gen=++generation;
        worker.post(()->open(texture,view,gen));
    }
    private void open(SurfaceTexture texture,TextureView view,int gen) {
        if(!valid(gen))return;closeResources();
        try {
            CameraManager manager=(CameraManager)app.getSystemService(Context.CAMERA_SERVICE);
            String id=null;CameraCharacteristics chars=null;
            for(String candidate:manager.getCameraIdList()) {
                CameraCharacteristics c=manager.getCameraCharacteristics(candidate);
                Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(facing!=null && facing==CameraCharacteristics.LENS_FACING_BACK){id=candidate;chars=c;break;}
            }
            if(id==null)throw new IllegalStateException("No rear camera was found.");
            StreamConfigurationMap map=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map==null)throw new IllegalStateException("The camera format is not supported.");
            Size imageSize=choose(map.getOutputSizes(ImageFormat.YUV_420_888));
            Size previewSize=choose(map.getOutputSizes(SurfaceTexture.class));
            texture.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());preview=new Surface(texture);
            Integer sensor=chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            main.post(()->{
                if(!valid(gen) || view.getWidth()==0 || view.getHeight()==0)return;
                int display=view.getDisplay()==null?0:view.getDisplay().getRotation();
                int rotation=((sensor==null?90:sensor)-display*90+360)%360;
                float vw=view.getWidth(),vh=view.getHeight(),bw=previewSize.getWidth(),bh=previewSize.getHeight();
                float scale=rotation%180==0?Math.max(vw/bw,vh/bh):Math.max(vw/bh,vh/bw);
                android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.setScale(bw*scale/vw,bh*scale/vh,vw/2,vh/2);matrix.postRotate(rotation,vw/2,vh/2);view.setTransform(matrix);
            });
            reader=ImageReader.newInstance(imageSize.getWidth(),imageSize.getHeight(),ImageFormat.YUV_420_888,2);
            reader.setOnImageAvailableListener(r->analyze(r,gen),worker);
            final CameraCharacteristics selected=chars;
            manager.openCamera(id,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice device){if(!valid(gen)){device.close();return;}camera=device;configure(gen,selected);}
                @Override public void onDisconnected(CameraDevice device){device.close();if(valid(gen)){wanted=false;closeResources();problem("The camera disconnected. Restart scanning.");}}
                @Override public void onError(CameraDevice device,int error){device.close();if(valid(gen)){wanted=false;closeResources();problem("The camera could not be opened ("+error+"). Restart scanning.");}}
            },worker);
            worker.postDelayed(()->{if(valid(gen) && session==null){wanted=false;closeResources();problem("Opening the camera timed out. Restart scanning.");}},8000);
        }catch(Exception e){wanted=false;closeResources();problem("The camera could not be started: "+e.getMessage());}
    }
    private static Size choose(Size[] sizes) {
        if(sizes==null || sizes.length==0)throw new IllegalArgumentException("No supported camera size was found.");
        Size best=null;long area=0;
        for(Size size:sizes) {
            long a=(long)size.getWidth()*size.getHeight();
            if(size.getWidth()<=1280 && size.getHeight()<=960 && a>area){best=size;area=a;}
        }
        if(best==null)throw new IllegalArgumentException("A camera stream of 1280×960 or smaller is required.");
        return best;
    }
    private void configure(int gen,CameraCharacteristics chars) {
        try {
            camera.createCaptureSession(Arrays.asList(preview,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession s){
                    if(!valid(gen) || camera==null){s.close();return;}session=s;
                    try {
                        CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(preview);request.addTarget(reader.getSurface());
                        request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
                        int[] modes=chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                        if(modes!=null)for(int mode:modes)if(mode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE){request.set(CaptureRequest.CONTROL_AF_MODE,mode);break;}
                        Range<Integer>[] rates=chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        if(rates!=null){Range<Integer> rate=null;for(Range<Integer> r:rates)if(r.getUpper()<=30 && r.getUpper()>=15 && (rate==null || r.getLower()<rate.getLower()))rate=r;if(rate!=null)request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,rate);}
                        s.setRepeatingRequest(request.build(),null,worker);
                    }catch(Exception e){wanted=false;closeResources();problem("The camera preview could not be started.");}
                }
                @Override public void onConfigureFailed(CameraCaptureSession s){s.close();if(valid(gen)){wanted=false;closeResources();problem("The camera stream could not be configured.");}}
            },worker);
        }catch(Exception e){wanted=false;closeResources();problem("The camera stream could not be opened.");}
    }
    private void analyze(ImageReader source,int gen) {
        Image image=null;
        boolean attempted=false;
        try {
            image=source.acquireLatestImage();
            long now=SystemClock.elapsedRealtime();
            if(image==null || !valid(gen) || now-lastDecode<120)return;
            attempted=true;
            int w=image.getWidth(),h=image.getHeight();
            if(w>1280 || h>960)return;
            Image.Plane plane=image.getPlanes()[0];ByteBuffer bytes=plane.getBuffer();int row=plane.getRowStride(),pixel=plane.getPixelStride(),base=bytes.position();
            if(gray==null || gray.length!=w*h)gray=new byte[w*h];
            for(int y=0;y<h;y++){
                if(pixel==1){bytes.position(base+y*row);bytes.get(gray,y*w,w);}else for(int x=0;x<w;x++)gray[y*w+x]=bytes.get(base+y*row+x*pixel);
            }
            image.close();image=null;
            int side=Math.min(w,h);int left=(w-side)/2,top=(h-side)/2;
            PlanarYUVLuminanceSource lum=new PlanarYUVLuminanceSource(gray,w,h,left,top,side,side,false);
            Result result=decoder.decode(new BinaryBitmap(new HybridBinarizer(lum)),hints);
            String text=result.getText();Listener l=listener;
            if(valid(gen) && l!=null && text!=null && text.length()<=QrProtocol.MAX_TEXT && text.startsWith("QL1|"))l.onText(text);
        }catch(NotFoundException | ChecksumException | FormatException ignored){
        }catch(OutOfMemoryError e){wanted=false;gray=null;closeResources();problem("Not enough memory. Scanning stopped.");
        }catch(Exception ignored){
            // Bad/late image buffers are dropped; there is no retry queue.
        }finally{if(image!=null)image.close();decoder.reset();if(attempted)lastDecode=SystemClock.elapsedRealtime();}
    }
    private boolean valid(int gen){return wanted && !disposed && generation==gen;}
    private void problem(String message){main.post(()->{Listener l=listener;if(l!=null && !disposed)l.onProblem(message);});}
    public void stop(){wanted=false;generation++;if(!disposed)worker.post(this::closeResources);}
    private void closeResources(){
        if(session!=null){try{session.stopRepeating();}catch(Exception ignored){}session.close();session=null;}
        if(camera!=null){camera.close();camera=null;}
        if(reader!=null){reader.setOnImageAvailableListener(null,null);reader.close();reader=null;}
        if(preview!=null){preview.release();preview=null;}gray=null;
    }
    public void dispose(){wanted=false;generation++;disposed=true;listener=null;main.removeCallbacksAndMessages(null);worker.post(()->{closeResources();thread.quitSafely();});}
}
