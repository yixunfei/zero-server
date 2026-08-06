package group.zn.zero.scene;

/**
 * 场景二维坐标。
 *
 * <p>该坐标用于阶段 3 最小原型。record 不可变且线程安全；
 * 坐标替换应在 scene lane 内完成。
 *
 * @param x 横坐标。
 * @param y 纵坐标。
 * @author zn
 */
public record ScenePosition(int x, int y) {
}
