package io.nozdormu.spi.error;

public enum InjectionProcessErrorType {

    ROOT_PACKAGE_NOT_EXIST(-60637, "can't find root package"),
    CANNOT_PARSER_SOURCE_CODE(-60638, "can't parser source code: %s"),
    CANNOT_GET_COMPILATION_UNIT(-60639, "can't get compilation unit of %s"),
    CLASS_NOT_EXIST(-60640, "class not exist in: %s"),
    PUBLIC_CLASS_NOT_EXIST(-60641, "public class not exist in: %s"),
    PUBLIC_ANNOTATION_NOT_EXIST(-60642, "public annotation not exist in: %s"),
    CONSTRUCTOR_NOT_EXIST(-60643, "can't find constructor of %s"),
    PROVIDER_TYPE_NOT_EXIST(-60644, "can't find type argument of provider"),
    INSTANCE_TYPE_NOT_EXIST(-60645, "can't find type argument of instance"),
    MODULE_PROVIDERS_METHOD_NOT_EXIST(-60646, "can't find module class providers method of %s"),
    COMPONENT_GET_METHOD_NOT_EXIST(-60647, "can't find component class get method of %s"),
    TYPE_ARGUMENT_NOT_EXIST(-60649, "can't find type argument"),
    ANNOTATION_NOT_EXIST(-60650, "annotation not exist in: %s"),

    CONFIG_PROPERTIES_PREFIX_NOT_EXIST(-60700, "prefix not exist in @ConfigProperties in: %s"),
    CONFIG_PROPERTY_NOT_EXIST(-60701, "@ConfigProperty not exist on: %s"),

    UNKNOWN(-60999, "unknown injection error");

    private final int code;
    private final String description;
    private Object[] variables;

    InjectionProcessErrorType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public int getCode() {
        return code;
    }

    public InjectionProcessErrorType bind(Object... variables) {
        this.variables = variables;
        return this;
    }

    @Override
    public String toString() {
        return code + ": " + String.format(description, variables);
    }
}
