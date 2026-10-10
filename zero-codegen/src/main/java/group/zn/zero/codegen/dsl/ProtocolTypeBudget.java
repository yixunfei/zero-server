package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.model.ProtocolType;
import java.util.ArrayDeque;
import java.util.Deque;

/** 类型树深度限制；使用显式栈，拒绝程序化构造的过深模型。 @author zn */
public final class ProtocolTypeBudget {
    /** 包含根节点的最大结构深度。 */
    public static final int MAX_DEPTH = 64;

    private ProtocolTypeBudget() { }

    /**
     * 检查类型结构预算，不修改模型，线程安全。
     * @param type 待检查类型。
     * @return 是否在深度预算内。
     */
    public static boolean permits(final ProtocolType type) {
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(new Node(type, 1));
        while (!pending.isEmpty()) {
            Node current = pending.pop();
            if (current.depth() > MAX_DEPTH) return false;
            for (ProtocolType argument : current.type().arguments()) {
                pending.push(new Node(argument, current.depth() + 1));
            }
        }
        return true;
    }

    /** 显式遍历节点和当前深度。 */
    private record Node(ProtocolType type, int depth) { }
}
