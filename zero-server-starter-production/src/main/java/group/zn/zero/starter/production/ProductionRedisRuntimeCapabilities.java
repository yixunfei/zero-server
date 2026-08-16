package group.zn.zero.starter.production;

import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;

/** Redis provider 之间共享、不会公开给业务代码的 typed resource capability。 */
final class ProductionRedisRuntimeCapabilities {

    static final ComponentKey<ProductionRedisResource> RESOURCE = ComponentKey.single(
            StandardRuntimeCapabilityModel.REDIS_RESOURCE,
            ProductionRedisResource.class);

    private ProductionRedisRuntimeCapabilities() {
    }
}
