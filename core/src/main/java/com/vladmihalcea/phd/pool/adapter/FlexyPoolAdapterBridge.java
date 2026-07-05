package com.vladmihalcea.phd.pool.adapter;

import com.vladmihalcea.flexypool.adaptor.PoolAdapter;
import com.vladmihalcea.phd.pool.api.PoolUnderTest;

import javax.sql.DataSource;

/**
 * Adapts a FlexyPool {@link PoolAdapter} to this project's {@link PoolUnderTest} SPI. The two interfaces
 * intentionally expose the same actuator ({@code getMaxPoolSize} / {@code setMaxPoolSize}); this bridge
 * makes the equivalence concrete, so the {@code UslAutoScalingController} written against
 * {@link PoolUnderTest} can drive an existing FlexyPool deployment unchanged. It also documents the
 * upstreaming path: the controller can be repackaged as a FlexyPool strategy plus a periodic estimator
 * tick, since FlexyPool's own strategy SPI is acquisition-driven and has no scheduler of its own.
 *
 * @author Vlad Mihalcea
 */
public final class FlexyPoolAdapterBridge implements PoolUnderTest {

    private final PoolAdapter<? extends DataSource> flexyPoolAdapter;
    private final String id;

    public FlexyPoolAdapterBridge(PoolAdapter<? extends DataSource> flexyPoolAdapter, String id) {
        this.flexyPoolAdapter = flexyPoolAdapter;
        this.id = id;
    }

    @Override
    public DataSource dataSource() {
        return flexyPoolAdapter.getTargetDataSource();
    }

    @Override
    public int getMaxPoolSize() {
        return flexyPoolAdapter.getMaxPoolSize();
    }

    @Override
    public void setMaxPoolSize(int maxPoolSize) {
        flexyPoolAdapter.setMaxPoolSize(maxPoolSize);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void close() {
        // The underlying FlexyPool-managed data source owns its own lifecycle.
    }
}
