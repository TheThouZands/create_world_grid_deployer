package co.thouzands.worldgriddeployer.client;

public interface WorldGridDeployerAnimationAccess {
    /**
     * @return hand extension in blocks, or {@link Float#NaN} when Create should render normally
     */
    float worldgriddeployer$getHandExtension(float partialTicks);
}
