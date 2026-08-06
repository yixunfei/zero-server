package group.zn.zero.log;

/**
 * 与具体日志实现无关的日志严重等级。
 *
 * @author zn
 */
public enum LogLevel {

    /**
     * 追踪级。
     */
    TRACE,

    /**
     * 调试级。
     */
    DEBUG,

    /**
     * 信息级。
     */
    INFO,

    /**
     * 警告级。
     */
    WARN,

    /**
     * 错误级。
     */
    ERROR
}
