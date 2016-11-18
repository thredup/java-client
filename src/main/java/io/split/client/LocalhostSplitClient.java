package io.split.client;

import com.google.common.collect.ImmutableMap;
import io.split.client.api.Key;
import io.split.grammar.Treatments;

import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An implementation of SplitClient that considers all partitions
 * passed in the constructor to be 100% on for all users, and
 * any other split to be 100% off for all users. This implementation
 * is useful for using Codigo in localhost environment.
 *
 * @author adil
 */
public final class LocalhostSplitClient implements SplitClient {

    private final ImmutableMap<String, String> _featureToTreatmentMap;

    public LocalhostSplitClient(Map<String, String> featureToTreatmentMap) {
        checkNotNull(featureToTreatmentMap, "featureToTreatmentMap must not be null");
        _featureToTreatmentMap = ImmutableMap.copyOf(featureToTreatmentMap);
    }

    @Override
    public String getTreatment(String key, String feature) {
        if (key == null || feature == null) {
            return Treatments.CONTROL;
        }

        String treatment = _featureToTreatmentMap.get(feature);

        if (treatment == null) {
            return Treatments.CONTROL;
        }

        return treatment;
    }

    @Override
    public String getTreatment(String key, String feature, Map<String, Object> attributes) {
        return getTreatment(key, feature);
    }

    @Override
    public String getTreatment(Key key, String feature, Map<String, Object> attributes) {
        return getTreatment(key.matchingKey(), feature, attributes);
    }

    public ImmutableMap<String, String> featureToTreatmentMap() {
        return _featureToTreatmentMap;
    }
}
