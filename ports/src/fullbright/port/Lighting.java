package fullbright.port;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class Lighting implements Opcodes {
    private static final String TOGGLE = "fullbright/port/Toggle";

    private Lighting() {}

    public static MethodNode lightmap(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            if (hasString(method, "lightTex") || hasString(method, "Lightmap shader not loaded")
                    || (hasString(method, "LightmapInfo") && findCall(method, "createRenderPass") != null)) {
                return method;
            }
        }
        return null;
    }

    public static MethodNode keyboard(ClassNode owner) {
        boolean keyboard = false;
        for (MethodNode method : owner.methods) {
            keyboard |= hasString(method, "debug.crash.message") || hasString(method, "debug.crash.warning")
                    || hasString(method, "debug.reload_chunks.message");
        }
        if (!keyboard) {
            return null;
        }
        for (MethodNode method : owner.methods) {
            if (method.desc.equals("(JIIII)V") || method.desc.matches("\\(JIL[^;]+;\\)V")) {
                if ((method.access & (ACC_STATIC | ACC_SYNTHETIC)) == 0) {
                    return method;
                }
            }
        }
        return null;
    }

    public static void patchKeyboard(MethodNode method, String keyAccessor, int keyCode) {
        InsnList code = new InsnList();
        if (method.desc.equals("(JIIII)V")) {
            code.add(new VarInsnNode(ILOAD, 3));
            code.add(new VarInsnNode(ILOAD, 5));
        } else {
            String keyEvent = method.desc.substring(4, method.desc.indexOf(';'));
            code.add(new VarInsnNode(ALOAD, 4));
            code.add(new MethodInsnNode(INVOKEVIRTUAL, keyEvent, keyAccessor, "()I", false));
            code.add(new VarInsnNode(ILOAD, 3));
        }
        code.add(new LdcInsnNode(keyCode));
        code.add(new MethodInsnNode(INVOKESTATIC, TOGGLE, "key", "(III)V", false));
        method.instructions.insert(code);
    }

    public static String keyEventClass(MethodNode method) {
        return method.desc.equals("(JIIII)V") ? "" : method.desc.substring(4, method.desc.indexOf(';'));
    }

    public static String patchLightmap(ClassNode owner, MethodNode method, boolean lwjgl2) {
        if (lwjgl2) {
            InsnList code = new InsnList();
            code.add(new IntInsnNode(BIPUSH, 34));
            code.add(new MethodInsnNode(INVOKESTATIC, "org/lwjgl/input/Keyboard", "isKeyDown", "(I)Z", false));
            code.add(new MethodInsnNode(INVOKESTATIC, TOGGLE, "legacyKey", "(Z)V", false));
            method.instructions.insert(code);
        }

        if (findCall(method, "createRenderPass") != null) {
            patchGpu(owner, method);
            return "gpu";
        }
        if (hasString(method, "Lightmap shader not loaded")) {
            patchFramebuffer(owner, method);
            return "framebuffer";
        }

        List<AbstractInsnNode> pixels = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) {
            if (node.getOpcode() == IASTORE) {
                pixels.add(node);
            } else if (node instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) node;
                if ((call.getOpcode() == INVOKEVIRTUAL || call.getOpcode() == INVOKESPECIAL)
                        && call.desc.equals("(III)V")) {
                    pixels.add(call);
                }
            }
        }
        if (pixels.size() != 1) {
            throw new IllegalStateException(owner.name + ": expected one lightmap pixel store, got " + pixels.size());
        }
        method.instructions.insertBefore(pixels.get(0),
                new MethodInsnNode(INVOKESTATIC, TOGGLE, "pixel", "(I)I", false));
        return "pixels";
    }

    private static void patchFramebuffer(ClassNode owner, MethodNode method) {
        for (MethodNode constructor : owner.methods) {
            if (!constructor.name.equals("<init>")) {
                continue;
            }
            for (AbstractInsnNode node : constructor.instructions) {
                if (!(node instanceof MethodInsnNode) || !((MethodInsnNode) node).desc.equals("(FFFF)V")) {
                    continue;
                }
                AbstractInsnNode receiver = node;
                for (int i = 0; i < 5; i++) {
                    receiver = previous(receiver);
                }
                AbstractInsnNode load = previous(receiver);
                AbstractInsnNode nextLoad = nextInsn(node);
                AbstractInsnNode nextReceiver = nextInsn(nextLoad);
                AbstractInsnNode clear = nextInsn(nextReceiver);
                if (receiver instanceof FieldInsnNode && load.getOpcode() == ALOAD
                        && nextReceiver instanceof FieldInsnNode && clear instanceof MethodInsnNode
                        && ((MethodInsnNode) clear).desc.equals("()V")
                        && ((FieldInsnNode) receiver).name.equals(((FieldInsnNode) nextReceiver).name)) {
                    InsnList code = new InsnList();
                    code.add(new VarInsnNode(ALOAD, 0));
                    code.add(receiver.clone(null));
                    code.add(clear.clone(null));
                    useWhiteTexture(method, code);
                    return;
                }
            }
        }
        throw new IllegalStateException(owner.name + ": lightmap framebuffer clear missing");
    }

    private static void patchGpu(ClassNode owner, MethodNode method) {
        for (MethodNode constructor : owner.methods) {
            if (!constructor.name.equals("<init>")) {
                continue;
            }
            MethodInsnNode clear = findCall(constructor, "clearColorTexture");
            MethodInsnNode device = findCall(constructor, "getDevice");
            MethodInsnNode encoder = findCall(constructor, "createCommandEncoder");
            if (clear == null || device == null || encoder == null) {
                continue;
            }
            AbstractInsnNode color = previous(clear);
            AbstractInsnNode texture = previous(color);
            if (!(texture instanceof FieldInsnNode) || texture.getOpcode() != GETFIELD) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(device.clone(null));
            code.add(encoder.clone(null));
            code.add(new VarInsnNode(ALOAD, 0));
            code.add(texture.clone(null));
            if (clear.desc.endsWith(";I)V")) {
                code.add(new InsnNode(ICONST_M1));
            } else if (color.getOpcode() == GETSTATIC && clear.desc.endsWith(";Lorg/joml/Vector4fc;)V")) {
                code.add(color.clone(null));
            } else {
                throw new IllegalStateException(owner.name + ": unexpected GPU clear color type");
            }
            code.add(clear.clone(null));
            useWhiteTexture(method, code);
            return;
        }
        throw new IllegalStateException(owner.name + ": GPU lightmap clear missing");
    }

    private static void useWhiteTexture(MethodNode method, InsnList clear) {
        LabelNode vanilla = new LabelNode();
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(INVOKESTATIC, TOGGLE, "enabled", "()Z", false));
        code.add(new JumpInsnNode(IFEQ, vanilla));
        code.add(clear);
        code.add(new InsnNode(RETURN));
        code.add(vanilla);
        code.add(new FrameNode(F_SAME, 0, null, 0, null));
        method.instructions.insert(code);
    }

    private static MethodInsnNode findCall(MethodNode method, String name) {
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof MethodInsnNode && ((MethodInsnNode) node).name.equals(name)) {
                return (MethodInsnNode) node;
            }
        }
        return null;
    }

    private static boolean hasString(MethodNode method, String value) {
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof LdcInsnNode && value.equals(((LdcInsnNode) node).cst)) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode previous(AbstractInsnNode node) {
        do {
            node = node.getPrevious();
        } while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static AbstractInsnNode nextInsn(AbstractInsnNode node) {
        do {
            node = node.getNext();
        } while (node != null && node.getOpcode() < 0);
        return node;
    }
}
