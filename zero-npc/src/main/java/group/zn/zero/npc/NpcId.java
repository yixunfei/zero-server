package group.zn.zero.npc; import java.util.Objects; public record NpcId(String value){public NpcId{Objects.requireNonNull(value);if(value.isBlank())throw new IllegalArgumentException("blank id");}}
