package your.mod;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.lang.reflect.Field;
import java.util.Optional;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ReflectionUtil {

    public static Optional<Field> getDeclaredField(String fieldName, Object instance) {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            return Optional.of(field);
        } catch (NoSuchFieldException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getDeclaredFieldValue(Field field, Object instance) {
        field.setAccessible(true);
        try {
            return Optional.ofNullable((T) field.get(instance));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static <T> Optional<T> getDeclaredFieldValue(String fieldName, Object instance) {
        return getDeclaredField(fieldName, instance)
            .flatMap(field -> getDeclaredFieldValue(field, instance));
    }
}
