package com.indeed.imhotep.iql;

import com.google.common.base.Predicate;
import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.ez.EZImhotepSession;
import com.indeed.imhotep.ez.Field;
import org.apache.log4j.Logger;

/**
 * @author jplaisance
 */
public final class StringPredicateCondition implements Condition {
    private static final Logger log = Logger.getLogger(StringPredicateCondition.class);

    private final Field.StringField stringField;
    private final Predicate<String> predicate;
    private final boolean negation;

    public StringPredicateCondition(final Field.StringField stringField, final Predicate<String> predicate, final boolean negation) {
        this.stringField = stringField;
        this.predicate = predicate;
        this.negation = negation;
    }

    public void filter(final EZImhotepSession session) throws ImhotepOutOfMemoryException {
        if(negation) {
            session.filterNegation(stringField, predicate);
        } else {
            session.filter(stringField, predicate);
        }
    }
}
