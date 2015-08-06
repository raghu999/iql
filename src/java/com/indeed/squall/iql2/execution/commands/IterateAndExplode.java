package com.indeed.squall.iql2.execution.commands;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.indeed.common.util.Pair;
import com.indeed.flamdex.query.Term;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.squall.iql2.execution.Commands;
import com.indeed.squall.iql2.execution.Session;
import com.indeed.squall.iql2.execution.TermSelects;
import com.indeed.squall.iql2.execution.commands.misc.FieldIterateOpts;
import com.indeed.squall.iql2.execution.commands.misc.FieldLimitingMechanism;
import com.indeed.squall.iql2.execution.compat.Consumer;
import com.indeed.squall.iql2.execution.metrics.aggregate.AggregateMetric;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class IterateAndExplode implements Command {
    public final String field;
    public final List<AggregateMetric> selecting;
    public final FieldIterateOpts fieldOpts;
    public final Optional<Pair<Integer, FieldLimitingMechanism>> fieldLimits;
    public final Optional<String> explodeDefaultName;

    public IterateAndExplode(String field, List<AggregateMetric> selecting, FieldIterateOpts fieldOpts, Optional<Pair<Integer, FieldLimitingMechanism>> fieldLimits, Optional<String> explodeDefaultName) {
        this.field = field;
        this.selecting = selecting;
        this.fieldOpts = fieldOpts;
        this.fieldLimits = fieldLimits;
        this.explodeDefaultName = explodeDefaultName;
    }

    @Override
    public void execute(Session session, Consumer<String> out) throws ImhotepOutOfMemoryException, IOException {
        final List<List<List<TermSelects>>> iterationResults = new SimpleIterate(field, fieldOpts, selecting, false).evaluate(session, out);
        final List<Commands.TermsWithExplodeOpts> explodes = Lists.newArrayList((Commands.TermsWithExplodeOpts) null);
        for (final List<List<TermSelects>> groupResults : iterationResults) {
            if (groupResults.size() > 0) {
                final List<TermSelects> groupFieldResults = groupResults.get(0);
                final List<Term> terms = Lists.newArrayListWithCapacity(groupFieldResults.size());
                for (final TermSelects result : groupFieldResults) {
                    terms.add(new Term(result.field, result.isIntTerm, result.intTerm, result.stringTerm));
                }
                explodes.add(new Commands.TermsWithExplodeOpts(terms, this.explodeDefaultName));
            } else {
                explodes.add(new Commands.TermsWithExplodeOpts(Collections.<Term>emptyList(), this.explodeDefaultName));
            }
        }
        new ExplodePerGroup(explodes).execute(session, out);
    }
}
