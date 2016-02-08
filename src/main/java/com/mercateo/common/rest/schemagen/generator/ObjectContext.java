package com.mercateo.common.rest.schemagen.generator;

import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javax.validation.Constraint;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.ws.rs.PathParam;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableList;
import com.googlecode.gentyref.GenericTypeReflector;
import com.mercateo.common.rest.schemagen.IgnoreInRestSchema;
import com.mercateo.common.rest.schemagen.PropertySubType;
import com.mercateo.common.rest.schemagen.PropertySubTypeMapper;
import com.mercateo.common.rest.schemagen.PropertyType;
import com.mercateo.common.rest.schemagen.PropertyTypeMapper;
import com.mercateo.common.rest.schemagen.SchemaPropertyContext;
import com.mercateo.common.rest.schemagen.SizeConstraints;
import com.mercateo.common.rest.schemagen.ValueConstraints;
import com.mercateo.common.rest.schemagen.generictype.GenericClass;
import com.mercateo.common.rest.schemagen.generictype.GenericType;
import com.mercateo.common.rest.schemagen.plugin.IndividualSchemaGenerator;
import com.mercateo.common.rest.schemagen.plugin.PropertySchema;
import com.mercateo.common.rest.schemagen.types.ObjectWithSchema;

public class ObjectContext<T> {

    private static final Set<Class<? extends Annotation>> IGNORE_ANNOTATIONS = new HashSet<>(Arrays
            .asList(JsonIgnore.class, IgnoreInRestSchema.class));

    private final GenericType<T> type;

    private final PropertyType propertyType;

    private final T defaultValue;

    private final List<T> allowedValues;

    private final boolean required;

    private final SizeConstraints sizeConstraints;

    private final ValueConstraints valueConstraints;

    private final Class<? extends IndividualSchemaGenerator> schemaGenerator;

    private T currentValue;
    private PropertySubType propertySubType;

    ObjectContext(GenericType<T> type, T defaultValue, List<T> allowedValues, boolean required,
                  SizeConstraints sizeConstraints,
                  ValueConstraints valueConstraints, Class<? extends IndividualSchemaGenerator> schemaGenerator, T currentValue) {
        this.type = requireNonNull(type);
        this.currentValue = currentValue;
        this.propertyType = PropertyTypeMapper.of(type);
        this.propertySubType = PropertySubTypeMapper.of(type, this.propertyType);
        this.defaultValue = defaultValue;
        this.allowedValues = allowedValues;
        this.required = required;
        this.sizeConstraints = sizeConstraints;
        this.valueConstraints = valueConstraints;
        this.schemaGenerator = schemaGenerator;
    }

    public static <T> Builder<T> buildFor(Type type, Class<T> clazz) {
        return new Builder<>(GenericType.of(type, clazz));
    }

    public static <T> Builder<T> buildFor(Class<T> clazz) {
        return new Builder<>(new GenericClass<>(clazz));
    }

    public static <T> Builder<T> buildFor(GenericType<T> type) {
        return new Builder<>(type);
    }

    private static <U, T extends U> ObjectContext<U> buildObjectContextForSuper(
            GenericType<U> superType, List<T> allowedValues, T defaultValue) {
        List<U> newAllowedValues = new ArrayList<>();
        if (allowedValues != null) {
            allowedValues.stream().forEach(newAllowedValues::add);
        }

        return ObjectContext.buildFor(superType).withDefaultValue(defaultValue).withAllowedValues(
                newAllowedValues).build();
    }

    public GenericType<T> getType() {
        return type;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public List<T> getAllowedValues() {
        return allowedValues;
    }

    public boolean isRequired() {
        return required;
    }

    public SizeConstraints getSizeConstraints() {
        return sizeConstraints;
    }

    public ValueConstraints getValueConstraints() {
        return valueConstraints;
    }

    public PropertyType getPropertyType() {
        return propertyType;
    }

    public PropertySubType getPropertySubType() {
        return propertySubType;
    }

    public Class<? extends IndividualSchemaGenerator> getSchemaGenerator() {
        return schemaGenerator;
    }

    public ObjectContext<?> forSuperType() {
        GenericType<? super T> superType = type.getSuperType();
        if (superType != null) {
            return buildObjectContextForSuper(superType, allowedValues, defaultValue);
        } else {
            return null;
        }
    }

    public ObjectContext<?> getContained() {
        final GenericType<?> containedType = type.getContainedType();
        return ObjectContext.buildFor(containedType).build();
    }

    @SuppressWarnings("unchecked")
    public <U> ObjectContext<U> forField(Field field) {
        final GenericType<U> fieldType = GenericType.of(GenericTypeReflector.getExactFieldType(
                field, type.getType()), (Class<U>) field.getType());
        final Builder<U> builder = ObjectContext.buildFor(fieldType);

        if (defaultValue != null) {
            try {
                builder.withDefaultValue((U) field.get(defaultValue));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        if (allowedValues != null && !fieldType.getRawType().isPrimitive()) {
            if (!field.isAccessible()) {
                field.setAccessible(true);
            }
            List<U> newAllowedValues = new ArrayList<>();
            allowedValues.stream().forEach(x -> addToAllowedValues(field, newAllowedValues, x));
            builder.withAllowedValues(newAllowedValues);
        }

        if (isRequired(field)) {
            builder.setRequired();
        }

        determineConstraints(Size.class, field, SizeConstraints::new).ifPresent(builder::withSizeConstraints);
        builder.withValueConstraints(new ValueConstraints(
                determineConstraints(Max.class, field, Max::value),
                determineConstraints(Min.class, field, Min::value)));

        final PropertySchema schemaGenerator = field.getAnnotation(PropertySchema.class);
        if (schemaGenerator != null) {
            builder.withSchemaGenerator(schemaGenerator.schemaGenerator());
        }

        return builder.build();
    }

    private boolean isRequired(Field field) {
        if (field.isAnnotationPresent(NotNull.class)) {
            return true;
        }
        Annotation[] annotations = field.getAnnotations();
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(Constraint.class) && annotationType
                    .isAnnotationPresent(NotNull.class)) {
                return true;
            }
        }
        return false;
    }

    private <U, C extends Annotation> Optional<U> determineConstraints(Class<C> clazz, Field field, Function<C, U> callback) {
        C constraint = field.getAnnotation(clazz);
        if (constraint != null) {
            return Optional.of(callback.apply(constraint));
        }
        for (Annotation annotation : field.getAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(Constraint.class) && annotationType.isAnnotationPresent(clazz)) {
                return Optional.of(callback.apply(annotationType.getAnnotation(clazz)));
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private <U> void addToAllowedValues(Field field, List<U> newAllowedValues, T x) {
        try {
            if (x != null) {
                newAllowedValues.add((U) field.get(x));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean isApplicable(Field field, SchemaPropertyContext context) {
        return !Modifier.isStatic(field.getModifiers()) && //
                !field.isSynthetic() && //
                !IGNORE_ANNOTATIONS.stream().anyMatch(a -> field.getAnnotation(a) != null) && //
                isApplicableFor(field, context) && //
                isApplicableForPathParam(field);
    }

    private boolean isApplicableForPathParam(Field field) {
        PathParam pathParamAnnotation = field.getAnnotation(PathParam.class);
        if (pathParamAnnotation == null) {
            return true;
        }
        if (currentValue == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            return (field.get(currentValue) == null);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            return false;
        }
    }

    private boolean isApplicableFor(Field field, SchemaPropertyContext context) {
        return field.getDeclaringClass().equals(ObjectWithSchema.class)
                || context.isFieldApplicable(field);
    }

    public Class<?> getRawType() {
        return type.getRawType();
    }

    public static class Builder<T> {

        private final GenericType<T> type;

        private T defaultValue;

        private List<T> allowedValues;

        private T currentValue;

        private boolean required;

        private SizeConstraints sizeConstraints = SizeConstraints.empty();

        private ValueConstraints valueConstraints = ValueConstraints.empty();

        private Class<? extends IndividualSchemaGenerator> schemaGenerator;

        private Builder(GenericType<T> type) {
            this.type = type;
        }

        public Builder<T> withDefaultValue(T defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder<T> withAllowedValues(List<T> allowedValues) {
            this.allowedValues = allowedValues;
            return this;
        }

        public Builder<T> withAllowedValue(T allowedValue) {
            this.allowedValues = ImmutableList.of(allowedValue);
            return this;
        }

        public Builder<T> withCurrentValue(T currentValue) {
            this.currentValue = currentValue;
            return this;
        }

        public ObjectContext<T> build() {
            return new ObjectContext<>(type, defaultValue, allowedValues, required, sizeConstraints, valueConstraints,
                    schemaGenerator, currentValue);
        }

        Builder<T> setRequired() {
            required = true;
            return this;
        }

        Builder<T> withSizeConstraints(SizeConstraints sizeConstraints) {
            this.sizeConstraints = sizeConstraints;
            return this;
        }

        Builder<T> withValueConstraints(ValueConstraints valueConstraints) {
            this.valueConstraints = valueConstraints;
            return this;
        }

        Builder<T> withSchemaGenerator(Class<? extends IndividualSchemaGenerator> schemaGenerator) {
            this.schemaGenerator = schemaGenerator;
            return this;
        }
    }
}
