package group.zn.zero.runtime.production;

/** Resolves an explicitly installed integration without creating driver resources. */
@FunctionalInterface
public interface ProductionModuleFactory {
    ProductionModule resolve(ProductionContext context);
}
