// WatchWorkshop standalone Monet, OPPO Watch2 A.92. Control-center appearance only.
// No NFC click replacement, WatchHide, QQ, blue point or global View hooks.
Java.perform(function () {
  const G = Java.use('e.e.c.b.u.h.G');
  const Presenter = Java.use('e.e.c.b.u.e.b');
  const View = Java.use('android.view.View');
  const Thread = Java.use('android.app.ActivityThread');
  const File = Java.use('java.io.File');
  const Log = Java.use('android.util.Log');
  const app = Thread.currentApplication();
  let theme = null;
  function log(e) { Log.e('WatchMonet', String(e)); }
  function field(o, name) {
    let c = o.getClass();
    while (c) { try { const f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (_) { c = c.getSuperclass(); } }
    throw new Error('Missing field ' + name);
  }
  function mark(error) {
    try {
      const process = Java.use('android.os.Process');
      const out = Java.use('java.io.FileOutputStream').$new(File.$new(app.createDeviceProtectedStorageContext().getFilesDir(), 'watch-monet-bridge.json'));
      try { out.write(Java.use('java.lang.String').$new(JSON.stringify({version:1,pid:process.myPid(),error:String(error||'')})).getBytes('UTF-8')); }
      finally { out.close(); }
    } catch (e) { log(e); }
  }
  function ensure() {
    if (theme) return theme;
    const cache = File.$new(app.getCodeCacheDir(), 'watchworkshop-monet-v6');
    if (!cache.exists() && !cache.mkdir()) throw new Error('Cannot create code cache');
    const loader = Java.use('dalvik.system.DexClassLoader').$new('/data/local/tmp/watchworkshop-runtime/theme.jar',cache.getAbsolutePath(),null,app.getClassLoader());
    const candidate = Java.ClassFactory.get(loader).use('dev.codex.watchtheme.ControlTheme');
    candidate.start(app);
    // ControlTheme restores persisted mode/seed/corners itself, including fixed mode.
    theme = candidate; mark(''); return theme;
  }
  function bind(g) {
    const kept = Java.retain(g);
    Java.scheduleOnMainThread(function () {
      try {
        const bridge=ensure();
        const id=app.getResources().getIdentifier('status_bar','id',app.getPackageName());
        const root=kept.findViewById.overload('int').call(kept,id);
        if(root)bridge.bind(Java.cast(root,View));
      } catch(e) { log(e); mark(e); }
      finally { kept.$dispose(); }
    });
  }
  const render=G.a.overload('java.util.concurrent.CopyOnWriteArrayList');
  render.implementation=function(list){const result=render.call(this,list);try{bind(this);}catch(e){log(e);}return result;};
  const init=G.initView.overload('android.view.View');
  init.implementation=function(view){const result=init.call(this,view);try{bind(this);}catch(e){log(e);}return result;};
  const details=Presenter.a.overload('android.view.View');
  details.implementation=function(view){
    let bridge=null;
    try{bridge=ensure();bridge.beginDetails();}catch(e){log(e);}
    try{
      const result=details.call(this,view);
      try{if(bridge){const d=field(this,'f');if(d){const root=field(d,'a');if(root)bridge.bindDetails(Java.cast(root,View));}}}catch(e){log(e);}
      return result;
    }finally{if(bridge)bridge.endDetails();}
  };
  Java.scheduleOnMainThread(function(){try{ensure();}catch(e){log(e);mark(e);}});
  let found=0;
  Java.choose('e.e.c.b.u.h.G',{onMatch:function(g){if(found++===0)bind(g);},onComplete:function(){}});
  rpc.exports={status:function(){return new Promise(resolve=>Java.scheduleOnMainThread(function(){try{resolve(JSON.parse(String(ensure().status())));}catch(e){resolve({error:String(e)});}}));}};
});
