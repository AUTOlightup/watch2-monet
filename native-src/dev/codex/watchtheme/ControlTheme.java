package dev.codex.watchtheme;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Checkable;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Event-driven AP theme helper. No service, wake lock, screen capture or timer polling. */
public final class ControlTheme {
  private static ControlTheme instance;
  private static final String TAG="WatchfaceTheme";
  private final Context context;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"watchface-color"));
  private final SharedPreferences preferences,settings;
  private final ControlCorners corners=new ControlCorners();
  private final ControlChrome chrome;
  private final List<Tile> tiles=new ArrayList<>();
  private final Map<View,Tile> records=new WeakHashMap<>();
  private final List<FileObserver> observers=new ArrayList<>();
  private final Rect visibleRect=new Rect();
  private WeakReference<View> root=new WeakReference<>(null);
  private ViewTreeObserver.OnPreDrawListener draws;
  private Object faceListener;
  private Application.ActivityLifecycleCallbacks lifecycle;
  private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
  private volatile ThemePalette palette;
  private ThemePalette autoPalette;
  private volatile String mode,key="",fingerprint="",source="",error="";
  private volatile int style,computations,reads,applies;
  private volatile long computeMs;
  private boolean queued,reading,pending,wasVisible;
  private int revision;
  private String watched="";
  private ThemeBridge bridge;

  private ControlTheme(Application app) {
    context=app.createDeviceProtectedStorageContext();
    chrome=new ControlChrome(context);
    preferences=context.getSharedPreferences("launcher_preference",0);
    settings=context.getSharedPreferences("codex_control_theme",0);
    corners.setEnabled(settings.getBoolean("corners",true));
    mode=settings.getString("mode","auto");
    palette=ThemePalette.fromSeed(settings.getInt("seed",0xff4285f4));
    autoPalette=palette;
    preferenceListener=(p,k)->{if("sp_present_watch_face".equals(k))request();};
    preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    lifecycle=new Application.ActivityLifecycleCallbacks(){
      public void onActivityResumed(Activity a){if(a.getWindow()!=null){int id=context.getResources().getIdentifier("status_bar","id",context.getPackageName());View v=a.getWindow().getDecorView().findViewById(id);if(v!=null)bindView(v);}request();}
      public void onActivityCreated(Activity a,Bundle b){}
      public void onActivityStarted(Activity a){}
      public void onActivityPaused(Activity a){}
      public void onActivityStopped(Activity a){}
      public void onActivitySaveInstanceState(Activity a,Bundle b){}
      public void onActivityDestroyed(Activity a){View v=root.get();if(v!=null&&a.getWindow()!=null&&v.getRootView()==a.getWindow().getDecorView())unbind();}
    };
    app.registerActivityLifecycleCallbacks(lifecycle);
    try {
      Class<?> manager=app.getClassLoader().loadClass("e.e.c.b.y.g.aa");
      Class<?> listener=app.getClassLoader().loadClass("e.e.c.b.y.g.aa$b");
      Object candidate=Proxy.newProxyInstance(app.getClassLoader(),new Class<?>[]{listener},(proxy,method,args)->{
        if(method.getDeclaringClass()==Object.class){if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);if("equals".equals(method.getName()))return proxy==args[0];return "WatchfaceThemeListener";}
        request();return null;
      });
      manager.getMethod("a",listener).invoke(manager.getMethod("d").invoke(null),candidate);
      faceListener=candidate;
    }catch(Exception e){fail("face listener",e);}
    request();
  }

  public static void start(Application app) {try{if(instance==null){instance=new ControlTheme(app);instance.bridge=new ThemeBridge(instance.context,instance.main);}}catch(Exception e){Log.e(TAG,"start failed",e);}}
  public static void bind(View view) {if(instance!=null&&view!=null)instance.bindView(view);}
  public static void refresh() {if(instance!=null)instance.request();}
  public static void beginDetails(){if(instance!=null)try{instance.chrome.beginDetails();}catch(Exception e){instance.fail("detail snapshot",e);}}
  public static void bindDetails(View view){if(instance!=null&&view!=null)try{instance.chrome.bindDetails(view);}catch(Exception e){instance.fail("detail bind",e);}}
  public static void endDetails(){if(instance!=null)try{instance.chrome.endDetails();instance.saveStatus();}catch(Exception e){instance.fail("detail restore",e);}}
  public static void setMode(String value) {if(instance==null||(!"auto".equals(value)&&!"original".equals(value)))throw new IllegalArgumentException("auto/original");instance.mode=value;instance.revision++;if("auto".equals(value))instance.palette=instance.autoPalette;instance.settings.edit().putString("mode",value).apply();instance.apply();if("auto".equals(value))instance.request();instance.saveStatus();}
  public static void setSeed(int seed) {if(instance==null)return;instance.mode="fixed";instance.revision++;instance.palette=ThemePalette.fromSeed(seed);instance.settings.edit().putString("mode","fixed").putInt("seed",seed).apply();instance.apply();instance.saveStatus();}
  public static String status(){return instance==null?"{\"ready\":false}":instance.describe().toString();}
  public static void setCorners(boolean enabled){if(instance==null)return;instance.corners.setEnabled(enabled);instance.settings.edit().putBoolean("corners",enabled).apply();instance.apply();instance.saveStatus();}

  /** One durable settings update, then one UI application on the Launcher main thread. */
  static void managedSet(String mode,int seed,boolean rounded,ThemePalette sampled)throws Exception {
    if(instance==null)throw new IllegalStateException("Helper not ready");
    if(!"auto".equals(mode)&&!"fixed".equals(mode)&&!"original".equals(mode))throw new IllegalArgumentException("Invalid mode");
    ThemePalette next="auto".equals(mode)?sampled:ThemePalette.fromSeed(seed);
    if(next==null)throw new IllegalArgumentException("Auto palette missing");
    if(!instance.settings.edit().putString("mode",mode).putInt("seed",next.seed).putBoolean("corners",rounded).commit())throw new java.io.IOException("Settings commit failed");
    instance.revision++;instance.mode=mode;instance.palette=next;
    if("auto".equals(mode)){instance.autoPalette=next;instance.fingerprint="";}
    instance.corners.setEnabled(rounded);instance.error="";instance.apply();instance.saveStatus();
    if(!instance.error.isEmpty())throw new java.io.IOException("Theme application failed");
    if("auto".equals(mode))instance.request();
  }

  private void bindView(View view) {
    boolean fresh=root.get()!=view;int oldSize=tiles.size();
    if(fresh) {
      unbind();root=new WeakReference<>(view);
      draws=()->{
        View current=root.get();boolean visible=current!=null&&current.isShown()&&current.getGlobalVisibleRect(visibleRect);
        if(visible){if(!wasVisible)request();apply();}
        wasVisible=visible;return true;
      };
      view.getViewTreeObserver().addOnPreDrawListener(draws);
      request();
    }
    collect(view);apply();if(fresh||oldSize!=tiles.size())saveStatus();
  }

  private void unbind() {
    View previous=root.get();
    if(previous!=null&&draws!=null&&previous.getViewTreeObserver().isAlive())previous.getViewTreeObserver().removeOnPreDrawListener(draws);
    tiles.clear();chrome.clearTop();root=new WeakReference<>(null);draws=null;wasVisible=false;
  }

  private void collect(View view) {
    try{chrome.collect(view);}catch(Exception e){fail("chrome bind",e);}
    String name=view.getClass().getName();
    if(name.equals("com.heytap.wearable.launcher.statusbar.view.LauncherToggleButton")||name.equals("com.heytap.wearable.launcher.statusbar.qs.LauncherProgressBar")) {
      boolean found=false;for(Tile tile:tiles)if(tile.view.get()==view){found=true;break;}
      if(!found)try{Tile tile=records.get(view);if(tile==null){tile=new Tile(view,view instanceof Checkable);records.put(view,tile);}tiles.add(tile);}catch(Exception e){fail("tile",e);}
    }
    if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i));}
  }

  private void request() {main.post(()->{if(queued)return;queued=true;main.postDelayed(()->{queued=false;read();},120);});}
  private void read() {
    if(!"auto".equals(mode))return;
    if(reading){pending=true;return;}
    reading=true;final int ticket=revision;final int fallback=palette.seed;
    worker.execute(()->{
      FaceSource found=null;ThemePalette computed=null;String problem="";long duration=0;
      try {
        reads++;found=FaceSource.resolve(context);
        if(!found.fingerprint.equals(fingerprint)) {
          Bitmap bitmap=found.bitmap();
          try{int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());long begin=SystemClock.elapsedRealtime();computed=ThemePalette.fromPixels(pixels,fallback);duration=SystemClock.elapsedRealtime()-begin;}
          finally{bitmap.recycle();}
        }
      }catch(Exception e){problem=e.toString();Log.e(TAG,"read/quantize failed; keeping palette",e);}
      final FaceSource result=found;final ThemePalette next=computed;final String issue=problem;final long elapsed=duration;
      main.post(()->{
        reading=false;
        if(ticket==revision&&"auto".equals(mode)) {
          if(result!=null){key=result.key;style=result.style;source=result.path.isEmpty()?"resource:"+result.resource:result.path;watch(result);}
          if(next!=null&&result!=null){palette=next;autoPalette=next;fingerprint=result.fingerprint;computations++;computeMs=elapsed;settings.edit().putInt("seed",palette.seed).apply();apply();Log.i(TAG,"palette "+hex(palette.active)+" source="+source);}
          error=issue;saveStatus();
        }
        if(pending){pending=false;request();}
      });
    });
  }

  private void watch(FaceSource face) {
    List<String> paths=new ArrayList<>();if(face.config.getParentFile().isDirectory())paths.add(face.config.getParent());
    if(!face.path.isEmpty()){String parent=new File(face.path).getParent();if(parent!=null&&!paths.contains(parent))paths.add(parent);}
    String joined=paths.toString();if(joined.equals(watched))return;
    for(FileObserver observer:observers)observer.stopWatching();observers.clear();watched=joined;
    for(String path:paths){FileObserver observer=new FileObserver(path,FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO|FileObserver.DELETE){public void onEvent(int event,String file){if(file!=null)request();}};observer.startWatching();observers.add(observer);}
  }

  private void apply() {
    boolean changed=false;
    try{changed=chrome.apply(palette,"original".equals(mode));}catch(Exception e){fail("chrome apply",e);}
    for(int i=tiles.size()-1;i>=0;i--){Tile tile=tiles.get(i);if(tile.view.get()==null){tiles.remove(i);continue;}try{changed|=tile.apply();}catch(Exception e){fail("apply",e);}}
    if(changed)applies++;
  }

  private final class Tile {
    final WeakReference<View> view;final boolean toggle;final Field on,off,icon,progress;final int originalOn,originalOff;
    final ControlCorners.Tile shape;
    Drawable originalIcon,originalProgress;TintIcon wrapped;int progressSeed;
    Tile(View view,boolean toggle)throws Exception {
      this.view=new WeakReference<>(view);this.toggle=toggle;
      shape=corners.bind(view);
      on=toggle?field(view,"a"):null;off=toggle?field(view,"b"):null;icon=field(view,toggle?"c":"c");progress=toggle?null:field(view,"mProgressDrawable");
      originalOn=toggle?on.getInt(view):0;originalOff=toggle?off.getInt(view):0;
    }
    boolean apply()throws Exception {
      View owner=view.get();if(owner==null)return false;boolean original="original".equals(mode),changed=shape.apply();
      if(toggle){int a=original?originalOn:palette.active,b=original?originalOff:palette.off;if(on.getInt(owner)!=a){on.setInt(owner,a);changed=true;}if(off.getInt(owner)!=b){off.setInt(owner,b);changed=true;}}
      Drawable current=(Drawable)icon.get(owner);
      if(original){if(current==wrapped){originalIcon.clearColorFilter();originalIcon.setCallback(null);icon.set(owner,originalIcon);changed=true;}if(originalProgress!=null){originalProgress.clearColorFilter();originalProgress=null;changed=true;}}
      else {
        if(current!=null&&current!=wrapped){originalIcon=current;wrapped=new TintIcon(current.mutate(),owner,toggle);icon.set(owner,wrapped);changed=true;}
        if(!toggle){Drawable p=(Drawable)progress.get(owner);if(p!=null&&(p!=originalProgress||progressSeed!=palette.seed)){originalProgress=p;progressSeed=palette.seed;p.mutate().setColorFilter(palette.track,PorterDuff.Mode.SRC_IN);changed=true;}}
      }
      if(wrapped!=null&&wrapped.seed!=palette.seed){wrapped.seed=palette.seed;wrapped.color=0;changed=true;}
      if(changed)owner.invalidate();return changed;
    }
  }

  private final class TintIcon extends Drawable implements Drawable.Callback {
    final Drawable drawable;final WeakReference<View> owner;final boolean toggle;int color,seed;
    TintIcon(Drawable drawable,View view,boolean toggle){this.drawable=drawable;this.owner=new WeakReference<>(view);this.toggle=toggle;drawable.setCallback(this);}
    public void draw(Canvas canvas){View v=owner.get();int next=toggle&&v instanceof Checkable&&((Checkable)v).isChecked()?palette.onActive:palette.onOff;if(next!=color){drawable.setColorFilter(next,PorterDuff.Mode.SRC_IN);color=next;}drawable.setBounds(getBounds());drawable.draw(canvas);}
    public void setAlpha(int alpha){drawable.setAlpha(alpha);}
    public int getAlpha(){return drawable.getAlpha();}
    public void setColorFilter(ColorFilter filter){drawable.setColorFilter(filter);color=0;}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    public int getIntrinsicWidth(){return drawable.getIntrinsicWidth();}
    public int getIntrinsicHeight(){return drawable.getIntrinsicHeight();}
    public int getMinimumWidth(){return drawable.getMinimumWidth();}
    public int getMinimumHeight(){return drawable.getMinimumHeight();}
    public boolean isStateful(){return drawable.isStateful();}
    protected boolean onStateChange(int[] state){return drawable.setState(state);}
    protected boolean onLevelChange(int level){return drawable.setLevel(level);}
    public void invalidateDrawable(Drawable d){invalidateSelf();}
    public void scheduleDrawable(Drawable d,Runnable r,long when){scheduleSelf(r,when);}
    public void unscheduleDrawable(Drawable d,Runnable r){unscheduleSelf(r);}
  }

  private static Field field(Object value,String name)throws Exception {for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
  private static String hex(int color){return String.format("#%06X",color&0xffffff);}
  private void fail(String where,Exception e){error=where+": "+e;Log.e(TAG,error,e);}
  private JSONObject describe() {
    JSONObject result=new JSONObject();
    try {
      result.put("ready",true).put("mode",mode).put("key",key).put("style",style).put("source",source).put("fingerprint",fingerprint)
          .put("seed",hex(palette.seed)).put("active",hex(palette.active)).put("onActive",hex(palette.onActive)).put("off",hex(palette.off)).put("onOff",hex(palette.onOff))
          .put("computations",computations).put("reads",reads).put("computeMs",computeMs).put("applies",applies).put("error",error).put("faceListener",faceListener!=null).put("observers",observers.size())
          .put("helperVersion",6).put("corners",corners.isEnabled()).put("cornerPathBuilds",corners.builds).put("outerRadiusFit",ControlCorners.OUTER_RADIUS).put("chrome",chrome.describe());
      JSONArray list=new JSONArray();for(Tile tile:tiles){View v=tile.view.get();if(v==null)continue;JSONObject info=new JSONObject();info.put("id",v.getId()).put("class",v.getClass().getSimpleName()).put("shown",v.isShown()).put("alpha",v.getAlpha()).put("wrapped",tile.icon.get(v) instanceof TintIcon).put("shape",tile.shape.describe());if(tile.toggle)info.put("checked",((Checkable)v).isChecked()).put("on",hex(tile.on.getInt(v))).put("off",hex(tile.off.getInt(v)));list.put(info);}result.put("tiles",list);
    }catch(Exception e){try{result.put("statusError",e.toString());}catch(Exception ignored){}}
    return result;
  }
  private void saveStatus(){try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"watchface-theme-status.json"))){out.write(describe().toString().getBytes("UTF-8"));}catch(Exception e){Log.e(TAG,"status write failed",e);}}
}
