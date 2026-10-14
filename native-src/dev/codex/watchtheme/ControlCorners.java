package dev.codex.watchtheme;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** AP control-panel geometry. Corner masks share the panel's opaque black background. */
final class ControlCorners {
  // The 402x476 compositor mask is measured separately; this is its nominal circular fit.
  static final int SCREEN_WIDTH=402,SCREEN_HEIGHT=476,OUTER_RADIUS=73,INSET=31,MINIMUM=32;
  private final Map<View,Grid> grids=new WeakHashMap<>();
  private boolean enabled=true;
  int builds;

  void setEnabled(boolean value){enabled=value;}
  boolean isEnabled(){return enabled;}

  Tile bind(View view)throws Exception {
    View parent=(View)view.getParent();
    String cls=parent.getClass().getName();
    if(!cls.equals("e.e.c.b.u.h.H")&&!cls.equals("e.e.c.b.u.h.J"))throw new IllegalArgumentException("Unexpected tile parent "+cls);
    Grid grid=grids.get(parent);if(grid==null){grid=new Grid(parent);grids.put(parent,grid);}
    return new Tile(view,grid);
  }

  private final class Grid {
    final WeakReference<View> view;
    final Field gap,top;
    final int originalGap,originalTop;
    Grid(View v)throws Exception{view=new WeakReference<>(v);gap=field(v,"e");top=field(v,"g");originalGap=gap.getInt(v);originalTop=top.getInt(v);}
    boolean apply()throws Exception {
      View v=view.get();if(v==null)return false;
      // Native H/J lay out and measure children themselves, so hit boxes move with the drawing.
      int nextGap=originalGap,nextTop=originalTop;
      boolean changed=gap.getInt(v)!=nextGap||top.getInt(v)!=nextTop;
      if(changed){gap.setInt(v,nextGap);top.setInt(v,nextTop);v.requestLayout();}
      return changed;
    }
  }

  final class Tile {
    final WeakReference<View> view;
    final Grid grid;
    final Drawable originalForeground;
    final boolean originalClip;
    final boolean originalOverlap;
    final Mask mask=new Mask();
    final Drawable foreground;
    boolean applied;
    int x,y,width,height;
    float[] radii=new float[4],verticalRadii=new float[4];
    Tile(View v,Grid grid){view=new WeakReference<>(v);this.grid=grid;originalForeground=v.getForeground();originalClip=v.getClipToOutline();originalOverlap=v.getHasOverlappingRendering();foreground=originalForeground==null?mask:new LayerDrawable(new Drawable[]{originalForeground,mask});}
    boolean apply()throws Exception {
      View v=view.get();if(v==null)return false;boolean changed=grid.apply();
      if(!enabled){if(applied){v.setForeground(originalForeground);v.setClipToOutline(originalClip);v.forceHasOverlappingRendering(originalOverlap);applied=false;changed=true;}return changed;}
      View parent=grid.view.get();if(parent==null||v.getWidth()==0||v.getHeight()==0)return changed;
      // Coordinates are relative to the panel, independent of page scroll and panel animation.
      View pager=(View)parent.getParent();
      x=v.getLeft();y=pager.getTop()+parent.getTop()+v.getTop();width=v.getWidth();height=v.getHeight();
      float[] axes=CornerGeometry.axes(SCREEN_WIDTH,SCREEN_HEIGHT,x,y,width,height,OUTER_RADIUS,MINIMUM);
      float[] next=Arrays.copyOfRange(axes,0,4),nextVertical=Arrays.copyOfRange(axes,4,8);
      if(!Arrays.equals(next,radii)||!Arrays.equals(nextVertical,verticalRadii)){radii=next;verticalRadii=nextVertical;mask.radii=next;mask.verticalRadii=nextVertical;mask.rebuild();v.invalidate();changed=true;}
      if(!applied||v.getForeground()!=foreground){v.setClipToOutline(false);v.setForeground(foreground);v.forceHasOverlappingRendering(true);applied=true;changed=true;}
      return changed;
    }
    JSONObject describe()throws Exception {
      JSONObject result=new JSONObject();result.put("enabled",applied).put("x",x).put("y",y).put("width",width).put("height",height)
          .put("tl",radii[0]).put("tr",radii[1]).put("br",radii[2]).put("bl",radii[3])
          .put("tlY",verticalRadii[0]).put("trY",verticalRadii[1]).put("brY",verticalRadii[2]).put("blY",verticalRadii[3]);return result;
    }
  }

  private final class Mask extends Drawable {
    final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path corners=new Path();
    float[] radii=new float[4],verticalRadii=new float[4];
    Mask(){paint.setColor(0xff000000);}
    protected void onBoundsChange(Rect bounds){rebuild();}
    void rebuild(){
      Rect bounds=getBounds();corners.reset();if(bounds.isEmpty())return;
      for(int corner=0;corner<4;corner++){
        double radius=radii[corner],vertical=verticalRadii[corner];if(radius<=0||vertical<=0)continue;
        float ox=(corner==1||corner==2)?bounds.right:bounds.left;
        float oy=corner>=2?bounds.bottom:bounds.top;
        float sx=(corner==1||corner==2)?-1:1,sy=corner>=2?-1:1;
        corners.moveTo(ox,oy);corners.lineTo(ox,oy+sy*(float)vertical);
        for(int step=1;step<=32;step++){double[] point=CornerGeometry.point(radius,step/32.0);corners.lineTo(ox+sx*(float)point[0],oy+sy*(float)(point[1]*vertical/radius));}
        corners.close();
      }
      builds++;invalidateSelf();
    }
    public void draw(Canvas canvas){canvas.drawPath(corners,paint);}
    public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    public void setColorFilter(ColorFilter ignored){}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }

  private static Field field(Object value,String name)throws Exception{
    for(Class<?> cls=value.getClass();cls!=null;cls=cls.getSuperclass())try{Field f=cls.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
    throw new NoSuchFieldException(name);
  }
}
