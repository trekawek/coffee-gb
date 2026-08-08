package eu.rekawek.coffeegb.harness;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Instruments every concrete production-core method and constructor at class-load time. */
public final class MethodCallAgent {

    private static final String CORE_PREFIX = "eu/rekawek/coffeegb/core/";
    private static final String HARNESS_PREFIX = "eu/rekawek/coffeegb/core/performance/";
    private static final String COUNTER = "eu/rekawek/coffeegb/harness/MethodCallCounter";

    private MethodCallAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new CoreMethodTransformer(), false);
    }

    private static final class CoreMethodTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) throws IllegalClassFormatException {
            if (className == null || !className.startsWith(CORE_PREFIX)
                    || className.startsWith(HARNESS_PREFIX)) {
                return null;
            }

            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, 0);
                ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor method = super.visitMethod(
                                access, name, descriptor, signature, exceptions);
                        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                                || "<clinit>".equals(name)) {
                            return method;
                        }
                        return new MethodVisitor(Opcodes.ASM9, method) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, COUNTER,
                                        "increment", "()V", false);
                            }
                        };
                    }
                };
                reader.accept(visitor, 0);
                return writer.toByteArray();
            } catch (Throwable throwable) {
                IllegalClassFormatException failure = new IllegalClassFormatException(
                        "Unable to instrument " + className + ": " + throwable);
                failure.initCause(throwable);
                throw failure;
            }
        }
    }
}
