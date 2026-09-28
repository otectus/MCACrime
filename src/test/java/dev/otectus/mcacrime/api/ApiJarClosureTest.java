package dev.otectus.mcacrime.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compile-only API jar compiles on its own (0.7.6).
 *
 * <p>{@code build.gradle}'s {@code apiReadModel} lists the classes outside {@code api/} that exported
 * signatures name. This walks every public and protected signature of every exported class —
 * constructors, methods, fields, supertypes and generic arguments — and fails on any
 * {@code dev.otectus.mcacrime} type the jar would not carry, so a consumer implementing an API type
 * against the published jar never finds a parameter type missing. The same check MCA: Quests runs.
 */
class ApiJarClosureTest {

    private static final Path CLASSES = Path.of("build", "classes", "java", "main");
    private static final String PREFIX = "dev.otectus.mcacrime.";

    private static Set<String> exported() throws IOException {
        Set<String> out = new TreeSet<>();
        Path api = CLASSES.resolve("dev/otectus/mcacrime/api");
        try (Stream<Path> walk = Files.walk(api)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> CLASSES.relativize(p).toString().replace('\\', '/'))
                    .map(p -> p.substring(0, p.length() - ".class".length()).replace('/', '.'))
                    .map(ApiJarClosureTest::topLevel)
                    .forEach(out::add);
        }
        String build = Files.readString(Path.of("build.gradle"));
        Matcher block = Pattern.compile("def apiReadModel = \\[(.*?)\\n]", Pattern.DOTALL).matcher(build);
        assertTrue(block.find(), "build.gradle has no apiReadModel list");
        Matcher entry = Pattern.compile("'([^']+)'").matcher(block.group(1));
        while (entry.find()) {
            out.add(entry.group(1).replace('/', '.'));
        }
        return out;
    }

    private static String topLevel(String name) {
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    @Test
    @DisplayName("every type an exported signature names is exported too")
    void exportedSignaturesAreClosed() throws Exception {
        Set<String> exported = exported();
        Set<String> missing = new TreeSet<>();
        ClassLoader loader = getClass().getClassLoader();
        List<Class<?>> toScan = new ArrayList<>();
        for (String name : exported) {
            toScan.add(Class.forName(name, false, loader));
        }
        Set<Class<?>> seen = new HashSet<>();
        while (!toScan.isEmpty()) {
            Class<?> type = toScan.remove(toScan.size() - 1);
            if (!seen.add(type) || !visible(type.getModifiers()) && type.getEnclosingClass() != null) {
                continue;
            }
            Set<Type> named = new HashSet<>();
            named.add(type.getGenericSuperclass());
            named.addAll(List.of(type.getGenericInterfaces()));
            for (TypeVariable<?> variable : type.getTypeParameters()) {
                named.addAll(List.of(variable.getBounds()));
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (visible(constructor.getModifiers())) {
                    named.addAll(List.of(constructor.getGenericParameterTypes()));
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (visible(method.getModifiers()) && !method.isSynthetic()) {
                    named.add(method.getGenericReturnType());
                    named.addAll(List.of(method.getGenericParameterTypes()));
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (visible(field.getModifiers()) && !field.isSynthetic()) {
                    named.add(field.getGenericType());
                }
            }
            for (Class<?> nested : type.getDeclaredClasses()) {
                if (visible(nested.getModifiers())) {
                    toScan.add(nested);
                }
            }
            Set<Class<?>> classes = new HashSet<>();
            named.forEach(t -> collect(t, classes, new HashSet<>()));
            for (Class<?> referenced : classes) {
                String name = referenced.getName();
                if (name.startsWith(PREFIX) && !exported.contains(topLevel(name))) {
                    missing.add(topLevel(name) + "  (named by " + type.getName() + ")");
                }
            }
        }
        assertEquals(new TreeSet<>(), missing,
                "exported signatures name types the api jar would not carry; add them to apiReadModel");
    }

    private static boolean visible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void collect(Type type, Set<Class<?>> out, Set<Type> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        if (type instanceof Class<?> clazz) {
            out.add(clazz.isArray() ? clazz.getComponentType() : clazz);
        } else if (type instanceof ParameterizedType parameterized) {
            collect(parameterized.getRawType(), out, visited);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collect(argument, out, visited);
            }
        } else if (type instanceof GenericArrayType array) {
            collect(array.getGenericComponentType(), out, visited);
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                collect(bound, out, visited);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                collect(bound, out, visited);
            }
        } else if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                collect(bound, out, visited);
            }
        }
    }
}
