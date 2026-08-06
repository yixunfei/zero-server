package group.zn.zero.hotupdate.config;

/**
 * 配置表重载结果状态。
 *
 * @author zn
 */
public enum ConfigReloadStatus {

    /**
     * 新快照已成功发布。
     */
    LOADED,

    /**
     * 文件摘要未改变，未增加版本也未替换快照。
     */
    NO_CHANGE,

    /**
     * 候选加载失败，旧快照保持不变。
     */
    REJECTED
}
