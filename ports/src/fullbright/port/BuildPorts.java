package fullbright.port;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class BuildPorts implements Opcodes {
    private static final String PACKAGE = "fullbright/port/";
    private static final Remapper SHADE_ASM = new Remapper() {
        @Override
        public String map(String name) {
            return name.startsWith("org/objectweb/asm/") ? PACKAGE + "internal/" + name : name;
        }
    };

    public static void main(String[] arguments) throws Exception {
        Path work = Paths.get(arguments[0]);
        Path output = Paths.get(arguments[1]);
        byte[] asmLicense = Files.readString(Paths.get(arguments[2]), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(output);
        int failures = 0;
        for (String row : Files.readAllLines(work.resolve("build.tsv"), StandardCharsets.UTF_8)) {
            String[] cells = row.split("\t", -1);
            try {
                build(work, output, cells[0], cells[1], asmLicense);
            } catch (Exception error) {
                System.err.println("Failed Minecraft " + cells[0]);
                error.printStackTrace();
                failures++;
            }
        }
        if (failures != 0) {
            throw new IllegalStateException(failures + " Minecraft builds failed");
        }
    }

    private static void build(Path work, Path output, String version, String mode, byte[] asmLicense) throws Exception {
        Path cache = work.resolve("data");
        boolean agent = mode.equals("java-agent");
        Path client = cache.resolve(version + ".jar");
        try (ZipFile vanilla = new ZipFile(client.toFile())) {
            ClassNode renderer = null;
            ClassNode keyHandler = null;
            Enumeration<? extends ZipEntry> entries = vanilla.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.indexOf('$') >= 0
                        || (name.indexOf('/') >= 0 && !name.startsWith("net/minecraft/client/"))) {
                    continue;
                }
                byte[] bytes = read(vanilla, entry);
                String constants = new String(bytes, StandardCharsets.ISO_8859_1);
                if (!constants.contains("lightTex") && !constants.contains("LightmapInfo")
                        && !constants.contains("Lightmap shader not loaded") && !constants.contains("debug.")) {
                    continue;
                }
                ClassNode owner = parse(bytes);
                if (Lighting.lightmap(owner) != null) {
                    if (renderer != null) {
                        throw new IllegalStateException("Ambiguous lightmap target: " + renderer.name + ", " + owner.name);
                    }
                    renderer = owner;
                }
                if (Lighting.keyboard(owner) != null) {
                    if (keyHandler != null) {
                        throw new IllegalStateException("Ambiguous keyboard target: " + keyHandler.name + ", " + owner.name);
                    }
                    keyHandler = owner;
                }
            }

            if (renderer == null || (needsGlfw(version) && keyHandler == null)) {
                throw new IllegalStateException("Client lightmap or keyboard target not found");
            }
            Remapper names = mode.equals("fabric") || mode.equals("legacy-fabric")
                    ? readMappings(cache.resolve(mode + "-" + version + ".jar")) : new Remapper() {};
            MethodNode render = Lighting.lightmap(renderer);
            ClassNode renderTarget = remap(renderer, names);
            MethodNode renderHook = mappedMethod(renderer, renderTarget, render, names);
            String style = Lighting.patchLightmap(renderTarget, renderHook, keyHandler == null);
            verify(renderTarget, renderHook);
            StringBuilder metadata = new StringBuilder();
            settings(metadata, "lightmap", renderTarget, renderHook);

            ClassNode keyboardTarget = null;
            if (keyHandler != null) {
                MethodNode key = Lighting.keyboard(keyHandler);
                keyboardTarget = remap(keyHandler, names);
                MethodNode keyHook = mappedMethod(keyHandler, keyboardTarget, key, names);
                String event = Lighting.keyEventClass(key);
                String accessor = event.isEmpty() ? "" : keyAccessor(vanilla, event);
                accessor = event.isEmpty() ? "" : names.mapMethodName(event, accessor, "()I");
                Lighting.patchKeyboard(keyHook, accessor);
                verify(keyboardTarget, keyHook);
                settings(metadata, "keyboard", keyboardTarget, keyHook);
                metadata.append("keyboard.accessor=").append(accessor).append('\n');
            } else {
                metadata.append("keyboard.class=\n");
            }

            String file = "fullbright-" + version + "-" + mode + ".jar";
            Path temporary = output.resolve(file + ".tmp");
            try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(temporary))) {
                add(jar, "fullbright-port.properties", metadata.toString());
                for (String type : new String[]{"Lighting", "Toggle", "Port", agent ? "Agent" : "MixinPlugin"}) {
                    String name = PACKAGE + type + ".class";
                    byte[] bytes = Files.readAllBytes(work.resolve("classes").resolve(name));
                    add(jar, name, agent ? shade(bytes) : bytes);
                }
                if (agent) {
                    add(jar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nPremain-Class: fullbright.port.Agent\r\n\r\n");
                    add(jar, "META-INF/licenses/ASM.txt", asmLicense);
                    embedAsm(jar, cache.resolve("asm-9.10.1.jar"));
                    embedAsm(jar, cache.resolve("asm-tree-9.10.1.jar"));
                } else {
                    String namesJson = "\"LightmapMixin\"";
                    mixin(jar, "LightmapMixin", renderTarget.name);
                    if (keyboardTarget != null) {
                        mixin(jar, "KeyboardMixin", keyboardTarget.name);
                        namesJson += ", \"KeyboardMixin\"";
                    }
                    add(jar, "fullbright-port.mixins.json", "{\"required\":true,\"minVersion\":\"0.8\","
                            + "\"package\":\"fullbright.port.mixin\",\"plugin\":\"fullbright.port.MixinPlugin\","
                            + "\"client\":[" + namesJson + "]}\n");
                    add(jar, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"fullbright_for_embeddium\","
                            + "\"name\":\"Fullbright\",\"version\":\"2.0.0\",\"license\":\"MIT\","
                            + "\"environment\":\"client\",\"mixins\":[\"fullbright-port.mixins.json\"],"
                            + "\"depends\":{\"minecraft\":\"=" + version + "\",\"fabricloader\":\">=0.16.0\"}}\n");
                }
            }
            Files.move(temporary, output.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println(version + " " + mode + " " + style + " " + renderTarget.name);
        }
    }

    private static boolean needsGlfw(String version) {
        return !(version.startsWith("1.8.") || version.matches("1\\.(9|10|11|12)(\\..*)?"));
    }

    private static void settings(StringBuilder properties, String prefix, ClassNode target, MethodNode method) {
        properties.append(prefix).append(".class=").append(target.name).append('\n')
                .append(prefix).append(".method=").append(method.name).append('\n')
                .append(prefix).append(".descriptor=").append(method.desc).append('\n');
    }

    private static String keyAccessor(ZipFile client, String eventName) throws IOException {
        ClassNode event = parse(read(client, client.getEntry(eventName + ".class")));
        if (event.recordComponents == null || event.recordComponents.isEmpty()) {
            throw new IllegalStateException("Expected a key event record: " + eventName);
        }
        String field = event.recordComponents.get(0).name;
        for (MethodNode method : event.methods) {
            if (!method.desc.equals("()I")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() == GETFIELD && ((FieldInsnNode) instruction).name.equals(field)) {
                    return method.name;
                }
            }
        }
        throw new IllegalStateException("Key event record accessor missing: " + eventName);
    }

    private static void verify(ClassNode owner, MethodNode method) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        ClassNode readBack = parse(writer.toByteArray());
        MethodNode hook = mappedMethod(owner, readBack, method, new Remapper() {});
        new Analyzer<>(new BasicVerifier()).analyze(readBack.name, hook);
    }

    private static MethodNode mappedMethod(ClassNode source, ClassNode target, MethodNode method, Remapper names) {
        String name = names.mapMethodName(source.name, method.name, method.desc);
        String descriptor = names.mapMethodDesc(method.desc);
        for (MethodNode candidate : target.methods) {
            if (candidate.name.equals(name) && candidate.desc.equals(descriptor)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Missing mapped method " + target.name + "." + name + descriptor);
    }

    private static Remapper readMappings(Path file) throws IOException {
        Map<String, String> classes = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        try (ZipFile jar = new ZipFile(file.toFile())) {
            String text = new String(read(jar, jar.getEntry("mappings/mappings.tiny")), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String[] columns = line.strip().split("\t");
                switch (columns[0]) {
                    case "CLASS":
                        classes.put(columns[1], columns[2]);
                        break;
                    case "FIELD":
                        fields.put(columns[1] + " " + columns[3] + " " + columns[2], columns[4]);
                        break;
                    case "METHOD":
                        methods.put(columns[1] + " " + columns[3] + " " + columns[2], columns[4]);
                        break;
                    default:
                        break;
                }
            }
        }
        return new Remapper() {
            @Override
            public String map(String name) {
                return classes.getOrDefault(name, name);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return fields.getOrDefault(owner + " " + name + " " + descriptor, name);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return methods.getOrDefault(owner + " " + name + " " + descriptor, name);
            }
        };
    }

    private static void mixin(ZipOutputStream jar, String shortName, String target) throws IOException {
        String name = PACKAGE + "mixin/" + shortName;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, name, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("targets");
        targets.visit(null, target.replace('/', '.'));
        targets.visitEnd();
        mixin.visit("remap", false);
        mixin.visitEnd();
        writer.visitEnd();
        add(jar, name + ".class", writer.toByteArray());
    }

    private static ClassNode remap(ClassNode source, Remapper names) {
        ClassNode target = new ClassNode();
        source.accept(new ClassRemapper(target, names));
        return target;
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] shade(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, SHADE_ASM), 0);
        return writer.toByteArray();
    }

    private static void embedAsm(ZipOutputStream output, Path dependency) throws IOException {
        try (ZipFile jar = new ZipFile(dependency.toFile())) {
            for (ZipEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (name.startsWith("org/objectweb/asm/") && name.endsWith(".class")) {
                    add(output, PACKAGE + "internal/" + name, shade(read(jar, entry)));
                }
            }
        }
    }

    private static byte[] read(ZipFile jar, ZipEntry entry) throws IOException {
        if (entry == null) {
            throw new IOException("Expected ZIP entry missing in " + jar.getName());
        }
        try (java.io.InputStream input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static void add(ZipOutputStream output, String name, String text) throws IOException {
        add(output, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void add(ZipOutputStream output, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(315532800000L);
        output.putNextEntry(entry);
        output.write(data);
        output.closeEntry();
    }
}
