package {{packageName}}.config;

/**
 * 道具配置。
 *
 * @param id 道具标识。
 * @param name 道具名称。
 * @param price 道具价格。
 * @author zn
 */
public record GameItemConfig(int id, String name, int price) {
}
