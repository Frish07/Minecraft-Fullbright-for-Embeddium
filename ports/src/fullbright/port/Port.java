package fullbright.port;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class Port {
    private final Properties settings = new Properties();

    public Port() {
        try (InputStream resource = Port.class.getResourceAsStream("/fullbright-port.properties")) {
            if (resource == null) {
                throw new IllegalStateException("Missing Fullbright build metadata");
            }
            settings.load(resource);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read Fullbright build metadata", error);
        }
    }

    public boolean targets(String name) {
        return name.equals(settings.getProperty("lightmap.class")) || name.equals(settings.getProperty("keyboard.class"));
    }

    public void apply(ClassNode owner) {
        if (owner.name.equals(settings.getProperty("lightmap.class"))) {
            Lighting.patchLightmap(owner, method(owner, "lightmap"), settings.getProperty("keyboard.class").isEmpty());
        } else if (owner.name.equals(settings.getProperty("keyboard.class"))) {
            Lighting.patchKeyboard(method(owner, "keyboard"), settings.getProperty("keyboard.accessor"));
        }
    }

    private MethodNode method(ClassNode owner, String target) {
        String name = settings.getProperty(target + ".method");
        String descriptor = settings.getProperty(target + ".descriptor");
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException("Missing Fullbright hook " + owner.name + "." + name + descriptor);
    }
}
