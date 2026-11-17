package fullbright.port;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public final class Agent implements ClassFileTransformer {
    private final Port port = new Port();

    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new Agent());
        System.out.println("[Fullbright] Client patch registered. Toggle with G.");
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> previousClass,
                            ProtectionDomain domain, byte[] bytes) {
        if (name == null || !port.targets(name)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode owner = new ClassNode();
            reader.accept(owner, 0);
            port.apply(owner);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            owner.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException error) {
            System.err.println("[Fullbright] Could not patch " + name);
            error.printStackTrace();
            return null;
        }
    }
}
