package group.zn.zero.codegen.generator;

import group.zn.zero.codegen.dsl.ProtocolDslValidator;
import group.zn.zero.codegen.dsl.ProtocolNames;
import group.zn.zero.codegen.generator.client.ClientCodegenSupport;
import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.JavaArtifactKind;
import group.zn.zero.codegen.model.ProtocolField;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.SourceVersion;

/** 按实际后端校验源码名称及生成器保留符号，不自动重命名。 @author zn */
public final class TargetNames {
    /** C# 保留关键字；上下文关键字在声明类型位置另外限制。 */
    private static final Set<String> CSHARP = words("abstract as base bool break byte case catch char checked class const "
            + "continue decimal default delegate do double else enum event explicit extern false finally fixed float for "
            + "foreach goto if implicit in int interface internal is lock long namespace new null object operator out override "
            + "params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct "
            + "switch this throw true try typeof uint ulong unchecked unsafe ushort using virtual void volatile while");
    /** TypeScript 关键字及严格模式保留字。 */
    private static final Set<String> TYPESCRIPT = words("break case catch class const continue debugger default delete do else "
            + "enum export extends false finally for function if import in instanceof new null return super switch this throw "
            + "true try typeof var void while with implements interface let package private protected public static yield await "
            + "abstract as asserts any async boolean constructor declare get infer intrinsic is keyof module namespace never "
            + "number object readonly require set string symbol type undefined unique unknown from global of override satisfies");
    /** GDScript 4 关键字、字面值和内建基础类型。 */
    private static final Set<String> GDSCRIPT = words("if elif else for while match break continue pass return class class_name "
            + "extends is in as self signal func static const enum var onready export setget breakpoint preload await yield "
            + "assert void null true false and or not super trait namespace String StringName Node Object RefCounted Resource "
            + "Array Dictionary int float bool Vector2 Vector3 Vector4 Color Callable Signal Variant PackedByteArray");
    /** 模板和标准类型导入占用的名称。 */
    private static final Set<String> RESERVED_TYPES = words("ProtocolIds GeneratedProtocolDefinitions ZeroGeneratedPayload "
            + "IZeroGeneratedPayload ZeroProtocolRuntime ZeroWriter ZeroReader ProtocolDispatcher GeneratedProtocolDispatcher "
            + "Serializable List Set Map HashMap LinkedHashMap HashSet LinkedHashSet ArrayList Arrays Objects String Integer "
            + "Long Double Float Boolean Byte Short Character Object Optional ByteBuffer ProtocolFrame ProtocolDefinition "
            + "ProtocolDirection ProtocolFeature ZeroPayloadCodec ZeroException ProtocolErrorCode java NAMESPACE");

    private TargetNames() { }

    /**
     * 验证单后端实际输出名称；只读、线程安全。
     * @param request 生成请求。
     * @param language 本次渲染后端。
     * @throws group.zn.zero.core.error.ZeroException 非法或冲突名称绑定 DSL_VALIDATION_FAILED。
     */
    public static void validate(final CodegenRequest request, final CodegenLanguage language) {
        ProtocolDslValidator.validate(request.document());
        namespace(request.namespace(language), language);
        if (language == CodegenLanguage.JAVA) {
            for (JavaArtifactKind kind : JavaArtifactKind.values()) namespace(request.javaPackage(kind), language);
        }
        String suffix = request.dtoSuffix(language);
        identifier("Dto" + suffix, language);
        Set<String> types = new HashSet<>(switch (language) {
            case JAVA -> RESERVED_TYPES;
            case CSHARP -> words("ProtocolIds IZeroGeneratedPayload ZeroReader ZeroWriter List Dictionary HashSet Array String Object");
            case TYPESCRIPT -> words("ProtocolIds ZeroGeneratedPayload ZeroReader ZeroWriter Map Set Array Uint8Array BigInt");
            case GDSCRIPT -> words("ProtocolIds ZeroReader ZeroWriter NAMESPACE");
        });
        for (var item : request.document().enums()) {
            declare(types, item.name(), language);
            for (var value : item.values()) {
                if (language == CodegenLanguage.TYPESCRIPT) ProtocolNames.identifier(value.name());
                else identifier(value.name(), language);
                if ((language == CodegenLanguage.JAVA && value.name().equals("value"))
                        || (language == CodegenLanguage.CSHARP && value.name().equals(item.name()))) {
                    ProtocolNames.fail("enum value conflicts with generated value field: " + item.name());
                }
            }
        }
        for (var message : request.document().messages()) {
            String name = message.name() + suffix;
            declare(types, name, language);
            fields(message.fields(), name, language);
        }
        if (language != CodegenLanguage.JAVA) {
            for (var message : request.document().messages()) declare(types, message.name() + suffix + "Codec", language);
        }
        Set<String> constants = new HashSet<>(Set.of("MAX_ID"));
        for (var protocol : request.document().protocols()) {
            declare(constants, ClientCodegenSupport.constantName(protocol.name()), language);
        }
        if (language == CodegenLanguage.JAVA) {
            methods(request, language);
            javaSymbols(request);
        }
    }

    private static void methods(final CodegenRequest request, final CodegenLanguage language) {
        for (var method : request.document().methods()) {
            identifier(method.boName(), language);
            identifier(method.methodName(), language);
            fields(method.parameters(), method.boName(), language);
        }
    }

    private static void javaSymbols(final CodegenRequest request) {
        java.util.Map<String, Set<String>> scopes = new java.util.HashMap<>();
        symbol(scopes, request, JavaArtifactKind.DTO, "ZeroGeneratedPayload");
        symbol(scopes, request, JavaArtifactKind.PROTOCOL, "ProtocolIds");
        symbol(scopes, request, JavaArtifactKind.PROTOCOL, "GeneratedProtocolDefinitions");
        for (var item : request.document().enums()) symbol(scopes, request, JavaArtifactKind.DTO, item.name());
        for (var message : request.document().messages()) {
            String name = message.name() + request.dtoSuffix(CodegenLanguage.JAVA);
            symbol(scopes, request, JavaArtifactKind.DTO, name);
            symbol(scopes, request, JavaArtifactKind.CODEC, name + "Codec");
        }
        if (!request.document().methods().isEmpty()) {
            symbol(scopes, request, JavaArtifactKind.DISPATCHER, "GeneratedProtocolDispatcher");
        }
        for (String bo : request.document().methods().stream().map(method -> method.boName()).distinct().toList()) {
            symbol(scopes, request, JavaArtifactKind.BO, bo);
            if (request.generateBoImpl()) {
                String impl = bo.startsWith("I") && bo.endsWith("BO") && bo.length() > 3
                        && Character.isUpperCase(bo.charAt(1)) ? bo.substring(1) + "Imp" : bo + "Imp";
                symbol(scopes, request, JavaArtifactKind.BO_IMPL, impl);
            }
        }
        Set<String> packages = new HashSet<>();
        for (String packageName : scopes.keySet()) {
            String prefix = packageName;
            while (!prefix.isEmpty()) {
                packages.add(prefix);
                int dot = prefix.lastIndexOf('.');
                prefix = dot < 0 ? "" : prefix.substring(0, dot);
            }
        }
        scopes.forEach((packageName, names) -> names.forEach(name -> {
            String qualified = packageName + "." + name;
            if (packages.contains(qualified)) ProtocolNames.fail("generated type clashes with package: " + qualified);
        }));
    }

    private static void symbol(final java.util.Map<String, Set<String>> scopes, final CodegenRequest request,
                               final JavaArtifactKind kind, final String name) {
        declare(scopes.computeIfAbsent(request.javaPackage(kind), ignored -> new HashSet<>()), name, CodegenLanguage.JAVA);
    }

    private static void fields(final List<ProtocolField> fields, final String owner, final CodegenLanguage language) {
        for (ProtocolField field : fields) {
            if (language == CodegenLanguage.TYPESCRIPT) {
                ProtocolNames.identifier(field.name());
                if (field.name().equals("constructor")) ProtocolNames.fail("reserved TypeScript member: constructor");
            } else {
                identifier(field.name(), language);
            }
            if ((language == CodegenLanguage.JAVA && field.name().equals("serialVersionUID"))
                    || (language == CodegenLanguage.CSHARP && field.name().equals(owner))
                    || (language == CodegenLanguage.GDSCRIPT && field.name().equals("_init"))) {
                ProtocolNames.fail("field conflicts with generated member: " + owner + "." + field.name());
            }
        }
    }

    private static void namespace(final String name, final CodegenLanguage language) {
        for (String part : name.split("\\.", -1)) {
            if (language == CodegenLanguage.TYPESCRIPT || language == CodegenLanguage.GDSCRIPT) {
                ProtocolNames.identifier(part); // 这两个后端只把 namespace 写为元数据。
            } else {
                identifier(part, language);
            }
        }
        if (language == CodegenLanguage.CSHARP) {
            for (String part : ClientCodegenSupport.toCSharpNamespace(name).split("\\.")) identifier(part, language);
        }
    }

    private static void declare(final Set<String> scope, final String name, final CodegenLanguage language) {
        identifier(name, language);
        if (language == CodegenLanguage.JAVA && Set.of("var", "yield", "record", "sealed", "permits").contains(name)) {
            ProtocolNames.fail("reserved Java type identifier: " + name);
        }
        if (!scope.add(name)) ProtocolNames.fail("generated symbol collision: " + name);
        // 输出需能在 Windows 上共享；大小写不同但同一路径的类型不允许共存。
        if (scope.stream().filter(value -> value.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))).count() > 1) {
            ProtocolNames.fail("case-insensitive generated symbol collision: " + name);
        }
    }

    private static void identifier(final String name, final CodegenLanguage language) {
        ProtocolNames.identifier(name);
        boolean reserved = switch (language) {
            case JAVA -> SourceVersion.isKeyword(name, SourceVersion.RELEASE_21);
            case CSHARP -> CSHARP.contains(name);
            case TYPESCRIPT -> TYPESCRIPT.contains(name);
            case GDSCRIPT -> GDSCRIPT.contains(name);
        };
        if (reserved) ProtocolNames.fail("reserved " + language + " identifier: " + name);
    }

    private static Set<String> words(final String words) {
        return Set.copyOf(Arrays.asList(words.split(" ")));
    }
}
