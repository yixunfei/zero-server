package {{packageName}}.config;

import group.zn.zero.hotupdate.config.ConfigTable;
import group.zn.zero.hotupdate.config.ConfigTableDefinition;
import group.zn.zero.hotupdate.config.LocalConfigHotReloadService;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * 道具 CSV 配置模块。
 *
 * @author zn
 */
public final class GameItemConfigModule {

    /**
     * 当前不可变配置表句柄。
     */
    private final ConfigTable<Integer, GameItemConfig> items;

    private GameItemConfigModule(final ConfigTable<Integer, GameItemConfig> items) {
        this.items = Objects.requireNonNull(items, "items");
    }

    /**
     * 在配置服务启动前注册道具表。
     *
     * @param service 配置热重载服务；不可为空且尚未启动。
     * @param configDirectory CSV 配置目录；不可为空。
     * @return 道具配置模块；不可为空，启动后可并发读取；本方法不读取文件。
     */
    public static GameItemConfigModule register(
            final LocalConfigHotReloadService service,
            final Path configDirectory) {
        ConfigTable<Integer, GameItemConfig> table = service.register(ConfigTableDefinition.of(
                "items",
                Objects.requireNonNull(configDirectory, "configDirectory").resolve("items.csv"),
                "id",
                Set.of("id", "name", "price"),
                Integer::valueOf,
                row -> new GameItemConfig(
                        Integer.parseInt(row.require("id")),
                        row.require("name"),
                        Integer.parseInt(row.require("price")))));
        return new GameItemConfigModule(table);
    }

    /**
     * 返回 typed 道具配置表。
     *
     * @return 配置表；不可为空，读取无文件 IO，线程安全。
     */
    public ConfigTable<Integer, GameItemConfig> items() {
        return items;
    }
}
