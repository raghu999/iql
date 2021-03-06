/*
 * Copyright (C) 2014 Indeed Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package com.indeed.imhotep.iql;

import com.indeed.imhotep.api.ImhotepOutOfMemoryException;
import com.indeed.imhotep.ez.EZImhotepSession;
import com.indeed.imhotep.ez.Field;
import org.apache.log4j.Logger;

/**
 * @author jplaisance
 */
public final class StringInCondition implements Condition {
    private static final Logger log = Logger.getLogger(StringInCondition.class);

    private final Field.StringField stringField;
    private boolean equality;   // true = equality, false = IN clause
    private final String[] values;
    private final boolean negation;

    public StringInCondition(Field.StringField stringField, boolean negation, boolean isEquality, final String... values) {
        this.stringField = stringField;
        this.equality = isEquality;
        this.values = values;
        this.negation = negation;
    }

    public void filter(final EZImhotepSession session) throws ImhotepOutOfMemoryException {
        if(negation) {
            session.filterNegation(stringField, values);
        } else {
            session.filter(stringField, values);
        }
    }

    public Field.StringField getStringField() {
        return stringField;
    }

    public String[] getValues() {
        return values;
    }

    public boolean isEquality() {
        return equality;
    }

    public boolean isNegation() {
        return negation;
    }
}
