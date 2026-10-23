package gielinor.builder;
import net.runelite.cache.definitions.ObjectDefinition;

/** Source offsets are signed16 and translate AFTER mirror/rotation in LocModelLoader.
 * Renderers rotate internally, so translate by the inverse-rotated offset here. */
final class SourcePlacement {
 static int signed16(int value){return (short)value;}
 static void diagonalAttachment(Mesh mesh,boolean mirror,int outsideDisplacement){
  double angle=mirror?-Math.PI/4:Math.PI/4,c=Math.cos(angle),s=Math.sin(angle);
  for(var v:mesh.vertices){double x=v[0];v[0]=c*x+s*v[2]+45+outsideDisplacement;v[2]=c*v[2]-s*x+(mirror?1:-1)*(45+outsideDisplacement);}
 }
 static void doubleAttachment(Mesh mesh,boolean mirror,int displacement){
  // Source type8 uses two opposing shape4 models, BOTH translated by the
  // same supporting-wall displacement (rather than pushing them apart).
  diagonalAttachment(mesh,mirror,0);
  int vertices=mesh.vertices.size(),faces=mesh.faces.size();
  for(int i=0;i<vertices;i++){var v=mesh.vertices.get(i);mesh.vertices.add(new double[]{-v[0],v[1],-v[2]});}
  for(int i=0;i<faces;i++){
   var f=mesh.faces.get(i);mesh.faces.add(new int[]{f[0]+vertices,f[1]+vertices,f[2]+vertices});
   mesh.colours.add(mesh.colours.get(i));mesh.alphas.add(i<mesh.alphas.size()?mesh.alphas.get(i):0);
  }
  for(var v:mesh.vertices){v[0]+=displacement;v[2]+=(mirror?1:-1)*displacement;}
 }
 static void offsets(Mesh mesh,ObjectDefinition def,int turn,boolean mirror,double height){
  double x=signed16(def.getOffsetX()),z=signed16(def.getOffsetY());
  for(int t=0;t<(turn&3);t++){double old=x;x=-z;z=old;}
  if(mirror)z=-z;
  for(var v:mesh.vertices){v[0]+=x;v[1]+=signed16(def.getOffsetHeight())+height;v[2]+=z;}
 }
}
