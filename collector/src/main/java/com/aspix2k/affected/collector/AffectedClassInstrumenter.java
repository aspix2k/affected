package com.aspix2k.affected.collector;

import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.ConstantDynamic;
import org.jetbrains.org.objectweb.asm.FieldVisitor;
import org.jetbrains.org.objectweb.asm.Handle;
import org.jetbrains.org.objectweb.asm.Label;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.Type;
import org.jetbrains.org.objectweb.asm.commons.AdviceAdapter;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class AffectedClassInstrumenter {
    private static final String AGENT = Type.getInternalName(AffectedCollectorAgent.class);
    private static final String CLASS = "java/lang/Class";
    private static final String CLASS_LOADER = "java/lang/ClassLoader";
    private static final String HIT_DESCRIPTOR = "(Ljava/lang/Class;)V";
    private static final String EXECUTION_FIELD = "$affectedExecutionId";
    private static final String THREAD = "java/lang/Thread";

    private AffectedClassInstrumenter() {
    }

    static void initialize() throws Exception {
        ClassLoader loader = AffectedClassInstrumenter.class.getClassLoader();
        Class.forName(InstrumentingClassVisitor.class.getName(), true, loader);
        Class.forName(InstrumentingMethodVisitor.class.getName(), true, loader);
        new ClassWriter(0);
    }

    static byte[] instrument(
        ClassLoader loader,
        String className,
        byte[] bytes,
        boolean productionClass,
        AffectedCollectorAgent.CollectorState state
    ) {
        if (className == null || bytes == null) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new SafeClassWriter(reader, loader);
        InstrumentingClassVisitor visitor = new InstrumentingClassVisitor(writer, className, productionClass, state);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return visitor.changed ? writer.toByteArray() : null;
    }

    static byte[] instrumentRunNotifier(byte[] bytes) {
        if (bytes == null) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new RunNotifierVisitor(writer), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class RunNotifierVisitor extends ClassVisitor {
        private static final String DESCRIPTION = "Lorg/junit/runner/Description;";

        private RunNotifierVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public MethodVisitor visitMethod(
            int access,
            String name,
            String descriptor,
            String signature,
            String[] exceptions
        ) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("fireTestStarted".equals(name) && ("(" + DESCRIPTION + ")V").equals(descriptor)) {
                return new AdviceAdapter(Opcodes.ASM9, visitor, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadArg(0);
                        invokeStatic(Type.getType(AffectedCollectorAgent.class),
                            org.jetbrains.org.objectweb.asm.commons.Method.getMethod(
                                "void junit4Started(java.lang.Object)"));
                    }
                };
            }
            if ("fireTestFinished".equals(name) && ("(" + DESCRIPTION + ")V").equals(descriptor)) {
                return new AdviceAdapter(Opcodes.ASM9, visitor, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadArg(0);
                        invokeStatic(Type.getType(AffectedCollectorAgent.class),
                            org.jetbrains.org.objectweb.asm.commons.Method.getMethod(
                                "void junit4Finished(java.lang.Object)"));
                    }
                };
            }
            if ("fireTestSuiteFinished".equals(name) && ("(" + DESCRIPTION + ")V").equals(descriptor)) {
                return new AdviceAdapter(Opcodes.ASM9, visitor, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadArg(0);
                        invokeStatic(Type.getType(AffectedCollectorAgent.class),
                            org.jetbrains.org.objectweb.asm.commons.Method.getMethod(
                                "void junit4SuiteFinished(java.lang.Object)"));
                    }
                };
            }
            if ("fireTestRunFinished".equals(name) && "(Lorg/junit/runner/Result;)V".equals(descriptor)) {
                return new AdviceAdapter(Opcodes.ASM9, visitor, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        invokeStatic(Type.getType(AffectedCollectorAgent.class),
                            org.jetbrains.org.objectweb.asm.commons.Method.getMethod(
                                "void junit4RunFinished()"));
                    }
                };
            }
            return visitor;
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private static final String OBJECT = "java/lang/Object";
        private final ClassLoader loader;
        private final Map<String, ClassInfo> classes = new HashMap<String, ClassInfo>();

        private SafeClassWriter(ClassReader reader, ClassLoader loader) {
            super(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String first, String second) {
            if (first.equals(second)) return first;
            if (first.startsWith("[") || second.startsWith("[")) return OBJECT;
            if (isAssignableFrom(first, second)) return first;
            if (isAssignableFrom(second, first)) return second;
            ClassInfo firstInfo = classInfo(first);
            if (firstInfo.isInterface) return OBJECT;
            String current = firstInfo.superName;
            while (current != null) {
                if (isAssignableFrom(current, second)) return current;
                current = classInfo(current).superName;
            }
            return OBJECT;
        }

        private boolean isAssignableFrom(String target, String source) {
            return isAssignableFrom(target, source, new HashSet<String>());
        }

        private boolean isAssignableFrom(String target, String source, Set<String> visited) {
            if (target.equals(source) || OBJECT.equals(target)) return true;
            if (!visited.add(source) || visited.size() > 10_000) return false;
            ClassInfo sourceInfo = classInfo(source);
            if (sourceInfo.superName != null && isAssignableFrom(target, sourceInfo.superName, visited)) return true;
            for (String contract : sourceInfo.interfaces) {
                if (isAssignableFrom(target, contract, visited)) return true;
            }
            return false;
        }

        private ClassInfo classInfo(String internalName) {
            ClassInfo cached = classes.get(internalName);
            if (cached != null) return cached;
            String resource = internalName + ".class";
            InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
            if (input == null) throw new IllegalStateException("class hierarchy: " + internalName);
            try {
                ClassReader reader = new ClassReader(input);
                ClassInfo result = new ClassInfo(
                    reader.getSuperName(),
                    reader.getInterfaces(),
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0
                );
                classes.put(internalName, result);
                return result;
            } catch (Exception failure) {
                throw new IllegalStateException("class hierarchy: " + internalName, failure);
            } finally {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class ClassInfo {
        private final String superName;
        private final String[] interfaces;
        private final boolean isInterface;

        private ClassInfo(String superName, String[] interfaces, boolean isInterface) {
            this.superName = superName;
            this.interfaces = interfaces;
            this.isInterface = isInterface;
        }
    }

    private static final class InstrumentingClassVisitor extends ClassVisitor {
        private final String className;
        private final boolean productionClass;
        private final AffectedCollectorAgent.CollectorState state;
        private boolean changed;
        private boolean guarded;

        private InstrumentingClassVisitor(
            ClassVisitor delegate,
            String className,
            boolean productionClass,
            AffectedCollectorAgent.CollectorState state
        ) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
            this.productionClass = productionClass;
            this.state = state;
        }

        @Override
        public void visit(
            int version,
            int access,
            String name,
            String signature,
            String superName,
            String[] interfaces
        ) {
            guarded = productionClass && (access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) == 0;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (EXECUTION_FIELD.equals(name)) guarded = false;
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (productionClass && (access & Opcodes.ACC_NATIVE) != 0) state.nativeMethod();
            if (delegate == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return delegate;
            return new InstrumentingMethodVisitor(delegate, access, name, descriptor, this);
        }

        private void changed() {
            changed = true;
        }

        @Override
        public void visitEnd() {
            if (guarded) {
                FieldVisitor field = super.visitField(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    EXECUTION_FIELD,
                    "J",
                    null,
                    null
                );
                if (field != null) field.visitEnd();
                changed();
            }
            super.visitEnd();
        }
    }

    private static final class InstrumentingMethodVisitor extends AdviceAdapter {
        private final InstrumentingClassVisitor owner;
        private final int access;
        private final String methodName;

        private InstrumentingMethodVisitor(
            MethodVisitor delegate,
            int access,
            String name,
            String descriptor,
            InstrumentingClassVisitor owner
        ) {
            super(Opcodes.ASM9, delegate, access, name, descriptor);
            this.owner = owner;
            this.access = access;
            this.methodName = name;
        }

        @Override
        protected void onMethodEnter() {
            if (owner.productionClass
                && ("<init>".equals(methodName)
                    || "<clinit>".equals(methodName)
                    || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0)) {
                emitProductionHit(owner.className);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String targetOwner, String name, String descriptor) {
            emitHit(targetOwner);
            super.visitFieldInsn(opcode, targetOwner, name, descriptor);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode != Opcodes.NEW) emitHit(type);
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            emitType(Type.getType(descriptor));
            super.visitMultiANewArrayInsn(descriptor, dimensions);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof Type && emitClassLiteral((Type) value)) return;
            emitConstant(value);
            super.visitLdcInsn(value);
        }

        @Override
        public void visitMethodInsn(int opcode, String targetOwner, String name, String descriptor, boolean isInterface) {
            owner.state.staticReference(owner.className, targetOwner);
            if (opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL) emitHit(targetOwner);
            super.visitMethodInsn(opcode, targetOwner, name, descriptor, isInterface);
            emitReflectionResult(targetOwner, name, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            emitHandle(bootstrap);
            for (Object argument : arguments) emitConstant(argument);
            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
        }

        private boolean emitClassLiteral(Type type) {
            Type target = elementType(type);
            if (target.getSort() != Type.OBJECT || !owner.state.isProductionClass(target.getInternalName())) return false;
            super.visitLdcInsn(type);
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "hit", HIT_DESCRIPTOR, false);
            owner.changed();
            return true;
        }

        private void emitType(Type type) {
            Type target = elementType(type);
            if (target.getSort() == Type.OBJECT) emitHit(target.getInternalName());
        }

        private void emitHit(String internalName) {
            if (owner.className.equals(internalName) || !owner.state.isProductionClass(internalName)) return;
            super.visitLdcInsn(Type.getObjectType(internalName));
            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "hit", HIT_DESCRIPTOR, false);
            owner.changed();
        }

        private void emitProductionHit(String internalName) {
            if (owner.guarded) {
                int execution = newLocal(Type.LONG_TYPE);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "currentExecutionId", "()J", false);
                storeLocal(execution, Type.LONG_TYPE);
                loadLocal(execution, Type.LONG_TYPE);
                super.visitFieldInsn(Opcodes.GETSTATIC, internalName, EXECUTION_FIELD, "J");
                super.visitInsn(Opcodes.LCMP);
                Label current = new Label();
                super.visitJumpInsn(Opcodes.IFEQ, current);
                loadLocal(execution, Type.LONG_TYPE);
                super.visitFieldInsn(Opcodes.PUTSTATIC, internalName, EXECUTION_FIELD, "J");
                super.visitLdcInsn(Type.getObjectType(internalName));
                super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "hitProduction", HIT_DESCRIPTOR, false);
                super.visitLabel(current);
                owner.changed();
                return;
            }
            super.visitLdcInsn(Type.getObjectType(internalName));
            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "hitProduction", HIT_DESCRIPTOR, false);
            owner.changed();
        }

        private void emitHandle(Handle handle) {
            if (handle == null) return;
            emitHit(handle.getOwner());
        }

        private void emitConstant(Object value) {
            if (value instanceof Type) {
                emitType((Type) value);
            } else if (value instanceof Handle) {
                emitHandle((Handle) value);
            } else if (value instanceof ConstantDynamic) {
                ConstantDynamic dynamic = (ConstantDynamic) value;
                emitHandle(dynamic.getBootstrapMethod());
                for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                    emitConstant(dynamic.getBootstrapMethodArgument(index));
                }
            }
        }

        private void emitReflectionResult(String targetOwner, String name, String descriptor) {
            String hook = null;
            String hookDescriptor = null;
            if ((CLASS.equals(targetOwner) && "forName".equals(name))
                || (CLASS_LOADER.equals(targetOwner) && "loadClass".equals(name))) {
                hook = "hit";
                hookDescriptor = HIT_DESCRIPTOR;
            } else if (CLASS.equals(targetOwner) && ("getField".equals(name) || "getDeclaredField".equals(name))) {
                hook = "hitField";
                hookDescriptor = "(Ljava/lang/reflect/Field;)V";
            } else if (CLASS.equals(targetOwner) && ("getFields".equals(name) || "getDeclaredFields".equals(name))) {
                hook = "hitFields";
                hookDescriptor = "([Ljava/lang/reflect/Field;)V";
            } else if (CLASS.equals(targetOwner) && ("getMethod".equals(name) || "getDeclaredMethod".equals(name))) {
                hook = "hitMethod";
                hookDescriptor = "(Ljava/lang/reflect/Method;)V";
            } else if (CLASS.equals(targetOwner) && ("getMethods".equals(name) || "getDeclaredMethods".equals(name))) {
                hook = "hitMethods";
                hookDescriptor = "([Ljava/lang/reflect/Method;)V";
            } else if (CLASS.equals(targetOwner)
                && ("getConstructor".equals(name) || "getDeclaredConstructor".equals(name))) {
                hook = "hitConstructor";
                hookDescriptor = "(Ljava/lang/reflect/Constructor;)V";
            } else if (CLASS.equals(targetOwner)
                && ("getConstructors".equals(name) || "getDeclaredConstructors".equals(name))) {
                hook = "hitConstructors";
                hookDescriptor = "([Ljava/lang/reflect/Constructor;)V";
            } else if (CLASS.equals(targetOwner)
                && ("getClasses".equals(name) || "getDeclaredClasses".equals(name))) {
                hook = "hitClasses";
                hookDescriptor = "([Ljava/lang/Class;)V";
            }
            if (hook == null || Type.getReturnType(descriptor).getSort() == Type.VOID) return;
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, hook, hookDescriptor, false);
            owner.changed();
        }

        private static Type elementType(Type type) {
            return type.getSort() == Type.ARRAY ? type.getElementType() : type;
        }
    }
}
