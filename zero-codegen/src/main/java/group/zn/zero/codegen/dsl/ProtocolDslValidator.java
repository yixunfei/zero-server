package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.error.CodegenErrorCode;
import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolEnumValue;
import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolMethod;
import group.zn.zero.codegen.model.ProtocolType;
import group.zn.zero.codegen.model.ProtocolTypeKind;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 协议 DSL 文档校验器。
 *
 * @author zn
 */
public final class ProtocolDslValidator {

    /**
     * 禁止实例化。
     */
    private ProtocolDslValidator() {
    }

    /**
     * 校验协议 DSL 文档。
     *
     * @param document 协议 DSL 文档；不可为空。
     * @throws ZeroException 文档结构非法时抛出，必须绑定 ErrorCode。
     */
    public static void validate(final ProtocolDslDocument document) {
        Objects.requireNonNull(document, "document");
        Set<String> typeNames = new HashSet<>();
        Set<String> enumNames = validateEnums(document.enums(), typeNames);
        Set<String> messageNames = validateMessages(document.messages(), typeNames);
        validateProtocols(document.protocols());
        validateMethods(document.methods(), document.protocols(), messageNames);
        for (ProtocolMessage message : document.messages()) {
            for (ProtocolField field : message.fields()) {
                validateType(field.type(), enumNames, messageNames);
            }
        }
    }

    private static Set<String> validateEnums(
            final List<ProtocolEnum> enums,
            final Set<String> typeNames) {
        Set<String> enumNames = new HashSet<>();
        for (ProtocolEnum item : enums) {
            if (!enumNames.add(item.name())) {
                fail("duplicate enum name: " + item.name());
            }
            if (!typeNames.add(item.name())) {
                fail("duplicate type name: " + item.name());
            }
            Set<String> valueNames = new HashSet<>();
            Set<Integer> values = new HashSet<>();
            for (ProtocolEnumValue value : item.values()) {
                if (!valueNames.add(value.name())) {
                    fail("duplicate enum value name: " + item.name() + "." + value.name());
                }
                if (!values.add(value.value())) {
                    fail("duplicate enum value: " + item.name() + "=" + value.value());
                }
            }
        }
        return enumNames;
    }

    private static Set<String> validateMessages(
            final List<ProtocolMessage> messages,
            final Set<String> typeNames) {
        Set<String> messageNames = new HashSet<>();
        for (ProtocolMessage message : messages) {
            if (!messageNames.add(message.name())) {
                fail("duplicate message name: " + message.name());
            }
            if (!typeNames.add(message.name())) {
                fail("duplicate type name: " + message.name());
            }
            Set<Integer> orders = new HashSet<>();
            Set<String> fieldNames = new HashSet<>();
            int previousOrder = 0;
            for (ProtocolField field : message.fields()) {
                if (!orders.add(field.order())) {
                    fail("duplicate field order: " + message.name() + "." + field.order());
                }
                if (!fieldNames.add(field.name())) {
                    fail("duplicate field name: " + message.name() + "." + field.name());
                }
                if (field.order() <= previousOrder) {
                    fail("field order must be strictly increasing: " + message.name() + "." + field.name());
                }
                previousOrder = field.order();
            }
        }
        return messageNames;
    }

    private static void validateProtocols(final List<ProtocolDefinition> protocols) {
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (ProtocolDefinition protocol : protocols) {
            if (!ids.add(protocol.id())) {
                fail("duplicate protocol id: " + protocol.id());
            }
            if (!names.add(protocol.name())) {
                fail("duplicate protocol name: " + protocol.name());
            }
        }
    }

    private static void validateMethods(
            final List<ProtocolMethod> methods,
            final List<ProtocolDefinition> protocols,
            final Set<String> messageNames) {
        Set<String> methodNames = new HashSet<>();
        Set<ProtocolDefinition> protocolSet = Set.copyOf(protocols);
        for (ProtocolMethod method : methods) {
            String methodKey = method.boName() + "." + method.methodName();
            if (!methodNames.add(methodKey)) {
                fail("duplicate method name: " + methodKey);
            }
            if (!protocolSet.contains(method.definition())) {
                fail("method references unknown protocol: " + method.methodName());
            }
            if (!messageNames.contains(method.requestMessage())) {
                fail("method references unknown request message: " + method.requestMessage());
            }
            if (!method.responseMessage().isBlank() && !messageNames.contains(method.responseMessage())) {
                fail("method references unknown response message: " + method.responseMessage());
            }
            validateMethodParameters(method);
        }
    }

    private static void validateMethodParameters(final ProtocolMethod method) {
        Set<Integer> orders = new HashSet<>();
        Set<String> names = new HashSet<>();
        int previousOrder = 0;
        for (ProtocolField parameter : method.parameters()) {
            if (!orders.add(parameter.order())) {
                fail("duplicate method parameter order: " + method.methodName() + "." + parameter.order());
            }
            if (!names.add(parameter.name())) {
                fail("duplicate method parameter name: " + method.methodName() + "." + parameter.name());
            }
            if (parameter.order() <= previousOrder) {
                fail("method parameter order must be strictly increasing: "
                        + method.methodName() + "." + parameter.name());
            }
            previousOrder = parameter.order();
        }
    }

    private static void validateType(
            final ProtocolType type,
            final Set<String> enumNames,
            final Set<String> messageNames) {
        switch (type.kind()) {
            case SCALAR -> {
                return;
            }
            case NULL -> fail("null type is reserved for internal use");
            case ENUM -> {
                if (!enumNames.contains(type.name())) {
                    fail("unknown enum type: " + type.name());
                }
            }
            case MESSAGE -> {
                if (!messageNames.contains(type.name())) {
                    fail("unknown message type: " + type.name());
                }
            }
            case LIST, SET, ARRAY, OPTIONAL -> validateArity(type, 1);
            case MAP -> validateArity(type, 2);
            default -> fail("unsupported type kind: " + type.kind());
        }
        for (ProtocolType argument : type.arguments()) {
            validateType(argument, enumNames, messageNames);
        }
    }

    private static void validateArity(final ProtocolType type, final int expected) {
        if (type.arguments().size() != expected) {
            fail("type " + type.kind() + " expects " + expected + " argument(s)");
        }
    }

    private static void fail(final String message) {
        throw ZeroException.of(CodegenErrorCode.DSL_VALIDATION_FAILED, message, null);
    }
}
