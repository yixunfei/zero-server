package group.zn.zero.codegen.dsl;

/**
 * 协议 ID 区间配置。
 *
 * @param schemaName schema 基名。
 * @param clientToServerStart 客户端到服务端协议起始 ID。
 * @param serverToClientStart 服务端到客户端协议起始 ID。
 * @author zn
 */
public record ProtocolIdRange(
        String schemaName,
        int clientToServerStart,
        int serverToClientStart) {

    /**
     * 创建协议 ID 区间配置。
     *
     * @throws NullPointerException 当 schema 基名为空时抛出。
     * @throws IllegalArgumentException 当 schema 基名空白或 ID 非正数时抛出。
     */
    public ProtocolIdRange {
        if (schemaName == null) {
            throw new NullPointerException("schemaName");
        }
        if (schemaName.isBlank()) {
            throw new IllegalArgumentException("schemaName must not be blank");
        }
        if (clientToServerStart <= 0) {
            throw new IllegalArgumentException("clientToServerStart must be positive");
        }
        if (serverToClientStart <= 0) {
            throw new IllegalArgumentException("serverToClientStart must be positive");
        }
    }
}
