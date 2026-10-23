package gielinor.builder;
import java.util.*;import net.runelite.cache.definitions.ObjectDefinition;
/** Geometry evidence for ordinary fountains; dry fountainheads are not assumed wet. */
final class FountainWater {
 record Pool(double sourceY,double relativeY,double area,double minX,double maxX,double minZ,double maxZ){}
 static final class Plane {
  double area,minX=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,minZ=minX,maxZ=maxX;
  void add(Mesh mesh,int[] face,double value){area+=value;for(int vi:face){var v=mesh.vertices.get(vi);minX=Math.min(minX,v[0]);maxX=Math.max(maxX,v[0]);minZ=Math.min(minZ,v[2]);maxZ=Math.max(maxZ,v[2]);}}
 }
 static Pool find(ObjectDefinition def,int type,Mesh mesh){
  if(type!=10&&type!=11)return null;
  String name=String.valueOf(def.getName()).toLowerCase(Locale.ROOT);
  if(!name.contains("fountain")||!name.matches(".*\\bfountain\\b.*"))return null;
  double offset=SourcePlacement.signed16(def.getOffsetHeight());var planes=new TreeMap<Double,Plane>();
  for(int f=0;f<mesh.faces.size();f++){
   int colour=mesh.colours.get(f),r=colour>>16&255,g=colour>>8&255,b=colour&255;
   if(g<=r*1.1||b<=r*1.15||b<g*.95||b<60||mesh.alphas.get(f)>=230)continue;
   var face=mesh.faces.get(f);var a=mesh.vertices.get(face[0]);var c=mesh.vertices.get(face[1]);var d=mesh.vertices.get(face[2]);
   if(Math.max(a[1],Math.max(c[1],d[1]))-Math.min(a[1],Math.min(c[1],d[1]))>1)continue;
   double y=Math.rint((a[1]+c[1]+d[1])/3*2)/2,relative=y+offset;
   if(relative< -160||relative>32)continue;
   double area=Math.abs((c[0]-a[0])*(d[2]-a[2])-(d[0]-a[0])*(c[2]-a[2]))*.5;
   if(area>0)planes.computeIfAbsent(y,k->new Plane()).add(mesh,face,area);
  }
  var best=planes.entrySet().stream().filter(e->e.getValue().area>=1024).max(Map.Entry.<Double,Plane>comparingByValue(Comparator.comparingDouble(p->p.area)).thenComparingDouble(Map.Entry::getKey)).orElse(null);
  if(best==null)return null;var p=best.getValue();return new Pool(best.getKey(),best.getKey()+offset,p.area,p.minX,p.maxX,p.minZ,p.maxZ);
 }
 static int liftBelowFloor(Mesh mesh,Pool pool){
  if(pool==null||pool.relativeY<0)return 0;
  double lift=Math.ceil(pool.relativeY)+1;int moved=0;
  // Water and its one-unit-higher foam retain their relative positions. The
  // bounded footprint avoids changing unrelated masonry at the same height.
  for(var v:mesh.vertices)if(v[1]>=pool.sourceY-1&&v[1]<=pool.sourceY&&v[0]>=pool.minX-16&&v[0]<=pool.maxX+16&&v[2]>=pool.minZ-16&&v[2]<=pool.maxZ+16){v[1]-=lift;moved++;}
  return moved;
 }
}
