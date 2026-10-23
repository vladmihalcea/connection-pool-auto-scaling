package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.flexypool.adaptor.AbstractPoolAdapter;
import com.vladmihalcea.flexypool.adaptor.PoolAdapter;
import com.vladmihalcea.flexypool.adaptor.PoolAdapterFactory;
import com.vladmihalcea.flexypool.common.ConfigurationProperties;
import com.vladmihalcea.flexypool.metric.Metrics;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration;

/**
 * A FlexyPool {@link PoolAdapter} for Agroal, which FlexyPool does not ship. Agroal resizes live through
 * {@code getConfiguration().connectionPoolConfiguration().setMaxSize(int)}, and it closes a returned
 * connection whenever the pool holds more than {@code maxSize}, so shrinking needs nothing else.
 *
 * @author Vlad Mihalcea
 */
public class AgroalPoolAdapter extends AbstractPoolAdapter<AgroalDataSource> {

    /** The message of the {@link java.sql.SQLException} Agroal throws when an acquisition times out. */
    public static final String ACQUISITION_TIMEOUT_MESSAGE = "Sorry, acquisition timeout!";

    public static final PoolAdapterFactory<AgroalDataSource> FACTORY = AgroalPoolAdapter::new;

    public AgroalPoolAdapter(
            ConfigurationProperties<AgroalDataSource, Metrics, PoolAdapter<AgroalDataSource>> configurationProperties) {
        super(configurationProperties);
    }

    @Override
    public int getMaxPoolSize() {
        return poolConfiguration().maxSize();
    }

    @Override
    public void setMaxPoolSize(int maxPoolSize) {
        AgroalConnectionPoolConfiguration pool = poolConfiguration();
        // Keep minSize <= maxSize: lower it first when shrinking.
        if (pool.minSize() > maxPoolSize) {
            pool.setMinSize(maxPoolSize);
        }
        pool.setMaxSize(maxPoolSize);
        if (getConfigurationProperties().isMaintainFixedSizePool()) {
            pool.setMinSize(maxPoolSize);
        }
    }

    private AgroalConnectionPoolConfiguration poolConfiguration() {
        return getTargetDataSource().getConfiguration().connectionPoolConfiguration();
    }

    @Override
    protected boolean isTimeoutAcquisitionException(Exception e) {
        return ACQUISITION_TIMEOUT_MESSAGE.equals(e.getMessage());
    }
}
