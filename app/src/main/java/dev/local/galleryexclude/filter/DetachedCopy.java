package dev.local.galleryexclude.filter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/** Copies every field of the verified Album DTO graph, including fields omitted by Parcelable. */
public final class DetachedCopy {
    private DetachedCopy() { }
    public static void verify(Class<?> root) throws ReflectiveOperationException {
        verify(root, root.getName(), new HashSet<>());
    }
    private static void verify(Class<?> type, String root, Set<Class<?>> seen) throws ReflectiveOperationException {
        if (immutable(type) || !seen.add(type)) return;
        if (!owned(type, root)) throw new IllegalArgumentException("UNSUPPORTED_MUTABLE_DTO_FIELD");
        type.getDeclaredConstructor();
        for (Class<?> parent=type;parent!=Object.class;parent=parent.getSuperclass())
            for(Field field:parent.getDeclaredFields())
                if (!Modifier.isStatic(field.getModifiers())) verify(field.getType(),root,seen);
    }
    public static Object copy(Object source, Class<?> root) throws ReflectiveOperationException {
        return copy(source,root.getName(),new IdentityHashMap<>());
    }
    private static Object copy(Object source, String root, IdentityHashMap<Object,Object> copies) throws ReflectiveOperationException {
        if (source==null || immutable(source.getClass())) return source;
        if (copies.containsKey(source)) return copies.get(source);
        Class<?> type=source.getClass();
        if (!owned(type,root)) throw new IllegalArgumentException("UNSUPPORTED_MUTABLE_DTO_FIELD");
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        Object detached=constructor.newInstance();copies.put(source,detached);
        for(Class<?> parent=type;parent!=Object.class;parent=parent.getSuperclass())
            for(Field field:parent.getDeclaredFields()) if(!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);field.set(detached,copy(field.get(source),root,copies));
            }
        return detached;
    }
    private static boolean owned(Class<?> type,String root) {
        return type.getName().equals(root) || type.getName().startsWith(root+"$");
    }
    private static boolean immutable(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type==String.class || type==Boolean.class || type==Character.class
                || (type.getName().startsWith("java.lang.") && Number.class.isAssignableFrom(type));
    }
}
