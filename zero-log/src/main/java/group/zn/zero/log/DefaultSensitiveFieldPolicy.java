package group.zn.zero.log;

/**
 * 不可关闭的默认敏感字段和凭据内容策略。
 *
 * <p>匹配使用无临时字符串分配的大小写无关字符扫描，并只在 `_ - . []` 分隔的完整路径段上
 * 判断别名；连接串检测同样使用有界字符扫描，不编译或执行正则表达式。
 *
 * @author zn
 */
public final class DefaultSensitiveFieldPolicy implements SensitiveFieldPolicy {

    /**
     * 绝对禁止字段段别名。
     */
    private static final String[] FORBIDDEN_ALIASES = {
        "authorization", "cookie", "setcookie", "token", "accesstoken", "refreshtoken",
        "authtoken", "idtoken", "password", "passwd", "pwd", "secret", "apikey",
        "accesskey", "secretkey", "credential", "credentials", "privatekey", "rawcommand",
        "commandtext"
    };

    /**
     * 使用路径分隔符拆开的绝对禁止复合别名。
     */
    private static final String[][] FORBIDDEN_ALIAS_PAIRS = {
        {"set", "cookie"}, {"access", "token"}, {"refresh", "token"}, {"auth", "token"},
        {"id", "token"}, {"api", "key"}, {"access", "key"}, {"secret", "key"},
        {"private", "key"}, {"raw", "command"}, {"command", "text"}
    };

    /**
     * 默认脱敏标识段别名。
     */
    private static final String[] IDENTIFIER_ALIASES = {
        "ip", "clientip", "sourceip", "operatorip", "remoteaddress", "sourceaddress",
        "operator", "operatorid", "accountid", "playerid", "targetid"
    };

    /**
     * 使用路径分隔符拆开的默认脱敏复合标识。
     */
    private static final String[][] IDENTIFIER_ALIAS_PAIRS = {
        {"client", "ip"}, {"source", "ip"}, {"operator", "ip"}, {"remote", "address"},
        {"source", "address"}, {"operator", "id"}, {"account", "id"}, {"player", "id"},
        {"target", "id"}
    };

    /**
     * 连接串中额外视为凭据的属性名。
     */
    private static final String[] CONNECTION_CREDENTIAL_ALIASES = {
        "user", "username"
    };

    /**
     * 可识别连接串前缀。
     */
    private static final String[] CONNECTION_PREFIXES = {
        "jdbc:", "mongodb:", "mongodb+srv:", "redis:"
    };

    /**
     * 无状态默认策略单例。
     */
    private static final DefaultSensitiveFieldPolicy INSTANCE = new DefaultSensitiveFieldPolicy();

    /**
     * 单例类不允许外部实例化。
     */
    private DefaultSensitiveFieldPolicy() {
    }

    /**
     * 返回不可关闭的默认策略单例。
     *
     * @return 无状态、线程安全的策略；不可为空。
     */
    public static DefaultSensitiveFieldPolicy instance() {
        return INSTANCE;
    }

    /**
     * 判断默认安全动作。
     *
     * @param fieldPath 字段路径；不可为空。
     * @param value 字段值；不可为空。
     * @return 拒绝、脱敏或允许动作；不可为空。
     */
    @Override
    public SensitiveFieldAction actionFor(final String fieldPath, final String value) {
        if (fieldPath == null || value == null) {
            return SensitiveFieldAction.REJECT;
        }
        if (containsAliasSegment(fieldPath, FORBIDDEN_ALIASES)
                || containsAliasPair(fieldPath, FORBIDDEN_ALIAS_PAIRS)
                || containsCredentialContent(value)) {
            return SensitiveFieldAction.REJECT;
        }
        if (containsAliasSegment(fieldPath, IDENTIFIER_ALIASES)
                || containsAliasPair(fieldPath, IDENTIFIER_ALIAS_PAIRS)) {
            return SensitiveFieldAction.REDACT;
        }
        return SensitiveFieldAction.ALLOW;
    }

    /**
     * 判断路径是否包含完整别名段。
     *
     * @param path 原始字段路径；不可为空；匹配时忽略大小写。
     * @param aliases 别名数组；不可为空。
     * @return 存在完整段时返回 true。
     */
    private boolean containsAliasSegment(final String path, final String[] aliases) {
        int segmentStart = 0;
        for (int index = 0; index <= path.length(); index++) {
            if (index == path.length() || isPathSeparator(path.charAt(index))) {
                if (matchesAny(path, segmentStart, index, aliases)) {
                    return true;
                }
                segmentStart = index + 1;
            }
        }
        return false;
    }

    /**
     * 判断路径是否包含由相邻完整段组成的复合别名。
     *
     * @param path 原始字段路径；不可为空；匹配时忽略大小写。
     * @param aliasPairs 两段别名数组；不可为空。
     * @return 存在相邻复合别名时返回 true。
     */
    private boolean containsAliasPair(final String path, final String[][] aliasPairs) {
        int previousStart = -1;
        int previousEnd = -1;
        int segmentStart = 0;
        for (int index = 0; index <= path.length(); index++) {
            if (index == path.length() || isPathSeparator(path.charAt(index))) {
                if (index > segmentStart && previousStart >= 0
                        && matchesAnyPair(path, previousStart, previousEnd, segmentStart, index, aliasPairs)) {
                    return true;
                }
                if (index > segmentStart) {
                    previousStart = segmentStart;
                    previousEnd = index;
                }
                segmentStart = index + 1;
            }
        }
        return false;
    }

    /**
     * 判断字段值是否包含凭据内容。
     *
     * @param value 字段值；不可为空。
     * @return 包含 URI user-info 或敏感属性时返回 true。
     */
    private boolean containsCredentialContent(final String value) {
        boolean connection = containsAny(value, CONNECTION_PREFIXES);
        return containsUriUserInfo(value)
                || containsSensitiveProperty(value, FORBIDDEN_ALIASES)
                || connection && containsSensitiveProperty(value, CONNECTION_CREDENTIAL_ALIASES);
    }

    /**
     * 判断 URI authority 是否包含 user-info。
     *
     * @param value 原始文本；不可为空。
     * @return 检测到 user-info 时返回 true。
     */
    private boolean containsUriUserInfo(final String value) {
        int schemeEnd = value.indexOf("://");
        while (schemeEnd >= 0) {
            int authorityStart = schemeEnd + 3;
            int authorityEnd = authorityStart;
            while (authorityEnd < value.length() && !isAuthorityTerminator(value.charAt(authorityEnd))) {
                authorityEnd++;
            }
            int at = value.indexOf('@', authorityStart);
            if (at >= authorityStart && at < authorityEnd) {
                return true;
            }
            schemeEnd = value.indexOf("://", authorityEnd);
        }
        return false;
    }

    /**
     * 判断文本是否包含敏感 `key=value` 属性。
     *
     * @param value 原始文本；不可为空；属性名匹配时忽略大小写。
     * @param aliases 敏感 key 别名；不可为空。
     * @return 匹配时返回 true。
     */
    private boolean containsSensitiveProperty(final String value, final String[] aliases) {
        for (int equalsAt = value.indexOf('='); equalsAt >= 0; equalsAt = value.indexOf('=', equalsAt + 1)) {
            int keyStart = equalsAt - 1;
            while (keyStart >= 0 && !isPropertySeparator(value.charAt(keyStart))) {
                keyStart--;
            }
            int start = keyStart + 1;
            while (start < equalsAt && Character.isWhitespace(value.charAt(start))) {
                start++;
            }
            int end = equalsAt;
            while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
                end--;
            }
            if (matchesAny(value, start, end, aliases)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断字符串范围是否等于任一 ASCII 别名。
     *
     * @param value 文本；不可为空。
     * @param start 起始位置。
     * @param end 结束位置。
     * @param aliases 别名；不可为空。
     * @return 匹配时返回 true。
     */
    private boolean matchesAny(
            final String value,
            final int start,
            final int end,
            final String[] aliases) {
        int length = end - start;
        if (length <= 0) {
            return false;
        }
        for (String alias : aliases) {
            if (alias.length() == length && value.regionMatches(true, start, alias, 0, length)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断相邻两个字符串范围是否匹配任一复合别名。
     *
     * @param value 路径文本；不可为空。
     * @param firstStart 第一段起始位置。
     * @param firstEnd 第一段结束位置。
     * @param secondStart 第二段起始位置。
     * @param secondEnd 第二段结束位置。
     * @param aliasPairs 两段别名数组；不可为空。
     * @return 匹配时返回 true。
     */
    private boolean matchesAnyPair(
            final String value,
            final int firstStart,
            final int firstEnd,
            final int secondStart,
            final int secondEnd,
            final String[][] aliasPairs) {
        int firstLength = firstEnd - firstStart;
        int secondLength = secondEnd - secondStart;
        for (String[] pair : aliasPairs) {
            if (pair[0].length() == firstLength
                    && pair[1].length() == secondLength
                    && value.regionMatches(true, firstStart, pair[0], 0, firstLength)
                    && value.regionMatches(true, secondStart, pair[1], 0, secondLength)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断文本是否包含任一固定前缀。
     *
     * @param value 文本；不可为空。
     * @param candidates 候选内容；不可为空。
     * @return 匹配时返回 true。
     */
    private boolean containsAny(final String value, final String[] candidates) {
        for (String candidate : candidates) {
            if (indexOfIgnoreCase(value, candidate) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在原字符串中执行无临时对象的大小写无关查找。
     *
     * @param value 原始字符串；不可为空。
     * @param candidate ASCII 候选字符串；不可为空且非空。
     * @return 首次匹配下标；不存在时返回 -1。
     */
    private int indexOfIgnoreCase(final String value, final String candidate) {
        int lastStart = value.length() - candidate.length();
        for (int index = 0; index <= lastStart; index++) {
            if (value.regionMatches(true, index, candidate, 0, candidate.length())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 判断字段路径分隔符。
     *
     * @param value 字符。
     * @return `_ - . []` 返回 true。
     */
    private boolean isPathSeparator(final char value) {
        return value == '_' || value == '-' || value == '.' || value == '[' || value == ']';
    }

    /**
     * 判断 URI authority 终止符。
     *
     * @param value 字符。
     * @return authority 终止符返回 true。
     */
    private boolean isAuthorityTerminator(final char value) {
        return value == '/' || value == '?' || value == '#' || Character.isWhitespace(value);
    }

    /**
     * 判断属性 key 左侧分隔符。
     *
     * @param value 字符。
     * @return 查询、属性或空白分隔符返回 true。
     */
    private boolean isPropertySeparator(final char value) {
        return value == '?' || value == '&' || value == ';' || value == ',' || value == ':'
                || value == '.' || value == '_' || value == '-' || value == '[' || value == ']'
                || Character.isWhitespace(value);
    }
}
