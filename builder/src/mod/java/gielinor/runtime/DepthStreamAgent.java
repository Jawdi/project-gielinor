package gielinor.runtime;

import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.*;
import java.nio.file.*;
import java.security.*;

/** Experimental, opt-in startup agent. Never rewrites projectzomboid.jar. */
public final class DepthStreamAgent {
    static final String TARGET="zombie/tileDepth/TileDepthTexture";
    static final String IDS="zombie/core/textures/TextureID";
    static final String TEX="zombie/core/textures/Texture";
    static final String SETS="zombie/tileDepth/TileDepthTextures";
    static final String SHEET="zombie/tileDepth/TilesetDepthTexture";
    static final String META="zombie/iso/IsoMetaGrid";
    static final ClassDesc MAP=ClassDesc.of("gielinor.runtime.WorldMapBinding");
    static final ClassDesc BRIDGE=ClassDesc.of("gielinor.runtime.DepthStreamRuntime");
    static final ClassDesc GPU=ClassDesc.of("gielinor.runtime.DepthGpuCache");
    static CodeTransform prepend(java.util.function.Consumer<CodeBuilder> prefix) {
        return CodeTransform.ofStateful(()->new CodeTransform() {
            boolean emitted;
            @Override public void accept(CodeBuilder builder,CodeElement element){if(!emitted){emitted=true;prefix.accept(builder);}builder.with(element);}
        });
    }
    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        install(Path.of(args), instrumentation);
    }
    /** Also supports loading through ZombieBuddy after game classes exist. */
    public static void install(Path root, Instrumentation instrumentation) throws Exception {
        if (!instrumentation.isRetransformClassesSupported())
            throw new IllegalStateException("The Java loader must support class retransformation");
        DepthStreamRuntime.initialize(root);
        // Pin to the exact installed class bytes; fail closed on another game version.
        var expected=new java.util.HashMap<String,String>();
        for(String name:java.util.List.of(TARGET,IDS,TEX,SETS,SHEET,META))try(var input=ClassLoader.getSystemResourceAsStream(name+".class")) {
            if(input==null)throw new IllegalStateException("Missing B42 class "+name);
            expected.put(name,java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            if(!expected.get(name).equals(DepthStreamRuntime.binding.getProperty("class."+name.replace('/','.'))))throw new IllegalStateException("Game class differs from validated streaming candidate: "+name);
        }
        var transformer = new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) throws IllegalClassFormatException {
                if(!expected.containsKey(name))return null;
                try {
                    String actual=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    // The VM can reorder constant-pool entries when reconstructing
                    // an already loaded class. Pin disk resources above, and check
                    // its schema before transforming a reconstructed definition.
                    if(type == null && !actual.equals(expected.get(name)))
                        throw new IllegalStateException("Another transformer changed the pinned depth class");
                    var model=ClassFile.of().parse(bytes);
                    if (type != null) try (var input=ClassLoader.getSystemResourceAsStream(name+".class")) {
                        var original=ClassFile.of().parse(input.readAllBytes());
                        var methods=model.methods().stream().map(m->m.methodName().stringValue()+m.methodType().stringValue()).collect(java.util.stream.Collectors.toSet());
                        var originalMethods=original.methods().stream().map(m->m.methodName().stringValue()+m.methodType().stringValue()).collect(java.util.stream.Collectors.toSet());
                        var fields=model.fields().stream().map(f->f.fieldName().stringValue()+f.fieldType().stringValue()).collect(java.util.stream.Collectors.toSet());
                        var originalFields=original.fields().stream().map(f->f.fieldName().stringValue()+f.fieldType().stringValue()).collect(java.util.stream.Collectors.toSet());
                        if(!methods.equals(originalMethods)||!fields.equals(originalFields))
                            throw new IllegalStateException("Loaded class schema differs from the pinned game class");
                    }
                    if(META.equals(name))return ClassFile.of().transformClass(model,(builder,element)->{
                        if(element instanceof MethodModel method&&
                           ((method.methodName().equalsString("getLotDirectories")&&method.methodType().equalsString("()Ljava/util/ArrayList;"))||method.methodName().equalsString("isValidSquare")||method.methodName().equalsString("isValidChunk")))
                            builder.transformMethod(method,(mb,me)->{
                                if(me instanceof CodeModel code)mb.withCode(b->{
                                    if(method.methodName().equalsString("getLotDirectories"))b.invokestatic(MAP,"selectedFolders",MethodTypeDesc.ofDescriptor("()Ljava/util/ArrayList;")).dup().ifThen(Opcode.IFNONNULL,yes->yes.areturn()).pop();
                                    else b.iload(1).iload(2).invokestatic(MAP,method.methodName().equalsString("isValidChunk")?"outsideChunk":"outsideSquare",MethodTypeDesc.ofDescriptor("(II)Z")).ifThen(Opcode.IFNE,yes->yes.iconst_0().ireturn());
                                    code.forEach(b::with);
                                });else mb.with(me);
                            });else builder.with(element);
                    });
                    if(SHEET.equals(name))return ClassFile.of().transformClass(model,(builder,element)->{
                        if(element instanceof MethodModel method&&method.methodName().equalsString("load"))builder.transformMethod(method,(mb,me)->{
                            if(me instanceof CodeModel code)mb.withCode(b->{b.aload(0).invokestatic(BRIDGE,"populateTileset",MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Z")).ifThen(Opcode.IFNE,yes->yes.return_());code.forEach(b::with);});else mb.with(me);
                        });else builder.with(element);
                    });
                    if(SETS.equals(name))return ClassFile.of().transformClass(model,(builder,element)->{
                        if(element instanceof MethodModel method&&method.methodName().equalsString("getTilesetRows"))builder.transformMethod(method,(mb,me)->{
                            if(me instanceof CodeModel code)mb.withCode(b->{b.aload(1).invokestatic(BRIDGE,"rows",MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)I")).dup().ifThen(Opcode.IFGE,yes->yes.ireturn()).pop();code.forEach(b::with);});else mb.with(me);
                        });else builder.with(element);
                    });
                    if(IDS.equals(name)||TEX.equals(name))return ClassFile.of().transformClass(model,(builder,element)->{
                        if(element instanceof MethodModel method&&java.util.Set.of("getID","bind","bindInternal").contains(method.methodName().stringValue()))
                            builder.transformMethod(method,(mb,me)->{
                                if(me instanceof CodeModel code)mb.withCode(b->{b.aload(0).invokestatic(GPU,TEX.equals(name)?"ensureTexture":"ensure",MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V"));code.forEach(b::with);});else mb.with(me);
                            });
                        else builder.with(element);
                    });
                    long loads=model.methods().stream().filter(m->m.methodName().equalsString("load")&&m.methodType().equalsString("([FLjava/nio/ByteBuffer;III)V")).count();
                    long gets=model.methods().stream().filter(m->m.methodName().equalsString("getTexture")&&m.methodType().equalsString("()Lzombie/core/textures/Texture;")).count();
                    if(loads!=1||gets!=1)throw new IllegalStateException("Unexpected B42 depth API");
                    var transformed=ClassFile.of().transformClass(model,(builder,element)->{
                        if(element instanceof MethodModel method && (method.methodName().equalsString("load")||method.methodName().equalsString("getTexture")||method.methodName().equalsString("lambda$updateGPUTexture$0"))) {
                            boolean loading=method.methodName().equalsString("load");
                            boolean getting=method.methodName().equalsString("getTexture");
                            builder.transformMethod(method,(mb,me)->{
                                if(me instanceof CodeModel code)mb.transformCode(code,CodeTransform.ofStateful(()->new CodeTransform(){
                                    boolean started;
                                    @Override public void accept(CodeBuilder b,CodeElement e){
                                        if(!started){started=true;
                                            if(loading)b.aload(0).invokestatic(BRIDGE,"deferLoad",MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Z")).ifThen(Opcode.IFNE,yes->yes.return_());
                                            else if(getting)b.aload(0).invokestatic(BRIDGE,"prepare",MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V"));
                                        }
                                        if(getting&&e instanceof java.lang.classfile.instruction.ReturnInstruction r&&r.opcode()==Opcode.ARETURN)
                                            b.dup().aload(0).swap().invokestatic(GPU,"register",MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/lang/Object;)V"));
                                        if(!loading&&!getting&&e instanceof java.lang.classfile.instruction.ReturnInstruction)
                                            b.aload(0).invokestatic(GPU,"uploaded",MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V"));
                                        b.with(e);
                                    }
                                }));else mb.with(me);
                            });
                        } else builder.with(element);
                    });
                    System.out.println("GIELINOR experimental lazy depth hooks installed; original game jar unchanged");
                    if(System.getProperty("gielinor.depth.dump")!=null)Files.write(Path.of(System.getProperty("gielinor.depth.dump")),transformed);
                    return transformed;
                } catch(Exception e) {
                    // Instrumentation ignores transformer exceptions. Invalid class bytes
                    // force class loading to stop instead of silently using eager loading.
                    e.printStackTrace();return new byte[]{0};
                }
            }
        };
        instrumentation.addTransformer(transformer, true);
        var loaded = new java.util.ArrayList<Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (expected.containsKey(type.getName().replace('.', '/'))) {
                if (!instrumentation.isModifiableClass(type))
                    throw new IllegalStateException("Cannot install Gielinor hooks in " + type.getName());
                loaded.add(type);
            }
        }
        if (!loaded.isEmpty()) instrumentation.retransformClasses(loaded.toArray(Class<?>[]::new));
    }
}
