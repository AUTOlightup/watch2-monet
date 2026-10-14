package dev.codex.watchtheme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** Native control-center chrome; updates only during its existing view draws. */
final class ControlChrome {
  private static final int ERROR=0xffe86057;
  private final Context context;
  private final List<Item> top=new ArrayList<>(),details=new ArrayList<>();
  private final Map<View,Item> records=new WeakHashMap<>();
  private WeakReference<View> net=new WeakReference<>(null),detailRoot=new WeakReference<>(null);
  private ViewTreeObserver.OnPreDrawListener draws;
  private View.OnAttachStateChangeListener attach;
  private ThemePalette palette;
  private boolean original,snapshot;
  int updates;

  ControlChrome(Context context){this.context=context;}
  void clearTop(){top.clear();net=new WeakReference<>(null);}
  void collect(View view)throws Exception {
    String cls=view.getClass().getName(),id=name(view);
    if(cls.endsWith(".NetSignalView"))net=new WeakReference<>(view);
    if(cls.endsWith(".BatteryPowerView"))add(top,view,"battery");
    else if("tile_indicator".equals(id))add(top,view,"dots");
    else if(view instanceof ImageView && (cls.endsWith(".StatusBarTopImageView")||"nfc_view".equals(id)||"gps".equals(id)||"battery_charge".equals(id)))add(top,view,id);
    else if(view instanceof TextView&&"tv_mode_change_content".equals(id))add(top,view,"notice");
  }
  private void add(List<Item> list,View view,String role)throws Exception {
    for(Item i:list)if(i.view.get()==view)return;
    Item item=records.get(view);if(item==null){item=new Item(view,role);records.put(view,item);}list.add(item);
  }
  boolean apply(ThemePalette next,boolean nativeMode)throws Exception {
    palette=next;original=nativeMode;boolean changed=applyList(top,nativeMode||snapshot);
    changed|=applyList(details,nativeMode);if(changed)updates++;return changed;
  }
  void beginDetails()throws Exception {snapshot=true;applyList(top,true);}
  void endDetails()throws Exception {snapshot=false;if(palette!=null)apply(palette,original);}
  void bindDetails(View root)throws Exception {
    clearDetails();detailRoot=new WeakReference<>(root);
    View close=root.findViewById(id("close"));if(close instanceof TextView)add(details,close,"action");
    View container=root.findViewById(id("linear_layout"));
    if(container instanceof ViewGroup){ViewGroup group=(ViewGroup)container;List<?> roles=(List<?>)field(container,"a").get(container);
      for(int i=0;i<group.getChildCount()&&i<roles.size();i++){
        View row=group.getChildAt(i);String role=String.valueOf(roles.get(i));
        View title=row.findViewById(id("information_title")),des=row.findViewById(id("information_des")),image=row.findViewById(id("information_img"));
        if(title instanceof TextView)add(details,title,"title");
        if(des instanceof TextView)add(details,des,"des:"+role);
        if(image instanceof ImageView)add(details,image,"img:"+role);
      }
    }
    draws=()->{View v=detailRoot.get();if(v!=null&&v.isShown()&&palette!=null)try{if(applyList(details,original))updates++;}catch(Exception e){android.util.Log.e("WatchfaceTheme","detail apply",e);}return true;};
    attach=new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){clearDetails();}};
    root.getViewTreeObserver().addOnPreDrawListener(draws);root.addOnAttachStateChangeListener(attach);
    if(palette!=null)applyList(details,original);
  }
  private void clearDetails(){View old=detailRoot.get();if(old!=null){if(draws!=null&&old.getViewTreeObserver().isAlive())old.getViewTreeObserver().removeOnPreDrawListener(draws);if(attach!=null)old.removeOnAttachStateChangeListener(attach);}details.clear();detailRoot=new WeakReference<>(null);draws=null;attach=null;}
  private boolean applyList(List<Item> list,boolean restore)throws Exception {boolean changed=false;for(int i=list.size()-1;i>=0;i--){Item item=list.get(i);View v=item.view.get();if(v==null){list.remove(i);continue;}changed|=item.apply(v,restore);}return changed;}
  private int phoneState()throws Exception {View v=net.get();return v==null?0:field(v,"s").getInt(v);}
  private int color(String role)throws Exception {
    if("action".equals(role))return palette.active;
    if("title".equals(role))return palette.secondary;
    if("notice".equals(role))return palette.onOff;
    // Preserve native disconnected/HFP warning colors. Other top glyphs
    // are near white; charging lightning alone keeps the full accent.
    if("phone_connect_view".equals(role)&&phoneState()!=1)return 0;
    if(!role.startsWith("des:")&&!role.startsWith("img:"))return "battery_charge".equals(role)?palette.active:palette.status;
    if("iv_mobile_network_type".equals(role)||"iv_mobile_leave".equals(role)||"img:mobile".equals(role)||"img:battery".equals(role))return palette.active;
    if(role.endsWith("phone_connect")){return phoneState()==1?palette.active:ERROR;}
    if(role.endsWith("wifi")||role.endsWith("nfc")||role.endsWith("gps")||role.endsWith("airplane")||role.endsWith("microphone")||role.endsWith("hd"))return palette.active;
    if(role.startsWith("des:"))return palette.onOff;
    return palette.status;
  }
  private boolean mobileInactive(TextView text){String t=text.getText().toString();return t.equals(string("status_bar_infomation_mobile_no_signal"))||t.equals(string("status_bar_infomation_mobile_error"));}
  private String string(String name){int i=context.getResources().getIdentifier(name,"string",context.getPackageName());return i==0?"":context.getString(i);}
  private int id(String name){return context.getResources().getIdentifier(name,"id",context.getPackageName());}
  private static String name(View view){try{return view.getResources().getResourceEntryName(view.getId());}catch(Exception e){return "";}}

  private final class Item {
    final WeakReference<View> view;final String role;
    final ColorStateList textColors;final ColorFilter imageFilter;
    final Field dot,trace;final int originalDot,originalTrace;
    final Paint[] paints;final int[] paintColors;
    WeakReference<Drawable> imageDrawable=new WeakReference<>(null);
    int appliedColor;boolean filtered;
    Item(View v,String role)throws Exception {
      view=new WeakReference<>(v);this.role=role;
      textColors=v instanceof TextView?((TextView)v).getTextColors():null;
      imageFilter=v instanceof ImageView?((ImageView)v).getColorFilter():null;
      if("dots".equals(role)){dot=field(v,"c");trace=field(v,"g");originalDot=dot.getInt(v);originalTrace=trace.getInt(v);}else{dot=trace=null;originalDot=originalTrace=0;}
      if("battery".equals(role)){paints=new Paint[4];paintColors=new int[4];for(int i=0;i<4;i++){paints[i]=(Paint)field(v,String.valueOf((char)('a'+i))).get(v);paintColors[i]=paints[i].getColor();}}else{paints=null;paintColors=null;}
    }
    boolean apply(View v,boolean restore)throws Exception {
      boolean changed=false;
      if(dot!=null){int d=restore?originalDot:palette.muted,t=restore?originalTrace:palette.active;
        if(dot.getInt(v)!=d){v.getClass().getMethod("setPageIndicatorDotsColor",int.class).invoke(v,d);changed=true;}
        if(trace.getInt(v)!=t){v.getClass().getMethod("setTraceDotColor",int.class).invoke(v,t);changed=true;}
      }else if(paints!=null){for(int i=0;i<4;i++){int c=restore?paintColors[i]:palette.status;if(paints[i].getColor()!=c){paints[i].setColor(c);changed=true;}}}
      else if(v instanceof TextView){TextView tv=(TextView)v;int c=restore?textColors.getColorForState(tv.getDrawableState(),textColors.getDefaultColor()):color(role);
        if(!restore&&"des:mobile".equals(role)&&mobileInactive(tv))c=palette.secondary;
        if(restore){if(tv.getTextColors()!=textColors){tv.setTextColor(textColors);changed=true;}}
        else if(tv.getCurrentTextColor()!=c){tv.setTextColor(c);changed=true;}
      }else if(v instanceof ImageView){ImageView image=(ImageView)v;int c=restore?0:color(role);
        // Untinted warning vectors otherwise share a resizable bitmap cache
        // with their smaller top-bar copies. Isolate each native drawable too.
        Drawable current=image.getDrawable();
        if(current!=null&&current!=imageDrawable.get()){current.mutate();imageDrawable=new WeakReference<>(current);changed=true;}
        // Original red phone icons (including HFP) remain exact native drawables.
        if("img:phone_connect".equals(role)&&phoneState()!=1)c=0;
        if(c==0){if(filtered){image.setColorFilter(imageFilter);filtered=false;appliedColor=0;changed=true;}}
        else if(!filtered||appliedColor!=c){if("iv_mobile_leave".equals(role)||"iv_wifi".equals(role)||"img:mobile".equals(role)||"img:wifi".equals(role))image.setColorFilter(grayFilter(c));else image.setColorFilter(c,PorterDuff.Mode.SRC_IN);filtered=true;appliedColor=c;changed=true;}
      }
      if(changed)v.invalidate();return changed;
    }
  }
  // Native signal vectors use 0x99 for lit bars and 0x3d for unlit bars.
  // Scaling channels preserves signal strength; SRC_IN would flatten both.
  private static ColorFilter grayFilter(int color){float r=((color>>16)&255)/153f,g=((color>>8)&255)/153f,b=(color&255)/153f;return new ColorMatrixColorFilter(new float[]{r,0,0,0,0, 0,g,0,0,0, 0,0,b,0,0, 0,0,0,1,0});}
  private static Field field(Object v,String name)throws Exception {for(Class<?> c=v.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
  JSONObject describe()throws Exception {JSONObject o=new JSONObject();o.put("top",top.size()).put("details",details.size()).put("detailAttached",detailRoot.get()!=null).put("updates",updates);if(palette!=null)o.put("secondary",hex(palette.secondary)).put("muted",hex(palette.muted)).put("status",hex(palette.status));return o;}
  private static String hex(int color){return String.format("#%06X",color&0xffffff);}
}
