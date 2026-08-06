package group.zn.zero.data.mongo;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * MongoDB document 形态的信封存储。
 *
 * <p>当前实现使用本地内存 map 保持单元测试可运行；真实 MongoDB driver 接入时应保持该
 * document 字段语义不变。
 *
 * @author zn
 */
public final class MongoDataEnvelopeStore implements ZeroDataEnvelopeStore {

    /**
     * document 存储。
     */
    private final Map<String, MongoDataDocument> documents = new LinkedHashMap<>();

    /**
     * 创建 MongoDB document 形态的信封存储。
     */
    public MongoDataEnvelopeStore() {
    }

    /**
     * 根据编码 ID 查询信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 查询结果；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized Optional<ZeroDataEnvelope> findById(final String id) {
        return document(id).map(MongoDataDocument::toEnvelope);
    }

    /**
     * 查询全部信封。
     *
     * @return 信封列表；不可为空；可能为空；有序；线程安全。
     */
    @Override
    public synchronized List<ZeroDataEnvelope> findAll() {
        return documents.values().stream()
                .map(MongoDataDocument::toEnvelope)
                .toList();
    }

    /**
     * 保存信封。
     *
     * @param envelope 信封；不可为空。
     */
    @Override
    public synchronized void save(final ZeroDataEnvelope envelope) {
        MongoDataDocument document = MongoDataDocument.fromEnvelope(envelope);
        documents.put(document.id(), document);
    }

    /**
     * 按期望版本条件保存信封。
     *
     * @param envelope 信封；不可为空。
     * @param expectedVersion 期望当前版本；必须大于等于 0。
     * @return true 表示保存成功；false 表示版本条件不满足；线程安全。
     */
    @Override
    public synchronized boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        MongoDataDocument document = MongoDataDocument.fromEnvelope(envelope);
        MongoDataDocument previous = documents.get(document.id());
        if (expectedVersion == 0L) {
            if (previous != null) {
                return false;
            }
        } else if (previous == null || previous.version() != expectedVersion) {
            return false;
        }
        documents.put(document.id(), document);
        return true;
    }

    /**
     * 根据编码 ID 删除信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     */
    @Override
    public synchronized void deleteById(final String id) {
        documents.remove(Objects.requireNonNull(id, "id"));
    }

    /**
     * 统计信封数量。
     *
     * @return 信封数量；线程安全。
     */
    @Override
    public synchronized long count() {
        return documents.size();
    }

    /**
     * 根据编码 ID 查询底层 document。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return document；为空表示不存在；线程安全。
     */
    public synchronized Optional<MongoDataDocument> document(final String id) {
        return Optional.ofNullable(documents.get(Objects.requireNonNull(id, "id")));
    }
}
