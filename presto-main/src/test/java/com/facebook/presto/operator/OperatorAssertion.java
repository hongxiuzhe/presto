/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.Session;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.block.InterleavedBlockBuilder;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.testing.MaterializedResult;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.facebook.presto.operator.PageAssertions.assertPageEquals;
import static com.facebook.presto.testing.assertions.Assert.assertEquals;
import static com.facebook.presto.util.StructuralTestUtil.appendToBlockBuilder;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.concurrent.MoreFutures.tryGetFutureValue;
import static io.airlift.testing.Assertions.assertEqualsIgnoreOrder;

public final class OperatorAssertion
{
    private OperatorAssertion()
    {
    }

    public static List<Page> toPages(Operator operator, Iterator<Page> input)
    {
        return ImmutableList.<Page>builder()
                .addAll(toPagesPartial(operator, input))
                .addAll(finishOperator(operator))
                .build();
    }

    public static List<Page> toPagesPartial(Operator operator, Iterator<Page> input)
    {
        // verify initial state
        assertEquals(operator.isFinished(), false);

        ImmutableList.Builder<Page> outputPages = ImmutableList.builder();
        for (int loopsSinceLastPage = 0; loopsSinceLastPage < 1_000; loopsSinceLastPage++) {
            if (handledBlocked(operator)) {
                continue;
            }

            if (input.hasNext() && operator.needsInput()) {
                operator.addInput(input.next());
                loopsSinceLastPage = 0;
            }

            Page outputPage = operator.getOutput();
            if (outputPage != null && outputPage.getPositionCount() != 0) {
                outputPages.add(outputPage);
                loopsSinceLastPage = 0;
            }
        }

        return outputPages.build();
    }

    public static List<Page> finishOperator(Operator operator)
    {
        ImmutableList.Builder<Page> outputPages = ImmutableList.builder();

        for (int loopsSinceLastPage = 0; !operator.isFinished() && loopsSinceLastPage < 1_000; loopsSinceLastPage++) {
            if (handledBlocked(operator)) {
                continue;
            }
            operator.finish();
            Page outputPage = operator.getOutput();
            if (outputPage != null && outputPage.getPositionCount() != 0) {
                outputPages.add(outputPage);
                loopsSinceLastPage = 0;
            }
        }

        assertEquals(operator.isFinished(), true, "Operator did not finish");
        assertEquals(operator.needsInput(), false, "Operator still wants input");
        assertEquals(operator.isBlocked().isDone(), true, "Operator is blocked");

        return outputPages.build();
    }

    private static boolean handledBlocked(Operator operator)
    {
        ListenableFuture<?> isBlocked = operator.isBlocked();
        if (!isBlocked.isDone()) {
            tryGetFutureValue(isBlocked, 1, TimeUnit.MILLISECONDS);
            return true;
        }
        return false;
    }

    public static List<Page> toPages(OperatorFactory operatorFactory, DriverContext driverContext, List<Page> input)
    {
        try (Operator operator = operatorFactory.createOperator(driverContext)) {
            return toPages(operator, input.iterator());
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    public static List<Page> toPages(OperatorFactory operatorFactory, DriverContext driverContext)
    {
        return toPages(operatorFactory, driverContext, ImmutableList.of());
    }

    public static MaterializedResult toMaterializedResult(Session session, List<Type> types, List<Page> pages)
    {
        // materialize pages
        MaterializedResult.Builder resultBuilder = MaterializedResult.resultBuilder(session, types);
        for (Page outputPage : pages) {
            resultBuilder.page(outputPage);
        }
        return resultBuilder.build();
    }

    public static Block toRow(List<Type> parameterTypes, Object... values)
    {
        checkArgument(parameterTypes.size() == values.length, "parameterTypes.size(" + parameterTypes.size() + ") does not equal to values.length(" + values.length + ")");

        BlockBuilder blockBuilder = new InterleavedBlockBuilder(parameterTypes, new BlockBuilderStatus(), parameterTypes.size());
        for (int i = 0; i < values.length; i++) {
            appendToBlockBuilder(parameterTypes.get(i), values[i], blockBuilder);
        }
        return blockBuilder.build();
    }

    public static void assertOperatorEquals(OperatorFactory operatorFactory, DriverContext driverContext, List<Page> input, List<Page> expected)
            throws Exception
    {
        List<Page> actual = toPages(operatorFactory, driverContext, input);
        assertEquals(actual.size(), expected.size());
        for (int i = 0; i < actual.size(); i++) {
            assertPageEquals(operatorFactory.getTypes(), actual.get(i), expected.get(i));
        }
    }

    public static void assertOperatorEquals(OperatorFactory operatorFactory, DriverContext driverContext, List<Page> input, MaterializedResult expected)
            throws Exception
    {
        assertOperatorEquals(operatorFactory, driverContext, input, expected, false, ImmutableList.of());
    }

    public static void assertOperatorEquals(OperatorFactory operatorFactory, DriverContext driverContext, List<Page> input, MaterializedResult expected, boolean hashEnabled, List<Integer> hashChannels)
            throws Exception
    {
        List<Page> pages = toPages(operatorFactory, driverContext, input);
        MaterializedResult actual;
        if (hashEnabled && !hashChannels.isEmpty()) {
            // Drop the hashChannel for all pages
            List<Page> actualPages = dropChannel(pages, hashChannels);
            List<Type> expectedTypes = without(operatorFactory.getTypes(), hashChannels);
            actual = toMaterializedResult(driverContext.getSession(), expectedTypes, actualPages);
        }
        else {
            actual = toMaterializedResult(driverContext.getSession(), operatorFactory.getTypes(), pages);
        }
        assertEquals(actual, expected);
    }

    public static void assertOperatorEqualsIgnoreOrder(
            OperatorFactory operatorFactory,
            DriverContext driverContext,
            List<Page> input,
            MaterializedResult expected)
    {
        assertOperatorEqualsIgnoreOrder(operatorFactory, driverContext, input, expected, false, Optional.empty());
    }

    public static void assertOperatorEqualsIgnoreOrder(
            OperatorFactory operatorFactory,
            DriverContext driverContext,
            List<Page> input,
            MaterializedResult expected,
            boolean hashEnabled,
            Optional<Integer> hashChannel)
    {
        List<Page> pages = toPages(operatorFactory, driverContext, input);
        MaterializedResult actual;
        if (hashEnabled && hashChannel.isPresent()) {
            // Drop the hashChannel for all pages
            List<Page> actualPages = dropChannel(pages, ImmutableList.of(hashChannel.get()));
            List<Type> expectedTypes = without(operatorFactory.getTypes(), ImmutableList.of(hashChannel.get()));
            actual = toMaterializedResult(driverContext.getSession(), expectedTypes, actualPages);
        }
        else {
            actual = toMaterializedResult(driverContext.getSession(), operatorFactory.getTypes(), pages);
        }

        assertEqualsIgnoreOrder(actual.getMaterializedRows(), expected.getMaterializedRows());
    }

    static <T> List<T> without(List<T> list, Collection<Integer> indexes)
    {
        Set<Integer> indexesSet = ImmutableSet.copyOf(indexes);

        return IntStream.range(0, list.size())
                .filter(index -> !indexesSet.contains(index))
                .mapToObj(list::get)
                .collect(toImmutableList());
    }

    static List<Page> dropChannel(List<Page> pages, List<Integer> channels)
    {
        List<Page> actualPages = new ArrayList<>();
        for (Page page : pages) {
            int channel = 0;
            Block[] blocks = new Block[page.getChannelCount() - channels.size()];
            for (int i = 0; i < page.getChannelCount(); i++) {
                if (channels.contains(i)) {
                    continue;
                }
                blocks[channel++] = page.getBlock(i);
            }
            actualPages.add(new Page(blocks));
        }
        return actualPages;
    }
}
