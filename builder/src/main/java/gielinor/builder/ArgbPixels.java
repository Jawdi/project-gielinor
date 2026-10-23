package gielinor.builder;
import java.awt.image.*;
/** Exact unpremultiplied ARGB access without per-pixel ColorModel conversions. */
final class ArgbPixels {
 static int[] read(BufferedImage source){
  var raster=source.getRaster();int w=source.getWidth(),h=source.getHeight();
  if(source.getType()==BufferedImage.TYPE_INT_ARGB&&raster.getDataBuffer() instanceof DataBufferInt data&&raster.getSampleModel() instanceof SinglePixelPackedSampleModel model&&data.getOffset()==0&&data.getData().length==w*h&&model.getScanlineStride()==w&&raster.getSampleModelTranslateX()==0&&raster.getSampleModelTranslateY()==0)return data.getData();
  if(source.getType()==BufferedImage.TYPE_4BYTE_ABGR&&!source.isAlphaPremultiplied()&&raster.getDataBuffer() instanceof DataBufferByte data&&raster.getSampleModel() instanceof ComponentSampleModel model&&data.getOffset()==0&&data.getData().length==w*h*4&&model.getPixelStride()==4&&model.getScanlineStride()==w*4&&raster.getSampleModelTranslateX()==0&&raster.getSampleModelTranslateY()==0&&java.util.Arrays.equals(model.getBandOffsets(),new int[]{3,2,1,0})){
   var bytes=data.getData();var out=new int[w*h];for(int i=0;i<out.length;i++){int p=i*4;out[i]=(bytes[p]&255)<<24|(bytes[p+3]&255)<<16|(bytes[p+2]&255)<<8|bytes[p+1]&255;}return out;
  }
  return source.getRGB(0,0,w,h,null,0,w);
 }
 static BufferedImage normalized(BufferedImage source){
  var out=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);System.arraycopy(read(source),0,((DataBufferInt)out.getRaster().getDataBuffer()).getData(),0,out.getWidth()*out.getHeight());return out;
 }
 /**
  * An 8-bit RGBA, non-interlaced PNG (how ImageIO writes TYPE_INT_ARGB images)
  * decoded straight into a TYPE_INT_ARGB image: the same pixels as
  * normalized(ImageIO.read(...)) without ImageIO's streams and intermediate
  * byte image. Null for any other kind of PNG; callers then use ImageIO.
  */
 private static final java.lang.invoke.VarHandle RGBA=java.lang.invoke.MethodHandles.byteArrayViewVarHandle(int[].class,java.nio.ByteOrder.BIG_ENDIAN);
 private static final ThreadLocal<byte[]> SCANLINES=ThreadLocal.withInitial(()->new byte[0]);
 private static final ThreadLocal<java.util.zip.Inflater> INFLATER=ThreadLocal.withInitial(java.util.zip.Inflater::new);
 static BufferedImage png(byte[] file){
  var in=java.nio.ByteBuffer.wrap(file);
  if(file.length<8||in.getLong()!=0x89504E470D0A1A0AL)return null;
  int w=0,h=0;var idat=new java.util.ArrayList<int[]>();
  while(in.remaining()>=12){
   int length=in.getInt(),type=in.getInt();if(length<0||length>in.remaining()-4)return null;
   if(type==0x49484452){
    if(length!=13)return null;w=in.getInt();h=in.getInt();int depth=in.get()&255,colour=in.get()&255,compression=in.get(),filter=in.get(),interlace=in.get();
    if(depth!=8||colour!=6||compression!=0||filter!=0||interlace!=0||w<=0||h<=0||(long)w*h>1<<24)return null;
   }else if(type==0x49444154){idat.add(new int[]{in.position(),length});in.position(in.position()+length);}
   else if(type==0x49454E44)break;
   else in.position(in.position()+length);
   in.getInt();
  }
  if(w==0)return null;
  // Filtered scanlines go into a reused per-thread buffer, and the image
  // data chunks are inflated in place rather than joined first.
  int stride=w*4,size=(stride+1)*h;byte[] raw=SCANLINES.get();if(raw.length<size)SCANLINES.set(raw=new byte[size]);
  var inflater=INFLATER.get();inflater.reset();
  try{
   int off=0,chunk=0;
   while(off<size&&!inflater.finished()){
    if(inflater.needsInput()){if(chunk==idat.size())return null;int[] c=idat.get(chunk++);inflater.setInput(file,c[0],c[1]);continue;}
    int n=inflater.inflate(raw,off,size-off);if(n==0&&inflater.needsDictionary())return null;off+=n;
   }
   if(off!=size)return null;
  }catch(java.util.zip.DataFormatException e){return null;}
  var out=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);int[] pixels=((DataBufferInt)out.getRaster().getDataBuffer()).getData();
  for(int y=0;y<h;y++){
   // One loop per PNG filter; the first row's "up" bytes are zero.
   int r=y*(stride+1)+1,p=r-(stride+1),filter=raw[r-1];
   switch(filter){
    case 0->{}
    case 1->{for(int i=4;i<stride;i++)raw[r+i]+=raw[r+i-4];}
    case 2->{if(y>0)for(int i=0;i<stride;i++)raw[r+i]+=raw[p+i];}
    case 3->{
     for(int i=0;i<4;i++)raw[r+i]+=(byte)((y>0?raw[p+i]&255:0)>>1);
     for(int i=4;i<stride;i++)raw[r+i]+=(byte)(((raw[r+i-4]&255)+(y>0?raw[p+i]&255:0))>>1);
    }
    case 4->{
     if(y==0){for(int i=4;i<stride;i++)raw[r+i]+=raw[r+i-4];}
     else{
      for(int i=0;i<4;i++)raw[r+i]+=raw[p+i];
      for(int i=4;i<stride;i++){
       int a=raw[r+i-4]&255,b=raw[p+i]&255,c=raw[p+i-4]&255,q=a+b-c,pa=Math.abs(q-a),pb=Math.abs(q-b),pc=Math.abs(q-c);
       raw[r+i]+=(byte)(pa<=pb&&pa<=pc?a:pb<=pc?b:c);
      }
     }
    }
    default->{return null;}
   }
   // RGBA bytes read as one big-endian int, rotated to ARGB.
   for(int px=0;px<w;px++)pixels[px+y*w]=Integer.rotateRight((int)RGBA.get(raw,r+px*4),8);
  }
  return out;
 }
}
