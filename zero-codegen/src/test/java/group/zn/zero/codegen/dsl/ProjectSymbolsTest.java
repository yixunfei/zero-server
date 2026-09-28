package group.zn.zero.codegen.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 全工程类型解析、源位置诊断和显式稳定协议号回归。 */
class ProjectSymbolsTest {
    /** 独立测试目录。 */
    @TempDir
    private Path root;

    @Test
    void crossFileTypesAndReorderedIdsShouldRemainStable() throws Exception {
        Files.writeString(root.resolve("Shared.si"), "enum State {\nOK = 1\n}\nstruct Item {\nState state\n}\n");
        Path api = root.resolve("Api.si");
        String first = "@id(301)\nfirst(Item item)\n";
        String second = "@id(303)\nsecond(list<Item> items)\n";
        Files.writeString(api, "client_to_server:\n" + first + second);
        Map<String, Integer> before = ids(parse(List.of(root, api)));
        Files.writeString(api, "client_to_server:\n" + second + first);
        assertEquals(before, ids(parse(List.of(root))));
        assertEquals(301, before.get("ApiFirstProtocol"));
        assertEquals(303, before.get("ApiSecondProtocol"));
    }

    @Test
    void unknownAndDuplicateTypesShouldNameTheirSources() throws Exception {
        Path api = root.resolve("Api.si");
        Files.writeString(api, "struct Value {\nMissing item\n}\n");
        ZeroException unknown = assertThrows(ZeroException.class, () -> parse(List.of(root)));
        assertTrue(unknown.message().contains("Api.si"));
        assertTrue(unknown.message().contains("line 2"));
        Files.writeString(api, "struct Value {\nint item\n}\n");
        Files.writeString(root.resolve("Other.si"), "struct Value {\nint item\n}\n");
        ZeroException duplicate = assertThrows(ZeroException.class, () -> parse(List.of(root)));
        assertTrue(duplicate.message().contains("Api.si"));
        assertTrue(duplicate.message().contains("Other.si"));
    }

    @Test
    void mixedExplicitAndImplicitIdsAndWrongParityShouldFail() throws Exception {
        Path api = root.resolve("Api.si");
        Files.writeString(api, "client_to_server:\n@id(301)\nfirst()\nsecond()\n");
        assertThrows(ZeroException.class, () -> parse(List.of(root)));
        Files.writeString(api, "client_to_server:\n@id(302)\nfirst()\n");
        assertThrows(ZeroException.class, () -> parse(List.of(root)));
    }

    private ProtocolDslDocument parse(final List<Path> inputs) {
        return new SiProtocolProjectParser().parse("group.zn.zero.test", inputs, null);
    }

    private Map<String, Integer> ids(final ProtocolDslDocument document) {
        return document.protocols().stream().collect(Collectors.toMap(p -> p.name(), p -> p.id()));
    }
}
