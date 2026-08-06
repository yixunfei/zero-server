package group.zn.zero.codegen.model;

/**
 * 代码生成目标语言。
 *
 * @author zn
 */
public enum CodegenLanguage {

    /**
     * Java 服务端协议代码。
     */
    JAVA,

    /**
     * TypeScript 客户端代码。
     */
    TYPESCRIPT,

    /**
     * C# 客户端代码。
     */
    CSHARP,

    /**
     * GDScript 客户端代码。
     */
    GDSCRIPT

    ;

    /**
     * 返回该语言默认的输出目录名。
     *
     * @return 默认输出目录名；JAVA 语言返回空串，表示直接使用根输出目录；线程安全。
     */
    public String defaultOutputFolder() {
        return switch (this) {
            case JAVA -> "";
            case CSHARP -> "csharp";
            case TYPESCRIPT -> "typescript";
            case GDSCRIPT -> "gdscript";
        };
    }
}
