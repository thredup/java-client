package io.split.engine.experiments;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.split.client.dtos.Condition;
import io.split.client.dtos.Matcher;
import io.split.client.dtos.MatcherGroup;
import io.split.client.dtos.Split;
import io.split.client.dtos.SplitChange;
import io.split.client.dtos.Status;
import io.split.engine.ConditionsTestUtil;
import io.split.engine.SDKReadinessGates;
import io.split.engine.matchers.AllKeysMatcher;
import io.split.engine.matchers.CombiningMatcher;
import io.split.engine.segments.NoChangeSegmentChangeFetcher;
import io.split.engine.segments.RefreshableSegmentFetcher;
import io.split.engine.segments.SegmentChangeFetcher;
import io.split.engine.segments.SegmentFetcher;
import io.split.engine.segments.StaticSegment;
import io.split.engine.segments.StaticSegmentFetcher;
import io.split.grammar.Treatments;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by adilaijaz on 5/11/15.
 */
public class RefreshableSplitFetcherTest {
    private static final Logger _log = LoggerFactory.getLogger(RefreshableSplitFetcherTest.class);

    @Test
    public void works_when_we_start_without_any_state() throws InterruptedException {
        works(0);
    }

    @Test
    public void works_when_we_start_with_any_state() throws InterruptedException {
        works(11L);
    }

    private void works(long startingChangeNumber) throws InterruptedException {
        AChangePerCallSplitChangeFetcher splitChangeFetcher = new AChangePerCallSplitChangeFetcher();
        SegmentFetcher segmentFetcher = new StaticSegmentFetcher(Collections.<String, StaticSegment>emptyMap());

        SDKReadinessGates gates = new SDKReadinessGates();

        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(splitChangeFetcher, new SplitParser(segmentFetcher), gates, startingChangeNumber);

        // execute the fetcher for a little bit.
        executeWaitAndTerminate(fetcher, 1, 3, TimeUnit.SECONDS);

        assertThat(splitChangeFetcher.lastAdded(), is(greaterThan(startingChangeNumber)));
        assertThat(fetcher.changeNumber(), is(equalTo(splitChangeFetcher.lastAdded())));

        // all previous splits have been removed since they are dead
        for (long i = startingChangeNumber; i < fetcher.changeNumber(); i++) {
            assertThat("Asking for " + i + " " + fetcher.fetchAll(), fetcher.fetch("" + i), is(not(nullValue())));
            assertThat(fetcher.fetch("" + i).killed(), is(true));
        }

        ParsedCondition expectedParsedCondition = ParsedCondition.createParsedConditionForTests(CombiningMatcher.of(new AllKeysMatcher()), Lists.newArrayList(ConditionsTestUtil.partition("on", 10)));
        List<ParsedCondition> expectedListOfMatcherAndSplits = Lists.newArrayList(expectedParsedCondition);
        ParsedSplit expected = ParsedSplit.createParsedSplitForTests("" + fetcher.changeNumber(), (int) fetcher.changeNumber(), false, Treatments.OFF, expectedListOfMatcherAndSplits, null, fetcher.changeNumber(), 1);

        ParsedSplit actual = fetcher.fetch("" + fetcher.changeNumber());

        assertThat(actual, is(equalTo(expected)));
        assertThat(gates.areSplitsReady(0), is(equalTo(true)));
    }

    @Test
    public void when_parser_fails_we_remove_the_experiment() throws InterruptedException {
        SegmentFetcher segmentFetcher = new StaticSegmentFetcher(Collections.<String, StaticSegment>emptyMap());

        Split validSplit = new Split();
        validSplit.status = Status.ACTIVE;
        validSplit.seed = (int) -1;
        validSplit.conditions = Lists.newArrayList(ConditionsTestUtil.makeAllKeysCondition(Lists.newArrayList(ConditionsTestUtil.partition("on", 10))));
        validSplit.defaultTreatment = Treatments.OFF;
        validSplit.name = "-1";

        SplitChange validReturn = new SplitChange();
        validReturn.splits = Lists.newArrayList(validSplit);
        validReturn.since = -1L;
        validReturn.till = 0L;

        MatcherGroup invalidMatcherGroup = new MatcherGroup();
        invalidMatcherGroup.matchers = Lists.<Matcher>newArrayList();

        Condition invalidCondition = new Condition();
        invalidCondition.matcherGroup = invalidMatcherGroup;
        invalidCondition.partitions = Lists.newArrayList(ConditionsTestUtil.partition("on", 10));

        Split invalidSplit = new Split();
        invalidSplit.status = Status.ACTIVE;
        invalidSplit.seed = (int) -1;
        invalidSplit.conditions = Lists.newArrayList(invalidCondition);
        invalidSplit.defaultTreatment = Treatments.OFF;
        invalidSplit.name = "-1";

        SplitChange invalidReturn = new SplitChange();
        invalidReturn.splits = Lists.newArrayList(invalidSplit);
        invalidReturn.since = 0L;
        invalidReturn.till = 1L;

        SplitChange noReturn = new SplitChange();
        noReturn.splits = Lists.<Split>newArrayList();
        noReturn.since = 1L;
        noReturn.till = 1L;

        SplitChangeFetcher splitChangeFetcher = mock(SplitChangeFetcher.class);
        when(splitChangeFetcher.fetch(-1L)).thenReturn(validReturn);
        when(splitChangeFetcher.fetch(0L)).thenReturn(invalidReturn);
        when(splitChangeFetcher.fetch(1L)).thenReturn(noReturn);


        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(splitChangeFetcher, new SplitParser(segmentFetcher), new SDKReadinessGates(), -1L);

        // execute the fetcher for a little bit.
        executeWaitAndTerminate(fetcher, 1, 5, TimeUnit.SECONDS);

        assertThat(fetcher.changeNumber(), is(equalTo(1L)));
        // verify that the fetcher return null
        assertThat(fetcher.fetch("-1"), is(nullValue()));
    }

    @Test
    public void if_there_is_a_problem_talking_to_split_change_count_down_latch_is_not_decremented() throws Exception {
        SegmentFetcher segmentFetcher = new StaticSegmentFetcher(Collections.<String, StaticSegment>emptyMap());
        SDKReadinessGates gates = new SDKReadinessGates();

        SplitChangeFetcher splitChangeFetcher = mock(SplitChangeFetcher.class);
        when(splitChangeFetcher.fetch(-1L)).thenThrow(new RuntimeException());

        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(splitChangeFetcher, new SplitParser(segmentFetcher), gates, -1L);

        // execute the fetcher for a little bit.
        executeWaitAndTerminate(fetcher, 1, 5, TimeUnit.SECONDS);

        assertThat(fetcher.changeNumber(), is(equalTo(-1L)));
        assertThat(gates.areSplitsReady(0), is(equalTo(false)));
    }

    private void executeWaitAndTerminate(Runnable runnable, long frequency, long waitInBetween, TimeUnit unit) throws InterruptedException {
        // execute the fetcher for a little bit.
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleWithFixedDelay(runnable, 0L, frequency, unit);
        Thread.currentThread().sleep(unit.toMillis(waitInBetween));

        scheduledExecutorService.shutdown();
        try {
            if (!scheduledExecutorService.awaitTermination(10L, TimeUnit.SECONDS)) {
                _log.info("Executor did not terminate in the specified time.");
                List<Runnable> droppedTasks = scheduledExecutorService.shutdownNow();
                _log.info("Executor was abruptly shut down. These tasks will not be executed: " + droppedTasks);
            }
        } catch (InterruptedException e) {
            // reset the interrupt.
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void works_with_user_defined_segments() throws Exception {
        long startingChangeNumber = -1;
        String segmentName = "foosegment";
        AChangePerCallSplitChangeFetcher experimentChangeFetcher = new AChangePerCallSplitChangeFetcher(segmentName);
        SDKReadinessGates gates = new SDKReadinessGates();

        SegmentChangeFetcher segmentChangeFetcher = new NoChangeSegmentChangeFetcher();
        SegmentFetcher segmentFetcher = new RefreshableSegmentFetcher(segmentChangeFetcher, 1,10, gates);
        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(experimentChangeFetcher, new SplitParser(segmentFetcher), gates, startingChangeNumber);

        // execute the fetcher for a little bit.
        executeWaitAndTerminate(fetcher, 1, 5, TimeUnit.SECONDS);

        assertThat(experimentChangeFetcher.lastAdded(), is(greaterThan(startingChangeNumber)));
        assertThat(fetcher.changeNumber(), is(equalTo(experimentChangeFetcher.lastAdded())));

        // all previous splits have been removed since they are dead
        for (long i = startingChangeNumber; i < fetcher.changeNumber(); i++) {
            assertThat("Asking for " + i + " " + fetcher.fetchAll(), fetcher.fetch("" + i), is(not(nullValue())));
            assertThat(fetcher.fetch("" + i).killed(), is(true));
        }

        assertThat(gates.areSplitsReady(0), is(equalTo(true)));
        assertThat(gates.isSegmentRegistered(segmentName), is(equalTo(true)));
        assertThat(gates.areSegmentsReady(100), is(equalTo(true)));
        assertThat(gates.isSDKReady(0), is(equalTo(true)));
    }

    @Test
    public void fetch_traffic_type_names_works_with_adds() throws Exception {
        long startingChangeNumber = -1;
        SplitChangeFetcherWithTrafficTypeNames changeFetcher = new SplitChangeFetcherWithTrafficTypeNames();
        SDKReadinessGates gates = new SDKReadinessGates();

        SegmentChangeFetcher segmentChangeFetcher = new NoChangeSegmentChangeFetcher();
        SegmentFetcher segmentFetcher = new RefreshableSegmentFetcher(segmentChangeFetcher, 1,10, gates);
        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(changeFetcher, new SplitParser(segmentFetcher), gates, startingChangeNumber);

        // Before, it should be empty
        Set<String> usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        Set<String> expected = Sets.newHashSet();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, it starts with since -1;
        changeFetcher.addSplitForSince(-1L, "test_1", "user");
        executeOnce(fetcher);
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        expected.add("user");
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, now with 0;
        changeFetcher.addSplitForSince(0L, "test_2", "user");
        changeFetcher.addSplitForSince(0L, "test_3", "account");
        executeOnce(fetcher);
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        expected.add("account");
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, now with 1;
        changeFetcher.addSplitForSince(1L, "test_4", "experiment");
        executeOnce(fetcher);
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        expected.add("experiment");
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, now with 2;
        changeFetcher.addSplitForSince(2L, "test_2", "user");
        changeFetcher.addSplitForSince(2L, "test_4", "account");
        changeFetcher.addSplitForSince(2L, "test_5", "experiment");
        executeOnce(fetcher);
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));
    }

    @Test
    public void fetch_traffic_type_names_already_existing_split() throws Exception {
        long startingChangeNumber = -1;
        SplitChangeFetcherWithTrafficTypeNames changeFetcher = new SplitChangeFetcherWithTrafficTypeNames();
        SDKReadinessGates gates = new SDKReadinessGates();

        SegmentChangeFetcher segmentChangeFetcher = new NoChangeSegmentChangeFetcher();
        SegmentFetcher segmentFetcher = new RefreshableSegmentFetcher(segmentChangeFetcher, 1,10, gates);
        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(changeFetcher, new SplitParser(segmentFetcher), gates, startingChangeNumber);

        // Before, it should be empty
        Set<String> usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        Set<String> expected = Sets.newHashSet();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, it starts with since -1;
        changeFetcher.addSplitForSince(-1L, "test_1", "user");
        executeOnce(fetcher);
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        expected.add("user");
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // Simulate that the split arrives again as active because it has been updated
        changeFetcher.addSplitForSince(0L, "test_1", "user");
        executeOnce(fetcher);
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        expected.add("user");
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // Simulate that the split is now removed. Traffic type user should no longer be present.
        changeFetcher.removeSplitForSince(1L, "test_1", "user");
        executeOnce(fetcher);
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        expected.clear();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));
    }


    @Test
    public void fetch_traffic_type_names_works_with_remove() throws Exception {
        long startingChangeNumber = -1;
        SplitChangeFetcherWithTrafficTypeNames changeFetcher = new SplitChangeFetcherWithTrafficTypeNames();
        SDKReadinessGates gates = new SDKReadinessGates();

        SegmentChangeFetcher segmentChangeFetcher = new NoChangeSegmentChangeFetcher();
        SegmentFetcher segmentFetcher = new RefreshableSegmentFetcher(segmentChangeFetcher, 1,10, gates);
        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(changeFetcher, new SplitParser(segmentFetcher), gates, startingChangeNumber);

        Set<String> expected = Sets.newHashSet();

        // execute once, it starts with since -1;
        changeFetcher.addSplitForSince(-1L, "test_1", "user");
        executeOnce(fetcher);
        Set<String> usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        expected.add("user");
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, now with 0;
        changeFetcher.addSplitForSince(0L, "test_2", "user");
        changeFetcher.addSplitForSince(0L, "test_3", "account");
        executeOnce(fetcher);
        expected.add("account");
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, now with 1;
        // This removes test_1, but still test_2 exists with user, so it should still return user and account
        changeFetcher.removeSplitForSince(1L, "test_1", "user");
        executeOnce(fetcher);
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, now with 2;
        // This removes test_2, so now there are no more splits with traffic type user.
        changeFetcher.removeSplitForSince(2L, "test_2", "user");
        executeOnce(fetcher);
        expected.remove("user");
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, now with 3;
        // This removes test_3, which removes account, now it should be empty
        changeFetcher.removeSplitForSince(3L, "test_3", "account");
        executeOnce(fetcher);
        expected.remove("account");
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));

        // execute once, now with 4;
        // Adding user once more
        changeFetcher.addSplitForSince(4L, "test_1", "user");
        executeOnce(fetcher);
        expected.add("user");
        usedTrafficTypes = fetcher.fetchKnownTrafficTypes();
        Assert.assertThat(usedTrafficTypes, Matchers.is(Matchers.equalTo(expected)));
    }

    private void executeOnce(Runnable runnable) throws InterruptedException {
        // execute the fetcher for a little bit.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(runnable);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }


}
