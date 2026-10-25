package dev.local.galleryexclude.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Exact hook signatures, plus the fields/methods the adapter actually reads at runtime. */
public final class TargetInterfaces {
    private TargetInterfaces() { }
    public static Method hookMethod(Class<?> owner, String name, Class<?> returns, Class<?>... parameters) throws NoSuchMethodException {
        for (Method member : owner.getDeclaredMethods()) {
            if (member.getName().equals(name) && member.getReturnType().equals(returns)
                    && Arrays.equals(member.getParameterTypes(), parameters)) {
                member.setAccessible(true); return member;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
    public static Method publicMethod(Class<?> owner, String name, Class<?> returns, Class<?>... parameters) throws NoSuchMethodException {
        Method member = owner.getMethod(name, parameters);
        if (!returns.isAssignableFrom(member.getReturnType()))
            throw new NoSuchMethodException(owner.getName() + "." + name + ":return");
        return member;
    }
    public static Field field(Class<?> owner, String name, Class<?> expected) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            Field field;
            try { field = type.getDeclaredField(name); }
            catch (NoSuchFieldException error) { continue; }
            if (expected != null && !expected.isAssignableFrom(field.getType()))
                throw new NoSuchFieldException(owner.getName() + "." + name + ":type");
            field.setAccessible(true); return field;
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }
}
