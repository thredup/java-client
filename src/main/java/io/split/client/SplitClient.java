package io.split.client;

import io.split.client.api.Key;

import java.util.Map;

/**
 * Created by adilaijaz on 5/8/15.
 */
public interface SplitClient {

    /**
     * Returns the treatment to show this key for this feature. The set of treatments
     * for a feature can be configured on the Split web console.
     * <p/>
     * <p/>
     * This method returns the string 'control' if:
     * <ol>
     * <li>Any of the parameters were null</li>
     * <li>There was an exception in evaluating the treatment</li>
     * <li>The SDK does not know of the existence of this feature</li>
     * <li>The feature was deleted through the web console.</li>
     * </ol>
     * 'control' is a reserved treatment (you cannot create a treatment with the
     * same name) to highlight these exceptional circumstances.
     * <p/>
     * <p/>
     * The sdk returns the default treatment of this feature if:
     * <ol>
     * <li>The feature was killed</li>
     * <li>The key did not match any of the conditions in the feature roll-out plan</li>
     * </ol>
     * The default treatment of a feature is set on the Split web console.
     * <p/>
     * <p/>
     * This method does not throw any exceptions. It also never returns null.
     *
     * @param key      a unique key of your customer (e.g. user_id, user_email, account_id, etc.) MUST not be null.
     * @param feature the feature we want to evaluate. MUST NOT be null.
     * @return the evaluated treatment, the default treatment of this feature, or 'control'.
     */
    String getTreatment(String key, String feature);

    /**
     * This method is useful when you want to determine the treatment to show
     * to an customer (user, account etc.) based on an attribute of that customer
     * instead of it's key.
     * <p/>
     * <p/>
     * Examples include showing a different treatment to users on trial plan
     * vs. premium plan. Another example is to show a different treatment
     * to users created after a certain date.
     *
     * @param key         a unique key of your customer (e.g. user_id, user_email, account_id, etc.) MUST not be null.
     * @param feature    the feature we want to evaluate. MUST NOT be null.
     * @param attributes of the customer (user, account etc.) to use in evaluation. Can be null or empty.
     * @return the evaluated treatment, the default treatment of this feature, or 'control'.
     */
    String getTreatment(String key, String feature, Map<String, Object> attributes);

    /**
     * To understand why this method is useful, consider the following simple Split as an example:
     *
     * if user is in segment employees then split 100%:on
     * else if user is in segment all then split 20%:on,80%:off
     *
     * There are two concepts here: matching and bucketing. Matching
     * refers to ‘user is in segment employees’ or ‘user is in segment
     * all’ whereas bucketing refers to ‘100%:on’ or ‘20%:on,80%:off’.
     *
     * By default, the same customer key is used for both matching and
     * bucketing. However, for some advanced use cases, you may want
     * to use different keys. For such cases, use this method.
     *
     * As an example, suppose you want to rollout to percentages of
     * users in specific accounts. You can achieve that by matching
     * via account id, but bucketing by user id.
     *
     * Another example is when you want to ensure that a user continues to get
     * the same treatment after they sign up for your product that they used
     * to get when they were simply a visitor to your site. In that case,
     * before they sign up, you can use their visitor id for both matching and bucketing, but
     * post log-in you can use their user id for matching and visitor id for bucketing.
     *
     *
     * @param key the matching and bucketing keys. MUST NOT be null.
     * @param feature the feature we want to evaluate. MUST NOT be null.
     * @param attributes of the entity (user, account etc.) to use in evaluation. Can be null or empty.
     *
     * @return the evaluated treatment, the default treatment of this feature, or 'control'.
     */
    String getTreatment(Key key, String feature, Map<String, Object> attributes);
}
