package dev.codex.watchtheme;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Process;
import android.system.Os;
import android.util.AtomicFile;
import android.util.Log;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Private, event-driven mailbox. Only Launcher uid and root can access it. */
final class ThemeBridge {
  private final Context context;
  private final Handler main;
  private final File directory;
  private final String session=UUID.randomUUID().toString();
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"theme-bridge"));
  private final FileObserver observer;
  private boolean busy;
  private String lastId="";

  ThemeBridge(Context context,Handler main)throws Exception {
    this.context=context;this.main=main;
    directory=new File(context.getFilesDir(),"watchworkshop-theme");
    if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Mailbox directory");
    Os.chmod(directory.getAbsolutePath(),0700);
    write("hello.json",new JSONObject().put("protocol",1).put("helperVersion",6).put("session",session).put("pid",Process.myPid()));
    observer=new FileObserver(directory.getAbsolutePath(),FileObserver.MOVED_TO){
      public void onEvent(int event,String file){if("request.json".equals(file))main.post(()->receive());}
    };
    observer.startWatching();
  }

  private void receive() {
    JSONObject request=null;
    try {
      request=read("request.json");
      String id=request.getString("id");
      if(!id.matches("[a-f0-9-]{36}")||id.equals(lastId))return;
      if(busy)return;
      if(request.getInt("protocol")!=1||!session.equals(request.getString("session")))throw new IOException("SESSION_CHANGED");
      long age=System.currentTimeMillis()-request.getLong("createdAt");
      if(age< -2000||age>15000)throw new IOException("EXPIRED");
      String action=request.getString("action");
      if(!"read".equals(action)&&!"preview".equals(action)&&!"apply".equals(action)&&!"rollback".equals(action))throw new IOException("INVALID_ACTION");
      lastId=id;busy=true;
      JSONObject before=safeState();
      JSONObject config="rollback".equals(action)?read("previous.json").getJSONObject("before"):request.optJSONObject("config");
      if("read".equals(action)){finish(request,true,"",before,null);return;}
      validate(config);
      final JSONObject req=request,cfg=config;
      final String mode=config.getString("mode");
      final int seed=parseSeed(config.getString("seedArgb"));
      final int fallback=parseSeed(before.getString("seed"));
      worker.execute(()->{
        ThemePalette palette=null;String problem="";
        try{palette="auto".equals(mode)?sample(fallback):ThemePalette.fromSeed(seed);}catch(Exception e){problem="SAMPLE_FAILED";Log.e("WatchfaceTheme","bridge sample failed",e);}
        final ThemePalette sampled=palette;final String issue=problem;
        main.post(()->{
          if(!issue.isEmpty()){finish(req,false,issue,null,null);return;}
          JSONObject prior=null;boolean attempted=false;
          try {
            if(System.currentTimeMillis()-req.getLong("createdAt")>15000)throw new IOException("EXPIRED");
            if("preview".equals(action)){finish(req,true,"",safeState(),paletteJson(sampled));return;}
            prior=safeState();
            if("apply".equals(action))write("previous.json",new JSONObject().put("before",configFromState(prior)));
            attempted=true;ControlTheme.managedSet(mode,seed,cfg.getBoolean("corners"),sampled);
            JSONObject after=safeState();
            if(!mode.equals(after.getString("mode"))||cfg.getBoolean("corners")!=after.getBoolean("corners")||("fixed".equals(mode)&&parseSeed(after.getString("seed"))!=seed))throw new IOException("VERIFY_FAILED");
            if("rollback".equals(action))new File(directory,"previous.json").delete();
            finish(req,true,"",after,paletteJson(sampled));
          }catch(Exception e){
            if(attempted&&prior!=null)try{int oldSeed=parseSeed(prior.getString("seed"));ControlTheme.managedSet(prior.getString("mode"),oldSeed,prior.getBoolean("corners"),ThemePalette.fromSeed(oldSeed));}catch(Exception restore){Log.e("WatchfaceTheme","automatic restore failed",restore);}
            finish(req,false,e.getMessage()==null?"APPLY_FAILED":e.getMessage(),null,null);
          }
        });
      });
    }catch(Exception e){if(request!=null)finish(request,false,e.getMessage()==null?"INVALID_REQUEST":e.getMessage(),null,null);}
  }

  private ThemePalette sample(int fallback)throws Exception {
    FaceSource source=FaceSource.resolve(context);Bitmap bitmap=source.bitmap();
    try{int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());return ThemePalette.fromPixels(pixels,fallback);}
    finally{bitmap.recycle();}
  }

  private void finish(JSONObject req,boolean ok,String code,JSONObject state,JSONObject preview) {
    try {
      JSONObject ack=new JSONObject().put("protocol",1).put("id",req.optString("id")).put("session",session).put("pid",Process.myPid()).put("ok",ok).put("code",code).put("completedAt",System.currentTimeMillis());
      if(state!=null)ack.put("state",state);
      if(preview!=null)ack.put("preview",preview);
      ack.put("canRollback",new File(directory,"previous.json").isFile());
      if(new File(directory,"previous.json").isFile())ack.put("rollbackConfig",read("previous.json").getJSONObject("before"));
      write("ack.json",ack);
    }catch(Exception e){Log.e("WatchfaceTheme","bridge acknowledgement failed",e);}
    busy=false;
  }

  private static JSONObject safeState()throws Exception {
    JSONObject full=new JSONObject(ControlTheme.status()),safe=new JSONObject();
    for(String field:new String[]{"ready","mode","seed","active","onActive","off","onOff","helperVersion","corners","applies"})if(full.has(field))safe.put(field,full.get(field));
    safe.put("error",full.optString("error").isEmpty()?"":"运行时读取或应用出现错误");
    return safe;
  }
  private static JSONObject configFromState(JSONObject state)throws Exception {
    return new JSONObject().put("mode",state.getString("mode")).put("seedArgb",String.format(java.util.Locale.ROOT,"#%08X",parseSeed(state.getString("seed")))).put("corners",state.getBoolean("corners"));
  }
  private static void validate(JSONObject config)throws Exception {
    if(config==null)throw new IOException("CONFIG_MISSING");
    String mode=config.getString("mode");if(!"auto".equals(mode)&&!"fixed".equals(mode)&&!"original".equals(mode))throw new IOException("INVALID_MODE");
    parseSeed(config.getString("seedArgb"));config.getBoolean("corners");
  }
  private static int parseSeed(String value)throws IOException {
    if(!value.matches("#[a-fA-F0-9]{6}|#FF[a-fA-F0-9]{6}"))throw new IOException("INVALID_SEED");
    return (int)Long.parseLong(value.substring(1),16)|0xff000000;
  }
  private static JSONObject paletteJson(ThemePalette p)throws Exception {
    JSONObject j=new JSONObject();
    String[] names={"seed","active","onActive","off","onOff","secondary","muted","status"};
    int[] colors={p.seed,p.active,p.onActive,p.off,p.onOff,p.secondary,p.muted,p.status};
    for(int i=0;i<names.length;i++)j.put(names[i],String.format(java.util.Locale.ROOT,"#%08X",colors[i]));return j;
  }
  private JSONObject read(String name)throws Exception {
    try(FileInputStream in=new AtomicFile(new File(directory,name)).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buffer=new byte[1024];int count;while((count=in.read(buffer))!=-1){out.write(buffer,0,count);if(out.size()>8192)throw new IOException("OVERSIZED_REQUEST");}
      return new JSONObject(new String(out.toByteArray(),"UTF-8"));
    }
  }
  private void write(String name,JSONObject value)throws Exception {
    AtomicFile file=new AtomicFile(new File(directory,name));FileOutputStream out=null;
    try{out=file.startWrite();out.write(value.toString().getBytes("UTF-8"));file.finishWrite(out);Os.chmod(new File(directory,name).getAbsolutePath(),0600);}
    catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
  }
}
