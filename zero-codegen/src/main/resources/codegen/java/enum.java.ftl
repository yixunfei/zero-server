/*
 * ${generatedMarker}. Do not edit manually.
 */
package ${packageName};

/**
 * ${comment}
 */
public enum ${name} {
<#list values as value>
    /**
     * ${value.comment}
     */
    ${value.name}(${value.value?c})<#if value_has_next>,<#else>;</#if>
</#list>

    /**
     * 协议枚举线格式值。
     */
    private final int value;

    /**
     * 创建协议枚举。
     *
     * @param value 协议枚举线格式值。
     */
    ${name}(final int value) {
        this.value = value;
    }

    /**
     * 返回协议枚举线格式值。
     *
     * @return 线格式值；线程安全。
     */
    public int value() {
        return value;
    }

    /**
     * 按线格式值查找枚举。
     *
     * @param value 线格式值。
     * @return 协议枚举；不可为空；线程安全。
     * @throws IllegalArgumentException 当枚举值未知时抛出。
     */
    public static ${name} fromValue(final int value) {
        for (${name} item : values()) {
            if (item.value == value) {
                return item;
            }
        }
        throw new IllegalArgumentException("unknown ${name} value: " + value);
    }
}
