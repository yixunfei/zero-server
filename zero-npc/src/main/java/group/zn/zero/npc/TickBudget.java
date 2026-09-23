package group.zn.zero.npc; public record TickBudget(long budgetMillis,int budgetOps){public TickBudget{if(budgetMillis<0||budgetOps<0)throw new IllegalArgumentException("negative budget");}}
