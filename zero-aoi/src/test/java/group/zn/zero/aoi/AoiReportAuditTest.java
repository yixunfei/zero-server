package group.zn.zero.aoi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** AOI 状态变化、离开标识与坐标边界回归。 @author zn */
class AoiReportAuditTest {
    /** 静止实体不发送 UPDATE，删除后 LEAVE 仍携带身份。 */
    @Test void unchangedEntityIsQuietAndLeaveIdentifiesEntity() {
        InMemoryAoiIndex index = new InMemoryAoiIndex();
        AoiEntity entity = new AoiEntity("e", new Position(0, 0), 1, "state");
        index.add(entity);
        index.observe("o", new Position(0, 0), 1);
        assertTrue(index.observe("o", new Position(0, 0), 1).isEmpty());
        index.remove("e");
        assertEquals(entity, index.observe("o", new Position(0, 0), 1).getFirst().entity());
    }
    /** 坐标差不能因 int 溢出被误认为邻近。 */
    @Test void distantCoordinatesDoNotOverflowIntoVisibility() {
        InMemoryAoiIndex index = new InMemoryAoiIndex();
        index.add(new AoiEntity("e", new Position(Integer.MAX_VALUE, 0), 1, "state"));
        assertTrue(index.visible(new Position(Integer.MIN_VALUE, 0), 2).isEmpty());
    }
}
