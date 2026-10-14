package dev.codex.watchtheme;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolve the selected style without rendering or waking the watchface. */
final class FaceSource {
  final String key, fingerprint, path;
  final int style, resource;
  final Context plugin;
  final File config;

  private FaceSource(String key,int style,String path,int resource,Context plugin,File config) throws Exception {
    this.key=key;this.style=style;this.path=path;this.resource=resource;this.plugin=plugin;this.config=config;
    File image=new File(path);
    fingerprint=key+"|"+style+"|"+path+"|"+resource+"|"+image.lastModified()+"|"+image.length()+"|"+
        config.lastModified()+"|"+plugin.getPackageManager().getPackageInfo(plugin.getPackageName(),0).lastUpdateTime;
  }

  static Object call(Object value,String name) throws Exception {return value.getClass().getMethod(name).invoke(value);}
  static String text(Object value,String name) {try{Object s=call(value,name);return s==null?"":String.valueOf(s);}catch(Exception e){return "";}}

  static FaceSource resolve(Context launcher) throws Exception {
    String key=launcher.getSharedPreferences("launcher_preference",0).getString("sp_present_watch_face","");
    ComponentName component=ComponentName.unflattenFromString(key);
    if(component==null)throw new IllegalStateException("Current watchface missing");
    Context plugin=launcher.createPackageContext(component.getPackageName(),Context.CONTEXT_IGNORE_SECURITY);
    File config=new File(launcher.getFilesDir(),"WatchFace/"+component.getPackageName()+"/"+component.getPackageName()+".json");
    Object info=null;int style=-1;
    try {
      Class<?> manager=launcher.getClassLoader().loadClass("e.e.c.b.y.g.aa");
      Object instance=manager.getMethod("d").invoke(null);
      info=manager.getMethod("d",String.class).invoke(instance,key);
      if(info!=null)style=((Number)call(info,"getStyleIndex")).intValue();
    }catch(Exception ignored){}
    String path="",name="";
    if(config.isFile()) {
      JSONObject json=new JSONObject(read(config));
      JSONArray styles=json.getJSONArray("mStyles");
      if(style<0||style>=styles.length())style=json.optInt("mCurrentStyleIndex",0);
      if(style<0||style>=styles.length())throw new IllegalStateException("Invalid watchface style "+style);
      JSONObject selected=styles.getJSONObject(style);
      path=selected.optString("mEditBackgroundPath","");
      if(path.isEmpty()||!new File(path).isFile())path=selected.optString("mPreviewResNamePath","");
      name=selected.optString("mPreviewResName","");
    } else {
      try {
        Class<?> manager=launcher.getClassLoader().loadClass("com.heytap.wearable.support.watchface.edit.ConfigManager");
        Object instance=manager.getMethod("getInstance").invoke(null);
        Object watchConfig=manager.getMethod("getWatchFaceConfig",Context.class,String.class,String.class)
            .invoke(instance,plugin,component.getPackageName(),component.getClassName());
        List<?> styles=(List<?>)call(watchConfig,"getStyles");
        if(style<0||style>=styles.size())style=0;
        Object selected=styles.get(style);
        path=text(selected,"getEditBackgroundPath");
        if(path.isEmpty()||!new File(path).isFile())path=text(selected,"getPreviewResNamePath");
        name=text(selected,"getPreviewResName");
      }catch(Exception ignored){}
    }
    if(style<0)style=0;
    int resource=name.isEmpty()?0:plugin.getResources().getIdentifier(name,"drawable",component.getPackageName());
    if((path.isEmpty()||!new File(path).isFile())&&info!=null) {
      String preview=text(info,"getPreviewPath");
      if(!preview.isEmpty()&&new File(preview).isFile())path=preview;
      if(resource==0)try{resource=((Number)call(info,"getPreviewResId")).intValue();}catch(Exception ignored){}
    }
    if(resource==0&&(!new File(path).isFile())) {
      ServiceInfo service=launcher.getPackageManager().getServiceInfo(component,PackageManager.GET_META_DATA);
      if(service.metaData!=null)resource=service.metaData.getInt("heytap.wearable.watchface.PREVIEW",0);
    }
    if(!new File(path).isFile())path="";
    if(path.isEmpty()&&resource==0)throw new IllegalStateException("Watchface has no usable background or preview");
    return new FaceSource(key,style,path,resource,plugin,config);
  }

  Bitmap bitmap() throws Exception {
    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;options.inScaled=false;
    if(!path.isEmpty())BitmapFactory.decodeFile(path,options);else BitmapFactory.decodeResource(plugin.getResources(),resource,options);
    options.inJustDecodeBounds=false;options.inSampleSize=1;
    while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>256)options.inSampleSize*=2;
    Bitmap bitmap=!path.isEmpty()?BitmapFactory.decodeFile(path,options):BitmapFactory.decodeResource(plugin.getResources(),resource,options);
    if(bitmap==null&&resource!=0) {
      Drawable drawable=plugin.getResources().getDrawable(resource,plugin.getTheme());
      int w=Math.max(1,drawable.getIntrinsicWidth()),h=Math.max(1,drawable.getIntrinsicHeight());
      double scale=128.0/Math.max(w,h);
      bitmap=Bitmap.createBitmap(Math.max(1,(int)Math.round(w*scale)),Math.max(1,(int)Math.round(h*scale)),Bitmap.Config.ARGB_8888);
      drawable.setBounds(0,0,bitmap.getWidth(),bitmap.getHeight());drawable.draw(new Canvas(bitmap));
    }
    if(bitmap==null)throw new IllegalStateException("Preview decode failed");
    double scale=Math.min(1.0,128.0/Math.max(bitmap.getWidth(),bitmap.getHeight()));
    if(scale<1) {
      Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)Math.round(bitmap.getWidth()*scale)),Math.max(1,(int)Math.round(bitmap.getHeight()*scale)),true);
      if(small!=bitmap)bitmap.recycle();bitmap=small;
    }
    return bitmap;
  }

  private static String read(File file)throws Exception {
    try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      byte[] buffer=new byte[4096];int n;
      while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>1048576)throw new IllegalStateException("Oversized watchface config");}
      return new String(out.toByteArray(),"UTF-8");
    }
  }
}
